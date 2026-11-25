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
 * reads as configured, enabled, running and connected; its word dictionary and reputation answer from memory.
 *
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

    private FakeSpamservice(Map<String, HttpHandler> own) throws IOException, ReflectiveOperationException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, HttpHandler> handlers = new LinkedHashMap<>();
        handlers.put("/v1/manage/status", exchange -> answer(exchange, 200, Map.of("status", "ready",
            "service", "spamservice", "management_api", "v1", "checks", Map.of("database", true))));
        handlers.put("/v1/manage/spam-words", this::words);
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
