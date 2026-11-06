# Third-party components

This project does not grant a license to third-party model weights or music. Review the chosen model's own license when distributing a model pack. Model weights are not included in the APK.

| Component | Version | License / source |
| --- | --- | --- |
| whisper.cpp (includes GGML) | 1.7.6 | MIT; https://github.com/ggml-org/whisper.cpp/tree/v1.7.6 |
| Chromaprint | 1.5.1 | LGPL-2.1-or-later; https://github.com/acoustid/chromaprint/tree/v1.5.1 |
| KissFFT bundled with Chromaprint | pinned by Chromaprint archive | BSD-3-Clause; see the bundled source's COPYING/LICENSES files |
| AndroidX / Media3 / Compose / Room / DataStore | pinned in Gradle | Apache-2.0; https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin / kotlinx.coroutines | pinned in Gradle | Apache-2.0; https://github.com/JetBrains/kotlin |
| Coil | 2.7.0 | Apache-2.0; https://github.com/coil-kt/coil/tree/2.7.0 |
| ICU on the Android platform | supplied by OS | Unicode/ICU license; https://unicode-org.github.io/icu/ |

Chromaprint is dynamically linked as `libchromaprint.so`. The source URLs, archive hashes, CMake recipe and build instructions in this repository identify the corresponding source and permit rebuilding the native libraries. Distribution must retain the upstream notices and applicable LGPL source/relinking rights. Native license text is bundled in `app/src/main/assets/licenses/`.

AcoustID and Cover Art Archive are optional network services, not libraries bundled with the agent. Comply with their usage conditions. Dontree requires the user's own AcoustID application key, does not hard-code credentials, and never rewrites local music metadata after recognition.
