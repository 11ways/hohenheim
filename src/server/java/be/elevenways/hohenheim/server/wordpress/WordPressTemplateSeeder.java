package be.elevenways.hohenheim.server.wordpress;

import be.elevenways.hohenheim.instance.ReadinessKind;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateDatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.seed.SeedContext;
import be.elevenways.zenit.server.orm.seed.Seeder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Starter WordPress templates, one per {@link WordPressPhp} member: the official Apache
 * image on a managed volume at the docroot, a declared MySQL database injected as the
 * {@code WORDPRESS_DB_*} family, the table prefix as a typed variable and the reverse-proxy
 * HTTPS fix in {@code WORDPRESS_CONFIG_EXTRA}. Ledgered {@code once} starter content like
 * the game templates -- operator edits and deletions stick -- and unapproved until an
 * operator says otherwise.
 *
 * AIDEV-NOTE: the declared database's env prefix IS the contract with the image: the
 * injection family {@code WORDPRESS_DB_HOST/USER/PASSWORD/NAME} spells exactly the
 * variables the official entrypoint reads, so no mapping layer exists and none may be
 * added. {@code WORDPRESS_DB_PORT} is injected too and ignored by the image (it dials
 * the engine's native 3306 over the link network, which is what the host name is).
 *
 * AIDEV-NOTE: the proxy terminates TLS and regenerates {@code X-Forwarded-Proto}
 * (ForwardingHeaders); without the {@code $_SERVER['HTTPS']} fix WordPress builds every
 * URL as http and every admin page loops through the force-https redirect.
 */
public final class WordPressTemplateSeeder implements Seeder {

    /** The ledger key the starter set is recorded under. */
    public static final String LEDGER_KEY = "hohenheim.wordpress-templates";

    private static final String KIND_DOCKER = "hohenheim:docker_container";
    private static final String TYPE_STRING = "hohenheim:string";

    /** The env prefix the declared database is injected under (the image's own spelling). */
    public static final String DB_PREFIX = "WORDPRESS_DB";

    /** The table-prefix variable the official image reads. */
    public static final String TABLE_PREFIX_KEY = "WORDPRESS_TABLE_PREFIX";

    /** Where the image keeps the docroot; the managed volume mounts here. */
    public static final String DOCROOT = "/var/www/html";

    /** The volume name the docroot rides, so an import knows what to copy into. */
    public static final String DOCROOT_VOLUME = "html";

    /**
     * The wp-config fragment the official image appends: trust the proxy's scheme so
     * WordPress builds https URLs and its force-ssl checks pass behind TLS termination.
     */
    public static final String CONFIG_EXTRA =
        "if (isset($_SERVER['HTTP_X_FORWARDED_PROTO'])"
            + " && $_SERVER['HTTP_X_FORWARDED_PROTO'] === 'https') {"
            + " $_SERVER['HTTPS'] = 'on'; }";

    public WordPressTemplateSeeder() {
    }

    @Override
    public void seed(@NonNull SeedContext ctx) {
        ctx.once(LEDGER_KEY, () -> {
            for (WordPressPhp php : WordPressPhp.values()) {
                seedTemplate(php);
            }
        });
    }

    /** The template name a member seeds under. */
    public static @NonNull String templateName(@NonNull WordPressPhp php) {
        return "WordPress (PHP " + php.version() + ")";
    }

    /** The kind settings baseline every WordPress template starts from. */
    public static @NonNull Map<String, Object> settingsFor(@NonNull WordPressPhp php) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", WordPressPhp.IMAGE);
        settings.put("tag", php.tag());
        settings.put("container_port", 80);
        settings.put("environment_variables", Map.of("WORDPRESS_CONFIG_EXTRA", CONFIG_EXTRA));
        settings.put("volumes", Map.of(DOCROOT_VOLUME, DOCROOT));
        settings.put("memory_limit_mb", 512);
        return settings;
    }

    private static void seedTemplate(WordPressPhp php) {
        var model = Models.get(InstanceTemplateModel.class);
        Row row = model.createEmptyRow();
        row.set(InstanceTemplateModel.NAME, templateName(php));
        row.set(InstanceTemplateModel.DESCRIPTION, php.frozen()
            ? "WordPress on the official Apache image with PHP " + php.version() + "."
                + " This tag is no longer updated upstream; use it to land an imported site"
                + " that still needs this PHP, never for a new one."
            : "WordPress on the official Apache image with PHP " + php.version() + "."
                + " Comes with a managed MySQL database, its docroot on a managed volume"
                + " and the reverse-proxy HTTPS fix.");
        row.set(InstanceTemplateModel.KIND, KIND_DOCKER);
        row.set(InstanceTemplateModel.SETTINGS, settingsFor(php));
        // A fresh docroot answers 302 to the installer and an imported one 200 to its
        // front page; both are "below 500", which is what the http probe asks.
        row.set(InstanceTemplateModel.READINESS_KIND, ReadinessKind.HTTP.token());
        row.set(InstanceTemplateModel.READINESS_TARGET, "/");
        row.set(InstanceTemplateModel.SOURCE, "hohenheim:starter");
        model.save(row);
        int templateId = row.get(InstanceTemplateModel.ID);

        var variables = Models.get(InstanceTemplateVariableModel.class);
        Row prefix = variables.createEmptyRow();
        prefix.set(InstanceTemplateVariableModel.TEMPLATE_ID, templateId);
        prefix.set(InstanceTemplateVariableModel.KEY, TABLE_PREFIX_KEY);
        prefix.set(InstanceTemplateVariableModel.LABEL, "Table prefix");
        prefix.set(InstanceTemplateVariableModel.DESCRIPTION,
            "Prefix of every WordPress table; an imported dump keeps whatever it used.");
        prefix.set(InstanceTemplateVariableModel.TYPE, TYPE_STRING);
        prefix.set(InstanceTemplateVariableModel.SETTINGS,
            Map.of("pattern", "^[A-Za-z0-9_]+$", "max_length", 32));
        prefix.set(InstanceTemplateVariableModel.REQUIRED, false);
        prefix.set(InstanceTemplateVariableModel.DEFAULT_VALUE, "wp_");
        variables.save(prefix);

        var databases = Models.get(InstanceTemplateDatabaseModel.class);
        Row database = databases.createEmptyRow();
        database.set(InstanceTemplateDatabaseModel.TEMPLATE_ID, templateId);
        database.set(InstanceTemplateDatabaseModel.ENGINE, DatabaseModel.ENGINE_MYSQL);
        database.set(InstanceTemplateDatabaseModel.ENV_PREFIX, DB_PREFIX);
        databases.save(database);
    }
}
