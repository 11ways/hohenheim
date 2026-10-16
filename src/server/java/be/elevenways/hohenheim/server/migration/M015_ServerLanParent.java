package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.LongField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.HeldHistory;
import be.elevenways.zenit.common.orm.migration.KeyTokens;
import be.elevenways.zenit.common.orm.migration.M012_RekeyCorePrincipals;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import be.elevenways.zenit.common.orm.migration.PrincipalColumns;
import be.elevenways.zenit.common.orm.migration.RecordIdMap;
import be.elevenways.zenit.common.orm.migration.RecordReference;
import be.elevenways.zenit.common.orm.migration.RecordRenumbering;
import be.elevenways.zenit.common.orm.migration.StoredKeys;
import be.elevenways.zenit.common.security.ZenitPrincipalKind;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Adds {@code servers.lan_parent}, the host interface its virtual machines may join the local network through, and
 * stores every account and permission group Hohenheim names by the UUID zenit-auth renumbered it to: a certificate's
 * requester, a host's posture acknowledger, a template's approver, a project's group, and the owner tokens packed into
 * quota overrides, released route claims and every row's quota bucket stamp, in their rows and in their history.
 *
 * AIDEV-NOTE: it depends on M012_RekeyCorePrincipals and zenit-auth's M024_RekeyAuthIds, so every live account and
 * group is mapped when it runs. An id of an account or group deleted since is adopted (a fresh UUID nothing carries),
 * in the id columns and in the owner tokens alike, so the row keeps naming the same nobody instead of refusing the
 * boot. Transaction-free, since the added columns must be committed before a step reads them; every account id is
 * checked first, in the check phase.
 *
 * AIDEV-NOTE: a bucket stamp is its budget's prefix and a packed owner set, folded to {@code sha256:} of the set
 * above 191 characters (OwnerQuota.bucketOf). The prefixes, the length and the fold below are that spelling frozen
 * here (a migration never reads a live vocabulary; HohenheimUpgradeJourneyTest pins them to OwnerBudget's): a stamp
 * is re-packed and folded as today's code writes it, so a shared owner's stamp may fold once its tokens are UUIDs. A
 * folded stamp maps through the owner sets this module keeps readable ({@link #ownerPacks}); one none of them names
 * stays, and stays consistent: a release hands back the stamp it charged, and the boot's QuotaReconciler rebuilds the
 * ledger ({@code zenit_quotas}) from the stamps, so the ledger's own keys are left to it.
 *
 * AIDEV-NOTE: it lives in the server source set for the fold's digest (SecureTokens); its version keeps its place in
 * the stream, which is all a migration's identity is.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class M015_ServerLanParent extends HohenheimMigration {

    /** The stored prefix of each quota budget whose bucket a row stamps, as OwnerBudget spelled it at this version. */
    public static final String SITES = "hohenheim:sites:";
    public static final String DATABASES = "hohenheim:databases:";
    public static final String INSTANCES = "hohenheim:instances:";
    public static final String DISK_GB = "hohenheim:disk_gb:";
    public static final String NICS = "hohenheim:nics:";
    public static final String PREVIEWS = "hohenheim:previews:";

    /** The longest unfolded bucket stamp, and what a longer one stores after its prefix, as OwnerQuota folded them. */
    private static final int BUCKET_LENGTH = 191;
    private static final String FOLDED = "sha256:";

    /** What separates the subjects of one packed owner set, sorted (RecordOwners.packSubjects). */
    private static final String PACK_SEPARATOR = "\n";

    /** The account kind, whose deleted ids the id columns adopt. */
    private static final Set<ZenitPrincipalKind> ADOPTED = Set.of(ZenitPrincipalKind.ACCOUNT);

    /** One stamped bucket column, the model its history is filed under and the budgets its stamps name. */
    private record Stamp(@NonNull String table, @NonNull Identifier model, @NonNull String column,
                         @NonNull List<String> budgets) {}

    /** One id column retyped from a number to a uuid, and the number type it is restored as. */
    private record Retyped(@NonNull String table, @NonNull String column, @NonNull ColumnType numberType) {}

    /** One text column holding owner tokens, under the name its step's description gives it. */
    private record TokenColumn(@NonNull String name, StoredKeys.@NonNull Column column) {}

    private static final List<Stamp> STAMPS = List.of(
        new Stamp("sites", SiteModel.MODEL_ID, "quota_bucket", List.of(SITES)),
        new Stamp("managed_databases", DatabaseModel.MODEL_ID, "quota_bucket", List.of(DATABASES)),
        new Stamp("instances", InstanceModel.MODEL_ID, "quota_bucket", List.of(INSTANCES)),
        new Stamp("instances", InstanceModel.MODEL_ID, "root_disk_bucket", List.of(DISK_GB)),
        new Stamp("instance_devices", InstanceDeviceModel.MODEL_ID, "quota_bucket", List.of(DISK_GB, NICS)),
        new Stamp("preview_deployments", PreviewDeploymentModel.MODEL_ID, "quota_bucket", List.of(PREVIEWS)));

    private static final String CERTIFICATES = "certificates";
    private static final String REQUESTER = "requested_by_user_id";
    private static final String REQUESTER_KIND = "requested_by_kind";
    private static final String SERVERS = "servers";
    private static final String ACKNOWLEDGER = "acknowledged_by";
    private static final String ACKNOWLEDGER_KIND = "acknowledged_by_kind";
    private static final String TEMPLATES = "instance_templates";
    private static final String APPROVER = "approved_by_user_id";
    private static final String PROJECTS = "projects";
    private static final String GROUP = "group_id";
    private static final String QUOTAS = "instance_quotas";
    private static final String QUOTA_SUBJECTS = "subjects";
    /** The cap the quota owner column was created with; ten UUID subjects outgrow it, so it becomes text. */
    private static final int NUMERIC_SUBJECTS_LENGTH = 400;
    private static final String CLAIMS = "released_route_claims";
    private static final String FORMER_SUBJECTS = "former_subjects";
    /** zenit-auth's record grants, whose manage grants are each record's owner set (RecordOwners). */
    private static final String GRANTS = "auth_record_grants";
    private static final String MANAGE = "manage";
    /** The column each retyped id is written into before it takes the old column's place, either way. */
    private static final String SWAP = "_swap";

    private static final List<Retyped> RETYPED = List.of(new Retyped(CERTIFICATES, REQUESTER, ColumnType.INTEGER),
        new Retyped(TEMPLATES, APPROVER, ColumnType.LONG), new Retyped(PROJECTS, GROUP, ColumnType.INTEGER));

    public M015_ServerLanParent() {
        super("015", "Server LAN parent and UUID account ids", false);
        dependsOn(M012_RekeyCorePrincipals.class);
        dependsOn("be.elevenways.zenit.auth.server.migration.M024_RekeyAuthIds");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        // 0. Every account id is mapped once deleted accounts are adopted, or nothing is written.
        for (PrincipalColumns.Rekeyed ids : accountIds(numeric(REQUESTER), uuid(REQUESTER + SWAP),
                numericLong(APPROVER), uuid(APPROVER + SWAP))) {
            schema.check("check every id of " + ids.name() + " is mapped", "1", ids.check(ADOPTED));
        }

        schema.alterTable(SERVERS, table ->
            table.addColumn("lan_parent", ColumnType.STRING, column -> column.nullable(true).maxLength(15)));
        for (Retyped retyped : RETYPED) {
            schema.alterTable(retyped.table(), table -> table.addColumn(retyped.column() + SWAP, ColumnType.UUID,
                column -> column.nullable(true).ifNotExists()));
        }
        schema.alterTable(QUOTAS, table -> table.changeColumn(QUOTA_SUBJECTS, ColumnType.TEXT,
            column -> column.nullable(false)));

        // 1. A deleted account's or group's number gets a fresh UUID, wherever an id or an owner token names it.
        for (PrincipalColumns.Rekeyed ids : accountIds(numeric(REQUESTER), uuid(REQUESTER + SWAP),
                numericLong(APPROVER), uuid(APPROVER + SWAP))) {
            schema.data("map every deleted account in " + ids.name(), "1", ids.adopt(ADOPTED));
        }
        schema.data("map every deleted permission group of a project", "1",
            projectGroups(numeric(GROUP), uuid(GROUP + SWAP)).adopt());
        for (TokenColumn tokens : tokenColumns()) {
            schema.data("map every deleted owner in " + tokens.name(), "1", tokens.column().adopt());
        }

        // 2. Each id moves into its uuid column, which then takes the old column's place; the acknowledger's text pair
        //    and the owner tokens are rewritten in place.
        for (PrincipalColumns.Rekeyed ids : accountIds(numeric(REQUESTER), uuid(REQUESTER + SWAP),
                numericLong(APPROVER), uuid(APPROVER + SWAP))) {
            schema.data("rekey every account in " + ids.name(), "1", ids.rekey());
        }
        schema.data("rekey every project's permission group", "1",
            projectGroups(numeric(GROUP), uuid(GROUP + SWAP)).rekey());
        for (TokenColumn tokens : tokenColumns()) {
            schema.data("rekey every owner token in " + tokens.name(), "1", tokens.column().rekey());
        }
        schema.data("follow every account in history", "1", accountHistory().rekey());
        schema.data("follow every permission group in history", "1", groupHistory().rekey());
        swap(schema);
    }

    /** Every id back to its number; a UUID the rekey maps never produced refuses where only a number fits. */
    @Override
    public void down(@NonNull MigrationBuilder schema) {
        for (Retyped retyped : RETYPED) {
            schema.alterTable(retyped.table(), table -> table.addColumn(retyped.column() + SWAP, retyped.numberType(),
                column -> column.nullable(true).ifNotExists()));
        }
        schema.data("restore every permission group number in history", "1", groupHistory().restore());
        schema.data("restore every account number in history", "1", accountHistory().restore());
        for (TokenColumn tokens : tokenColumns()) {
            schema.data("restore every owner token number in " + tokens.name(), "1", tokens.column().restore());
        }
        schema.data("restore every project's permission group number", "1",
            projectGroups(numeric(GROUP + SWAP), uuid(GROUP)).restore());
        for (PrincipalColumns.Rekeyed ids : accountIds(numeric(REQUESTER + SWAP), uuid(REQUESTER),
                numericLong(APPROVER + SWAP), uuid(APPROVER))) {
            schema.data("restore every account number in " + ids.name(), "1", ids.restore());
        }
        swap(schema);
        schema.alterTable(QUOTAS, table -> table.changeColumn(QUOTA_SUBJECTS, ColumnType.STRING,
            column -> column.nullable(false).maxLength(NUMERIC_SUBJECTS_LENGTH)));
        schema.alterTable(SERVERS, table -> table.dropColumn("lan_parent"));
    }

    /** Drops each retyped id column and gives its swap column the name. */
    private static void swap(@NonNull MigrationBuilder schema) {
        for (Retyped retyped : RETYPED) {
            schema.alterTable(retyped.table(), table -> {
                table.dropColumn(retyped.column());
                table.renameColumn(retyped.column() + SWAP, retyped.column());
            });
        }
    }

    /**
     * @return the account ids, read from {@code requester}/{@code approver} into the given targets; the requester
     *         and approver end nullable (an unattended order, an unapproved template), the acknowledger is text in place
     */
    private static @NonNull List<PrincipalColumns.Rekeyed> accountIds(@NonNull Supplier<Field<?, ?>> requester,
                                                                     @NonNull Supplier<Field<?, ?>> requesterTarget,
                                                                     @NonNull Supplier<Field<?, ?>> approver,
                                                                     @NonNull Supplier<Field<?, ?>> approverTarget) {
        return List.of(
            PrincipalColumns.ids(CERTIFICATES, key(), REQUESTER_KIND, requester).into(requesterTarget).endsNullable(),
            PrincipalColumns.ids(SERVERS, key(), ACKNOWLEDGER_KIND, text(ACKNOWLEDGER)).endsNullable(),
            PrincipalColumns.ids(TEMPLATES, key(), ZenitPrincipalKind.ACCOUNT, approver).into(approverTarget)
                .endsNullable());
    }

    /** @return the permission group of every project, a key of zenit-auth's renumbered group model */
    private static PrincipalColumns.@NonNull Ids projectGroups(@NonNull Supplier<Field<?, ?>> source,
                                                               @NonNull Supplier<Field<?, ?>> target) {
        return RecordReference.keyColumn(PROJECTS, key(), PermissionGroupModel.MODEL_ID, source).into(target)
            .endsNullable();
    }

    /** @return every text column holding owner tokens: packed owner sets and every budget's bucket stamps */
    private static @NonNull List<TokenColumn> tokenColumns() {
        List<TokenColumn> columns = new ArrayList<>();
        columns.add(new TokenColumn(QUOTAS + "." + QUOTA_SUBJECTS, packed().in(QUOTAS, key(), text(QUOTA_SUBJECTS))));
        columns.add(new TokenColumn(CLAIMS + "." + FORMER_SUBJECTS,
            packed().in(CLAIMS, key(), text(FORMER_SUBJECTS))));
        for (Stamp stamp : STAMPS) {
            for (String budget : stamp.budgets()) {
                columns.add(new TokenColumn(stamp.table() + "." + stamp.column() + " (" + budget + ")",
                    bucket(budget).in(stamp.table(), key(), text(stamp.column()))));
            }
        }
        return columns;
    }

    /** @return every account in the history of the columns this migration rekeys */
    private static @NonNull HeldHistory accountHistory() {
        return held(HeldHistory.principals(ZenitPrincipalKind.ACCOUNT)
            .pairedIn(new RecordRenumbering.Holder(CertificateModel.MODEL_ID, CERTIFICATES, key()), REQUESTER,
                REQUESTER_KIND)
            .pairedIn(new RecordRenumbering.Holder(ServerModel.MODEL_ID, SERVERS, key()), ACKNOWLEDGER,
                ACKNOWLEDGER_KIND)
            .heldIn(InstanceTemplateModel.MODEL_ID, APPROVER));
    }

    /** @return every permission group in the history of the columns this migration rekeys */
    private static @NonNull HeldHistory groupHistory() {
        return held(HeldHistory.of(PermissionGroupModel.MODEL_ID, RecordIdMap.recorded(PermissionGroupModel.MODEL_ID))
            .heldIn(ProjectModel.MODEL_ID, GROUP));
    }

    /** @return {@code history}, also following the owner tokens of every packed owner set and bucket stamp */
    private static @NonNull HeldHistory held(@NonNull HeldHistory history) {
        HeldHistory held = history.heldIn(InstanceQuotaModel.MODEL_ID, QUOTA_SUBJECTS, packed())
            .heldIn(ReleasedRouteClaimModel.MODEL_ID, FORMER_SUBJECTS, packed());
        for (Stamp stamp : STAMPS) {
            for (String budget : stamp.budgets()) {
                held = held.heldIn(stamp.model(), stamp.column(), bucket(budget));
            }
        }
        return held;
    }

    /** @return a packed owner set: subject tokens, sorted as RecordOwners.packSubjects sorts them */
    private static @NonNull StoredKeys packed() {
        return StoredKeys.packed(PACK_SEPARATOR, Comparator.naturalOrder(), tokens());
    }

    /** @return the bucket stamps of one budget: its prefix and a packed owner set, folded above the length */
    private static @NonNull StoredKeys bucket(@NonNull String budget) {
        return packed().behind(budget).foldedAbove(BUCKET_LENGTH, M015_ServerLanParent::fold,
            M015_ServerLanParent::ownerPacks);
    }

    /** @return the owner tokens: an account's and a permission group's */
    private static @NonNull KeyTokens tokens() {
        String user = GrantSubjectType.USER.key() + ":";
        String group = GrantSubjectType.GROUP.key() + ":";
        return KeyTokens.of().principal(ZenitPrincipalKind.ACCOUNT, user, user)
            .record(PermissionGroupModel.MODEL_ID, group, group);
    }

    /** @return a packed owner set as a folded bucket stores it */
    private static @NonNull String fold(@NonNull String packed) {
        return FOLDED + SecureTokens.sha256Hex(packed);
    }

    /**
     * @return the owner sets a folded stamp may stand for: every quota override's and released claim's, and the
     *         manage-grant subjects of every record, packed as RecordOwners.packSubjects packs them
     */
    private static @NonNull List<String> ownerPacks() {
        List<String> packs = new ArrayList<>();
        for (String column : List.of(QUOTA_SUBJECTS, FORMER_SUBJECTS)) {
            String table = column.equals(QUOTA_SUBJECTS) ? QUOTAS : CLAIMS;
            for (Row row : new FrozenModel(table, key().get(), text(column).get()).find().noCache().all()) {
                if (row.get(column) instanceof String pack) {
                    packs.add(pack);
                }
            }
        }
        StringField model = StringField.builder().name("model").nullable(true).build();
        StringField record = StringField.builder().name("record_id").nullable(true).build();
        StringField capability = StringField.builder().name("capability").nullable(true).build();
        BooleanField value = BooleanField.builder("value").nullable(true).build();
        StringField type = StringField.builder().name("subject_type").nullable(true).build();
        UuidField subject = UuidField.builder().name("subject_id").nullable(true).build();
        FrozenModel grants = new FrozenModel(GRANTS, StringField.builder().name("id").build(), model, record,
            capability, value, type, subject);
        Map<String, Set<String>> owners = new HashMap<>();
        for (Row grant : grants.find().noCache().where(capability.eq(MANAGE)).all()) {
            UUID id = grant.get(subject);
            if (Boolean.TRUE.equals(grant.get(value)) && id != null) {
                owners.computeIfAbsent(grant.get(model) + "\n" + grant.get(record), ignored -> new TreeSet<>())
                    .add(grant.get(type) + ":" + id);
            }
        }
        for (Set<String> owner : owners.values()) {
            packs.add(String.join(PACK_SEPARATOR, owner));
        }
        return packs;
    }

    private static @NonNull Supplier<Field<?, ?>> key() {
        return () -> IntegerField.builder().name("id").build();
    }

    private static @NonNull Supplier<Field<?, ?>> numeric(@NonNull String column) {
        return () -> IntegerField.builder().name(column).nullable(true).build();
    }

    private static @NonNull Supplier<Field<?, ?>> numericLong(@NonNull String column) {
        return () -> LongField.builder(column).nullable(true).build();
    }

    private static @NonNull Supplier<Field<?, ?>> text(@NonNull String column) {
        return () -> StringField.builder().name(column).nullable(true).build();
    }

    private static @NonNull Supplier<Field<?, ?>> uuid(@NonNull String column) {
        return () -> UuidField.builder().name(column).nullable(true).build();
    }
}
