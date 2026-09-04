package com.mirrorphone.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class QrPayload(
    val v: String = "2",
    val host: String,
    val port: Int = 7878,
    val pin: String,
    val name: String = ""
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(raw: String): QrPayload? = try {
            json.decodeFromString(QrPayload.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }
}

data class Server(
    val name: String,
    val host: String,
    val port: Int,
    val pin: String? = null,
    val version: String = "2"
) {
    override fun toString(): String = "$name  ·  $host:$port"
}
