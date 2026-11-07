#include <jni.h>
#include <whisper.h>
#include <chromaprint.h>
#include <atomic>
#include <algorithm>
#include <memory>
#include <sstream>
#include <string>
#include <vector>
#include <thread>

struct Engine {
    whisper_context *context;
    std::atomic<bool> cancelled{false};
    std::atomic<int> progress{0};
};
static void fail(JNIEnv *env, const char *message) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message); }
static jstring utf8(JNIEnv *env, const std::string &text) {
    auto bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    auto type = env->FindClass("java/lang/String");
    auto charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(type, env->GetMethodID(type, "<init>", "([BLjava/lang/String;)V"), bytes, charset));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(charset); env->DeleteLocalRef(type);
    return result;
}
static std::string quote(const char *value) {
    std::string out = "\"";
    for (const unsigned char *p = reinterpret_cast<const unsigned char *>(value); *p; ++p) {
        if (*p == '"' || *p == '\\') { out += '\\'; out += static_cast<char>(*p); }
        else if (*p < 0x20) { const char *hex = "0123456789abcdef"; out += "\\u00"; out += hex[*p >> 4]; out += hex[*p & 15]; }
        else out += static_cast<char>(*p);
    }
    return out + '"';
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_cryptgregresearch_dontree_agent_NativeAudio_open(JNIEnv *env, jobject, jstring path) {
    const char *file = env->GetStringUTFChars(path, nullptr);
    auto params = whisper_context_default_params(); params.use_gpu = false;
    auto context = whisper_init_from_file_with_params(file, params);
    env->ReleaseStringUTFChars(path, file);
    if (!context) { fail(env, "Cannot open this Whisper model. Import a supported multilingual GGML tiny/base model."); return 0; }
    if (!whisper_is_multilingual(context)) { whisper_free(context); fail(env, "Thai requires a multilingual model, not an English-only .en model."); return 0; }
    return reinterpret_cast<jlong>(new Engine{context});
}

extern "C" JNIEXPORT void JNICALL
Java_org_cryptgregresearch_dontree_agent_NativeAudio_cancel(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Engine *>(handle)->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_org_cryptgregresearch_dontree_agent_NativeAudio_close(JNIEnv *, jobject, jlong handle) {
    if (handle) { auto engine = reinterpret_cast<Engine *>(handle); whisper_free(engine->context); delete engine; }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_cryptgregresearch_dontree_agent_NativeAudio_transcribe(JNIEnv *env, jobject, jlong handle, jfloatArray audio, jboolean translate) {
    auto engine = reinterpret_cast<Engine *>(handle);
    if (!engine || engine->cancelled.load()) { fail(env, "Analysis cancelled"); return nullptr; }
    const auto count = env->GetArrayLength(audio);
    if (count <= 0 || count > 16000 * 30) { fail(env, "Invalid audio chunk"); return nullptr; }
    std::vector<float> samples(count);
    env->GetFloatArrayRegion(audio, 0, count, samples.data());
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "th"; params.translate = translate; params.no_context = true;
    params.n_threads = std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
    params.print_realtime = false; params.print_progress = false; params.print_timestamps = false; params.print_special = false;
    params.token_timestamps = true; params.suppress_blank = true; params.suppress_nst = true;
    // Sung audio fails Whisper's quality checks. The default temperature fallback then
    // re-decodes the same window up to six times, so the first section stays at 0% for
    // the whole song. One greedy pass, capped so a repetition loop cannot run unbound.
    params.temperature_inc = -1.0f;
    params.max_tokens = std::max(48, count * 12 / 16000);
    params.abort_callback = [](void *data) { return static_cast<Engine *>(data)->cancelled.load(); };
    params.abort_callback_user_data = engine;
    engine->progress.store(0);
    params.progress_callback = [](whisper_context *, whisper_state *, int progress, void *user) {
        static_cast<Engine *>(user)->progress.store(progress);
    };
    params.progress_callback_user_data = engine;
    if (whisper_full(engine->context, params, samples.data(), count) != 0 || engine->cancelled.load()) {
        fail(env, engine->cancelled.load() ? "Analysis cancelled" : "Local transcription failed"); return nullptr;
    }
    auto ctx = engine->context;
    std::ostringstream json; json << '[';
    const auto segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        if (i) json << ',';
        json << "{\"start\":" << whisper_full_get_segment_t0(ctx, i) * 10
             << ",\"end\":" << whisper_full_get_segment_t1(ctx, i) * 10
             << ",\"text\":" << quote(whisper_full_get_segment_text(ctx, i)) << ",\"words\":[";
        bool comma = false;
        for (int j = 0; j < whisper_full_n_tokens(ctx, i); ++j) {
            const auto token = whisper_full_get_token_data(ctx, i, j);
            if (token.id >= whisper_token_eot(ctx) || token.t0 < 0 || token.t1 < token.t0) continue;
            if (comma) json << ',';
            comma = true;
            json << "{\"start\":" << token.t0 * 10 << ",\"end\":" << token.t1 * 10
                 << ",\"text\":" << quote(whisper_full_get_token_text(ctx, i, j)) << '}';
        }
        json << "]}";
    }
    json << ']'; return utf8(env, json.str());
}

extern "C" JNIEXPORT jint JNICALL
Java_org_cryptgregresearch_dontree_agent_NativeAudio_progress(JNIEnv *, jobject, jlong handle) {
    if (!handle) return 0;
    return reinterpret_cast<Engine *>(handle)->progress.load();
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_cryptgregresearch_dontree_agent_NativeAudio_fingerprint(JNIEnv *env, jobject, jfloatArray audio) {
    const auto count = env->GetArrayLength(audio);
    if (count < 16000 * 5 || count > 16000 * 30) { fail(env, "Recognition needs 5–30 seconds of audio"); return nullptr; }
    std::vector<float> samples(count); env->GetFloatArrayRegion(audio, 0, count, samples.data());
    std::vector<int16_t> pcm(count);
    for (int i = 0; i < count; ++i) pcm[i] = static_cast<int16_t>(std::clamp(samples[i], -1.0f, 1.0f) * 32767);
    auto context = chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT);
    if (!context) { fail(env, "Cannot create fingerprint engine"); return nullptr; }
    char *fingerprint = nullptr;
    const bool ok = chromaprint_start(context, 16000, 1) && chromaprint_feed(context, pcm.data(), count)
        && chromaprint_finish(context) && chromaprint_get_fingerprint(context, &fingerprint);
    std::string result = fingerprint ? fingerprint : "";
    if (fingerprint) chromaprint_dealloc(fingerprint);
    chromaprint_free(context);
    if (!ok) { fail(env, "Could not fingerprint this recording"); return nullptr; }
    return utf8(env, result);
}
