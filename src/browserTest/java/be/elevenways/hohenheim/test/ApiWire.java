package be.elevenways.hohenheim.test;

import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.test.support.RateLimitExemption;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE golden harness of the {@code /api/v1} live wire: every route test records its exchanges here and compares the
 * transcript, byte for byte, to the committed capture of the same requests answered by Hohenheim java-rewrite
 * 1446a91a.
 *
 * AIDEV-NOTE: a difference is a changed wire, never a file to refresh; the current transcript is written under
 * {@code build/api-v1-wire/} for review. Status, the Content-Type header literal and the body bytes are compared raw,
 * with ONE normalization: the members of every JSON object are written in sorted key order, because the API builds
 * bodies with {@code Map.of}, whose iteration order is randomized per JVM (production answers vary the same way). The
 * canonical form keeps every scalar's raw lexeme and is only taken when re-emitting the parsed body in its own order
 * reproduces the received bytes exactly, so nothing but the order can move. A value that is genuinely per-run is
 * named by a {@link PerRun} member and declared on the exchange that carries it; every other id and instant comes
 * from a fixed fixture.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class ApiWire {

    /** Where the committed captures live, one transcript per route test. */
    private static final String GOLDEN_DIR = "/api-v1-wire/";

    /**
     * The values a reply carries that no fixture can pin, each with the reason it differs between two runs.
     *
     * AIDEV-NOTE: the reason is the justification the golden relies on; a member is never added to make a
     * difference go away, only for a value that the request itself stamps from the wall clock.
     */
    public enum PerRun {

        /** The create stamps {@code Now.instant()} on the row it inserts, and the projection serves it. */
        CREATED_AT("created_at");

        private final String member;

        PerRun(String member) {
            this.member = member;
        }

        /** @return the JSON member, at any depth, whose value this replaces */
        public @NonNull String member() {
            return this.member;
        }

        /** @return the stand-in written in place of the value */
        @NonNull String placeholder() {
            return "\"(per-run " + this.name() + ")\"";
        }
    }

    /** Who a request speaks as. */
    public sealed interface Caller {

        /** No credential at all. */
        record Anonymous() implements Caller {
        }

        /** An API key in the {@code X-Api-Key} header. */
        record Key(@NonNull String key) implements Caller {
        }

        /** A browser session cookie, the principal the automation API refuses. */
        record Session(@NonNull String cookie) implements Caller {
        }
    }

    /** One recorded exchange; its per-run declarations apply when the transcript is written. */
    public static final class Reply {

        private final String label;
        private final String request;
        private final int status;
        private final String contentType;
        private final byte[] body;
        private final Set<PerRun> perRun = EnumSet.noneOf(PerRun.class);

        private Reply(String label, String request, int status, String contentType, byte[] body) {
            this.label = label;
            this.request = request;
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        /** Declare that this reply carries {@code value}; an exchange may only declare what it really carries. */
        public @NonNull Reply perRun(@NonNull PerRun value) {
            this.perRun.add(value);
            return this;
        }

        public int status() {
            return this.status;
        }

        /** @return the body as UTF-8 text, for a test that reads an id out of it */
        public @NonNull String text() {
            return new String(this.body, StandardCharsets.UTF_8);
        }

        private @NonNull String render() {
            StringBuilder out = new StringBuilder();
            out.append("## ").append(this.label).append('\n');
            out.append("> ").append(this.request).append('\n');
            out.append("< ").append(this.status).append(' ').append(this.contentType).append('\n');
            out.append(renderBody(this.body, this.perRun)).append('\n');
            return out.toString();
        }
    }

    private final String name;
    private final Function<String, HttpRequest.Builder> requests;
    private final Function<HttpRequest.Builder, HttpResponse<byte[]>> transport;
    private final List<Reply> replies = new ArrayList<>();

    /**
     * @param name      the transcript's name, its golden file being {@code /api-v1-wire/<name>.txt}
     * @param requests  a request builder aimed at a path on the test server
     * @param transport the suite's transport, answering the raw body bytes
     */
    public ApiWire(@NonNull String name, @NonNull Function<String, HttpRequest.Builder> requests,
                   @NonNull Function<HttpRequest.Builder, HttpResponse<byte[]>> transport) {
        this.name = name;
        this.requests = requests;
        this.transport = transport;
    }

    public @NonNull Reply get(@NonNull String label, @NonNull Caller caller, @NonNull String path) {
        return this.send(label, "GET " + path, caller, this.requests.apply(path).GET());
    }

    /** A form-encoded POST, the shape every write of this API reads. */
    public @NonNull Reply post(@NonNull String label, @NonNull Caller caller, @NonNull String path,
                               @NonNull String form) {
        return this.post(label, caller, path, form.getBytes(StandardCharsets.UTF_8),
            "application/x-www-form-urlencoded");
    }

    /** A POST of raw bytes; the transcript names the body by its length, never its content. */
    public @NonNull Reply post(@NonNull String label, @NonNull Caller caller, @NonNull String path,
                               byte @NonNull [] body, @NonNull String contentType) {
        HttpRequest.Builder request = this.requests.apply(path)
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return this.send(label, "POST " + path + " (" + contentType + ", " + body.length + " bytes)", caller,
            request);
    }

    private @NonNull Reply send(String label, String request, Caller caller, HttpRequest.Builder builder) {
        switch (caller) {
            case Caller.Anonymous anonymous -> {
            }
            case Caller.Key key -> builder.header("X-Api-Key", key.key());
            case Caller.Session session -> builder.header("Cookie", session.cookie());
        }
        HttpResponse<byte[]> response = this.transport.apply(builder);
        Reply reply = new Reply(label, request, response.statusCode(),
            response.headers().firstValue("Content-Type").orElse("(none)"), response.body());
        this.replies.add(reply);
        return reply;
    }

    /** Compare the whole transcript to its committed capture, writing the current one for review when they differ. */
    public void assertGolden() {
        List<String> rendered = new ArrayList<>();
        for (Reply reply : this.replies) {
            rendered.add(reply.render());
        }
        String current = String.join("\n", rendered);
        String stored = golden(this.name);
        if (!current.equals(stored)) {
            Path written = Path.of(System.getProperty("user.dir"), "build", "api-v1-wire", this.name + ".txt");
            try {
                Files.createDirectories(written.getParent());
                Files.writeString(written, current, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        assertThat(current).as("the /api/v1 wire of %s is the java-rewrite capture (current at %s)",
            this.name, "build/api-v1-wire/" + this.name + ".txt").isEqualTo(stored);
    }

    /**
     * Run {@code body} with the declared rate limits of every endpoint in force, which the suite otherwise lifts.
     *
     * AIDEV-NOTE: the limiter's buckets outlive the call, so a caller spends them under a credential nothing else
     * uses.
     */
    public static void withRateLimits(@NonNull Runnable body) {
        RateLimitExemption.restore();
        try {
            body.run();
        } finally {
            RateLimitExemption.exemptAll();
        }
    }

    /** @return the operator account of the current database, holding every permission, created on first use */
    public static int operatorId() {
        return TenantConduits.operatorUser().get(UserModel.ID);
    }

    private static @NonNull String golden(@NonNull String name) {
        try (InputStream in = ApiWire.class.getResourceAsStream(GOLDEN_DIR + name + ".txt")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    // -- the body ----------------------------------------------------------------------------------------------

    /**
     * The body as written in the transcript: canonical JSON when it is JSON whose only freedom is member order,
     * otherwise its raw text, or base64 when it is not UTF-8.
     */
    private static @NonNull String renderBody(byte @NonNull [] body, @NonNull Set<PerRun> perRun) {
        if (body.length == 0) {
            return "(empty)";
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException binary) {
            return "(base64) " + Base64.getEncoder().encodeToString(body);
        }
        Node parsed = Json.parse(text);
        if (parsed == null || !parsed.write(false, Set.of()).equals(text)) {
            assertThat(perRun).as("a per-run value is only declared on a JSON reply: " + text).isEmpty();
            return text;
        }
        Set<String> members = new HashSet<>();
        for (PerRun value : perRun) {
            members.add(value.member());
        }
        String canonical = parsed.write(true, members);
        for (PerRun value : perRun) {
            assertThat(canonical).as("the reply really carries the declared per-run %s", value)
                .contains(value.placeholder());
        }
        return canonical;
    }

    /** A parsed JSON value that remembers every scalar's raw lexeme. */
    private sealed interface Node {

        /**
         * @param sorted  whether object members are written in sorted key order
         * @param perRun  the member names whose values are written as their {@link PerRun} placeholder
         */
        @NonNull String write(boolean sorted, @NonNull Set<String> perRun);
    }

    private record Scalar(String raw) implements Node {
        @Override
        public @NonNull String write(boolean sorted, @NonNull Set<String> perRun) {
            return this.raw;
        }
    }

    private record Array(List<Node> items) implements Node {
        @Override
        public @NonNull String write(boolean sorted, @NonNull Set<String> perRun) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < this.items.size(); i++) {
                if (i > 0) out.append(',');
                out.append(this.items.get(i).write(sorted, perRun));
            }
            return out.append(']').toString();
        }
    }

    /** Members in received order; keys are raw string lexemes, decoded only to compare. */
    private record Obj(List<Map.Entry<String, Node>> members) implements Node {
        @Override
        public @NonNull String write(boolean sorted, @NonNull Set<String> perRun) {
            List<Map.Entry<String, Node>> order = this.members;
            if (sorted) {
                TreeMap<String, Map.Entry<String, Node>> byKey = new TreeMap<>();
                for (Map.Entry<String, Node> member : this.members) {
                    byKey.put(Json.decodeKey(member.getKey()), member);
                }
                order = new ArrayList<>(byKey.values());
            }
            StringBuilder out = new StringBuilder("{");
            for (int i = 0; i < order.size(); i++) {
                if (i > 0) out.append(',');
                Map.Entry<String, Node> member = order.get(i);
                out.append(member.getKey()).append(':');
                PerRun value = sorted ? perRunOf(Json.decodeKey(member.getKey()), perRun) : null;
                out.append(value != null ? value.placeholder() : member.getValue().write(sorted, perRun));
            }
            return out.append('}').toString();
        }

        private static @Nullable PerRun perRunOf(String key, Set<String> perRun) {
            if (!perRun.contains(key)) return null;
            for (PerRun value : PerRun.values()) {
                if (value.member().equals(key)) return value;
            }
            return null;
        }
    }

    /** A strict, minimal JSON reader: null when the text is not one complete JSON value. */
    private static final class Json {

        private final String text;
        private int at;

        private Json(String text) {
            this.text = text;
        }

        static @Nullable Node parse(@NonNull String text) {
            Json reader = new Json(text);
            try {
                Node node = reader.value();
                reader.skipSpace();
                return reader.at == text.length() ? node : null;
            } catch (IllegalArgumentException notJson) {
                return null;
            }
        }

        static @NonNull String decodeKey(@NonNull String raw) {
            Json reader = new Json(raw);
            return reader.decodedString();
        }

        private Node value() {
            this.skipSpace();
            char c = this.peek();
            return switch (c) {
                case '{' -> this.object();
                case '[' -> this.array();
                case '"' -> new Scalar(this.rawString());
                default -> new Scalar(this.literal());
            };
        }

        private Node object() {
            this.at++;
            List<Map.Entry<String, Node>> members = new ArrayList<>();
            this.skipSpace();
            if (this.peek() == '}') {
                this.at++;
                return new Obj(members);
            }
            while (true) {
                this.skipSpace();
                if (this.peek() != '"') throw new IllegalArgumentException("key expected");
                String key = this.rawString();
                this.skipSpace();
                this.expect(':');
                members.add(Map.entry(key, this.value()));
                this.skipSpace();
                char next = this.peek();
                this.at++;
                if (next == '}') return new Obj(members);
                if (next != ',') throw new IllegalArgumentException("comma expected");
            }
        }

        private Node array() {
            this.at++;
            List<Node> items = new ArrayList<>();
            this.skipSpace();
            if (this.peek() == ']') {
                this.at++;
                return new Array(items);
            }
            while (true) {
                items.add(this.value());
                this.skipSpace();
                char next = this.peek();
                this.at++;
                if (next == ']') return new Array(items);
                if (next != ',') throw new IllegalArgumentException("comma expected");
            }
        }

        private String rawString() {
            int start = this.at;
            this.at++;
            while (true) {
                char c = this.peek();
                this.at++;
                if (c == '\\') {
                    this.at++;
                } else if (c == '"') {
                    return this.text.substring(start, this.at);
                }
            }
        }

        private String decodedString() {
            StringBuilder out = new StringBuilder();
            this.at++;
            while (true) {
                char c = this.peek();
                this.at++;
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escaped = this.peek();
                this.at++;
                switch (escaped) {
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(this.text.substring(this.at, this.at + 4), 16));
                        this.at += 4;
                    }
                    default -> out.append(escaped);
                }
            }
        }

        private String literal() {
            int start = this.at;
            while (this.at < this.text.length() && "{}[],: \t\r\n\"".indexOf(this.text.charAt(this.at)) < 0) {
                this.at++;
            }
            String raw = this.text.substring(start, this.at);
            if (!raw.equals("true") && !raw.equals("false") && !raw.equals("null")
                    && !raw.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
                throw new IllegalArgumentException("not a JSON literal: " + raw);
            }
            return raw;
        }

        private void skipSpace() {
            while (this.at < this.text.length() && " \t\r\n".indexOf(this.text.charAt(this.at)) >= 0) {
                this.at++;
            }
        }

        private char peek() {
            if (this.at >= this.text.length()) throw new IllegalArgumentException("unexpected end");
            return this.text.charAt(this.at);
        }

        private void expect(char c) {
            if (this.peek() != c) throw new IllegalArgumentException(c + " expected");
            this.at++;
        }
    }
}
