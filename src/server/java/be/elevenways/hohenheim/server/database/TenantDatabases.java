package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.Secrets;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * THE tenant database-allocation funnel: one derivation for the stored name, the
 * credentials, the engine image, the PLACEMENT, the creator's {@code manage} grant and the
 * quota charge, reached by every surface that may allocate on a tenant's behalf.
 *
 * It is one method for the reason {@code InstanceTemplates.createFromTemplate} is one
 * method: those five answers must agree, and three of them are only correct RELATIVE to
 * the other two. The grant must exist before the engine instance row is written, because
 * {@code InstanceQuota} charges an owned instance to the owner of the record that owns it
 * -- plant it afterwards and the charge lands in the operator's bucket and is never
 * released.
 *
 * What a tenant does NOT get to choose, and what stands in for each: the HOST (
 * {@link InstancePlacement}, which ignores a submitted one for a non-admin), the IMAGE
 * (the engine's own default image -- the managed-database analogue of "approved templates
 * only"; DatabaseContainerKind is {@code tenantAuthored() == false} and has no image
 * policy of its own), the resource CAPS (the kind's declared footprint, which is also the
 * cgroup cap the daemon enforces), and every credential.
 *
 * @author Jelle De Loecker
 */
public final class TenantDatabases {

    /**
     * Type-level authority to allocate a managed database. A PERMISSION and not a record
     * capability for the {@link HohenheimAccess#INSTANCES_CREATE} reason: no record exists
     * yet to hold a capability on. It is eligibility only -- the real bounds are the
     * transactional instance quota the engine is charged to and placement.
     */
    public static final Permission DATABASES_CREATE =
        Permission.of("hohenheim.databases.create");

    /** Docker's object-name ceiling, minus room for the owner prefix and the volume suffix. */
    private static final int MAX_LABEL_LENGTH = 32;

    private TenantDatabases() {
    }

    /**
     * Whether the context may allocate databases at all (admins always may; a null
     * context is in-process operator work, the {@code InstancePlacement.forActor} seam).
     */
    public static boolean canAllocate(@Nullable AccessContext ctx) {
        return ctx == null || HohenheimAccess.isAdmin(ctx) || ctx.hasPermission(DATABASES_CREATE);
    }

    /**
     * THE stored name of a database allocated by {@code ctx}, per-OWNER namespaced.
     *
     * The name is not decoration: it is the container handle
     * ({@code ControllerScope.KIND_DB}), the data volume
     * ({@link DatabaseInstances#dataVolumeOf}) and the backup directory, and it is UNIQUE
     * installation-wide. Left un-namespaced, one tenant taking "wordpress" would deny it
     * to every other tenant forever and turn the create form into a name oracle over
     * other tenants' databases. Namespacing makes the label per-owner private; the
     * INSERT-only refusal ({@code database_name_taken}) then only ever fires within one
     * owner's own names.
     *
     * OPERATOR allocations keep the bare label, so every existing record and the admin
     * panel are unaffected -- an operator is the one owner whose namespace is the
     * installation.
     */
    public static @NonNull String storedNameFor(@Nullable AccessContext ctx,
                                                @NonNull String label) {
        Set<String> owner = HohenheimAccess.creationOwnerSubjects(ctx);
        if (owner.isEmpty()) {
            return label;
        }
        if (owner.size() == 1) {
            String subject = owner.iterator().next();
            if (subject.startsWith("user:")) {
                return "u" + subject.substring("user:".length()) + "-" + label;
            }
        }
        // A project (or any multi-subject) owner: a short stable digest of the packed set,
        // because the readable spelling of a subject set is not a legal object name.
        return "o" + SecureTokens.sha256Hex(HohenheimAccess.packSubjects(owner))
            .substring(0, 8) + "-" + label;
    }

    /**
     * Allocate a managed database for {@code ctx} and return the persisted record.
     *
     * The container work is deliberately NOT part of this call: it is scheduled to run
     * after the caller's transaction commits, so a refusal anywhere in the funnel leaves
     * NOTHING behind -- no record, no instance row, no reservation, and no pool thread
     * racing a row that was rolled back.
     *
     * @throws Violations {@code databases_not_permitted}, {@code name_format},
     *         {@code unknown_engine}, {@code database_name_taken}, or any placement /
     *         quota / capacity refusal raised by the engine-row reservation
     */
    public static @NonNull Row allocate(@NonNull AccessContext ctx, @Nullable Object rawName,
                                        @Nullable Object rawEngine) {
        return allocate(ctx, rawName, rawEngine, null, null);
    }

