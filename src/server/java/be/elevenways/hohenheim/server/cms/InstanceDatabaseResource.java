package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Instance-database attachments: the join records that drive connection-variable injection
 * into a workload's environment, its command, its cloud-init and its config files. Hidden
 * from the sidebar -- reached through an instance's Databases tab.
 *
 * AIDEV-NOTE: the two-sided AUTHORITY GATE is not here. It lives on the model write
 * pipeline in {@code TenantWrites.requireInstanceLinkAuthority}, for the reason that class
 * documents at length: the framework's revision-restore endpoint, the automation API and
 * any direct {@code model.save} never pass through a resource method, so a resource-level
 * check would be a UX affordance rather than a gate. What lives here is REACHABILITY --
 * the things that make an attachment work at all -- plus ONE early call to that same
 * authority check for tenant actors, which decides the refusal ORDER: the reachability
 * lookups are unscoped, and run before the authority answer they were a cross-tenant
 * existence/name/host oracle (see the note inside {@link #validate}).
 */
public class InstanceDatabaseResource extends RowResource {

    /** env prefixes become variable names: a letter, then letters/digits/underscores. */
    private static final String PREFIX_PATTERN = "[A-Za-z][A-Za-z0-9_]*";

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(InstanceDatabaseModel.INSTANCE_ID, InstanceModel.MODEL_ID).build())
        .add(RelationPick.of(InstanceDatabaseModel.DATABASE_ID, DatabaseModel.MODEL_ID).build())
        .add(InstanceDatabaseModel.ENV_PREFIX)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(InstanceDatabaseModel.INSTANCE_ID)
            .relation(RelationPick.of(InstanceDatabaseModel.INSTANCE_ID,
                InstanceModel.MODEL_ID).build()).build())
        .column(ColumnSpec.fromField(InstanceDatabaseModel.DATABASE_ID)
            .relation(RelationPick.of(InstanceDatabaseModel.DATABASE_ID,
                DatabaseModel.MODEL_ID).build()).build())
        .column(ColumnSpec.fromField(InstanceDatabaseModel.ENV_PREFIX).copyable().build())
        .column(ColumnSpec.fromField(InstanceDatabaseModel.CREATED_AT).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_database"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance_database"); }
    @Override public @NonNull String slug() { return "instance-databases"; }
    @Override public @NonNull Model model() { return Models.get(InstanceDatabaseModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }

    /** The env prefix names the injected variable family; nothing else here is text. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(InstanceDatabaseModel.ENV_PREFIX);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 21; }
    @Override public @NonNull Icon icon() { return Icon.of("database"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> AccessDecision.allow(QueryPredicate.of(
            InstanceDatabaseModel.INSTANCE_ID.isNotNull()));
    }

    @Override
    public @Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("instances",
            row -> row.get(InstanceDatabaseModel.INSTANCE_ID)).tab("databases");
    }

    /**
     * Edit and delete both ride {@code TenantWrites.requireInstanceLinkAuthority}'s
     * two-sided rule (instance {@code config} plus database {@code manage}), so the
     * synthesized affordances are offered on exactly that answer -- the
     * {@link InstanceDeviceResource} shape: the delegated peer's read scope is the
     * wider {@code view}, and without this a view-only delegate was shown Edit/Delete
     * buttons the write pipeline could only refuse. The boolean twin, never a second
     * authority: the pipeline hook stays the gate.
     */
    @Override
    public boolean writableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        Integer instanceId = record.get(InstanceDatabaseModel.INSTANCE_ID);
        Integer databaseId = record.get(InstanceDatabaseModel.DATABASE_ID);
        return instanceId != null && databaseId != null
            && HohenheimAccess.hasInstanceCapability(accessContext, instanceId,
                HohenheimAccess.CONFIG)
            && HohenheimAccess.hasDatabaseCapability(accessContext, databaseId,
                HohenheimAccess.MANAGE);
    }

    /**
     * The env prefix only: renaming the injected variable family is the one thing about an
     * attachment that is text rather than a pick.
     *
     * AIDEV-NOTE: it bites at the NEXT DEPLOY, not at this click -- injection reads the
     * prefix when the workload starts, so a running container keeps the old variable names
     * until it is redeployed. Only remove-hooks exist on this join
     * ({@code InstanceDatabaseLinks}), so nothing else moves. INSTANCE_ID and DATABASE_ID
     * are RelationPicks, outside the compact cell subset, and re-pointing either is what
     * the two-sided authority gate exists to judge on a form.
     */
    @Override
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of(InstanceDatabaseModel.ENV_PREFIX);
    }

    /** The instance's Databases tab links here with ?instance_id= so the pick is preselected. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        String instanceId = conduit.getQueryParam("instance_id");
        if (instanceId != null && !instanceId.isEmpty()) {
            try {
                return Map.of("instance_id", Integer.parseInt(instanceId),
                    "env_prefix", InstanceDatabaseModel.DEFAULT_PREFIX);
            } catch (NumberFormatException ignored) {
                // Malformed prefill: render the bare form.
            }
        }
        return Map.of("env_prefix", InstanceDatabaseModel.DEFAULT_PREFIX);
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        validate(coerced, null);
        return super.persistRow(coerced, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        validate(coerced, existing);
        super.updateRow(existing, coerced, accessContext);
    }

    /**
     * Reachability, enforced at LINK time so injection never emits credentials the workload
     * cannot connect to: the instance must run on a driver that HAS link networks (Docker;
     * an Incus container or VM has none, and the deploy would refuse), and the database must
     * live on the same server, because a link network exists only on the daemon both
     * workloads share. Both are refused again on the deploy path -- this one exists so the
     * operator learns it while attaching rather than at the next start.
     */
    private void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        Integer instanceId = intOf(coerced.get("instance_id"),
            existing != null ? existing.get(InstanceDatabaseModel.INSTANCE_ID) : null);
        if (instanceId == null) {
            throw Violations.ofField("instance_id", null,
                CmsSupport.violationText("instance_required"));
        }
        Integer databaseId = intOf(coerced.get("database_id"),
            existing != null ? existing.get(InstanceDatabaseModel.DATABASE_ID) : null);
        // AIDEV-NOTE: for a TENANT the authority decision comes BEFORE any lookup below,
        // because the lookups are UNSCOPED and their refusals used to be a cross-tenant
        // oracle: absent answered database_missing, wrong-host answered
        // database_instance_server_mismatch interpolating the stored (owner-namespaced)
        // name and both host names, and only same-host-not-yours fell through to the
        // uniform refusal -- three distinguishable answers to three probes, violating
        // HohenheimAccess.databaseRefusal's "visibility, absence and denial are one
        // answer". This is the same two-sided check TenantWrites' hook re-runs at save;
        // asked here it decides the ORDER, so absent and not-yours collapse and only a
        // database the actor may attach ever reaches the reachability messages below.
        // Operators (no tenant origin) keep the full diagnostic vocabulary.
        if (TenantWrites.isTenantOriginated()) {
            if (databaseId == null) {
                throw Violations.ofField("database_id", null,
                    CmsSupport.violationText("database_required"));
            }
            TenantWrites.requireInstanceLinkAuthority(instanceId, databaseId);
        }
        Row instance = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
            .first();
        if (instance == null) {
            throw Violations.ofField("instance_id", instanceId,
                CmsSupport.violationText("instance_missing"));
        }
        String kind = instance.get(InstanceModel.KIND);
        InstanceKindHandler handler = InstanceKinds.getHandler(kind);
        if (handler == null || !ServerModel.RUNTIME_DOCKER.equals(handler.requiredRuntime())) {
            throw Violations.ofField("instance_id", instanceId,
                CmsSupport.violationText("instance_kind_no_injection")
                    .withArg("kind", String.valueOf(kind)));
        }

        if (databaseId == null) {
            throw Violations.ofField("database_id", null,
                CmsSupport.violationText("database_required"));
        }
        Row database = Models.get(DatabaseModel.class).find()
            .where(DatabaseModel.ID.eq(databaseId)).first();
        if (database == null) {
            throw Violations.ofField("database_id", databaseId,
                CmsSupport.violationText("database_missing"));
        }
        int databaseServer = ServerModel.canonicalServerId(database.get(DatabaseModel.SERVER_ID));
        int instanceServer = ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID));
        if (databaseServer != instanceServer) {
            throw Violations.ofField("database_id", databaseId,
                CmsSupport.violationText("database_instance_server_mismatch")
                    .withArg("name", database.get(DatabaseModel.NAME))
                    .withArg("server", ServerModel.nameOf(databaseServer))
                    .withArg("instance_server", ServerModel.nameOf(instanceServer)));
        }

        String prefix = prefixOf(coerced, existing);
        if (!prefix.matches(PREFIX_PATTERN)) {
            throw Violations.ofField("env_prefix", prefix,
                CmsSupport.violationText("prefix_format"));
        }

        Integer existingId = existing != null ? existing.get(InstanceDatabaseModel.ID) : null;
        for (Row link : Models.get(InstanceDatabaseModel.class).findByInstanceId(instanceId)) {
            if (existingId != null && existingId.equals(link.get(InstanceDatabaseModel.ID))) {
                continue;
            }
            if (databaseId.equals(link.get(InstanceDatabaseModel.DATABASE_ID))) {
                throw Violations.ofField("database_id", databaseId,
                    CmsSupport.violationText("database_already_attached"));
            }
            String linkPrefix = DatabaseEnvInjection.normalizedPrefix(
                link.get(InstanceDatabaseModel.ENV_PREFIX));
            if (linkPrefix.equalsIgnoreCase(prefix)) {
                throw Violations.ofField("env_prefix", prefix,
                    CmsSupport.violationText("prefix_taken")
                        .withArg("prefix", prefix.toUpperCase(Locale.ROOT)));
            }
        }
    }

    private static @NonNull String prefixOf(@NonNull Map<String, Object> coerced,
                                            @Nullable Row existing) {
        Object submitted = coerced.containsKey("env_prefix") ? coerced.get("env_prefix")
            : existing != null ? existing.get(InstanceDatabaseModel.ENV_PREFIX) : null;
        String prefix = submitted != null ? String.valueOf(submitted).trim() : "";
        return prefix.isEmpty() ? InstanceDatabaseModel.DEFAULT_PREFIX : prefix;
    }

    private static @Nullable Integer intOf(@Nullable Object submitted, @Nullable Integer fallback) {
        return submitted instanceof Integer i ? i : fallback;
    }
}
