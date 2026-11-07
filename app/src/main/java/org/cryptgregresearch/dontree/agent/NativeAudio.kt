package org.cryptgregresearch.dontree.agent

/** No network, model downloads, prompts or remote inference exist in this boundary. */
object NativeAudio {
    init { System.loadLibrary("dontree") }
    external fun open(path: String): Long
    external fun close(handle: Long)
    external fun cancel(handle: Long)
    external fun transcribe(handle: Long, pcm: FloatArray, translate: Boolean): String
    /** 0–100 within the whisper call currently running on this handle. */
    external fun progress(handle: Long): Int
    external fun fingerprint(pcm: FloatArray): String
}
