package be.elevenways.hohenheim.server.game;

import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.seed.SeedContext;
import be.elevenways.zenit.server.orm.seed.Seeder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/**
 * Starter game templates: a Velocity proxy and a Paper Minecraft backend, the pair the
 * game-domains mapping fronts. Ledgered {@code once} starter content -- operator edits
 * and deletions stick -- and both land UNAPPROVED: only an explicit operator act makes
 * a template tenant-selectable.
 *
 * AIDEV-NOTE: the Velocity template ships NO velocity.toml -- that file is GENERATED
 * wholly by GameDomains from the mappings (attributed instance_files row). It ships the
 * forwarding.secret file riding the auto-generated FORWARDING_SECRET variable, and the
 * backend template consumes the SAME value via VELOCITY_FORWARDING_SECRET (generate off:
 * the mapping materializer fills it from the proxy, a random mint would break the pair).
 */
public final class GameTemplateSeeder implements Seeder {

    private static final String KIND_DOCKER = "hohenheim:docker_container";
    private static final String TYPE_SECRET = "hohenheim:secret";

    public GameTemplateSeeder() {
    }

    @Override
    public void seed(@NonNull SeedContext ctx) {
        ctx.once("hohenheim.game-templates", () -> {
            seedVelocity();
            seedMinecraft();
        });
    }

    private static void seedVelocity() {
        int templateId = template(
            "Velocity proxy",
            "Fronts Minecraft backends: forced hosts, modern player-info forwarding"
                + " and the public entry port. Backends attach via game-domain mappings.",
            Map.of(
                "image", "dockcenter/velocity",
                "tag", "latest",
                "container_port", 25577,
                "environment_variables", Map.of("JAVA_MEMORY", "512M"),
                "volumes", Map.of("data", "/data"),
                "memory_limit_mb", 768),
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
            "Minecraft server (Paper)",
            "A Paper Minecraft backend behind Velocity: no published port of its own,"
                + " reachable only through the proxy's link network.",
            Map.of(
                "image", "itzg/minecraft-server",
                "tag", "latest",
                "environment_variables", Map.of(
                    "EULA", "TRUE",
                    "TYPE", "PAPER",
                    "MEMORY", "1G",
                    "ONLINE_MODE", "FALSE"),
                "volumes", Map.of("data", "/data"),
                "memory_limit_mb", 1536),
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
        row.set(InstanceTemplateModel.READINESS_LINE, readinessLine);
        row.set(InstanceTemplateModel.STOP_COMMAND, stopCommand);
        row.set(InstanceTemplateModel.SOURCE, "hohenheim:starter");
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
