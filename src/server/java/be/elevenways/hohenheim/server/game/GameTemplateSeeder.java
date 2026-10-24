package be.elevenways.hohenheim.server.game;

import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.instance.ReadinessKind;
import be.elevenways.hohenheim.instance.StarterTemplate;
import be.elevenways.hohenheim.instance.StatusProbe;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.ports.PortPurpose;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.seed.SeedContext;
import be.elevenways.zenit.server.orm.seed.Seeder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Starter game templates: a Minecraft server players reach directly, and a Velocity proxy with a Paper backend, the
 * pair the game-domains mapping fronts. Ledgered {@code once} starter content -- operator edits and deletions stick
 * -- and all land UNAPPROVED: only an explicit operator act makes a template tenant-selectable.
 *
 * AIDEV-NOTE: the Velocity template ships NO velocity.toml -- that file is GENERATED wholly by GameDomains from the
 * mappings (attributed instance_files row). It ships the forwarding.secret file riding the auto-generated
 * FORWARDING_SECRET variable, and the backend template consumes the SAME value via VELOCITY_FORWARDING_SECRET
 * (generate off: the mapping materializer fills it from the proxy, a random mint would break the pair). A change to
 * a seeded starter is a new once-key that upgrades installed rows still carrying what was seeded
 * ({@link #upgradeStarters}), never an edit of a ledgered block alone, which would reach fresh installs only.
 */
public final class GameTemplateSeeder implements Seeder {

    private static final String KIND_DOCKER = "hohenheim:docker_container";
    private static final String TYPE_SECRET = "hohenheim:secret";

    /** The standalone server's name, shown as the chooser's "Minecraft server" family. */
    public static final String MINECRAFT_SERVER = StarterTemplate.MINECRAFT_SERVER.familyName();

    /** The behind-the-proxy backend's name: a family of its own, never the standalone server's. */
    public static final String MINECRAFT_BACKEND = StarterTemplate.MINECRAFT_BACKEND.familyName()
        + " (behind Velocity)";

    public static final String VELOCITY_PROXY = StarterTemplate.VELOCITY_PROXY.familyName();

    /** The port Velocity listens on inside its container, published as its game port. */
    private static final int VELOCITY_PORT = 25577;

    public GameTemplateSeeder() {
    }

    @Override
    public void seed(@NonNull SeedContext ctx) {
        ctx.once("hohenheim.game-templates", () -> {
            seedVelocity();
            seedMinecraft();
        });
        ctx.once("hohenheim.game-templates.standalone", GameTemplateSeeder::seedMinecraftStandalone);
        ctx.once("hohenheim.game-templates.names-and-ports", GameTemplateSeeder::upgradeStarters);
        ctx.once("hohenheim.game-templates.status-probe", GameTemplateSeeder::declareStatusProbes);
    }

    /**
     * Bring installed starter rows to what is seeded today, each only while it still carries what an earlier seed
     * wrote (an operator's rename or edit sticks): the standalone server and the backend get names that cannot be
     * confused, and the Velocity proxy publishes its entry port as a published game port (so it is put online through
     * the Ports step and gets a game address) instead of the routing tier's single public port.
     */
    public static void upgradeStarters() {
        rename("Minecraft (Paper)", MINECRAFT_SERVER);
        rename("Minecraft server (Paper)", MINECRAFT_BACKEND);
        var model = Models.get(InstanceTemplateModel.class);
        for (Row row : starters(VELOCITY_PROXY)) {
            Map<String, Object> settings = new LinkedHashMap<>();
            if (row.get(InstanceTemplateModel.SETTINGS) instanceof Map<?, ?> stored) {
                stored.forEach((key, value) -> settings.put(String.valueOf(key), value));
            }
            if (!(settings.get("container_port") instanceof Number port && port.intValue() == VELOCITY_PORT)
                    || !"public".equals(settings.get("port_exposure")) || PublishedPort.declaredIn(settings)) {
                continue;
            }
            settings.remove("container_port");
            settings.remove("port_exposure");
            settings.putAll(velocityPorts());
            row.set(InstanceTemplateModel.SETTINGS, settings);
            model.save(row);
        }
    }

    /**
     * The starters players connect to directly declare the Minecraft server-list ping as their status probe, so an
     * instance made from one shows its players online: the standalone server and the Velocity proxy (which answers for
     * its whole network). Only a row whose settings still equal what was seeded changes ({@link #standaloneSettings},
     * {@link #velocitySettings}); any operator edit, a deliberately cleared probe among them, sticks.
     */
    public static void declareStatusProbes() {
        var model = Models.get(InstanceTemplateModel.class);
        Map<String, Map<String, Object>> seeded = Map.of(
            MINECRAFT_SERVER, standaloneSettings(),
            VELOCITY_PROXY, velocitySettings());
        seeded.forEach((name, baseline) -> {
            for (Row row : starters(name)) {
                if (!sameValue(row.get(InstanceTemplateModel.SETTINGS), baseline)) {
                    continue;
                }
                Map<String, Object> settings = new LinkedHashMap<>(baseline);
                settings.put(StatusProbe.SETTING, StatusProbe.MINECRAFT_JAVA.token());
                row.set(InstanceTemplateModel.SETTINGS, settings);
                model.save(row);
            }
        });
    }

    /** @return whether two stored settings values are the same, numbers compared by value whatever their type */
    private static boolean sameValue(@Nullable Object stored, @Nullable Object seeded) {
        if (stored instanceof Map<?, ?> left && seeded instanceof Map<?, ?> right) {
            if (left.size() != right.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : right.entrySet()) {
                if (!left.containsKey(entry.getKey()) || !sameValue(left.get(entry.getKey()), entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (stored instanceof Number left && seeded instanceof Number right) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        }
        return Objects.equals(stored, seeded);
    }

    private static void rename(@NonNull String seeded, @NonNull String name) {
        var model = Models.get(InstanceTemplateModel.class);
        for (Row row : starters(seeded)) {
            row.set(InstanceTemplateModel.NAME, name);
            model.save(row);
        }
    }

    /** @return the starter rows still carrying this seeded name */
    private static @NonNull List<Row> starters(@NonNull String name) {
        return Models.get(InstanceTemplateModel.class).find()
            .where(InstanceTemplateModel.NAME.eq(name))
            .and(InstanceTemplateModel.SOURCE.eq(InstanceTemplateModel.SOURCE_STARTER))
            .all();
    }

    /** The Velocity proxy's entry port: a published game port with its SRV service, players' way in. */
    private static @NonNull Map<String, Object> velocityPorts() {
        return Map.of(
            PublishedPort.FIELD, Map.of(PortPurpose.GAME.token(), VELOCITY_PORT + "/" + PublishedPort.TCP),
            PublishedPort.SRV_SERVICE, "minecraft");
    }

    /**
     * A Minecraft server players connect to directly: its game port is published on its host (the template declares
     * it, putting it online picks the host port) and a game address gets an SRV record for it. Online mode stays on,
     * since nothing in front of it checks who joins.
     *
     * AIDEV-NOTE: TemplateFamily reads the family off the name ("Name (variant)" folds into "Name"), so the backend is
     * named "Minecraft backend (...)": named "Minecraft server (Paper)" it folded into this server's family and the
     * chooser's card opened the behind-Velocity backend instead of this one.
     */
    private static void seedMinecraftStandalone() {
        template(
            MINECRAFT_SERVER,
            "A Paper server with a world volume and a console, reached on its own game port.",
            standaloneSettings(),
            ")! For help, type",
            "stop");
    }

    /** @return the settings the standalone server was seeded with, before its status probe */
    static @NonNull Map<String, Object> standaloneSettings() {
        return Map.of(
            InstanceKindFields.IMAGE, "itzg/minecraft-server",
            "tag", "latest",
            InstanceKindFields.ENVIRONMENT_VARIABLES, Map.of(
                "EULA", "TRUE",
                "TYPE", "PAPER",
                "MEMORY", "1G"),
            "volumes", Map.of("data", "/data"),
            PublishedPort.FIELD, Map.of(PortPurpose.GAME.token(), "25565/" + PublishedPort.TCP),
            PublishedPort.SRV_SERVICE, "minecraft",
            InstanceKindFields.MEMORY_LIMIT_MB, 1536);
    }

    /**
     * @return the settings the Velocity proxy was seeded with (or upgraded to by {@link #upgradeStarters}), before its
     *         status probe
     */
    static @NonNull Map<String, Object> velocitySettings() {
        Map<String, Object> settings = new LinkedHashMap<>(Map.of(
            InstanceKindFields.IMAGE, "dockcenter/velocity",
            "tag", "latest",
            InstanceKindFields.ENVIRONMENT_VARIABLES, Map.of("JAVA_MEMORY", "512M"),
            "volumes", Map.of("data", "/data"),
            InstanceKindFields.MEMORY_LIMIT_MB, 768));
        // The proxy IS the public entry: players connect to its published game port (the SRV rides it). The backend
        // template stays unpublished.
        settings.putAll(velocityPorts());
        return settings;
    }

    private static void seedVelocity() {
        int templateId = template(
            VELOCITY_PROXY,
            "Fronts Minecraft backends: forced hosts, modern player-info forwarding"
                + " and the public entry port. Backends attach via game-domain mappings.",
            velocitySettings(),
            "Done (",
            "shutdown");
        variable(templateId, GameDomains.PROXY_SECRET_KEY, "Forwarding secret",
            "Velocity's player-info forwarding secret; minted automatically and handed"
                + " to every mapped backend.",
            Map.of("generate", true, "generate_bytes", 32));
        file(templateId, "/data/forwarding.secret",
            "{{" + GameDomains.PROXY_SECRET_KEY + "}}", "0644");
    }

    private static void seedMinecraft() {
        int templateId = template(
            MINECRAFT_BACKEND,
            "A Paper Minecraft backend behind Velocity: no published port of its own,"
                + " reachable only through the proxy's link network.",
            Map.of(
                InstanceKindFields.IMAGE, "itzg/minecraft-server",
                "tag", "latest",
                InstanceKindFields.ENVIRONMENT_VARIABLES, Map.of(
                    "EULA", "TRUE",
                    "TYPE", "PAPER",
                    "MEMORY", "1G",
                    "ONLINE_MODE", "FALSE"),
                "volumes", Map.of("data", "/data"),
                InstanceKindFields.MEMORY_LIMIT_MB, 1536),
            ")! For help, type",
            "stop");
        variable(templateId, GameDomains.BACKEND_SECRET_KEY, "Velocity forwarding secret",
            "Filled from the proxy's forwarding secret by the game-domain mapping;"
                + " leave blank.",
            Map.of("generate", false));
        file(templateId, "/data/config/paper-global.yml",
            "# Managed by hohenheim: Velocity player-info forwarding.\n"
                + "proxies:\n"
                + "  velocity:\n"
                + "    enabled: true\n"
                + "    online-mode: true\n"
                + "    secret: \"{{" + GameDomains.BACKEND_SECRET_KEY + "}}\"\n",
            "0644");
    }

    private static int template(String name, String description, Map<String, Object> settings,
                                String readinessLine, String stopCommand) {
        var model = Models.get(InstanceTemplateModel.class);
        Row row = model.createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.DESCRIPTION, description);
        row.set(InstanceTemplateModel.KIND, KIND_DOCKER);
        row.set(InstanceTemplateModel.SETTINGS, settings);
        // A seeded line is CONSOLE readiness by declaration: the column default is `port`,
        // and a port probe on a game server answers long before the world has generated.
        if (readinessLine != null && !readinessLine.isBlank()) {
            row.set(InstanceTemplateModel.READINESS_KIND, ReadinessKind.CONSOLE_LINE.token());
        }
        row.set(InstanceTemplateModel.READINESS_LINE, readinessLine);
        row.set(InstanceTemplateModel.STOP_COMMAND, stopCommand);
        row.set(InstanceTemplateModel.SOURCE, InstanceTemplateModel.SOURCE_STARTER);
        model.save(row);
        return row.get(InstanceTemplateModel.ID);
    }

    private static void variable(int templateId, String key, String label, String description,
                                 Map<String, Object> settings) {
        var model = Models.get(InstanceTemplateVariableModel.class);
        Row row = model.createEmptyRow();
        row.set(InstanceTemplateVariableModel.TEMPLATE_ID, templateId);
        row.set(InstanceTemplateVariableModel.KEY, key);
        row.set(InstanceTemplateVariableModel.LABEL, label);
        row.set(InstanceTemplateVariableModel.DESCRIPTION, description);
        row.set(InstanceTemplateVariableModel.TYPE, TYPE_SECRET);
        row.set(InstanceTemplateVariableModel.SETTINGS, settings);
        row.set(InstanceTemplateVariableModel.REQUIRED, false);
        model.save(row);
    }

    private static void file(int templateId, String path, String content, String mode) {
        var model = Models.get(InstanceTemplateFileModel.class);
        Row row = model.createEmptyRow();
        row.set(InstanceTemplateFileModel.TEMPLATE_ID, templateId);
        row.set(InstanceTemplateFileModel.CONTAINER_PATH, path);
        row.set(InstanceTemplateFileModel.CONTENT, content);
        row.set(InstanceTemplateFileModel.MODE, mode);
        model.save(row);
    }
}
