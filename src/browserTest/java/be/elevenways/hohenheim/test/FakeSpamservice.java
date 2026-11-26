package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.spamservice.ManagedServiceProcess;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.protoblast.server.process.Subprocess;
import be.elevenways.spamservice.client.SpamserviceClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A spam service's management API in this process, installed as the managed runtime's client, so the installation
 * reads as configured, enabled, running and connected; its word dictionary, its clients, their keys, its samples and
 * reputation answer from memory, a client, key and sample named by its id or by the code the service gives it.
 * AIDEV-NOTE: the runtime facts of the manager singleton are swapped, never its operations' handlers, so a call
 * reaches the production availability checks and the real client; {@link #close()} puts every fact back.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class FakeSpamservice implements AutoCloseable {

    private final HttpServer server;
    private ManagedServiceProcess process;
    private final Map<Field, Object> original = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> words = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> clients = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> keys = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> samples = new ConcurrentHashMap<>();
    /** Per sample, the score a rescore gives it. */
    private final Map<String, Integer> rescores = new ConcurrentHashMap<>();

    private FakeSpamservice(Map<String, HttpHandler> own) throws IOException, ReflectiveOperationException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, HttpHandler> handlers = new LinkedHashMap<>();
        handlers.put("/v1/manage/status", exchange -> answer(exchange, 200, Map.of("status", "ready",
            "service", "spamservice", "management_api", "v1", "checks", Map.of("database", true))));
        handlers.put("/v1/manage/spam-words", this::words);
        handlers.put("/v1/manage/clients", this::clients);
        handlers.put("/v1/manage/keys", this::keys);
        handlers.put("/v1/manage/samples", this::samples);
        handlers.put("/v1/manage/reputation", exchange -> {
            String ip = query(exchange).getOrDefault("ip", "");
            answer(exchange, 200, Map.of("ip", ip, "subnet", ip + "/24", "datasets", Map.of("tor_exit", false),
                "events", Map.of("categories", Map.of("auth_failure", Map.of("count", 3))),
                "positive", Map.of("categories", Map.of())));
        });
        handlers.putAll(own);
        handlers.forEach(this.server::createContext);
        this.server.start();
    }

    /** @return the fake, its own handlers answering the paths they name instead of the fake's */
    public static FakeSpamservice start(Map<String, HttpHandler> own) throws IOException, ReflectiveOperationException {
        FakeSpamservice fake = new FakeSpamservice(own);
        try {
            fake.process = ManagedServiceProcess.start(Subprocess.of("sleep", "300"), List.of());
            SpamserviceManager manager = SpamserviceManager.get();
            fake.replace(manager, "configurationPresent", true);
            fake.replace(manager, "configurationEnabled", true);
            fake.replace(manager, "process", fake.process);
            fake.replace(manager, "client", SpamserviceClient.builder(
                "http://127.0.0.1:" + fake.server.getAddress().getPort(), "test-key").build());
        } catch (IOException | ReflectiveOperationException | RuntimeException failed) {
            fake.close();
            throw failed;
        }
        return fake;
    }

    /** @return the dictionary as the service holds it: per entry id, its word, score, language and leet */
    public Map<String, Map<String, Object>> words() {
        return this.words;
    }

    /** @return the clients as the service holds them, per id: their code and settings */
    public Map<String, Map<String, Object>> clients() {
        return this.clients;
    }

    /** @return the keys as the service holds them, per id: their code, client, name and whether they are active */
    public Map<String, Map<String, Object>> keys() {
        return this.keys;
    }

    /** @return the samples as the service holds them, per id: their code, verdict, score and confirmation */
    public Map<String, Map<String, Object>> samples() {
        return this.samples;
    }

    /**
     * Adds an unconfirmed sample the service checked, scoring {@code score} now and {@code rescored} once scored again.
     *
     * @return its code
     */
    public String sample(String ip, int score, int rescored) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("id", id);
        sample.put("code", "SPS" + (this.samples.size() + 1));
        sample.put("client_id", null);
        sample.put("ip", ip);
        sample.put("spam", score > 50);
        sample.put("score", score);
        sample.put("confirmed", false);
        sample.put("flags", "");
        sample.put("languages", "eng");
        sample.put("created_at", null);
        sample.put("updated_at", null);
        this.samples.put(id, sample);
        this.rescores.put(id, rescored);
        return String.valueOf(sample.get("code"));
    }

    @Override
    public void close() throws ReflectiveOperationException {
        try {
            SpamserviceManager manager = SpamserviceManager.get();
            for (Map.Entry<Field, Object> fact : this.original.entrySet()) fact.getKey().set(manager, fact.getValue());
            if (this.process != null) this.process.stop();
        } finally {
            this.server.stop(0);
        }
    }

    private void replace(SpamserviceManager manager, String name, Object value) throws ReflectiveOperationException {
        Field field = SpamserviceManager.class.getDeclaredField(name);
        field.setAccessible(true);
        this.original.put(field, field.get(manager));
        field.set(manager, value);
    }

    /** The dictionary's list (its q a word match), create, read, change and delete. */
    private void words(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String id = path.length() > "/v1/manage/spam-words/".length() ? path.substring(path.lastIndexOf('/') + 1)
            : null;
        String method = exchange.getRequestMethod();
        if (id == null && "GET".equals(method)) {
            String q = query(exchange).getOrDefault("q", "");
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> entry : this.words.values()) {
                if (String.valueOf(entry.get("word")).contains(q)) items.add(entry);
            }
            answer(exchange, 200, Map.of("items", items, "page", 1, "page_size", 100, "total", items.size()));
        } else if (id == null && "POST".equals(method)) {
            String created = UUID.randomUUID().toString();
            this.words.put(created, entry(created, body(exchange)));
            answer(exchange, 201, Map.of("id", created));
        } else if ("PATCH".equals(method) && this.words.containsKey(id)) {
            this.words.put(id, entry(id, body(exchange)));
            answer(exchange, 200, this.words.get(id));
        } else if ("DELETE".equals(method) && this.words.remove(id) != null) {
            answer(exchange, 200, Map.of());
        } else if ("GET".equals(method) && this.words.containsKey(id)) {
            answer(exchange, 200, this.words.get(id));
        } else {
            answer(exchange, 404, Map.of("error", Map.of("code", "not_found", "message", "No such word")));
        }
    }

    /** A client's create, read by id or code, and change; its keys' create under it, by its id or code. */
    private void clients(HttpExchange exchange) throws IOException {
        List<String> path = segments(exchange, "/v1/manage/clients");
        String method = exchange.getRequestMethod();
        if (path.isEmpty() && "POST".equals(method)) {
            String id = UUID.randomUUID().toString();
            Map<String, Object> client = new LinkedHashMap<>();
            client.put("id", id);
            client.put("code", "SPC" + (this.clients.size() + 1));
            client.put("external_id", null);
            client.put("provisioned_by_client_id", null);
            client.put("created_at", null);
            client.put("updated_at", null);
            settings(client, body(exchange), 1);
            this.clients.put(id, client);
            answer(exchange, 201, client);
            return;
        }
        Map<String, Object> client = path.isEmpty() ? null : named(this.clients, path.getFirst());
        if (client == null) {
            answer(exchange, 404, Map.of("error", Map.of("code", "client_not_found", "message", "No such client")));
        } else if (path.size() == 1 && "GET".equals(method)) {
            answer(exchange, 200, client);
        } else if (path.size() == 1 && "PUT".equals(method)) {
            settings(client, body(exchange), Integer.parseInt(String.valueOf(client.get("revision")).substring(1)) + 1);
            answer(exchange, 200, client);
        } else if (path.size() == 2 && "keys".equals(path.get(1)) && "POST".equals(method)) {
            String body = body(exchange);
            String given = field(body, "key");
            String id = UUID.randomUUID().toString();
            Map<String, Object> key = new LinkedHashMap<>();
            key.put("id", id);
            key.put("code", "SPK" + (this.keys.size() + 1));
            key.put("client_id", client.get("id"));
            key.put("name", field(body, "name"));
            key.put("active", true);
            key.put("last_used", null);
            key.put("created_at", null);
            this.keys.put(id, key);
            Map<String, Object> created = new LinkedHashMap<>(key);
            created.put("key", given != null ? given : "spamkey_" + UUID.randomUUID().toString().replace("-", ""));
            created.put("generated", given == null);
            answer(exchange, 201, created);
        } else {
            answer(exchange, 405, Map.of("error", Map.of("code", "method_not_allowed", "message", method)));
        }
    }

    /** A key's read by id or code, its rename and turning on or off, and its revoke. */
    private void keys(HttpExchange exchange) throws IOException {
        List<String> path = segments(exchange, "/v1/manage/keys");
        Map<String, Object> key = path.size() == 1 ? named(this.keys, path.getFirst()) : null;
        String method = exchange.getRequestMethod();
        if (key == null) {
            answer(exchange, 404, Map.of("error", Map.of("code", "key_not_found", "message", "No such key")));
            return;
        }
        if ("PATCH".equals(method)) {
            String body = body(exchange);
            String name = field(body, "name");
            String active = field(body, "active");
            if (name != null) key.put("name", name);
            if (active != null) key.put("active", Boolean.parseBoolean(active));
        } else if ("DELETE".equals(method)) {
            key.put("active", false);
        }
        answer(exchange, 200, key);
    }

    /** A sample's read by id or code, its verdicts and its rescore, which a confirmed sample refuses. */
    private void samples(HttpExchange exchange) throws IOException {
        List<String> path = segments(exchange, "/v1/manage/samples");
        Map<String, Object> sample = path.isEmpty() ? null : named(this.samples, path.getFirst());
        if (sample == null) {
            answer(exchange, 404, Map.of("error", Map.of("code", "sample_not_found", "message", "No such sample")));
            return;
        }
        String action = path.size() == 2 ? path.get(1) : "";
        if ("rescore".equals(action) && Boolean.TRUE.equals(sample.get("confirmed"))) {
            answer(exchange, 409, Map.of("error", Map.of("code", "sample_frozen",
                "message", "Confirmed samples cannot be rescored")));
            return;
        }
        switch (action) {
            case "mark-spam" -> {
                sample.put("spam", true);
                sample.put("confirmed", true);
            }
            case "mark-ham" -> {
                sample.put("spam", false);
                sample.put("confirmed", true);
            }
            case "rescore" -> sample.put("score", this.rescores.get(String.valueOf(sample.get("id"))));
            default -> { }
        }
        Map<String, Object> detail = new LinkedHashMap<>(sample);
        detail.put("useragent", null);
        detail.put("heuristic_score", sample.get("score"));
        detail.put("threshold", 50);
        detail.put("confirmed_origin", null);
        detail.put("location", Map.of());
        detail.put("asn", Map.of());
        detail.put("properties", List.of());
        detail.put("breakdown", List.of());
        answer(exchange, 200, detail);
    }

    /** Writes a client write's settings over the client, at its next revision. */
    private static void settings(Map<String, Object> client, String body, int revision) {
        client.put("name", field(body, "name"));
        for (String flag : List.of("enabled", "trusted", "provisioner", "manager")) {
            client.put(flag, Boolean.parseBoolean(field(body, flag)));
        }
        client.put("allowed_languages", field(body, "allowed_languages"));
        client.put("spam_threshold", Integer.valueOf(field(body, "spam_threshold")));
        client.put("notes", field(body, "notes"));
        client.put("revision", "r" + revision);
    }

    /** @return the record an id or a code names, null for none */
    private static Map<String, Object> named(Map<String, Map<String, Object>> records, String idOrCode) {
        Map<String, Object> byId = records.get(idOrCode);
        if (byId != null) return byId;
        for (Map<String, Object> record : records.values()) {
            if (idOrCode.equals(record.get("code"))) return record;
        }
        return null;
    }

    /** @return the decoded path segments after {@code base} */
    private static List<String> segments(HttpExchange exchange, String base) {
        String rest = exchange.getRequestURI().getPath().substring(base.length());
        List<String> segments = new ArrayList<>();
        for (String segment : rest.split("/")) {
            if (!segment.isEmpty()) segments.add(URLDecoder.decode(segment, StandardCharsets.UTF_8));
        }
        return segments;
    }

    /** @return a dictionary entry of the fields a word write sent */
    private static Map<String, Object> entry(String id, String body) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("word", field(body, "word"));
        entry.put("score", Integer.valueOf(field(body, "score")));
        String language = field(body, "language");
        if (language != null) entry.put("language", language);
        entry.put("leet", Boolean.parseBoolean(field(body, "leet")));
        return entry;
    }

    /** @return the value of a flat JSON body's member, unquoted; null for a missing or null one */
    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|[^,}\\s]+)")
            .matcher(body);
        if (!matcher.find()) return null;
        if (matcher.group(2) != null) return matcher.group(2);
        return "null".equals(matcher.group(1)) ? null : matcher.group(1);
    }

    private static String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> query = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return query;
        for (String pair : raw.split("&")) {
            int at = pair.indexOf('=');
            if (at > 0) {
                query.put(pair.substring(0, at), URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8));
            }
        }
        return query;
    }

    private static void answer(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
        exchange.close();
    }
}
