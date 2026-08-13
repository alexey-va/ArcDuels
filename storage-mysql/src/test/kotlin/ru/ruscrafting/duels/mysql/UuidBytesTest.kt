package ru.ruscrafting.duels.mysql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class UuidBytesTest : StringSpec({
    "UUID binary storage round-trips without string allocation" {
        val uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")

        UuidBytes.decode(UuidBytes.encode(uuid)) shouldBe uuid
        UuidBytes.encode(uuid).size shouldBe 16
    }

    "invalid binary UUID length is rejected" {
        shouldThrow<IllegalArgumentException> { UuidBytes.decode(byteArrayOf(1, 2, 3)) }
    }
})
