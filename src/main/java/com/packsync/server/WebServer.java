package com.packsync.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, dependency-free static file server for packsync/web.
 * Only a fixed whitelist of URLs is served (no directory traversal possible),
 * GET/HEAD only, bounded headers, per-IP and global connection limits, Range support.
 */
public final class WebServer implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger("PackSync");
    private static final Pattern FILE_URL = Pattern.compile("^/files/([0-9a-f]{64})$");
    private static final Pattern RANGE = Pattern.compile("^bytes=(\\d{1,15})-(\\d{0,15})$");
    private static final int MAX_HEADER = 8192;

    private final Path webRoot;
    private final String bind;
    private final int port;
    private final int maxPerIp;
    private final ThreadPoolExecutor pool;
    private final Map<InetAddress, AtomicInteger> perIp = new ConcurrentHashMap<>();
    private volatile ServerSocket socket;
    private volatile boolean running;

    public WebServer(Path webRoot, String bind, int port, int maxConnections, int maxPerIp) {
        this.webRoot = webRoot;
        this.bind = bind;
        this.port = port;
        this.maxPerIp = maxPerIp;
        AtomicInteger n = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(0, maxConnections, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "PackSync-HTTP-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public void start() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        InetSocketAddress addr = bind == null || bind.isBlank() || bind.equals("0.0.0.0")
                ? new InetSocketAddress(port) : new InetSocketAddress(bind, port);
        ss.bind(addr, 128);
        socket = ss;
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "PackSync-HTTP-Accept");
        acceptor.setDaemon(true);
        acceptor.start();
        LOG.info("[PackSync] File server listening on {}:{}", addr.getHostString(), ss.getLocalPort());
    }

    public int boundPort() {
        ServerSocket s = socket;
        return s == null ? -1 : s.getLocalPort();
    }

    public boolean isRunning() { return running; }

    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                s = socket.accept();
            } catch (IOException e) {
                if (running) LOG.warn("[PackSync] accept failed: {}", e.getMessage());
                continue;
            }
            InetAddress ip = s.getInetAddress();
            AtomicInteger count = perIp.computeIfAbsent(ip, k -> new AtomicInteger());
            if (count.incrementAndGet() > maxPerIp) {
                count.decrementAndGet();
                quickReject(s, 429, "Too Many Requests");
                continue;
            }
            try {
                pool.execute(() -> {
                    try {
                        handle(s);
                    } finally {
                        if (count.decrementAndGet() <= 0) perIp.remove(ip, count);
                        closeQuietly(s);
                    }
                });
            } catch (RejectedExecutionException e) {
                count.decrementAndGet();
                quickReject(s, 503, "Service Unavailable");
            }
        }
    }

    private void quickReject(Socket s, int code, String text) {
        try {
            s.setSoTimeout(2000);
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 " + code + " " + text + "\r\nRetry-After: 2\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException ignored) {
        } finally {
            closeQuietly(s);
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30_000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String head = readHead(in);
            OutputStream out = s.getOutputStream();
            if (head == null) {
                respond(out, 400, "Bad Request", "text/plain", "bad request\n".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length != 3 || !req[2].startsWith("HTTP/1.")) {
                respond(out, 400, "Bad Request", "text/plain", "bad request\n".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            String method = req[0];
            boolean headOnly = method.equals("HEAD");
            if (!method.equals("GET") && !headOnly) {
                respond(out, 405, "Method Not Allowed", "text/plain", "GET only\n".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            String range = null;
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0 && lines[i].substring(0, c).trim().equalsIgnoreCase("range")) range = lines[i].substring(c + 1).trim();
            }
            String path = req[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            Path file;
            String type;
            String cache;
            Matcher fm = FILE_URL.matcher(path);
            if (fm.matches()) {
                file = webRoot.resolve("files").resolve(fm.group(1));
                type = "application/octet-stream";
                cache = "public, max-age=31536000, immutable";
            } else {
                switch (path) {
                    case "/manifest.json" -> { file = webRoot.resolve("manifest.json"); type = "application/json"; cache = "no-cache"; }
                    case "/packsync-client.json" -> { file = webRoot.resolve("packsync-client.json"); type = "application/json"; cache = "no-cache"; }
                    case "/pack.zip" -> { file = webRoot.resolve("pack.zip"); type = "application/zip"; cache = "no-cache"; }
                    case "/", "/index.html" -> { file = webRoot.resolve("index.html"); type = "text/html; charset=utf-8"; cache = "no-cache"; }
                    case "/health" -> {
                        respond(out, 200, "OK", "text/plain", "ok\n".getBytes(StandardCharsets.UTF_8), headOnly);
                        return;
                    }
                    default -> {
                        respond(out, 404, "Not Found", "text/plain", "not found\n".getBytes(StandardCharsets.UTF_8), headOnly);
                        return;
                    }
                }
            }
            if (!Files.isRegularFile(file)) {
                respond(out, 404, "Not Found", "text/plain", "not found\n".getBytes(StandardCharsets.UTF_8), headOnly);
                return;
            }
            sendFile(out, file, type, cache, range, headOnly);
        } catch (SocketException ignored) {
            // client went away
        } catch (IOException e) {
            LOG.debug("[PackSync] http error: {}", e.toString());
        }
    }

    private void sendFile(OutputStream out, Path file, String type, String cache, String range, boolean headOnly) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long len = raf.length();
            long start = 0;
            long end = len - 1;
            boolean partial = false;
            if (range != null) {
                Matcher m = RANGE.matcher(range);
                if (m.matches()) {
                    start = Long.parseLong(m.group(1));
                    if (!m.group(2).isEmpty()) end = Math.min(end, Long.parseLong(m.group(2)));
                    if (start >= len || start > end) {
                        String h = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + len + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                        out.write(h.getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        return;
                    }
                    partial = true;
                }
            }
            long count = len == 0 ? 0 : end - start + 1;
            StringBuilder h = new StringBuilder();
            h.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: ").append(type).append("\r\n");
            h.append("Content-Length: ").append(count).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(len).append("\r\n");
            h.append("Cache-Control: ").append(cache).append("\r\n");
            h.append("X-Content-Type-Options: nosniff\r\n");
            h.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(java.time.ZoneOffset.UTC))).append("\r\n");
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
            if (!headOnly && count > 0) {
                raf.seek(start);
                byte[] buf = new byte[1 << 16];
                long left = count;
                while (left > 0) {
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
            }
            out.flush();
        }
    }

    private static void respond(OutputStream out, int code, String text, String type, byte[] body, boolean headOnly) throws IOException {
        String h = "HTTP/1.1 " + code + " " + text + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        if (!headOnly) out.write(body);
        out.flush();
    }

    /** Reads the request head up to CRLFCRLF, or returns null if it is too large/malformed. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int state = 0;
        while (buf.size() < MAX_HEADER) {
            int b = in.read();
            if (b < 0) return null;
            buf.write(b);
            state = switch (state) {
                case 0, 2 -> b == '\r' ? state + 1 : 0;
                case 1, 3 -> b == '\n' ? state + 1 : (b == '\r' ? 1 : 0);
                default -> 0;
            };
            if (state == 4) {
                String s = buf.toString(StandardCharsets.ISO_8859_1);
                return s.substring(0, s.length() - 4);
            }
        }
        return null;
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) { }
    }

    @Override
    public void close() {
        running = false;
        ServerSocket s = socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
        pool.shutdownNow();
        LOG.info("[PackSync] File server stopped");
    }

    @Override
    public String toString() {
        return "WebServer[" + bind + ":" + port + "]" + (running ? " running" : " stopped");
    }
}
