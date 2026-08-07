package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * THE substrate every product tier that OWNS instances shares: the GeneratedRows
 * attribution guard on {@link InstanceModel}, the refusal that keeps a
 * {@link InstanceKindHandler#generatedOnly() generated-only} kind unwritable outside its
 * owning tier's system scope, the scope entry itself, and the owned-row lookups.
 *
 * The canonical relation this implements (instance-tier-plan, Phase 7): the INSTANCE
 * TIER IS the runtime-resource contract, and a product record (a Site, a managed
 * Database) stays a product record that OWNS its runtime through
 * {@code instances.generated_for_model/_for_id}. Ownership itself stays grant-derived --
 * the attribution is a structural parent link, never an owner column.
 *
 * AIDEV-NOTE: this class exists because the second tier to lower (managed databases)
 * would otherwise have copied SiteInstances' install/scope/lookup trio verbatim, which
 * is the four-copies-of-the-discipline outcome the plan rejected a narrower abstraction
 * to avoid. The tier-SPECIFIC parts (what a release is, what converge means) stay in
 * SiteInstances and DatabaseInstances; only the attribution mechanics live here.
 */
public final class OwnedInstances {

    private static volatile boolean installed;

    private OwnedInstances() {
    }

    /**
     * Install the ownership funnel on the instance write pipeline (MODULES stage): the
     * GeneratedRows attribution guard plus the generated-only kind refusal. Idempotent,
     * because more than one owning tier calls it.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        GeneratedRows.installGuards(InstanceModel.SCHEMA, InstanceModel.class,
            new GeneratedRows.Columns(InstanceModel.GENERATED_BY,
                InstanceModel.GENERATED_FOR_MODEL, InstanceModel.GENERATED_FOR_ID,
                InstanceModel.GENERATED_AT),
            "instance_generated_readonly", "instance_generated_attribution");
        InstanceModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || GeneratedRows.inSystemScope()) {
                return;
            }
            String kind = row.get(InstanceModel.KIND);
            InstanceKindHandler handler = kind == null ? null : InstanceKinds.getHandler(kind);
            if (handler != null && handler.generatedOnly()) {
                throw Violations.ofField(InstanceModel.KIND.getName(), kind,
                    Microcopy.of("instance_kind_owner_managed")
                        .withFilter("scope", "violations")
                        .withArg("kind", handler.getDisplayName()));
            }
        });
    }

    /** A scope body that may fail the way the write it wraps fails. */
    @FunctionalInterface
    public interface ScopedWork<T> {
        T run() throws Exception;
    }

    /**
     * Run {@code work} inside the owning record's GeneratedRows attribution scope, so
     * every instance row written inside is stamped {@code (source, model, id)} and the
     * instance-tier tenant gates judge it as the system consequence it is.
     */
    public static <T> T inScope(@NonNull String source, @NonNull Identifier model, int recordId,
                                @NonNull ScopedWork<T> work) throws Exception {
        Object[] result = new Object[1];
        GeneratedRows.as(new GeneratedRows.Attribution(source, model.toString(), recordId),
            () -> result[0] = work.run());
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    /** {@link #inScope} for callers with no checked exceptions to surface. */
    public static void inScopeUnchecked(@NonNull String source, @NonNull Identifier model,
                                        int recordId, @NonNull Runnable work) {
        try {
            inScope(source, model, recordId, () -> {
                work.run();
                return null;
            });
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every live instance owned by one product record, newest first. */
    public static @NonNull List<Row> ownedBy(@NonNull Identifier model, int recordId) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(model.toString()))
            .where(InstanceModel.GENERATED_FOR_ID.eq(recordId))
            .where(InstanceModel.DELETED_AT.isNull())
            .orderBy(InstanceModel.ID, SortOrder.DESC)
            .all();
    }

    /** The single live instance owned by one product record (newest wins), or null. */
    public static @Nullable Row soleOwnedBy(@NonNull Identifier model, int recordId) {
        List<Row> owned = ownedBy(model, recordId);
        return owned.isEmpty() ? null : owned.get(0);
    }
}
