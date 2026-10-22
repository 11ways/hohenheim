package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.query.criteria.BlankTextCriteria;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * The shared form shape and resize lane of a record that describes a provisioned container: a
 * dedicated managed database ({@link DatabaseParts#admin}) and a shared engine
 * ({@link DatabaseParts#engines}).
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ProvisionedRecords {

    /**
     * The columns one record type keeps its identity, its two ceilings and its outcome in.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    public record Columns(@NonNull Field<Integer, ?> id,
                   @NonNull Field<Integer, ?> memoryMb,
                   @NonNull Field<Double, ?> cpus,
                   @NonNull Field<String, ?> status,
                   @NonNull Field<String, ?> failureReason) {
    }

    /**
     * Books new ceilings against the host budget before anything is written.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    @FunctionalInterface
    public interface Reservation {

        /** @throws Violations quota, capacity, fence or attribution refusals, unwrapped */
        void reserve(@NonNull Row existing, @NonNull ResourceLimits limits) throws Exception;
    }

    private ProvisionedRecords() {
    }

    /**
     * The failure reason, shown ONLY on a record that carries one: the create form (null record)
     * and a healthy record never render an empty failure box. Presentation per record, so the
     * list still sorts and filters by it (the cross-record answer is declared).
     */
    static @NonNull ResourceFieldBinding failureReasonWhenSet(@NonNull Field<String, ?> failureReason) {
        return ResourceFieldBinding.of(failureReason.getName(),
            FieldAccess.customRecordAware((ctx, record) -> {
                String reason = record instanceof Row row ? row.get(failureReason) : null;
                return reason != null && !reason.isBlank()
                    ? FieldAccess.Decision.READONLY : FieldAccess.Decision.HIDDEN;
            }).acrossRecords(ctx -> FieldAccess.Decision.READONLY)
            .readableWhere(ctx -> Criteria.and(failureReason.isNotNull(),
                Criteria.not(BlankTextCriteria.of(failureReason)))));
    }

    /** The ceilings this write asks for, off the merged record: a ceiling it does not carry keeps its stored one. */
    static @NonNull ResourceLimits requested(OperationInput.@NonNull Values merged, @NonNull Columns columns) {
        Object memory = merged.get(columns.memoryMb().getName());
        Object cpus = merged.get(columns.cpus().getName());
        return ResourceLimits.of(memory instanceof Integer mb ? mb : null, cpus instanceof Double c ? c : null);
    }

    /**
     * Whether this write carries a non-null value for either ceiling.
     *
     * AIDEV-NOTE: deliberately the SUBMITTED entries, never merged(): a shared record refuses a ceiling it was SENT,
     * while its stored ceilings are no write at all.
     */
    public static boolean carriesCeiling(@NonNull Map<String, Object> coerced, @NonNull Columns columns) {
        return coerced.getOrDefault(columns.memoryMb().getName(), null) != null
            || coerced.getOrDefault(columns.cpus().getName(), null) != null;
    }

    /**
     * THE resize: re-book the ceilings inline, store them with a provisioning status and recreate
     * the container after commit.
     *
     * AIDEV-NOTE: the order is the one the reservation was split for. The reservation runs
     * INLINE, so a host without room refuses on the form the operator is looking at; the
     * container work rides {@code afterCommit}, because a deploy scheduled from inside the CMS
     * mutation transaction would read the row on its own connection before this one commits and
     * apply the OLD ceiling. An unchanged ceiling pair is a no-op on purpose: a deploy is a
     * RECREATE, so "operator pressed Save" must never drop every live connection for nothing.
     *
     * @param merged the stored record's values with the submission over them; a present null is a deliberate clear
     * @param redeploy recreates the container of the record with this id, in the background
     */
    public static void resize(@NonNull Model model, @NonNull Row existing, OperationInput.@NonNull Values merged,
                              @NonNull Columns columns, @NonNull Reservation reservation,
                              @NonNull IntConsumer redeploy) {
        ResourceLimits limits = requested(merged, columns);
        if (Objects.equals(limits.memoryMb(), existing.get(columns.memoryMb()))
                && Objects.equals(limits.cpus(), existing.get(columns.cpus()))) {
            return;
        }
        Integer recordId = existing.get(columns.id());
        if (recordId == null) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("database_resize_failed")
                .withArg("reason", "the record carries no id"));
        }
        try {
            reservation.reserve(existing, limits);
        } catch (Violations refused) {
            throw refused;
        } catch (Exception e) {
            throw resizeFailed(e);
        }
        existing.set(columns.memoryMb(), limits.memoryMb());
        existing.set(columns.cpus(), limits.cpus());
        // Provisioning again is the honest status: the container is being recreated, and it is
        // what the list badge, the detail page and AttentionCollector already read.
        existing.set(columns.status(), DatabaseModel.STATUS_PROVISIONING);
        existing.set(columns.failureReason(), null);
        model.save(existing);
        model.getResolvedDatasource().afterCommit(() -> redeploy.accept(recordId));
    }

    /** The resize refusal, carrying the failure's own text only where an operator reads it. */
    private static @NonNull Violations resizeFailed(@NonNull Exception failure) {
        String detail = WithheldFailure.operatorDetail(failure);
        return detail == null
            ? Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("database_resize_failed_tenant"))
            : Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("database_resize_failed").withArg("reason", detail));
    }
}
