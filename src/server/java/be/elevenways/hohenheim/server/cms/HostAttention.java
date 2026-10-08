package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.OnboardingStage;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.host.HostStanding;
import be.elevenways.hohenheim.host.PreflightCheckView;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.DockerHealth;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.server.cms.AttentionItems.action;
import static be.elevenways.hohenheim.server.cms.AttentionItems.byHost;
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
            CmsRoutes.list(ADMIN, SettingsPage.DEFAULT_SLUG),
            action("act_open_settings"));
    }

    /**
     * Every host that takes no new apps by its verdict ({@link HostVerdict}), and every host that holds apps back.
     *
     * AIDEV-NOTE: ONE item per host, from the verdict the Hosts list and the host page word, titled by its standing
     * ({@link HostStanding#attentionTitle}: "cannot run apps yet" before admission, "takes no new apps" after): a
     * waiting host says why it waits ("Never checked yet", its failed required checks) with Check and admit; an
     * ADMITTED host the gate refuses (a stale memory reading, a posture, a check that no longer passes) says the gate's
     * own words with the remedy that clears them (Check again re-measures; a posture or a trust decision opens the
     * host). DEP9 found Starfleet's local host refused by placement over a memory reading from 2026-08-29 while
     * nothing raised it. A cordoned host is a deliberate state and raises nothing until an app waits on it (D8), and
     * an item is the ROOT of what its host holds back: it names how many apps wait for it and the dashboard folds
     * their own items under it. It states the checklist's admission stage, so while that step is open the step
     * presents it. Gated on the same roles that put the Hosts list in the panel, so the link always exists.
     */
    public static void hostsTakingNoApps(List<AttentionItem> items) {
        hostsTakingNoApps(items, AppHealth.heldBackByHost());
    }

    /** @param heldBack what each host holds back ({@link AppHealth#heldBackByHost}), read once for the tier */
    static void hostsTakingNoApps(List<AttentionItem> items, Map<Integer, AppHealth.HeldBack> heldBack) {
        for (Row server : Models.get(ServerModel.class).find().all()) {
            int id = server.get(ServerModel.ID);
            HostVerdict verdict = HostVerdict.of(server);
            AppHealth.HeldBack held = heldBack.get(id);
            boolean raised = verdict.standing().raisesAttention();
            if (!raised && held == null) {
                continue;
            }
            Object name = server.get(ServerModel.NAME);
            items.add(item(AttentionSeverity.WARNING, "server", verdict.standing().attentionTitle(name),
                raised ? verdict.reason() : held.reason(),
                CmsRoutes.open(ADMIN, ServerParts.SLUG, id),
                verdict.remedyAction(name))
                .about(AttentionSubject.host(id), heldBackText(held))
                .forStage(OnboardingStage.ADMISSION));
        }
    }

    /** @return "2 apps wait for it", null when the host holds nothing back */
    private static @Nullable Microcopy heldBackText(AppHealth.@Nullable HeldBack held) {
        return held == null ? null : copy("held_back", "attention_detail", "count", held.apps());
    }

    /** @return the host's required preflight checks that did not pass, in words, in the stored report's order */
    static @NonNull List<Microcopy> failedRequiredChecks(@NonNull Row server) {
        List<Microcopy> failed = new ArrayList<>();
        for (PreflightCheckView check : ServerOverviewState.preflightReport(server).mustPass()) {
            if (check.notPassing()) {
                failed.add(check.label());
            }
        }
        return failed;
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
        List<Row> stuck = Models.get(PortAllocationModel.class).find()
            .where(PortAllocationModel.STATUS.eq(PortAllocationModel.STATUS_RELEASING))
            .all().stream()
            .filter(claim -> {
                Instant parkedAt = claim.get(PortAllocationModel.UPDATED_AT);
                return parkedAt != null && !parkedAt.isAfter(threshold);
            })
            .toList();
        byHost(stuck, claim -> serverNameOf(claim.get(PortAllocationModel.SERVER_ID)),
            claim -> claim.get(PortAllocationModel.PORT) + "/" + claim.get(PortAllocationModel.PROTOCOL)).forEach((server, ports) -> items.add(item(AttentionSeverity.WARNING, "ethernet",
            copy("ports_releasing", "attention_title", "server", server),
            copy("ports_releasing", "attention_detail",
                "count", ports.size(),
                "hours", RELEASING_STUCK_AFTER.toHours(),
                "ports", String.join(", ", ports)))));
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
