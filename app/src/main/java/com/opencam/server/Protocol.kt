package com.opencam.server

import com.opencam.Codec

/** Wire framing and request parsing for the droidcam-obs-plugin protocol. */
object Protocol {
    const val DEFAULT_PORT = 4747
    const val HEADER_SIZE = 12
    const val NO_PTS = -1L
    const val MAX_PACKET = 16 * 1024 * 1024

    fun frameHeader(ptsUs: Long, len: Int): ByteArray {
        require(len >= 0 && len <= MAX_PACKET) { "Invalid packet length: $len" }
        val header = ByteArray(HEADER_SIZE)
        var pts = ptsUs
        for (i in 7 downTo 0) {
            header[i] = (pts and 0xFF).toByte()
            pts = pts shr 8
        }
        header[8] = (len ushr 24).toByte()
        header[9] = (len ushr 16).toByte()
        header[10] = (len ushr 8).toByte()
        header[11] = len.toByte()
        return header
    }

    fun framePacket(data: ByteArray, ptsUs: Long): ByteArray {
        require(data.size <= MAX_PACKET) { "Packet exceeds protocol maximum" }
        val out = ByteArray(HEADER_SIZE + data.size)
        val len = data.size
        var pts = ptsUs
        for (i in 7 downTo 0) {
            out[i] = (pts and 0xFF).toByte()
            pts = pts shr 8
        }
        out[8] = (len ushr 24).toByte()
        out[9] = (len ushr 16).toByte()
        out[10] = (len ushr 8).toByte()
        out[11] = len.toByte()
        System.arraycopy(data, 0, out, HEADER_SIZE, len)
        return out
    }

    sealed interface Request {
        data class Video(
            val codec: Codec,
            val width: Int,
            val height: Int,
            val port: Int,
            val hdr: Boolean,
        ) : Request
        data object Audio : Request
        data object Battery : Request
        data class Tally(val state: String) : Request
        data object Ping : Request

        /** `GET /v1/status` — JSON snapshot of the current stream + phone state. */
        data object Status : Request

        /** `PUT /v1/settings?key=value&...` — apply settings pushed by a client. */
        data class Settings(val params: Map<String, String>) : Request

        data object Unknown : Request
    }

    private val videoRe = Regex(
        "^/v5/video/([^/]+)/(\\d+)x(\\d+)/port/(\\d+)/os/([^/]*)/obs/([^/]*)/client/([^/]*)/hdr/([01])/nonce/(\\d+)/?$"
    )
    private val tallyRe = Regex("^/v1/tally/([a-z]+)/?$")
    private val TALLY_STATES = setOf("program", "preview", "idle")

    fun parseRequest(method: String, path: String): Request {
        if (method.equals("GET", ignoreCase = true)) {
            videoRe.matchEntire(path)?.let { match ->
                val codec = Codec.fromWire(match.groupValues[1]) ?: return Request.Unknown
                val width = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..8192 }
                    ?: return Request.Unknown
                val height = match.groupValues[3].toIntOrNull()?.takeIf { it in 1..8192 }
                    ?: return Request.Unknown
                val port = match.groupValues[4].toIntOrNull()?.takeIf { it in 0..65535 }
                    ?: return Request.Unknown
                return Request.Video(codec, width, height, port, hdr = match.groupValues[8] == "1")
            }
            return when (path.trimEnd('/')) {
                "/v2/audio" -> Request.Audio
                "/battery" -> Request.Battery
                "/ping" -> Request.Ping
                "/v1/status" -> Request.Status
                else -> Request.Unknown
            }
        }

        if (method.equals("PUT", ignoreCase = true)) {
            val trimmed = path.trimEnd('/')
            if (trimmed == "/v1/settings" || trimmed.startsWith("/v1/settings?")) {
                val query = trimmed.substringAfter('?', "")
                val params = mutableMapOf<String, String>()
                if (query.isNotEmpty()) {
                    for (pair in query.split('&')) {
                        if (pair.isEmpty()) continue
                        val (key, value) = pair.split('=', limit = 2)
                            .let { if (it.size == 2) it[0] to it[1] else it[0] to "" }
                        params[urlDecode(key)] = urlDecode(value)
                    }
                }
                return Request.Settings(params)
            }
            val match = tallyRe.matchEntire(path) ?: return Request.Unknown
            val state = match.groupValues[1]
            return if (state in TALLY_STATES) Request.Tally(state) else Request.Unknown
        }
        return Request.Unknown
    }

    private fun urlDecode(input: String): String = try {
        java.net.URLDecoder.decode(input, Charsets.UTF_8.name())
    } catch (_: Exception) {
        input
    }

    fun parseRequestLine(line: String): Pair<String, String>? {
        val parts = line.trim().split(Regex("\\s+"), limit = 3)
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return parts[0].uppercase() to parts[1]
    }
}
