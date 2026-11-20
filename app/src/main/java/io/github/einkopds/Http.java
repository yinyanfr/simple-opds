package io.github.einkopds;

import android.util.Base64;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.SSLException;

final class Http {
    private static final ExecutorService DISCONNECT = Executors.newSingleThreadExecutor();

    static final class Failure extends IOException {
        final UiMessage message;

        Failure(int resource, Object... arguments) {
            message = new UiMessage(resource, arguments);
        }
    }

    static final class Call {
        private final Proxy proxy;
        private volatile boolean cancelled;
        private volatile HttpURLConnection active;

        Call() {
            this(null);
        }

        Call(Proxy proxy) {
            this.proxy = proxy;
        }

        void cancel() {
            cancelled = true;
            HttpURLConnection connection = active;
            if (connection != null) DISCONNECT.execute(connection::disconnect);
        }

        boolean cancelled() {
            return cancelled;
        }

        void check() throws InterruptedIOException {
            if (cancelled) throw new InterruptedIOException();
        }
    }

    static final class Response implements Closeable {
        private final HttpURLConnection connection;
        private final Call call;
        final URL url;

        Response(HttpURLConnection connection, URL url, Call call) {
            this.connection = connection;
            this.url = url;
            this.call = call;
        }

        String header(String name) {
            return connection.getHeaderField(name);
        }

        long length() {
            if (isGzip()) return -1;
            try {
                return Long.parseLong(header("Content-Length"));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }

        private boolean isGzip() {
            return "gzip".equalsIgnoreCase(header("Content-Encoding"));
        }

        InputStream stream() throws IOException {
            call.check();
            InputStream input = connection.getInputStream();
            if (!isGzip()) return input;
            try {
                return new GZIPInputStream(input, 8192);
            } catch (IOException failure) {
                input.close();
                throw failure;
            }
        }

        @Override public void close() {
            call.active = null;
            connection.disconnect();
        }
    }

    private Http() {}

    static URL address(String value) throws MalformedURLException {
        URL url = new URL(value);
        if (!("http".equals(url.getProtocol()) || "https".equals(url.getProtocol()))
                || url.getHost().isEmpty() || url.getUserInfo() != null
                || value.indexOf(' ') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new MalformedURLException();
        }
        return url;
    }

    static String resolve(String base, String link) {
        if (link == null || link.trim().isEmpty()) return null;
        try {
            URL origin = new URL(base);
            String target = link.trim();
            URL absolute = target.startsWith("?")
                    ? new URL(origin.getProtocol(), origin.getHost(), origin.getPort(), origin.getPath() + target)
                    : new URL(origin, target);
            return address(absolute.toExternalForm()).toExternalForm();
        } catch (MalformedURLException ignored) {
            return null;
        }
    }

    static Response get(String address, LibraryStore.Source source, String accept,
                        boolean gzip, Call call) throws IOException {
        URL url = address(address);
        URL origin = address(source.address);
        for (int redirects = 0; redirects <= 8; redirects++) {
            call.check();
            HttpURLConnection connection = (HttpURLConnection) (call.proxy == null
                    ? url.openConnection() : url.openConnection(call.proxy));
            call.active = connection;
            boolean keep = false;
            try {
                call.check();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "EinkOPDS/1.0");
                connection.setRequestProperty("Accept", accept);
                connection.setRequestProperty("Accept-Encoding", gzip ? "gzip" : "identity");
                if (!source.user.isEmpty() && sameOrigin(origin, url)) {
                    byte[] credentials = (source.user + ":" + source.password)
                            .getBytes(StandardCharsets.UTF_8);
                    connection.setRequestProperty("Authorization",
                            "Basic " + Base64.encodeToString(credentials, Base64.NO_WRAP));
                }

                int status = connection.getResponseCode();
                if (status >= 200 && status < 300 && status != 206) {
                    keep = true;
                    return new Response(connection, url, call);
                }
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String next = resolve(url.toExternalForm(), connection.getHeaderField("Location"));
                    if (next == null) throw new Failure(R.string.error_redirect_invalid);
                    URL destination = address(next);
                    if ("https".equals(url.getProtocol()) && "http".equals(destination.getProtocol())) {
                        throw new Failure(R.string.error_redirect_insecure);
                    }
                    url = destination;
                    continue;
                }
                if (status == 401) throw new Failure(R.string.error_login_required);
                if (status == 403) throw new Failure(R.string.error_http_forbidden);
                if (status == 404) throw new Failure(R.string.error_http_not_found);
                if (status == 407) throw new Failure(R.string.error_proxy_auth_required);
                throw new Failure(R.string.error_http_status, status);
            } finally {
                if (!keep) {
                    call.active = null;
                    connection.disconnect();
                }
            }
        }
        throw new Failure(R.string.error_redirect_limit);
    }

    private static boolean sameOrigin(URL first, URL second) {
        int firstPort = first.getPort() == -1 ? first.getDefaultPort() : first.getPort();
        int secondPort = second.getPort() == -1 ? second.getDefaultPort() : second.getPort();
        return firstPort == secondPort && first.getProtocol().equals(second.getProtocol())
                && first.getHost().equalsIgnoreCase(second.getHost());
    }

    static String mediaType(String value) {
        return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    static InputStream bounded(InputStream input, int maxBytes, Call call) {
        return new FilterInputStream(input) {
            private int remaining = maxBytes;

            @Override public int read() throws IOException {
                call.check();
                int next = in.read();
                if (next != -1) consumed(1);
                return next;
            }

            @Override public int read(byte[] buffer, int offset, int size) throws IOException {
                call.check();
                int count = in.read(buffer, offset, Math.min(size, remaining + 1));
                if (count > 0) consumed(count);
                return count;
            }

            private void consumed(int count) throws Failure {
                remaining -= count;
                if (remaining < 0) throw new Failure(R.string.error_catalog_size);
            }
        };
    }

    static UiMessage error(Exception failure, int fallback) {
        if (failure instanceof Failure) return ((Failure) failure).message;
        if (failure instanceof SocketTimeoutException) return new UiMessage(R.string.error_timeout);
        if (failure instanceof SSLException) return new UiMessage(R.string.error_https);
        return new UiMessage(fallback);
    }
}
