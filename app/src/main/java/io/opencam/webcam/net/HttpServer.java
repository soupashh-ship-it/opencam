package io.opencam.webcam.net;

import io.opencam.webcam.util.Logs;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Small keep-alive HTTP server. Accept loop runs on its own thread; every connection gets a
 * handler thread that reads one request head and delegates to the {@link Host}.
 */
public class HttpServer extends Thread {

    /** Callback that consumes one HTTP request. Return false to close the connection. */
    public interface Host {
        boolean handleRequest(String requestHead, Socket socket);
    }

    private final int port;
    private final Host host;
    private volatile boolean running;
    private ServerSocket serverSocket;

    public HttpServer(int port, Host host) {
        this.port = port;
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setSoTimeout(500);
            running = true;
            Logs.i("server listening on :" + port);
        } catch (IOException e) {
            Logs.e("server bind failed: " + e);
            return;
        }
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (running) {
                    Logs.e("accept error: " + e);
                }
                break;
            }
            handleConnection(socket);
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        Logs.i("server stopped");
    }

    private void handleConnection(final Socket socket) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    socket.setSoTimeout(10000);
                    String head = HttpResponse.readRequestHead(socket.getInputStream());
                    int firstLf = head.indexOf('\n');
                    String requestLine = firstLf > 0 ? head.substring(0, firstLf).trim() : head.trim();
                    if (requestLine.isEmpty() || !host.handleRequest(requestLine, socket)) {
                        try {
                            socket.close();
                        } catch (IOException ignored) {
                        }
                    }
                } catch (IOException e) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }, "http-handler").start();
    }

    public void shutdown() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
    }
}
