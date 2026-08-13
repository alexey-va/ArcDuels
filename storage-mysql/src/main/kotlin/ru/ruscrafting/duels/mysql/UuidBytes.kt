package ru.ruscrafting.duels.mysql

import java.nio.ByteBuffer
import java.util.UUID

internal object UuidBytes {
    fun encode(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()

    fun decode(bytes: ByteArray): UUID {
        require(bytes.size == 16) { "A binary UUID must contain exactly 16 bytes" }
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }
}
