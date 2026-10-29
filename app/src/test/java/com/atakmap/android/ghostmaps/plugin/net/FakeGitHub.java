package com.atakmap.android.ghostmaps.plugin.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny local stand-in for api.github.com and raw.githubusercontent.com,
 * with switches for the failure modes the client must survive. Plain
 * java.net sockets (one request per connection), so it runs under Android
 * unit tests, which lack the JDK's com.sun.net.httpserver.
 */
final class FakeGitHub implements AutoCloseable {

    private final ServerSocket server;
    private final Thread acceptor;
    volatile String branch = "main";
    volatile String defaultBranch = "main";
    volatile String commit = "1111111111111111111111111111111111111111";
    final Map<String, byte[]> files = Collections.synchronizedMap(new LinkedHashMap<String, byte[]>());

    // failure switches
    volatile boolean rateLimited;
    volatile int raw500s; // next N raw requests answer 500
    volatile int rawCutAfter = -1; // drop the connection after this many body bytes (once)
    volatile boolean ignoreRange;
    volatile boolean wrongContentRange;
    volatile byte[] corruptBody; // serve these bytes instead (once)
    volatile boolean redirectRawElsewhere; // 302 to the same server under another host name

    final AtomicInteger apiCalls = new AtomicInteger();
    final AtomicInteger treeCalls = new AtomicInteger();
    final List<String> rangeHeaders = Collections.synchronizedList(new ArrayList<String>());

    /** One HTTP request. */
    private static final class Request {
        String path;
        final Map<String, String> headers = new LinkedHashMap<>();

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }
    }

    /** One HTTP response. */
    private static final class Response {
        final int code;
        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body;
        int cutAfter = -1;

        Response(int code, String body) {
            this.code = code;
            this.body = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        }
    }

    FakeGitHub() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "FakeGitHub");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    String apiBase() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/api";
    }

    String rawBase() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/raw";
    }

    static String blobSha(byte[] data) throws Exception {
        return com.atakmap.android.ghostmaps.plugin.core.GitBlobHasher.of(data);
    }

    static byte[] bytes(int n, int seed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < n; i++)
            out.write((i * 31 + seed) & 0xff);
        return out.toByteArray();
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    // ------------------------------------------------------------- handlers

    private Response handle(Request q) throws Exception {
        if (q.path.startsWith("/api/"))
            return api(q);
        if (q.path.startsWith("/raw/"))
            return raw(q);
        return new Response(404, "{}");
    }

    private Response api(Request q) throws Exception {
        apiCalls.incrementAndGet();
        if (rateLimited) {
            Response r = new Response(403, "{}");
            r.headers.put("X-RateLimit-Remaining", "0");
            r.headers.put("X-RateLimit-Reset", "1900000000");
            return r;
        }
        String path = q.path;
        if (path.equals("/api/repos/o/r"))
            return new Response(200, new JSONObject().put("default_branch", defaultBranch).toString());
        if (path.startsWith("/api/repos/o/r/commits/")) {
            String b = path.substring("/api/repos/o/r/commits/".length());
            if (!b.equals(branch))
                return new Response(404, "{}");
            String etag = "\"" + commit + "\"";
            if (etag.equals(q.header("If-None-Match")))
                return new Response(304, null);
            Response r = new Response(200, commit);
            r.headers.put("ETag", etag);
            return r;
        }
        if (path.startsWith("/api/repos/o/r/git/trees/")) {
            treeCalls.incrementAndGet();
            JSONArray tree = new JSONArray();
            synchronized (files) {
                for (Map.Entry<String, byte[]> e : files.entrySet())
                    tree.put(new JSONObject().put("path", e.getKey()).put("type", "blob")
                            .put("sha", blobSha(e.getValue())).put("size", e.getValue().length));
            }
            return new Response(200, new JSONObject().put("tree", tree).put("truncated", false).toString());
        }
        return new Response(404, "{}");
    }

    private Response raw(Request q) throws Exception {
        if (redirectRawElsewhere && !"localhost".equals(hostOf(q))) {
            Response r = new Response(302, null);
            r.headers.put("Location", "http://localhost:" + server.getLocalPort() + q.path);
            return r;
        }
        if (raw500s > 0) {
            raw500s--;
            return new Response(500, "oops");
        }
        String rest = q.path.substring("/raw/o/r/".length());
        int slash = rest.indexOf('/');
        String c = rest.substring(0, slash);
        String path = URLDecoder.decode(rest.substring(slash + 1).replace("+", "%2B"), "UTF-8");
        byte[] data = files.get(path);
        if (!c.equals(commit) || data == null)
            return new Response(404, "missing");
        if (corruptBody != null) {
            data = corruptBody;
            corruptBody = null;
        }
        String range = q.header("Range");
        rangeHeaders.add(String.valueOf(range));
        int start = 0;
        Response r;
        if (range != null && !ignoreRange) {
            start = Integer.parseInt(range.substring("bytes=".length(), range.indexOf('-')));
            int shownStart = wrongContentRange ? start + 1 : start;
            r = new Response(206, null);
            r.headers.put("Content-Range", "bytes " + shownStart + "-" + (data.length - 1) + "/" + data.length);
        } else {
            r = new Response(200, null);
        }
        byte[] body = new byte[data.length - start];
        System.arraycopy(data, start, body, 0, body.length);
        r.body = body;
        if (rawCutAfter >= 0) {
            r.cutAfter = rawCutAfter;
            rawCutAfter = -1;
        }
        return r;
    }

    private static String hostOf(Request q) {
        String h = q.header("Host");
        return h == null ? "" : h.replaceAll(":.*$", "");
    }

    // ------------------------------------------------------------ plumbing

    private void acceptLoop() {
        while (!server.isClosed()) {
            final Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                return; // closed
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve(s);
                }
            }, "FakeGitHub-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            InputStream in = new BufferedInputStream(sock.getInputStream());
            Request q = new Request();
            String line = readLine(in);
            if (line == null)
                return;
            String[] parts = line.split(" ");
            String target = parts.length > 1 ? parts[1] : "/";
            int qm = target.indexOf('?');
            q.path = qm < 0 ? target : target.substring(0, qm);
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0)
                    q.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                            line.substring(colon + 1).trim());
            }
            Response r;
            try {
                r = handle(q);
            } catch (Exception e) {
                r = new Response(500, "{}");
            }
            write(sock.getOutputStream(), r);
        } catch (IOException ignored) {
            // client went away
        }
    }

    private static void write(OutputStream out, Response r) throws IOException {
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(r.code).append(' ')
                .append(reason(r.code)).append("\r\n");
        for (Map.Entry<String, String> e : r.headers.entrySet())
            h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        int len = r.body == null ? 0 : r.body.length;
        h.append("Content-Length: ").append(len).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        if (r.body != null) {
            int n = r.cutAfter >= 0 ? Math.min(r.cutAfter, len) : len;
            out.write(r.body, 0, n);
        }
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200:
                return "OK";
            case 206:
                return "Partial Content";
            case 304:
                return "Not Modified";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            default:
                return "Error";
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n')
                break;
            if (c != '\r')
                sb.append((char) c);
        }
        return c == -1 && sb.length() == 0 ? null : sb.toString();
    }
}
