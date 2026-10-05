package io.github.orhanyarkin.saiman.sellerapi.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One HTTP/1.1 request over a raw socket. The JDK HTTP client refuses to set a {@code Host} header, and the
 * {@code /internal/**} guards are about exactly that header (and about odd path forms a client would normalise).
 */
public final class RawHttp {

    private RawHttp() {}

    /** The answer: status, lower-cased header block, raw body (possibly chunked, so assert with {@code contains}). */
    public record Response(int status, String headers, String body) {

        /** The value of header {@code name} (lower case), or the empty string. */
        public String header(String name) {
            int start = headers.indexOf("\r\n" + name + ":");
            if (start < 0) {
                return "";
            }
            int end = headers.indexOf("\r\n", start + 2);
            return headers.substring(start + name.length() + 3, end < 0 ? headers.length() : end)
                    .trim();
        }
    }

    /**
     * Sends {@code method path} to the local server on {@code port} with the given {@code Host} and extra headers, and a
     * JSON body if {@code json} is not null.
     */
    public static Response exchange(
            int port, String method, String path, String host, Map<String, String> headers, @Nullable String json)
            throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(30_000);
            StringBuilder request = new StringBuilder();
            request.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(host).append("\r\n");
            request.append("Accept: application/json, application/problem+json\r\n");
            headers.forEach((name, value) ->
                    request.append(name).append(": ").append(value).append("\r\n"));
            byte[] body = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
            if (json != null) {
                request.append("Content-Type: application/json\r\n");
                request.append("Content-Length: ").append(body.length).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            InputStream in = socket.getInputStream();
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int end = raw.indexOf("\r\n\r\n");
            String head = end < 0 ? raw : raw.substring(0, end);
            String rest = end < 0 ? "" : raw.substring(end + 4);
            int status = Integer.parseInt(head.substring(9, 12));
            return new Response(status, head.toLowerCase(Locale.ROOT), rest);
        }
    }
}
