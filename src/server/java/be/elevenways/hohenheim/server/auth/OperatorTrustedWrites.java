package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Where an OPERATOR-OWNED record connects is written under the non-delegable {@code hohenheim.admin.system} alone,
 * enforced on the model write pipelines like {@link TenantWrites}.
 *
 * AIDEV-NOTE: an operator-owned site (TenantUpstreams) and git provider (SourceOwnership.providerGuard) are dialled
 * with OutboundUrlGuard.ANY_ADDRESS, so their target is a trusted, any-address fetch. A delegated admin.access holder
 * passes TenantWrites as the operator, so without this gate they could point that fetch at this host or the LAN
 * (decided 2026-10-02). Every other column of those records stays theirs. Declared system work (seeds, tasks) passes;
 * work with no identity is refused, like TenantWrites. A record nobody holds manage on (a create included) is
 * operator-owned, and an unreadable grant set counts as operator-owned: the gate fails closed.
 *
 * @author Jelle De Loecker
 * @since 0.9.0
 */
public final class OperatorTrustedWrites {

    /** One record type whose target columns only the system tier writes while the record is operator-owned. */
    private record Guarded(@NonNull Class<? extends Model> model, @NonNull Identifier modelId,
                           @NonNull Field<?, ?> id, @NonNull List<Field<?, ?>> targets) {
    }

    /**
     * The URL-bearing columns: a site's upstream (its kind, the per-kind settings holding every dial target, and the
     * trust flag that lifts a tenant site to any-address reach) and a provider's base URL.
     */
    private static final List<Guarded> GUARDED = List.of(
        new Guarded(SiteModel.class, SiteModel.MODEL_ID, SiteModel.ID,
            List.of(SiteModel.UPSTREAM_KIND, SiteModel.SETTINGS, SiteModel.TRUSTED_UPSTREAM)),
        new Guarded(GitProviderModel.class, GitProviderModel.MODEL_ID, GitProviderModel.ID,
            List.of(GitProviderModel.BASE_URL)));

    private static volatile boolean installed;

    private OperatorTrustedWrites() {
    }

    /** Install the gate on every guarded model; idempotent, called at the MODULES boot stage. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        for (Guarded guarded : GUARDED) {
            Models.get(guarded.model()).getSchema().addBeforeValidateHook(context -> {
                Row row = context.getRow();
                if (row != null && !mayWriteTrustedTargets()) {
                    refuseTargetChanges(guarded, row);
                }
            });
        }
    }

    /** Whether the work in flight may aim an operator-owned record: declared system work, or a system-tier caller. */
    public static boolean mayWriteTrustedTargets() {
        ExecutionIdentity identity = ExecutionIdentity.current();
        if (identity == null) {
            return false;
        }
        return switch (identity.kind()) {
            case SYSTEM -> true;
            case CALLER -> Objects.requireNonNull(identity.callerContext()).hasPermission(HohenheimSources.ADMIN_SYSTEM);
        };
    }

    /** @throws Violations {@code operator_trusted_target} on the first target column that moves */
    private static void refuseTargetChanges(@NonNull Guarded guarded, @NonNull Row row) {
        Model model = Models.get(guarded.model());
        Row stored = StoredRows.of(model, row);
        for (Field<?, ?> target : guarded.targets()) {
            String name = target.getName();
            if (!row.has(name)) {
                continue;
            }
            Object baseline = stored != null ? stored.get(name) : target.getDefaultValue();
            if (Objects.equals(row.get(name), baseline)) {
                continue;
            }
            if (operatorOwned(guarded, stored)) {
                throw Violations.ofField(name, row.get(name), CmsSupport.violationText("operator_trusted_target"));
            }
            return;
        }
    }

    /** Whether nobody holds manage on the record; a create, and an unreadable grant set, count as operator-owned. */
    private static boolean operatorOwned(@NonNull Guarded guarded, @Nullable Row stored) {
        Object id = stored != null ? stored.get(guarded.id().getName()) : null;
        if (id == null) {
            return true;
        }
        Set<String> subjects = HohenheimAccess.manageSubjectsOf(guarded.modelId(), id);
        return subjects == null || subjects.isEmpty();
    }
}
