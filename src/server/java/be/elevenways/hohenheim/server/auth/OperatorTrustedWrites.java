package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.source.GitRepository;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * Where an OPERATOR-OWNED record connects is written under the non-delegable {@code hohenheim.admin.system} alone, and
 * every record remembers whether its target was last set by that tier, both on the model write pipelines like
 * {@link TenantWrites}.
 *
 * AIDEV-NOTE: an operator-owned site (TenantUpstreams), git provider (SourceOwnership.providerGuard) and instance
 * source (SourceOwnership.localSourcesAllowed) reach any address or a controller path, so their target is a trusted
 * fetch. A delegated admin.access holder passes TenantWrites as the operator, so the gate refuses them that target on
 * an operator-owned record; every other column stays theirs. Declared system work passes; work
 * with no identity is refused, like TenantWrites. A record nobody holds manage on is operator-owned; a create reads
 * the declared creation owner before its grant exists, and an unreadable grant set counts as operator-owned.
 *
 * AIDEV-NOTE: the gate alone cannot decide reach, because ownership changes where no write hook sees it (a revoked
 * grant, a deleted tenant, a cascade): a target a delegate set on a tenant-owned record would be dialled with
 * any-address reach once the record became operator-owned. So the hook also stamps the record's TARGET_TRUSTED mark,
 * and the fetch side asks for it beside ownership. The mark follows WHO SET the target: a system-tier caller whose
 * write sets the target's column sets it (the operator re-saving vouches for what the form showed; a cell edit of
 * another column carries no target, though its row was loaded whole), declared system work
 * sets it only when it changes the target (a task re-saving a loaded row vouches for nothing), any other writer
 * changing the target clears it, and any other write keeps the stored mark. A mark a writer carries itself is never
 * taken: the hook always writes its own decision.
 *
 * @author Jelle De Loecker
 * @since 0.9.0
 */
public final class OperatorTrustedWrites {

    /**
     * One operator-trustable target of a record.
     *
     * @param field     the column the target lives in, which a refusal names
     * @param column    the stored column holding the target, which a write carries when it sets it
     * @param value     the target as a row holds it
     * @param refusable which values a delegate is refused on an operator-owned record
     */
    private record Target(@NonNull String field, @NonNull Field<?, ?> column, @NonNull Function<Row, Object> value,
                          @Nullable Object createBaseline, @NonNull Predicate<Object> refusable) {

        /** A whole column, every value of which is the target. */
        static @NonNull Target column(@NonNull Field<?, ?> field) {
            String name = field.getName();
            return new Target(name, field, row -> row.get(name), field.getDefaultValue(),
                value -> true);
        }

        /** An instance's source repository URL, a delegate being refused only a local one (a controller path). */
        static @NonNull Target instanceSource() {
            return new Target(GitSourceSchema.REPOSITORY_URL, InstanceModel.SETTINGS,
                row -> {
                    Object url = InstanceModel.settingsOf(row).get(GitSourceSchema.REPOSITORY_URL);
                    String text = trimmed(url);
                    return text.isEmpty() ? null : text;
                }, null, value -> GitRepository.isLocalCloneUrl((String) value));
        }
    }

    /** One record type with operator-trustable targets and the mark of who set them. */
    private record Guarded(@NonNull Class<? extends Model> model, @NonNull Identifier modelId,
                           @NonNull Field<?, ?> id, @NonNull BooleanField mark, @NonNull List<Target> targets) {
    }