    /**
     * {@link #allocate(AccessContext, Object, Object)} for a TEMPLATE-declared database:
     * the image is the template's operator-authored override (null = the engine's
     * default) and the requested host is the instance's, honoured exactly as
     * {@link InstancePlacement#forActor} honours it -- for an admin or in-process
     * caller; a tenant still walks the chooser and the caller compares the answer.
     *
     * @throws Violations as the three-argument form
     */
    public static @NonNull Row allocate(@Nullable AccessContext ctx, @Nullable Object rawName,
                                        @Nullable Object rawEngine, @Nullable String image,
                                        @Nullable Integer requestedServerId) {
        if (!canAllocate(ctx)) {
            throw Violations.ofForm(CmsSupport.violationText("databases_not_permitted"));
        }

        String label = BlastString.lower(rawName == null ? "" : String.valueOf(rawName).trim());
        if (!label.matches("[a-z0-9][a-z0-9-]*") || label.length() > MAX_LABEL_LENGTH) {
            throw Violations.ofField(DatabaseModel.NAME.getName(), label,
                CmsSupport.violationText("name_format"));
        }
        String engineToken = BlastString.lower(
            rawEngine == null ? "" : String.valueOf(rawEngine).trim());
        ManagedDatabase.Engine engine = engineOf(engineToken);

        String storedName = storedNameFor(ctx, label);
        // The engine's own default image, never a SUBMITTED one: the managed-database
        // reading of "tenants run operator-approved images only". A template's declared
        // image is operator-authored (and approval-gated for tenants), which is the one
        // override this funnel takes.
        InstanceKindHandler kind = InstanceKinds.getHandler(DatabaseContainerKind.ID.toString());
        String resolvedImage = image == null || image.isBlank() ? engine.defaultImage : image;
        Map<String, Object> placementSettings = Map.of("engine", engine.token(),
            "image", resolvedImage);
        int serverId = InstancePlacement.forActor(ctx, requestedServerId,
            InstancePlacement.Workload.of(kind, placementSettings));

        DatabaseService service = new DatabaseService();
        Row[] created = new Row[1];
        TenantWrites.inDatabaseAllocation(() -> created[0] = service.insertRecord(storedName,
            engine, image == null || image.isBlank() ? null : image, "app",
            Secrets.generatePassword(), sqlIdentifier(label), false,
            ServerModel.nameOf(serverId), ResourceLimits.none(),
            DatabaseService.STATUS_PROVISIONING));
        Row record = created[0];
        int recordId = record.get(DatabaseModel.ID);

        // Ownership BEFORE the engine row: InstanceQuota charges an owned instance to the
        // owner of the record that owns it, and reads exactly these grants to find out who
        // that is. Planting them afterwards charges the operator for a tenant's engine.
        grantCreatorManage(recordId, ctx);

        // AIDEV-NOTE: the record is compensated EXPLICITLY rather than left to the scoped
        // create transaction. That transaction only exists when the resource's access
        // function returns a predicate, which is true for a tenant and false for an admin
        // (unconstrained) -- so relying on it alone would leave an operator's refused
        // allocation as a "provisioning" record that never provisions. Removing it here is
        // correct with or without one.
        try {
            DatabaseInstances.reserveEngineRow(record, ResourceLimits.none());
        } catch (RuntimeException | Error refused) {
            abandon(recordId, ctx);
            throw refused;
        } catch (Exception e) {
            abandon(recordId, ctx);
            throw new IllegalStateException(e);
        }

        Models.get(DatabaseModel.class).getResolvedDatasource().afterCommit(
            () -> service.provisionInBackground(recordId));
        return record;
    }

    /**
     * Undo a half-finished allocation. No container exists yet -- the provisioning is only
     * scheduled after the whole funnel succeeds -- so this is a record delete and a grant
     * revoke, in THAT order: the delete rides the tenant-write hook that demands
     * {@code destroy} on the row, which the creator's own manage grant implies. Revoking
     * first would make the compensation refuse itself.
     */
    private static void abandon(int databaseId, @Nullable AccessContext ctx) {
        Models.get(DatabaseModel.class).find()
            .where(DatabaseModel.ID.eq(databaseId)).delete();
        for (String subject : HohenheimAccess.creationOwnerSubjects(ctx)) {
            int separator = subject.indexOf(':');
            RecordGrants.revoke(GrantSubjectType.fromKey(subject.substring(0, separator)),
                Integer.parseInt(subject.substring(separator + 1)),
                DatabaseModel.MODEL_ID, databaseId, HohenheimAccess.MANAGE);
        }
    }

    /**
     * Hand a tenant creator {@code manage} on what they just allocated. Operator
     * allocations plant nothing: an empty subject set IS operator ownership, and a grant
     * there would make one admin's database look tenant-held to sameOwner.
     */
    private static void grantCreatorManage(int databaseId, @Nullable AccessContext ctx) {
        for (String subject : HohenheimAccess.creationOwnerSubjects(ctx)) {
            int separator = subject.indexOf(':');
            RecordGrants.grant(GrantSubjectType.fromKey(subject.substring(0, separator)),
                Integer.parseInt(subject.substring(separator + 1)),
                DatabaseModel.MODEL_ID, databaseId, HohenheimAccess.MANAGE, true);
        }
        // The request memo caches "which records does this principal hold X on", and the
        // line above just changed the answer. zenit-cms verifies the created row against
        // the caller's own scope predicate before committing, so a stale memo makes a
        // legitimate allocation refuse ITSELF with out_of_scope.
        if (ctx != null) {
            HohenheimAccess.forgetGrantedRecordIds(ctx);
        }
    }

    private static ManagedDatabase.@NonNull Engine engineOf(@NonNull String token) {
        ManagedDatabase.Engine engine = ManagedDatabase.Engine.forToken(token);
        if (engine == null) {
            throw Violations.ofField(DatabaseModel.ENGINE.getName(), token,
                CmsSupport.violationText("unknown_engine").withArg("engine", token));
        }
        return engine;
    }

    /** The label as an in-engine database name: hyphens are not portable identifiers. */
    private static @NonNull String sqlIdentifier(@NonNull String label) {
        String identifier = label.replace('-', '_');
        if (!identifier.isEmpty() && identifier.charAt(0) >= '0' && identifier.charAt(0) <= '9') {
            identifier = "db_" + identifier;
        }
        return identifier;
    }
}
