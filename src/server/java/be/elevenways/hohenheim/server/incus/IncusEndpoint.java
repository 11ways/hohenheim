package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The parsed {@code incus_url} of one host record: {@code https://host[:8443]} or a
 * unix socket ({@code unix://[/path]}, blank = the default socket).
 */
public record IncusEndpoint(boolean https, @NonNull String host, int port,
                            @NonNull String socketPath) {

    /** Default Incus API port. */
    public static final int DEFAULT_PORT = 8443;

    public static @NonNull IncusEndpoint of(@NonNull Row server) {
        return parse(server.get(ServerModel.INCUS_URL));
    }

    /**
     * @throws IllegalArgumentException on a spelling that is neither https nor unix --
     *         a URL nothing validated must never reach a connect call
     */
    public static @NonNull IncusEndpoint parse(@Nullable String raw) {
        String url = raw != null ? raw.trim() : "";
        if (url.isEmpty() || url.equals("unix://")) {
            return new IncusEndpoint(false, "", 0, UnixIncusTransport.DEFAULT_SOCKET);
        }
        if (url.startsWith("unix://")) {
            return new IncusEndpoint(false, "", 0, url.substring("unix://".length()));
        }
        if (!url.startsWith("https://")) {
            throw new IllegalArgumentException("Incus URL must be https://host[:port]"
                + " or unix://[path], got '" + url + "'");
        }
        String rest = url.substring("https://".length());
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        String host = rest;
        int port = DEFAULT_PORT;
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("unclosed IPv6 literal in '" + url + "'");
            }
            host = rest.substring(1, close);
            if (rest.length() > close + 1 && rest.charAt(close + 1) == ':') {
                port = parsePort(rest.substring(close + 2), url);
            }
        } else {
            int colon = rest.lastIndexOf(':');
            if (colon >= 0) {
                host = rest.substring(0, colon);
                port = parsePort(rest.substring(colon + 1), url);
            }
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("no host in Incus URL '" + url + "'");
        }
        return new IncusEndpoint(true, host, port, "");
    }

    private static int parsePort(@NonNull String text, @NonNull String url) {
        try {
            int port = Integer.parseInt(text);
            if (port <= 0 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad port in Incus URL '" + url + "'");
        }
    }

    public @NonNull String describe() {
        return this.https ? "https://" + this.host + ":" + this.port
            : "unix://" + this.socketPath;
    }
}
