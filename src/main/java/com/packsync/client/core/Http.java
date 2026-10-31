package com.packsync.client.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/** HttpURLConnection lives in java.base: no module surprises under Forge. */
public final class Http {
    public static final String USER_AGENT = "PackSync/1.0 (Minecraft Forge 1.20.1)";

    private Http() {}

    public static final class StatusException extends IOException {
        public final int status;

        public StatusException(int status, String url) {
            super("HTTP " + status + " from " + url);
            this.status = status;
        }
    }

    public static HttpURLConnection open(String url, int connectMs, int readMs) throws IOException {
        URL u;
        try {
            u = URI.create(url).toURL();
        } catch (IllegalArgumentException e) {
            throw new IOException("Bad URL: " + url);
        }
        String proto = u.getProtocol();
        if (!proto.equals("http") && !proto.equals("https")) throw new IOException("Unsupported URL: " + url);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Cache-Control", "no-cache");
        return c;
    }

    public static byte[] get(String url, int maxBytes) throws IOException {
        HttpURLConnection c = open(url, 8000, 20000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new StatusException(code, url);
            long len = c.getContentLengthLong();
            if (len > maxBytes) throw new IOException("Response too large");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(len > 0 ? (int) len : 8192);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) throw new IOException("Response too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
