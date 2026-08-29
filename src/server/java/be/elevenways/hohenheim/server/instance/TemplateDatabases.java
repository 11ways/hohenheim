package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateDatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.database.TenantDatabases;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The create-from-template half of a template's DECLARED databases: one managed database
 * allocated per declaration on the instance's own host (the link network exists only on
 * the daemon both share) and attached under the declared env prefix.
 *
 * AIDEV-NOTE: allocation rides {@link TenantDatabases#allocate}, THE database-allocation
 * funnel, so a template-created database is named, owned, placed, quota-charged and
 * granted exactly like one a tenant allocates by hand -- there is no second lane and the
 * declared image is the only thing the funnel takes from the template that a hand
 * allocation could not choose.
 */
public final class TemplateDatabases {

    /** One allocated database and the prefix it will be attached under. */
    public record Allocation(@NonNull Row database, @NonNull String prefix) {
    }

    /** {@link TenantDatabases}' label ceiling, applied after the prefix suffix is added. */
    private static final int MAX_LABEL_LENGTH = 32;

    private TemplateDatabases() {
    }

    /** The template's declarations, prefix-ordered. */
    public static @NonNull List<Row> declared(int templateId) {
        return Models.get(InstanceTemplateDatabaseModel.class).findByTemplateId(templateId);
    }

    /**
     * What can be refused about the declarations BEFORE any instance row exists: an
     * unknown engine, a kind without link networks, and a database label already taken
     * -- so the create never has to compensate for the common refusals.
     *
     * @throws Violations {@code unknown_engine}, {@code instance_kind_no_injection},
     *         {@code database_name_taken}
     */
    public static void precheck(@NonNull Row template, @NonNull String instanceName,
                                @Nullable AccessContext ctx) {
        List<Row> declarations = declared(template.get(InstanceTemplateModel.ID));
        if (declarations.isEmpty()) {
            return;
        }
        String kind = template.get(InstanceTemplateModel.KIND);
        InstanceKindHandler handler = InstanceKinds.getHandler(kind);
        if (handler == null || !handler.supportedRuntimes().contains(ServerModel.RUNTIME_DOCKER)) {
            throw Violations.ofField("kind", kind,
                CmsSupport.violationText("instance_kind_no_injection")
                    .withArg("kind", String.valueOf(kind)));
        }
        DatabaseModel databases = Models.get(DatabaseModel.class);
        for (Row declaration : declarations) {
            String engine = declaration.get(InstanceTemplateDatabaseModel.ENGINE);
            if (ManagedDatabase.Engine.forToken(engine) == null) {
                throw Violations.ofField("engine", engine,
                    CmsSupport.violationText("unknown_engine").withArg("engine", engine));
            }
            String label = labelFor(instanceName, prefixOf(declaration));
            String stored = TenantDatabases.storedNameFor(ctx, label);
            if (databases.findByName(stored) != null) {
                throw Violations.ofField(DatabaseModel.NAME.getName(), stored,
                    CmsSupport.violationText("database_name_taken").withArg("name", stored));
            }
        }
    }

    /**
     * Allocate every declared database for an instance placed on {@code serverId}.
     *
     * @throws Violations every allocation refusal of the funnel, plus
     *         {@code template_database_host_mismatch} when the chooser lands a tenant's
     *         database on a host other than the instance's
     */
    public static @NonNull List<Allocation> allocate(@NonNull Row template,
                                                     @NonNull String instanceName,
                                                     int serverId,
                                                     @Nullable AccessContext ctx) {
        List<Allocation> allocations = new ArrayList<>();
        for (Row declaration : declared(template.get(InstanceTemplateModel.ID))) {
            String prefix = prefixOf(declaration);
            String image = declaration.get(InstanceTemplateDatabaseModel.IMAGE);
            Row database = TenantDatabases.allocate(ctx, labelFor(instanceName, prefix),
                declaration.get(InstanceTemplateDatabaseModel.ENGINE),
                image == null || image.isBlank() ? null : image.trim(), serverId);
            int placed = ServerModel.canonicalServerId(database.get(DatabaseModel.SERVER_ID));
            if (placed != serverId) {
                // Injection dials the engine over a link network that only exists on the
                // instance's daemon: a database elsewhere would be credentials for a host
                // the workload cannot reach, which is the silent-success shape.
                throw Violations.ofForm(CmsSupport.violationText("template_database_host_mismatch")
                    .withArg("name", String.valueOf((Object) database.get(DatabaseModel.NAME)))
                    .withArg("server", ServerModel.nameOf(placed))
                    .withArg("instance_server", ServerModel.nameOf(serverId)));
            }
            allocations.add(new Allocation(database, prefix));
        }
        return allocations;
    }

    /** Attach every allocation to the created instance under its prefix. */
    public static void link(int instanceId, @NonNull List<Allocation> allocations) {
        InstanceDatabaseModel links = Models.get(InstanceDatabaseModel.class);
        for (Allocation allocation : allocations) {
            Row link = links.createEmptyRow();
            link.set(InstanceDatabaseModel.INSTANCE_ID, instanceId);
            link.set(InstanceDatabaseModel.DATABASE_ID, allocation.database().get(DatabaseModel.ID));
            link.set(InstanceDatabaseModel.ENV_PREFIX, allocation.prefix());
            links.save(link);
        }
    }

    /**
     * THE label of a template-created database: the instance name slugged, then the
     * prefix, inside the funnel's own label ceiling ("anymedia-wordpress-db").
     */
    public static @NonNull String labelFor(@NonNull String instanceName, @NonNull String prefix) {
        String suffix = "-" + slug(prefix);
        String head = slug(instanceName);
        int room = MAX_LABEL_LENGTH - suffix.length();
        if (head.length() > room) {
            head = head.substring(0, room);
            while (head.endsWith("-")) {
                head = head.substring(0, head.length() - 1);
            }
        }
        return (head.isEmpty() ? "db" : head) + suffix;
    }

    private static @NonNull String slug(@NonNull String text) {
        String slug = BlastString.lower(text).replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("^-+", "").replaceAll("-+$", "");
        return slug;
    }

    private static @NonNull String prefixOf(@NonNull Row declaration) {
        String prefix = declaration.get(InstanceTemplateDatabaseModel.ENV_PREFIX);
        return prefix == null || prefix.isBlank()
            ? InstanceDatabaseModel.DEFAULT_PREFIX : prefix.trim().toUpperCase(Locale.ROOT);
    }
}
