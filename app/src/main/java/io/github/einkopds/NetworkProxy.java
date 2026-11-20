package io.github.einkopds;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.IDN;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;

final class NetworkProxy {
    enum Mode {
        SYSTEM(R.string.proxy_system, null),
        DIRECT(R.string.proxy_direct, null),
        HTTP(R.string.proxy_http, Proxy.Type.HTTP),
        SOCKS(R.string.proxy_socks, Proxy.Type.SOCKS);

        final int label;
        final Proxy.Type type;

        Mode(int label, Proxy.Type type) {
            this.label = label;
            this.type = type;
        }

        boolean usesEndpoint() {
            return type != null;
        }
    }

    final Mode mode;
    final String host;
    final int port;

    NetworkProxy(Mode mode, String host, int port) {
        this.mode = mode;
        this.host = host;
        this.port = port;
    }

    static NetworkProxy load(Context context) {
        SharedPreferences preferences = preferences(context);
        String savedMode = preferences.getString("mode", Mode.SYSTEM.name());
        String host = preferences.getString("host", "");
        int port = preferences.getInt("port", 0);
        try {
            return new NetworkProxy(Mode.valueOf(savedMode), host, port);
        } catch (IllegalArgumentException ignored) {
            return new NetworkProxy(Mode.SYSTEM, host, port);
        }
    }

    void save(Context context) {
        preferences(context).edit().putString("mode", mode.name()).putString("host", host)
                .putInt("port", port).apply();
    }

    Proxy forConnection() {
        if (mode == Mode.SYSTEM) return null;
        if (mode == Mode.DIRECT) return Proxy.NO_PROXY;
        return new Proxy(mode.type, InetSocketAddress.createUnresolved(host, port));
    }

    String summary(Context context) {
        String label = context.getString(mode.label);
        return mode.usesEndpoint() ? label + " · " + (host.contains(":") ? "[" + host + "]" : host)
                + ":" + port : label;
    }

    static String parseHost(String input) {
        String host = input.trim();
        if (host.isEmpty()) throw new IllegalArgumentException();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (!host.contains(":")) {
            String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            if (ascii.isEmpty() || ascii.equals(".") || ascii.length() > 253) throw new IllegalArgumentException();
            return ascii;
        }
        // URI validates the IPv6 syntax without querying DNS or opening a socket.
        if (URI.create("http://[" + host + "]").getHost() == null) throw new IllegalArgumentException();
        return host;
    }

    static int parsePort(String input) {
        int port = Integer.parseInt(input.trim());
        if (port < 1 || port > 65535) throw new IllegalArgumentException();
        return port;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("network_proxy", Context.MODE_PRIVATE);
    }
}
