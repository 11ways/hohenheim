package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A stack service's instance settings publish its ports in the one published-port vocabulary: the {@code ports} list
 * of {@code {container_port, host_port, protocol, host_ip}} entries becomes the {@code published_ports} map of
 * {@code <protocol>-<host port>} to Docker's {@code [bind:]host:container/protocol}.
 *
 * AIDEV-NOTE: frozen spellings, as production stored them; the live writer is StackSpec.PortSpec.published, whose
 * naming this step reproduces so a stack's next deploy finds the settings it would write. An entry without both ports
 * published nothing before (StackServiceKind skipped it) and is dropped. down() is exact for every migrated entry.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public class M014_StackPublishedPorts extends HohenheimMigration {

    /** The stack service kind, as production stored it. */
    static final String STACK_SERVICE_KIND = "hohenheim:stack_service";

    /** The settings key of the old list, as production stored it. */
    static final String PORTS_KEY = "ports";

    /** The settings key of the published-port map, as PublishedPort.FIELD spells it today. */
    static final String PUBLISHED_PORTS_KEY = "published_ports";

    public M014_StackPublishedPorts() {
        super("014", "Stack published ports");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.data("publish every stack service's ports as published ports", "1",
            M014_StackPublishedPorts::foldStackPorts);
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.data("store every stack service's published ports as its ports list again", "1",
            M014_StackPublishedPorts::unfoldStackPorts);
    }

    /** The data step moving every stack service's {@code ports} list into its {@code published_ports} map. */
    public static void foldStackPorts(@NonNull Datasource datasource) {
        rewriteStackSettings(datasource, PORTS_KEY, stored -> {
            Map<String, Object> published = new LinkedHashMap<>();
            if (stored instanceof List<?> entries) {
                for (Object entry : entries) {
                    if (entry instanceof Map<?, ?> port && positive(port.get("container_port")) != null
                            && positive(port.get("host_port")) != null) {
                        int host = positive(port.get("host_port"));
                        String protocol = "udp".equals(text(port.get("protocol"))) ? "udp" : "tcp";
                        String bind = text(port.get("host_ip"));
                        published.put(protocol + "-" + host, (bind.isEmpty() ? "" : bind + ":") + host + ":"
                            + positive(port.get("container_port")) + "/" + protocol);
                    }
                }
            }
            return Map.entry(PUBLISHED_PORTS_KEY, published);
        });
    }

    /** The inverse step: every {@code published_ports} entry back into a {@code ports} list entry. */
    public static void unfoldStackPorts(@NonNull Datasource datasource) {
        rewriteStackSettings(datasource, PUBLISHED_PORTS_KEY, stored -> {
            List<Object> ports = new ArrayList<>();
            if (stored instanceof Map<?, ?> published) {
                for (Object value : published.values()) {
                    String spec = text(value);
                    int slash = spec.indexOf('/');
                    String protocol = slash < 0 ? "tcp" : spec.substring(slash + 1);
                    String[] parts = (slash < 0 ? spec : spec.substring(0, slash)).split(":");
                    if (parts.length < 2) {
                        continue;
                    }
                    Map<String, Object> port = new LinkedHashMap<>();
                    port.put("container_port", Integer.parseInt(parts[parts.length - 1]));
                    port.put("host_port", Integer.parseInt(parts[parts.length - 2]));
                    port.put("protocol", protocol);
                    port.put("host_ip", String.join(":", List.of(parts).subList(0, parts.length - 2)));
                    ports.add(port);
                }
            }
            return Map.entry(PORTS_KEY, ports);
        });
    }

    /** Replace one settings key of every stack service (trashed rows included) with what {@code rewrite} answers. */
    private static void rewriteStackSettings(@NonNull Datasource datasource, @NonNull String from,
                                             @NonNull Function<@Nullable Object, Map.Entry<String, Object>> rewrite) {
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            StringField kind = StringField.builder().name("kind").build();
            SchemaField settings = SchemaField.builder("settings").build();
            FrozenModel instances = new FrozenModel("instances", id, kind, settings);
            for (Row row : instances.find().where(kind.eq(STACK_SERVICE_KIND)).all()) {
                Map<?, ?> stored = row.get(settings) instanceof Map<?, ?> map ? map : Map.of();
                if (!stored.containsKey(from)) {
                    continue;
                }
                Map.Entry<String, Object> replacement = rewrite.apply(stored.get(from));
                Map<String, Object> rewritten = new LinkedHashMap<>();
                stored.forEach((key, value) -> {
                    if (!from.equals(String.valueOf(key))) {
                        rewritten.put(String.valueOf(key), value);
                    }
                });
                rewritten.put(replacement.getKey(), replacement.getValue());
                row.set(settings, rewritten);
                instances.save(row);
            }
        });
    }

    private static @Nullable Integer positive(@Nullable Object value) {
        int number;
        if (value instanceof Number given) {
            number = given.intValue();
        } else {
            try {
                number = Integer.parseInt(text(value));
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return number > 0 ? number : null;
    }

    private static @NonNull String text(@Nullable Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
