package com.opencam

import com.opencam.server.AudioClient
import com.opencam.server.ServerCallbacks
import com.opencam.server.StreamServer
import com.opencam.server.VideoClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StreamServerTest {

    private fun getFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private fun createMockCallbacks(): ServerCallbacks = object : ServerCallbacks {
        override fun onVideoClientConnected(client: VideoClient) {}
        override fun onVideoClientDisconnected(client: VideoClient) {}
        override fun onAudioClientConnected(client: AudioClient) {}
        override fun onAudioClientDisconnected(client: AudioClient) {}
        override fun batteryPercent(): Int = 99
        override fun onTally(state: String) {}
        override fun statusJson(): String = """{"status":"ok"}"""
        override fun applySettings(params: Map<String, String>) {}
    }

    @Test
    fun testVideoAndAudioClientsZeroCopy() {
        val server = StreamServer(getFreePort(), createMockCallbacks())
        // videoClients and audioClients should return the CopyOnWriteArrayList directly
        // without allocating a new .toList() wrapper every call.
        val list1 = server.videoClients
        val list2 = server.videoClients
        assertSame("videoClients must return backing list reference directly to avoid GC churn", list1, list2)

        val audio1 = server.audioClients
        val audio2 = server.audioClients
        assertSame("audioClients must return backing list reference directly to avoid GC churn", audio1, audio2)
    }

    @Test
    fun testStartAndStopGraceful() {
        val port = getFreePort()
        val server = StreamServer(port, createMockCallbacks())
        assertTrue("Server start should succeed", server.start())

        // Ensure server socket accepts connections
        Socket("127.0.0.1", port).use { client ->
            assertTrue("Client should connect", client.isConnected)
        }

        // stop() should gracefully interrupt acceptThread without throwing uncaught InterruptedException
        server.stop()

        // Verify port can be rebound immediately (reuseAddress or freed socket)
        val server2 = StreamServer(port, createMockCallbacks())
        assertTrue("Server should be restartable on freed port", server2.start())
        server2.stop()
    }

    @Test
    fun testRapidStartStopCycles() {
        for (i in 1..5) {
            val port = getFreePort()
            val server = StreamServer(port, createMockCallbacks())
            assertTrue(server.start())
            server.stop()
        }
    }

    @Test
    fun testConnectionSpikeHandlingWithAbortPolicy() {
        val port = getFreePort()
        val server = StreamServer(port, createMockCallbacks())
        assertTrue(server.start())

        val sockets = mutableListOf<Socket>()
        try {
            // Burst connect > 36 connections to exceed pool core (2) + max (4) + queue (32) = 36 capacity
            for (i in 1..40) {
                try {
                    val s = Socket("127.0.0.1", port)
                    s.tcpNoDelay = true
                    sockets.add(s)
                } catch (_: Exception) {
                    // Rejection by server or OS backlog is acceptable
                }
            }
        } finally {
            sockets.forEach { try { it.close() } catch (_: Exception) {} }
            server.stop()
        }
    }
}
