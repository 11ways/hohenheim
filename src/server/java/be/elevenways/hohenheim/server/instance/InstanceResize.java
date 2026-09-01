package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * THE resource-ceiling resize for an instance: saving a new {@code memory_limit_mb} or
 * {@code cpu_limit} on a LIVE workload recreates its container, because a cgroup ceiling
 * is stamped at create and there is no update path for it.
 *
 * AIDEV-NOTE: this exists because the two tiers disagreed, and only one of them was
 * right. Saving a Memory limit on {@code /admin/databases} reprovisions
 * ({@code DatabaseResource.updateRow} -> {@code provisionInBackground} -> recreate), while
 * saving the same number on an INSTANCE persisted the setting and MOVED THE BOOKING --
 * both ledgers, host and owner, through the write hooks -- while the daemon kept applying
 * the old {@code HostConfig.Memory} until somebody happened to press Restart. That breaks
 * the invariant the whole capacity design rests on ({@code ResourceLimits}: "a workload
 * physically cannot exceed its own booking"): for the length of that gap the charge was
 * the new number and the cap was the old one. Proven on production robbedoes 2026-09-01,
 * where the activity log for such a save reads as a lone {@code update hohenheim:instance}.
 *
 * The rule, and it is the DATABASE tier's rule verbatim: an unchanged ceiling is a no-op
 * (a recreate drops every live connection, so "the operator pressed Save" must never mean
 * "bounce the workload"), and a STOPPED workload only persists -- it has no container
 * carrying a stale ceiling, and the next start reads the stored settings anyway.
 *
 * Localization: nothing here is user-facing text; the consequence is NAMED on the form by
 * the resource's {@code formNotice}.
 */
public final class InstanceResize {

    /**
     * The stored statuses that mean a container exists and is carrying the OLD ceiling.
     *
     * AIDEV-NOTE: {@code error} is deliberately absent. A record in error may or may not
     * have a container, its status says nothing about which, and an automatic deploy of a
     * workload an operator is already looking at a failure on is a surprise rather than a
     * fix -- the Deploy row action is right there.
     */
    private static final Set<String> LIVE_STATUSES =
        Set.of(InstanceModel.STATUS_RUNNING, InstanceModel.STATUS_STARTING);

    /**
     * THE recreate lane, as a seam so a hermetic test can observe the decision without a
     * daemon; production always holds {@link #deployNow}.
     */
    private static volatile IntConsumer recreater = InstanceResize::deployNow;

    private InstanceResize() {
    }

    /**
     * Whether the new ceilings can only reach the daemon through a recreate.
     *
     * @param before       the settings map the stored row carried
     * @param after        the settings map the write is landing
     * @param storedStatus the status of the STORED row -- what the daemon is running now
     */
    public static boolean requiresRecreate(@Nullable Map<String, Object> before,
                                           @Nullable Map<String, Object> after,
                                           @Nullable String storedStatus) {
        if (!LIVE_STATUSES.contains(storedStatus)) {
            return false;
        }
        ResourceLimits old = ResourceLimits.fromSettings(before == null ? Map.of() : before);
        ResourceLimits fresh = ResourceLimits.fromSettings(after == null ? Map.of() : after);
        return !Objects.equals(old.memoryMb(), fresh.memoryMb())
            || !Objects.equals(old.cpus(), fresh.cpus());
    }

    /**
     * Recreate the workload once the caller's write has COMMITTED, when the ceilings moved
     * on a live one.
     *
     * AIDEV-NOTE: afterCommit, for the reason DatabaseResource.updateRow records: the
     * deploy reads the row on its own connection, so one scheduled from inside the CMS
     * mutation transaction would apply the OLD ceiling it was fired to replace. The deploy
     * itself then rides a virtual thread because it is live daemon work (pull, create,
     * start, readiness) and the operator's form submit must not hold on it -- a failure
     * stamps the record {@code error} with its named refusal, which is what the list
     * badge, the detail page and AttentionCollector already read.
     */
    public static void recreateAfterCommit(int instanceId,
                                           @Nullable Map<String, Object> before,
                                           @Nullable Map<String, Object> after,
                                           @Nullable String storedStatus) {
        if (!requiresRecreate(before, after, storedStatus)) {
            return;
        }
        Models.get(InstanceModel.class).getResolvedDatasource()
            .afterCommit(() -> recreater.accept(instanceId));
    }

    /**
     * A SNAPSHOT of a row's settings; a SchemaField answers Object.
     *
     * AIDEV-NOTE: a copy on purpose. The caller reads this BEFORE applying the submitted
     * values and compares it AFTER, and a live view of the row's own map would answer the
     * new value to both reads -- a resize that then silently never recreated anything.
     */
    @SuppressWarnings("unchecked")
    public static @NonNull Map<String, Object> settingsOf(@Nullable Row row) {
        return row != null && row.get(InstanceModel.SETTINGS) instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }

    private static void deployNow(int instanceId) {
        JobRunner.startVirtualThread(() -> {
            try {
                new InstanceService().deploy(instanceId, DeployTrigger.SYSTEM);
            } catch (RuntimeException refused) {
                // The service already stamped the record and named the refusal; this line
                // ties the failure to the RESIZE that asked for it.
                Blast.log("INSTANCE: recreate after a resource-limit change of instance",
                    instanceId, "failed -", refused.getMessage());
            }
        });
    }

    /** Replace the recreate lane; tests only, and they must restore it. */
    public static void setRecreaterForTesting(@NonNull IntConsumer replacement) {
        recreater = replacement;
    }

    /** Restore the production recreate lane. */
    public static void resetRecreaterForTesting() {
        recreater = InstanceResize::deployNow;
    }
}
