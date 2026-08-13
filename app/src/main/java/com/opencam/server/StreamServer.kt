package com.opencam.server

import com.opencam.Codec
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Bounded drop-oldest queue so a slow client never stalls an encoder thread. */
class FrameQueue(capacity: Int = 4) {
    private val frames = ArrayBlockingQueue<ByteArray>(capacity.coerceAtLeast(1))

    fun push(frame: ByteArray) {
        while (!frames.offer(frame)) frames.poll()
    }

    fun poll(timeoutMs: Long): ByteArray? = try {
        frames.poll(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    fun clear() = frames.clear()
}

sealed class StreamClient protected constructor(
    private val socket: Socket,
    queueCapacity: Int,
) {
    val remoteIp: String = socket.inetAddress?.hostAddress ?: "?"
    private val queue = FrameQueue(queueCapacity)
    private val closed = AtomicBoolean(false)
    private val callbackNotified = AtomicBoolean(false)
    @Volatile private var closeCallback: (() -> Unit)? = null
    protected abstract val threadName: String

    fun start(onClosed: () -> Unit) {
        closeCallback = onClosed
        Thread({
            try {
                socket.keepAlive = true
                socket.soTimeout = 0
                val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
                while (!closed.get()) {
                    val frame = queue.poll(500) ?: continue
                    out.write(frame)
                    out.flush()
                }
            } catch (_: Exception) {
                // Peer disconnected or the server closed the socket.
            } finally {
                closed.set(true)
                try { socket.close() } catch (_: Exception) {}
                notifyClosed()
            }
        }, threadName).apply { start() }
    }

    fun send(frame: ByteArray) {
        if (!closed.get()) queue.push(frame)
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            queue.clear()
            try { socket.close() } catch (_: Exception) {}
        }
        notifyClosed()
    }

    private fun notifyClosed() {
        if (!callbackNotified.compareAndSet(false, true)) return
        val callback = closeCallback
        closeCallback = null
        try { callback?.invoke() } catch (_: Exception) {}
    }
}

class VideoClient(
    socket: Socket,
    val codec: Codec,
    val width: Int,
    val height: Int,
) : StreamClient(socket, 4) {
    override val threadName = "opencam-video-client"
}

class AudioClient(socket: Socket) : StreamClient(socket, 8) {
    override val threadName = "opencam-audio-client"
}

interface ServerCallbacks {
    fun onVideoClientConnected(client: VideoClient)
    fun onVideoClientDisconnected(client: VideoClient)
    fun onAudioClientConnected(client: AudioClient)
    fun onAudioClientDisconnected(client: AudioClient)
    fun batteryPercent(): Int
    fun onTally(state: String)

    /** JSON snapshot of the current stream + phone state (GET /v1/status). */
    fun statusJson(): String

