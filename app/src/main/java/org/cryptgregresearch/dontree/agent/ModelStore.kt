package org.cryptgregresearch.dontree.agent

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class ModelStore(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "models").apply { mkdirs() }
    val file get() = File(directory, "whisper.bin")
    val ready get() = file.isFile && File(directory, "sha256").isFile
    val revision get() = File(directory, "sha256").takeIf { it.isFile }?.readText()?.trim() ?: "missing"

    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        val temp = File(directory, "import.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { source ->
                temp.outputStream().use { destination ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = source.read(buffer); if (count < 0) break
                        total += count
                        require(total <= 512L * 1024 * 1024) { "Use a tiny, base or quantized small model under 512 MB" }
                        digest.update(buffer, 0, count); destination.write(buffer, 0, count)
                    }
                    destination.fd.sync()
                }
            } ?: error("Could not read the selected model")
            require(temp.length() > 1_000_000) { "This file is too small to be a Whisper model" }
            val header = temp.inputStream().use { input -> ByteArray(4).also { check(input.read(it) == 4) } }
            require(header.contentEquals(byteArrayOf(0x6c, 0x6d, 0x67, 0x67))) { "Expected a whisper.cpp GGML .bin model" }
            coroutineContext.ensureActive()
            // Validate before replacing a working model; native rejects English-only packs.
            val handle = NativeAudio.open(temp.absolutePath)
            NativeAudio.close(handle)
            coroutineContext.ensureActive()
            check(temp.renameTo(file)) { "Could not save model" }
            File(directory, "sha256").writeText(digest.digest().joinToString("") { "%02x".format(it) })
        } finally { temp.delete() }
    }
}
