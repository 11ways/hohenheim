package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.docker.DockerStreamConnection;
import be.elevenways.hohenheim.server.docker.DockerStreamTransport;
import be.elevenways.hohenheim.server.docker.DockerTransport;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.hohenheim.server.util.Tar;
import be.elevenways.protoblast.common.dry.Dry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A hermetic container FILESYSTEM behind the Docker Engine API: container inspect, the
 * archive endpoints (HEAD lstat, GET single-file tar, PUT tar extraction) and the exec lane
 * the file manager's constant scripts ride -- over BOTH transport faces, so the real
 * {@code DockerClient} and {@code DockerInstanceRuntime} run unmodified against it.
 *
 * AIDEV-NOTE: a separate fake from {@link FakeDockerDaemon} on purpose: that one models the
 * release tier's container lifecycle and has no archive, exec or streaming face; this one
 * models only what the file lane touches. Like the daemon it resolves an INTERMEDIATE
 * symlink component and reports the result as an ordinary file (the behaviour the
 * containment walk exists for), and it can be told to answer a read with a tar the
 * container authored ({@link #answerArchive}), which is how the host-read races are staged.
 * Every mutating act lands in {@link #calls()}, so a refusal can be proven to have written
 * nothing.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class FakeContainerFiles implements DockerTransport, DockerStreamTransport {

    /** What one node of the fake filesystem is. */
    public enum Kind { FILE, DIRECTORY, SYMLINK }

    /** One node; content for files, target for links. */
    public static final class Node {
        final @NonNull Kind kind;
        byte @NonNull [] content;
        final @NonNull String target;
        int mode;
        @NonNull String owner = "0:0";

        Node(@NonNull Kind kind, byte @NonNull [] content, @NonNull String target, int mode) {
            this.kind = kind;
            this.content = content;
            this.target = target;
            this.mode = mode;
        }
    }

    private final @NonNull String handle;
    private @NonNull Map<String, String> labels;
    private final TreeMap<String, Node> nodes = new TreeMap<>();
    private final List<String> calls = new ArrayList<>();
    private final List<String> targets = new ArrayList<>();
    private final Map<String, byte[]> answers = new LinkedHashMap<>();
    private final Map<String, List<String>> execs = new LinkedHashMap<>();
    private final List<String> lastPutEntries = new ArrayList<>();
    private int execCounter;

    public FakeContainerFiles(@NonNull String handle, @NonNull Map<String, String> labels) {
        this.handle = handle;
        this.labels = Map.copyOf(labels);
        this.nodes.put("/", new Node(Kind.DIRECTORY, new byte[0], "", 0755));
    }

    // -- scripting ------------------------------------------------------------

    public synchronized @NonNull FakeContainerFiles directory(@NonNull String path) {
        this.nodes.put(path, new Node(Kind.DIRECTORY, new byte[0], "", 0755));
        return this;
    }

    public synchronized @NonNull FakeContainerFiles file(@NonNull String path, @NonNull String text) {
        return file(path, text.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized @NonNull FakeContainerFiles file(@NonNull String path, byte @NonNull [] content) {
        this.nodes.put(path, new Node(Kind.FILE, content, "", 0644));
        return this;
    }

    public synchronized @NonNull FakeContainerFiles symlink(@NonNull String path, @NonNull String target) {
        this.nodes.put(path, new Node(Kind.SYMLINK, new byte[0], target, 0777));
        return this;
    }

    /** Answer the next archive READ of {@code path} with this raw tar instead of the file. */
    public synchronized void answerArchive(@NonNull String path, byte @NonNull [] tar) {
        this.answers.put(path, tar);
    }

    /** Re-label the container (a same-named FOREIGN container). */
    public synchronized void relabel(@NonNull Map<String, String> labels) {
        this.labels = Map.copyOf(labels);
    }

    /** The node's content as text, or null when absent or not a file. */
    public synchronized @Nullable String text(@NonNull String path) {
        Node node = this.nodes.get(path);
        return node == null || node.kind != Kind.FILE
            ? null : new String(node.content, StandardCharsets.UTF_8);
    }

    public synchronized boolean exists(@NonNull String path) {
        return this.nodes.containsKey(path);
    }

    public synchronized @Nullable String ownerOf(@NonNull String path) {
        Node node = this.nodes.get(path);
        return node == null ? null : node.owner;
    }

    /** Every mutating act, in order: {@code put:<path>}, {@code exec:<verb> <path>}. */
    public synchronized @NonNull List<String> calls() {
        return List.copyOf(this.calls);
    }

    /** Every request target seen, in order (the raw request-line target). */
    public synchronized @NonNull List<String> targets() {
        return List.copyOf(this.targets);
    }

    /** The entry names the last archive PUT carried. */
    public synchronized @NonNull List<String> lastPutEntries() {
        return List.copyOf(this.lastPutEntries);
    }

    // -- the buffered face ----------------------------------------------------

    @Override
    public byte[] roundTrip(byte[] request, long timeoutMs) throws IOException {
        return roundTrip(request, timeoutMs, Long.MAX_VALUE);
    }

    @Override
    public synchronized byte[] roundTrip(byte[] request, long timeoutMs, long maxResponseBytes)
            throws IOException {
        String text = new String(request, StandardCharsets.ISO_8859_1);
        int headEnd = text.indexOf("\r\n\r\n");
        String[] line = text.substring(0, text.indexOf("\r\n")).split(" ");
        String method = line[0];
        String target = line[1];
        this.targets.add(target);
        String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
        String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";
        String body = headEnd < 0 ? "" : text.substring(headEnd + 4);
        String container = "/containers/" + this.handle;

        if ("GET".equals(method) && path.equals(container + "/json")) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("Labels", this.labels);
            config.put("Tty", false);
            config.put("OpenStdin", true);
            return json(200, Map.of("Id", this.handle, "Config", config,
                "State", Map.of("Running", true)));
        }
        if ("HEAD".equals(method) && path.equals(container + "/archive")) {
            Node node = lstat(param(query, "path"));
            if (node == null) {
                return raw(404, Map.of(), new byte[0]);
            }
            return raw(200, Map.of("X-Docker-Container-Path-Stat", statHeader(
                param(query, "path"), node), "Content-Length", String.valueOf(
                    node.content.length)), new byte[0]);
        }
        if ("POST".equals(method) && path.equals(container + "/exec")) {
            Map<String, Object> spec = parse(body);
            List<String> command = new ArrayList<>();
            for (Object part : (List<?>) spec.get("Cmd")) {
                command.add(String.valueOf(part));
            }
            if (spec.get("Env") instanceof List<?> env) {
                for (Object entry : env) {
                    command.add("ENV:" + entry);
                }
            }
            String id = "exec-" + (++this.execCounter);
            this.execs.put(id, command);
            return json(201, Map.of("Id", id));
        }
        if ("POST".equals(method) && path.startsWith("/exec/") && path.endsWith("/start")) {
            String id = path.substring("/exec/".length(), path.length() - "/start".length());
            ExecOutcome outcome = runScript(this.execs.get(id));
            this.execs.put(id, List.of("EXIT:" + outcome.exit()));
            return raw(200, Map.of("Content-Type", "application/vnd.docker.raw-stream"),
                frame(outcome.stdout()));
        }
        if ("GET".equals(method) && path.startsWith("/exec/") && path.endsWith("/json")) {
            String id = path.substring("/exec/".length(), path.length() - "/json".length());
            String exit = this.execs.get(id).get(0).substring("EXIT:".length());
            return json(200, Map.of("ExitCode", Integer.parseInt(exit)));
        }
        if (path.startsWith("/containers/")) {
            return json(404, Map.of("message", "No such container"));
        }
        throw new IOException("FakeContainerFiles: unhandled " + method + " " + path);
    }

    // -- the streamed face ----------------------------------------------------

    @Override
    public synchronized @NonNull DockerStreamConnection openStream(byte @NonNull [] request,
                                                                   long connectTimeoutMs)
            throws IOException {
        String text = new String(request, StandardCharsets.ISO_8859_1);
        String[] line = text.substring(0, text.indexOf("\r\n")).split(" ");
        String method = line[0];
        String target = line[1];
        this.targets.add(target);
        String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
        String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";
        if (!path.equals("/containers/" + this.handle + "/archive")) {
            throw new IOException("FakeContainerFiles: unhandled stream " + method + " " + path);
        }
        String archivePath = param(query, "path");
        if ("GET".equals(method)) {
            return new Connection(readArchive(archivePath), null);
        }
        if ("PUT".equals(method)) {
            return new Connection(null, archivePath);
        }
        throw new IOException("FakeContainerFiles: unhandled stream " + method + " " + path);
    }

    private byte[] readArchive(String path) throws IOException {
        byte[] scripted = this.answers.remove(path);
        if (scripted != null) {
            return raw(200, Map.of("Content-Type", "application/x-tar",
                "Content-Length", String.valueOf(scripted.length)), scripted);
        }
        Node node = resolve(path, true);
        if (node == null) {
            return json(404, Map.of("message", "Could not find the file " + path));
        }
        if (node.kind != Kind.FILE) {
            throw new IOException("FakeContainerFiles: only file reads are modelled");
        }
        byte[] tar = new TestTars().file(baseName(path), node.content).build();
        return raw(200, Map.of("Content-Type", "application/x-tar",
            "Content-Length", String.valueOf(tar.length)), tar);
    }

    /** Apply a complete chunked tar upload at {@code directory}; the daemon's answer. */
    private synchronized byte[] extract(String directory, byte[] body) throws IOException {
        Node target = resolve(directory, true);
        if (target == null || target.kind != Kind.DIRECTORY) {
            return json(404, Map.of("message", "Could not find the file " + directory));
        }
        this.lastPutEntries.clear();
        Tar.Reader reader = new Tar.Reader(new ByteArrayInputStream(body));
        Tar.Entry entry;
        String base = "/".equals(directory) ? "" : directory;
        while ((entry = reader.next()) != null) {
            this.lastPutEntries.add(entry.name());
            String name = entry.name().startsWith("./") ? entry.name().substring(2) : entry.name();
            if (name.endsWith("/")) {
                name = name.substring(0, name.length() - 1);
            }
            if (name.isEmpty()) {
                continue;
            }
            String full = base + "/" + name;
            if (entry.kind() == Tar.Kind.DIRECTORY) {
                this.nodes.putIfAbsent(full, new Node(Kind.DIRECTORY, new byte[0], "", entry.mode()));
            } else if (entry.kind() == Tar.Kind.FILE) {
                byte[] content = reader.body().readAllBytes();
                ensureParents(full);
                this.nodes.put(full, new Node(Kind.FILE, content, "", entry.mode()));
            } else {
                throw new IOException("FakeContainerFiles: unexpected pushed entry " + entry);
            }
            this.calls.add("put:" + full);
        }
        return json(200, Map.of());
    }

    private void ensureParents(String full) {
        int slash = full.lastIndexOf('/');
        while (slash > 0) {
            String parent = full.substring(0, slash);
            this.nodes.putIfAbsent(parent, new Node(Kind.DIRECTORY, new byte[0], "", 0755));
            slash = parent.lastIndexOf('/');
        }
    }

    /** One streamed exchange: a ready answer, or an upload collected until its body ends. */
    private final class Connection implements DockerStreamConnection {

        private final @Nullable String uploadDirectory;
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private @Nullable ByteArrayInputStream answer;
        private boolean closed;

        Connection(byte @Nullable [] answer, @Nullable String uploadDirectory) {
            this.answer = answer == null ? null : new ByteArrayInputStream(answer);
            this.uploadDirectory = uploadDirectory;
        }

        @Override
        public synchronized int read(byte @NonNull [] buffer, int offset, int length)
                throws IOException {
            while (this.answer == null && !this.closed) {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
            if (this.answer == null) {
                throw new IOException("connection closed");
            }
            return this.answer.read(buffer, offset, length);
        }

        @Override
        public synchronized void write(byte @NonNull [] data) throws IOException {
            if (this.uploadDirectory == null || this.closed) {
                throw new IOException("connection does not accept a body");
            }
            this.written.writeBytes(data);
            byte[] body = dechunked(this.written.toByteArray());
            if (body != null) {
                this.answer = new ByteArrayInputStream(extract(this.uploadDirectory, body));
                notifyAll();
            }
        }

        @Override
        public synchronized void close() {
            this.closed = true;
            notifyAll();
        }

        @Override
        public synchronized boolean isReleased() {
            return this.closed;
        }

        @Override
        public @NonNull String diagnostics() {
            return "";
        }
    }

    // -- the filesystem -------------------------------------------------------

    /** lstat: intermediate symlinks are FOLLOWED (as the daemon does), the leaf is not. */
    private @Nullable Node lstat(String path) {
        return resolve(path, false);
    }

    private @Nullable Node resolve(String path, boolean followLeaf) {
        return resolve(path, followLeaf, 0);
    }

    private @Nullable Node resolve(String path, boolean followLeaf, int depth) {
        if (depth > 16) {
            return null;
        }
        if ("/".equals(path)) {
            return this.nodes.get("/");
        }
        String current = "";
        String[] parts = path.substring(1).split("/");
        for (int i = 0; i < parts.length; i++) {
            current = current + "/" + parts[i];
            Node node = this.nodes.get(current);
            if (node == null) {
                return null;
            }
            boolean leaf = i == parts.length - 1;
            if (node.kind == Kind.SYMLINK && (!leaf || followLeaf)) {
                String linked = node.target.startsWith("/") ? node.target
                    : current.substring(0, current.lastIndexOf('/')) + "/" + node.target;
                StringBuilder rest = new StringBuilder(linked);
                for (int j = i + 1; j < parts.length; j++) {
                    rest.append('/').append(parts[j]);
                }
                return resolve(rest.toString(), followLeaf, depth + 1);
            }
        }
        return this.nodes.get(current);
    }

    private record ExecOutcome(int exit, @NonNull String stdout) {}

    /** The file manager's constant scripts, interpreted; anything else is exit 127. */
    private ExecOutcome runScript(List<String> command) {
        String script = command.size() > 2 ? command.get(2) : "";
        Map<String, String> env = new LinkedHashMap<>();
        for (String part : command) {
            if (part.startsWith("ENV:")) {
                String assignment = part.substring(4);
                int eq = assignment.indexOf('=');
                env.put(assignment.substring(0, eq), assignment.substring(eq + 1));
            }
        }
        String path = env.getOrDefault("HH_FM_PATH", "");
        if (script.startsWith("find ")) {
            StringBuilder out = new StringBuilder();
            String prefix = path.endsWith("/") ? path : path + "/";
            for (Map.Entry<String, Node> entry : this.nodes.entrySet()) {
                String name = entry.getKey();
                if (name.startsWith(prefix) && name.indexOf('/', prefix.length()) < 0
                        && name.length() > prefix.length()) {
                    Node node = entry.getValue();
                    int type = switch (node.kind) {
                        case DIRECTORY -> 0040000;
                        case SYMLINK -> 0120000;
                        case FILE -> 0100000;
                    };
                    out.append(Integer.toHexString(type | node.mode)).append(' ')
                        .append(node.kind == Kind.FILE ? node.content.length : 0).append(' ')
                        .append(0).append(' ').append(name).append('\n');
                }
            }
            return new ExecOutcome(0, out.toString());
        }
        if (script.startsWith("mkdir ")) {
            if (this.nodes.containsKey(path)) {
                return new ExecOutcome(1, "");
            }
            this.nodes.put(path, new Node(Kind.DIRECTORY, new byte[0], "", 0755));
            this.calls.add("exec:mkdir " + path);
            return new ExecOutcome(0, "");
        }
        if (script.startsWith("mv -- ")) {
            String to = env.get("HH_FM_TARGET");
            Map<String, Node> moved = new TreeMap<>(this.nodes.subMap(path, true, path + "/￿", true));
            for (Map.Entry<String, Node> entry : moved.entrySet()) {
                if (entry.getKey().equals(path) || entry.getKey().startsWith(path + "/")) {
                    this.nodes.remove(entry.getKey());
                    this.nodes.put(to + entry.getKey().substring(path.length()), entry.getValue());
                }
            }
            this.calls.add("exec:mv " + path + " " + to);
            return new ExecOutcome(0, "");
        }
        if (script.startsWith("rm -f -- ") || script.startsWith("rm -rf -- ")) {
            this.nodes.keySet().removeIf(key -> key.equals(path) || key.startsWith(path + "/"));
            this.calls.add("exec:rm " + path);
            return new ExecOutcome(0, "");
        }
        if (script.startsWith("stat -c '%u:%g'")) {
            Node node = this.nodes.get(path);
            return new ExecOutcome(node == null ? 1 : 0, node == null ? "" : node.owner + "\n");
        }
        if (script.startsWith("chown -h ")) {
            Node node = this.nodes.get(path);
            if (node != null) {
                node.owner = env.get("HH_FM_OWNER");
            }
            this.calls.add("exec:chown-h " + path);
            return new ExecOutcome(0, "");
        }
        this.calls.add("exec:unknown " + script);
        return new ExecOutcome(127, "");
    }

    private static String statHeader(String path, Node node) {
        long mode = switch (node.kind) {
            case DIRECTORY -> (1L << 31) | node.mode;
            case SYMLINK -> (1L << 27) | 0777;
            case FILE -> node.mode;
        };
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("name", baseName(path));
        stat.put("size", node.kind == Kind.FILE ? node.content.length : 0);
        stat.put("mode", mode);
        stat.put("mtime", "2026-09-01T00:00:00Z");
        stat.put("linkTarget", node.target);
        return Base64.getEncoder().encodeToString(
            Json.stringify(stat).getBytes(StandardCharsets.UTF_8));
    }

    // -- wire plumbing --------------------------------------------------------

    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String param(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) throws IOException {
        try {
            return (Map<String, Object>) new Dry().parse(json);
        } catch (RuntimeException malformed) {
            throw new IOException("FakeContainerFiles: bad JSON body: " + json, malformed);
        }
    }

    /** Docker's stdout frame: [1,0,0,0,size(4be)] + payload. */
    private static byte[] frame(String stdout) {
        byte[] payload = stdout.getBytes(StandardCharsets.UTF_8);
        byte[] framed = new byte[8 + payload.length];
        framed[0] = 1;
        framed[4] = (byte) (payload.length >>> 24);
        framed[5] = (byte) (payload.length >>> 16);
        framed[6] = (byte) (payload.length >>> 8);
        framed[7] = (byte) payload.length;
        System.arraycopy(payload, 0, framed, 8, payload.length);
        return framed;
    }

    private static byte[] json(int status, Object payload) {
        byte[] body = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        return raw(status, Map.of("Content-Type", "application/json"), body);
    }

    private static byte[] raw(int status, Map<String, String> headers, byte[] body) {
        StringBuilder head = new StringBuilder("HTTP/1.1 " + status + " "
            + (status < 400 ? "OK" : "Error") + "\r\n");
        headers.forEach((name, value) -> head.append(name).append(": ").append(value)
            .append("\r\n"));
        if (!headers.containsKey("Content-Length")) {
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        byte[] headBytes = head.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] response = new byte[headBytes.length + body.length];
        System.arraycopy(headBytes, 0, response, 0, headBytes.length);
        System.arraycopy(body, 0, response, headBytes.length, body.length);
        return response;
    }

    /** The body of a COMPLETE chunked stream, or null while the zero chunk is missing. */
    private static byte @Nullable [] dechunked(byte[] raw) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int position = 0;
        while (true) {
            int lineEnd = -1;
            for (int i = position; i + 1 < raw.length; i++) {
                if (raw[i] == '\r' && raw[i + 1] == '\n') {
                    lineEnd = i;
                    break;
                }
            }
            if (lineEnd < 0) {
                return null;
            }
            int size = Integer.parseInt(new String(raw, position, lineEnd - position,
                StandardCharsets.ISO_8859_1).trim(), 16);
            int start = lineEnd + 2;
            if (size == 0) {
                return raw.length >= start + 2 ? body.toByteArray() : null;
            }
            if (raw.length < start + size + 2) {
                return null;
            }
            body.write(raw, start, size);
            position = start + size + 2;
        }
    }
}
