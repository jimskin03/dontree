package org.cryptgregresearch.dontree.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

fun InputStream.readLimited(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= maxBytes) { "The selected file or response is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