    /** Applies client-pushed settings (PUT /v1/settings?key=value&...). */
    fun applySettings(params: Map<String, String>)
}

/** TCP server for video, audio, battery, tally and ping connections. */
class StreamServer(
    private val port: Int,
    private val callbacks: ServerCallbacks,
) {
    private val videoClientList = CopyOnWriteArrayList<VideoClient>()
    private val audioClientList = CopyOnWriteArrayList<AudioClient>()
    val videoClients: List<VideoClient> get() = videoClientList.toList()
    val audioClients: List<AudioClient> get() = audioClientList.toList()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val closed = AtomicBoolean(true)
    private val threadNumber = AtomicInteger()
    private val connectionPool = ThreadPoolExecutor(
        2,
        4,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(32),
        ThreadFactory { runnable ->
            Thread(runnable, "opencam-server-conn-${threadNumber.incrementAndGet()}")
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun start(): Boolean {
        if (!closed.compareAndSet(true, false)) return true
        return try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            acceptThread = Thread({ acceptLoop() }, "opencam-server-accept").apply { start() }
            true
        } catch (_: IOException) {
            closed.set(true)
            try { serverSocket?.close() } catch (_: Exception) {}
            serverSocket = null
            connectionPool.shutdownNow()
            false
        }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = try {
                serverSocket?.accept()
            } catch (_: Exception) {
                null
            } ?: continue
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = REQUEST_TIMEOUT_MS
                connectionPool.execute { handleConnection(socket) }
            } catch (_: Throwable) {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        var handedOff = false
        try {
            val (method, path) = readRequestLine(socket) ?: return
            when (val request = Protocol.parseRequest(method, path)) {
                is Protocol.Request.Video -> {
                    val client = VideoClient(socket, request.codec, request.width, request.height)
                    val accepted = synchronized(videoClientList) {
                        if (videoClientList.size >= MAX_VIDEO_CLIENTS) false
                        else videoClientList.add(client)
                    }
                    if (!accepted) {
                        respond(socket, SERVICE_UNAVAILABLE)
                        return
                    }
                    try {
                        client.start {
                            videoClientList.remove(client)
                            callbacks.onVideoClientDisconnected(client)
                        }
                        handedOff = true
                        callbacks.onVideoClientConnected(client)
                    } catch (t: Throwable) {
                        videoClientList.remove(client)
                        client.close()
                        throw t
                    }
                }
                is Protocol.Request.Audio -> {
                    val client = AudioClient(socket)
                    val accepted = synchronized(audioClientList) {
                        if (audioClientList.size >= MAX_AUDIO_CLIENTS) false
                        else audioClientList.add(client)
                    }
                    if (!accepted) {
                        respond(socket, SERVICE_UNAVAILABLE)
                        return
                    }
                    try {
                        client.start {
                            audioClientList.remove(client)
                            callbacks.onAudioClientDisconnected(client)
                        }
                        handedOff = true
                        callbacks.onAudioClientConnected(client)
                    } catch (t: Throwable) {
                        audioClientList.remove(client)
                        client.close()
                        throw t
                    }
                }
                is Protocol.Request.Battery -> {
                    val value = callbacks.batteryPercent().coerceIn(0, 100).toString()
                    respond(socket, "HTTP/1.1 200 OK\r\nContent-Length: ${value.length}\r\nConnection: close\r\n\r\n$value")
                }
                is Protocol.Request.Tally -> {
                    callbacks.onTally(request.state)
                    respond(socket, EMPTY_OK)
                }
                is Protocol.Request.Ping -> respond(socket, EMPTY_OK)
                is Protocol.Request.Status -> {
                    val body = callbacks.statusJson()
                    respond(
                        socket,
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body",
                    )
                }
                is Protocol.Request.Settings -> {
                    callbacks.applySettings(request.params)
                    respond(socket, EMPTY_OK)
                }
                is Protocol.Request.Unknown -> respond(
                    socket,
                    "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                )
            }
        } catch (_: Exception) {
            // Invalid/aborted connection.
        } finally {
            if (!handedOff) try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Bare video/audio requests have no newline. As soon as a complete known
     * path has arrived and the socket receive buffer is empty, the handshake is
     * accepted instead of paying the old fixed 500 ms timeout.
     */
    private fun readRequestLine(socket: Socket): Pair<String, String>? {
        val input = socket.getInputStream()
        val buffer = ByteArrayOutputStream(256)
        while (buffer.size() < MAX_REQUEST_LINE) {
            val value = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                break
            } catch (_: IOException) {
                return null
            }
            if (value == -1) break
            if (value == '\n'.code) {
                val parsed = Protocol.parseRequestLine(buffer.toString(Charsets.UTF_8.name()))
                drainHeaders(input)
                return parsed
            }
            if (value != '\r'.code) buffer.write(value)

            if (input.available() == 0) {
                val parsed = Protocol.parseRequestLine(buffer.toString(Charsets.UTF_8.name()))
                if (parsed != null) {
                    when (Protocol.parseRequest(parsed.first, parsed.second)) {
                        is Protocol.Request.Video, is Protocol.Request.Audio -> return parsed
                        else -> Unit
                    }
                }
            }
        }
        if (buffer.size() == 0 || buffer.size() >= MAX_REQUEST_LINE) return null
        return Protocol.parseRequestLine(buffer.toString(Charsets.UTF_8.name()))
    }

    private fun drainHeaders(input: InputStream) {
        try {
            var history = 0L
            while (true) {
                val value = input.read()
                if (value == -1) return
                history = ((history shl 8) or (value.toLong() and 0xFF)) and 0xFFFFFFFFL
                if (history == 0x0D0A0D0AL || (history and 0xFFFFL) == 0x0A0AL) return
            }
        } catch (_: Exception) {
        }
    }

    private fun respond(socket: Socket, text: String) {
        try {
            socket.getOutputStream().apply {
                write(text.toByteArray(Charsets.UTF_8))
                flush()
            }
        } catch (_: IOException) {
        }
    }

    fun closeVideoClients() {
        videoClientList.forEach { it.close() }
        videoClientList.clear()
    }

    fun closeAudioClients() {
        audioClientList.forEach { it.close() }
        audioClientList.clear()
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        acceptThread?.interrupt()
        try { acceptThread?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        acceptThread = null
        connectionPool.shutdownNow()
        closeVideoClients()
        closeAudioClients()
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 250
        const val MAX_REQUEST_LINE = 2048
        const val MAX_VIDEO_CLIENTS = 4
        const val MAX_AUDIO_CLIENTS = 4
        const val EMPTY_OK = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        const val SERVICE_UNAVAILABLE =
            "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    }
}