    /**
     * The targets: a site's upstream (its kind, the per-kind settings holding every dial target, and the trust flag
     * that lifts a tenant site to any-address reach), a provider's base URL and an instance's source.
     */
    private static final List<Guarded> GUARDED = List.of(
        new Guarded(SiteModel.class, SiteModel.MODEL_ID, SiteModel.ID, SiteModel.TARGET_TRUSTED,
            List.of(Target.column(SiteModel.UPSTREAM_KIND), Target.column(SiteModel.SETTINGS),
                Target.column(SiteModel.TRUSTED_UPSTREAM))),
        new Guarded(GitProviderModel.class, GitProviderModel.MODEL_ID, GitProviderModel.ID,
            GitProviderModel.TARGET_TRUSTED, List.of(Target.column(GitProviderModel.BASE_URL))),
        new Guarded(InstanceModel.class, InstanceModel.MODEL_ID, InstanceModel.ID, InstanceModel.TARGET_TRUSTED,
            List.of(Target.instanceSource())));

    private static volatile boolean installed;

    private OperatorTrustedWrites() {
    }

    /** Install the gate and the mark on every guarded model; idempotent, called at the MODULES boot stage. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        for (Guarded guarded : GUARDED) {
            Models.get(guarded.model()).getSchema().addBeforeValidateHook(context -> {
                Row row = context.getRow();
                if (row != null) {
                    judge(guarded, row);
                }
            });
        }
    }

    /** Whether the work in flight may aim an operator-owned record: declared system work, or a system-tier caller. */
    public static boolean mayWriteTrustedTargets() {
        return TenantWrites.systemOrCaller(caller -> caller.hasPermission(HohenheimSources.ADMIN_SYSTEM));
    }

    /**
     * Refuse a delegate's target on an operator-owned record, then stamp the mark.
     *
     * @throws Violations {@code operator_trusted_target} on the first target a delegate may not move
     */
    private static void judge(@NonNull Guarded guarded, @NonNull Row row) {
        Row stored = StoredRows.of(Models.get(guarded.model()), row);
        boolean systemTier = mayWriteTrustedTargets();
        boolean carried = false;
        boolean changed = false;
        for (Target target : guarded.targets()) {
            // AIDEV-NOTE: actual stored-vs-new changes must be judged even without setter history (map rows and
            // in-place settings edits). Intent additionally lets the operator vouch for an unchanged submitted target.
            if (!row.has(target.column())) {
                continue;
            }
            boolean targetChanged = row.changes(target.column(), stored, target.value(), target.createBaseline());
            carried |= stored == null || row.isWritten(target.column()) || targetChanged;
            Object value = target.value().apply(row);
            if (!targetChanged) {
                continue;
            }
            changed = true;
            if (!systemTier && value != null && target.refusable().test(value) && operatorOwned(guarded, stored)) {
                throw Violations.ofField(target.field(), value,
                    HohenheimMicrocopy.VIOLATIONS.of("operator_trusted_target"));
            }
        }
        boolean storedMark = stored != null && Boolean.TRUE.equals(stored.get(guarded.mark()));
        row.set(guarded.mark(), markAfter(systemTier, carried, changed, storedMark));
    }

    /** The mark a write leaves: see the class note for who sets, clears and keeps it. */
    private static boolean markAfter(boolean systemTier, boolean carried, boolean changed, boolean storedMark) {
        if (!systemTier) {
            return !changed && storedMark;
        }
        ExecutionIdentity identity = ExecutionIdentity.current();
        boolean declaredSystem = identity != null && identity.kind() == ExecutionIdentity.Kind.SYSTEM;
        return (declaredSystem ? changed : carried) || storedMark;
    }

    /** A create uses its prospective owner; existing records and unreadable grants retain the stored ownership gate. */
    private static boolean operatorOwned(@NonNull Guarded guarded, @Nullable Row stored) {
        if (stored == null) {
            ExecutionIdentity identity = ExecutionIdentity.current();
            return identity == null || identity.kind() != ExecutionIdentity.Kind.CALLER
                || HohenheimAccess.creationOwnerSubjects(identity.callerContext()).isEmpty();
        }
        Object id = stored.get(guarded.id().getName());
        if (id == null) {
            return true;
        }
        Set<String> subjects = HohenheimAccess.manageSubjectsOf(guarded.modelId(), id);
        return subjects == null || subjects.isEmpty();
    }
}
