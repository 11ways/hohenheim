package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.DockerHealth;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.server.cms.AttentionItems.copy;
import static be.elevenways.hohenheim.server.cms.AttentionItems.item;
import static be.elevenways.hohenheim.server.cms.AttentionItems.literal;

/**
 * The host tier's attention items: the local daemon, host admission and the port ledger.
 *
 * Reads the boot probe's recorded answer and stored rows only; a host is never dialled per render.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class HostAttention {

    private static final String ADMIN = HohenheimSlugs.ADMIN;

    /** How long a claim may sit in {@code releasing} before it is an alarm: two hourly
     *  reconciler sweeps should have observed and freed it by then. */
    static final Duration RELEASING_STUCK_AFTER = Duration.ofHours(2);

    private HostAttention() {
    }

    /**
     * A docker-requiring node whose probe found no daemon: a red item, not silence.
     *
     * @param health the probe to read, injectable so the decision is testable without a daemon
     * @return the item, or null when the daemon answered or no role needed it
     */
    public static @Nullable AttentionItem dockerUnreachable(@NonNull DockerHealth health) {
        if (health.status() != DockerHealth.Status.UNREACHABLE) {
            return null;
        }
        return item(AttentionSeverity.ERROR, "cubes",
            copy("docker_unreachable", "attention_title"),
            literal(health.problem()),
            CmsRoutes.list(ADMIN, SettingsPage.DEFAULT_SLUG));
    }

    /**
     * Enrolled hosts that are not admitted for placement.
     *
     * AIDEV-NOTE: this list used to say "All clear" directly beneath the onboarding card
     * naming a host the deploy lane would refuse -- the checklist watched admission and
     * this collector did not, so the dashboard contradicted itself on one screen. Gated
     * on the same roles that put the Servers list in the panel, so the link always exists.
     * CORDONED is deliberately absent: an operator drained that host on purpose, and a
     * permanent warning over a deliberate state is how a warning stops being read.
     */
    public static void hostsNotAdmitted(List<AttentionItem> items) {
        for (Row server : Models.get(ServerModel.class).find()
                .where(ServerModel.ADMISSION.eq(ServerModel.ADMISSION_BLOCKED))
                .all()) {
            items.add(item(AttentionSeverity.WARNING, "server",
                copy("host_not_admitted", "attention_title",
                    "name", server.get(ServerModel.NAME)),
                copy("host_not_admitted", "attention_detail"),
                CmsRoutes.detail(ADMIN, "servers", server.get(ServerModel.ID))));
        }
    }

    /**
     * Port claims stuck in {@code releasing} past the age threshold -- the ledger's
     * never-cleared alarm. A row lands there when a teardown could not verify itself
     * (or a host was removed); the reconciler deletes it once it OBSERVES the port
     * free, so one that lingers means the port is genuinely still bound by something
     * we no longer manage, or the host is unobservable. The flip time is updated_at:
     * releasing rows are never re-saved (the park is idempotent). The threshold is a
     * parameter only so a test can prove the projection without forging timestamps.
     */
    public static void stuckReleasingPorts(List<AttentionItem> items, Instant threshold) {
        Map<String, List<String>> stuckByServer = new LinkedHashMap<>();
        List<Row> releasing = Models.get(PortAllocationModel.class).find()
            .where(PortAllocationModel.STATUS.eq(PortAllocationModel.STATUS_RELEASING))
            .all();
        for (Row claim : releasing) {
            Instant parkedAt = claim.get(PortAllocationModel.UPDATED_AT);
            if (parkedAt == null || parkedAt.isAfter(threshold)) {
                continue;
            }
            stuckByServer.computeIfAbsent(serverNameOf(claim.get(PortAllocationModel.SERVER_ID)),
                    k -> new ArrayList<>())
                .add(claim.get(PortAllocationModel.PORT) + "/"
                    + claim.get(PortAllocationModel.PROTOCOL));
        }
        stuckByServer.forEach((server, ports) -> items.add(item(AttentionSeverity.WARNING, "ethernet",
            copy("ports_releasing", "attention_title", "server", server),
            copy("ports_releasing", "attention_detail",
                "count", ports.size(),
                "hours", RELEASING_STUCK_AFTER.toHours(),
                "ports", String.join(", ", ports)),
            null)));
    }

    // A releasing claim can outlive its servers row (host removal parks claims and
    // deletes nothing), so a dangling id must still render, not throw.
    private static String serverNameOf(@Nullable Integer serverId) {
        try {
            return ServerModel.nameOf(serverId);
        } catch (IllegalArgumentException gone) {
            return "removed host #" + serverId;
        }
    }
}
