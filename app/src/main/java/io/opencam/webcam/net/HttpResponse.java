package io.opencam.webcam.net;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Minimal HTTP response helpers for the embedded server. */
public final class HttpResponse {

    public static final String[] NO_CACHE = {"Expires: 0", "Cache-Control: no-cache, must-revalidate"};

    private HttpResponse() {
    }

    /** Read request bytes until the blank line ending the head (up to 8 KiB). */
    public static String readRequestHead(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        int b;
        int lf = 0;
        while ((b = in.read()) != -1) {
            sb.append((char) b);
            if (b == '\n') {
                lf++;
                if (lf >= 2) {
                    break;
                }
            } else if (b != '\r') {
                lf = 0;
            }
            if (sb.length() > 8192) {
                break;
            }
        }
        return sb.toString();
    }

    public static void send(Socket socket, String status, String contentType,
                            byte[] body, String[] extraHeaders) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(status).append("\r\n");
        // One-shot responses: close after writing so the handler thread doesn't leak the
        // socket. Stream endpoints (/video, /v5/video/*, /v2/audio) do NOT use these helpers
        // and keep their socket open for the lifetime of the stream.
        sb.append("Connection: close\r\n");
        sb.append("Content-Type: ")
          .append(contentType == null ? "text/plain; charset=UTF-8" : contentType)
          .append("\r\n");
        sb.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
        if (extraHeaders != null) {
            for (String h : extraHeaders) {
                sb.append(h).append("\r\n");
            }
        }
        sb.append("\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        if (body != null) {
            out.write(body);
        }
        out.flush();
        socket.close();
    }

    public static void sendText(Socket socket, String body) throws IOException {
        send(socket, "200 OK", null, body.getBytes(StandardCharsets.UTF_8), NO_CACHE);
    }

    public static void sendJson(Socket socket, String body) throws IOException {
        send(socket, "200 OK", "text/json; charset=UTF-8", body.getBytes(StandardCharsets.UTF_8), NO_CACHE);
    }

    public static void sendHtml(Socket socket, String body) throws IOException {
        send(socket, "200 OK", "text/html; charset=UTF-8", body.getBytes(StandardCharsets.UTF_8), NO_CACHE);
    }

    public static void sendRedirect(Socket socket, String location) throws IOException {
        send(socket, "302 Found", null, new byte[0], new String[]{"Location: " + location});
    }

    public static void sendError(Socket socket, String status) throws IOException {
        send(socket, status, null, new byte[0], NO_CACHE);
    }

    /** Serve a file from assets/www using chunked transfer encoding. */
    public static void sendAsset(Context context, Socket socket, String assetPath) throws IOException {
        if (assetPath.contains("..") || assetPath.contains("//")) {
            sendError(socket, "403 Forbidden");
            return;
        }
        InputStream in = null;
        try {
            in = context.getAssets().open("www/" + assetPath);
        } catch (IOException e) {
            sendError(socket, "404 Not Found");
            return;
        }
        String type = "text/plain; charset=UTF-8";
        if (assetPath.endsWith(".html") || assetPath.endsWith(".htm")) {
            type = "text/html; charset=UTF-8";
        } else if (assetPath.endsWith(".css")) {
            type = "text/css; charset=UTF-8";
        } else if (assetPath.endsWith(".js")) {
            type = "text/javascript; charset=UTF-8";
        } else if (assetPath.endsWith(".ico")) {
            type = "image/x-icon";
        }
        StringBuilder sb = new StringBuilder(192);
        sb.append("HTTP/1.1 200 OK\r\n");
        sb.append("Transfer-Encoding: chunked\r\n");
        sb.append("Connection: close\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        byte[] buf = new byte[8190];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(String.format("%x\r\n", n).getBytes(StandardCharsets.US_ASCII));
            out.write(buf, 0, n);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        in.close();
        socket.close();
    }
}
