package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.host.HostMemoryCell;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.host.HostFactView;
import be.elevenways.hohenheim.host.HostPreflightReportView;
import be.elevenways.hohenheim.host.PreflightCheckView;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.server.host.PreflightFinding;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Hosts list reads as board Hosts: what each machine takes in the admission's own words, why a waiting one waits
 * (its failed required checks, also named by the list's attention band and the dashboard), the memory it has booked
 * and how many apps and databases it runs.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class HostsPageJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "w9a-hosts-";
    private static final LocaleChain EN = LocaleChain.ofTags("en");

    @Test
    void theHostsListSaysWhatEachHostTakesAndWhy() throws Exception {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            // 1. Three hosts: one taking new apps, one taking none, and one waiting with a failed required check and
            //    no memory reading (stored facts merge, so the fixture's reading is taken away explicitly).
            int admitted = HostFixtures.admittedIncusHost(PREFIX + "admitted");
            cleanup.add(() -> deleteServer(admitted));
            int cordoned = HostFixtures.admittedIncusHost(PREFIX + "cordoned");
            cleanup.add(() -> deleteServer(cordoned));
            setAdmission(cordoned, ServerModel.ADMISSION_CORDONED);
            int waiting = HostFixtures.admittedIncusHost(PREFIX + "waiting");
            cleanup.add(() -> deleteServer(waiting));
            HostPreflight.store(PREFIX + "waiting", new HostPreflight.Report(List.of(
                new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "fake daemon"),
                HostPreflight.Check.of("nftables", HostPreflight.STATUS_FAIL, true,
                    PreflightFinding.NFT_REFUSED.with("error", "nft: command not found")),
                HostPreflight.Check.of("lsm", HostPreflight.STATUS_FAIL, false, PreflightFinding.PROBE_UNANSWERED.with())),
                Map.of(), false, Now.instant(), null));
            setAdmission(waiting, ServerModel.ADMISSION_BLOCKED);
            forgetMemoryReading(waiting);

            // 2. The state cell answers in the admission's words and says what it means.
            StateLineCell taking = ServerParts.stateCellOf(server(admitted));
            assertThat(say(taking.label())).as("step 2: an admitted host takes new apps").isEqualTo("Takes new apps");
            assertThat(taking.variant()).as("step 2: and reads as fine").isEqualTo(BadgeVariant.SUCCESS);
            StateLineCell drained = ServerParts.stateCellOf(server(cordoned));
            assertThat(say(drained.label())).as("step 2: a cordoned host takes none").isEqualTo("Takes no new apps");
            assertThat(say(drained.detail())).as("step 2: and keeps what it runs")
                .isEqualTo("Apps already here keep running");
            StateLineCell held = ServerParts.stateCellOf(server(waiting));
            assertThat(say(held.label())).as("step 2: a blocked host waits").isEqualTo("Waiting for its checks");
            assertThat(held.detail()).as("step 2: why it waits is the band's item, never repeated in its row")
                .isNull();

            // 3. The same failure leads the list and the dashboard, as one attention item with its fix.
            AttentionItem item = null;
            for (AttentionItem candidate : AttentionCollector.hosts()) {
                if (say(candidate.title()).equals(PREFIX + "waiting cannot run apps yet")) {
                    item = candidate;
                }
            }
            assertThat(item).as("step 3: the host tier names the waiting host").isNotNull();
            assertThat(say(item.detail())).as("step 3: naming only the REQUIRED checks that failed, in words")
                .isEqualTo("1 required check failed: Firewall control");
            assertThat(say(item.action())).as("step 3: and Check and admit").isEqualTo("Check and admit");
            assertThat(AttentionCollector.collect()).as("step 3: the dashboard reads the same item")
                .anySatisfy(found -> assertThat(say(found.title())).isEqualTo(PREFIX + "waiting cannot run apps yet"));

            // 4. Memory reads the capacity ledger: a host without a reading has no bar, only the words.
            HostMemoryCell measured = ServerParts.memoryCellOf(server(admitted));
            assertThat(measured.measured()).as("step 4: the fixture's 16 GiB reading is a budget").isTrue();
            assertThat(say(measured.text())).as("step 4: booked against budget, in words").contains(" of ")
                .endsWith(" booked");
            HostMemoryCell blind = ServerParts.memoryCellOf(server(waiting));
            assertThat(blind.measured()).as("step 4: no memory fact, no bar").isFalse();
            assertThat(say(blind.text())).as("step 4: and says so").isEqualTo("Not measured yet");

            // 5. The rendered list: words, not tokens, and the band above it.
            Row app = Models.get(InstanceModel.class).createEmptyRow();
            Map.of(InstanceModel.NAME.getName(), (Object) (PREFIX + "app"),
                InstanceModel.KIND.getName(), "hohenheim:system_container",
                InstanceModel.SETTINGS.getName(), new LinkedHashMap<>(Map.of("image", "images:debian/12")),
                InstanceModel.STATUS.getName(), InstanceModel.STATUS_STOPPED,
                InstanceModel.SERVER_ID.getName(), admitted).forEach(app::set);
            Models.get(InstanceModel.class).save(app);
            cleanup.add(0, () -> HardDeletes.row(Models.get(InstanceModel.class), app));
            HttpResponse<String> list = adminGet("/admin/" + HohenheimSlugs.SERVERS);
            assertThat(list.statusCode()).as("step 5: the Hosts list renders").isEqualTo(200);
            assertThat(list.body()).as("step 5: the admission in words")
                .contains("Takes new apps", "Takes no new apps", "Waiting for its checks")
                .as("step 5: why the waiting host waits").contains("1 required check failed: Firewall control")
                .as("step 5: the board's verb").contains("Add host").doesNotContain("New host")
                .as("step 5: the board's lead").contains("The machines your apps run on.")
                .as("step 5: never a check's code").doesNotContain("failed: nftables")
                .as("step 5: what each host runs").contains("1 app, 0 databases").contains("Nothing yet")
                .as("step 5: the attention band above the list").contains(PREFIX + "waiting cannot run apps yet")
                .as("step 5: the memory bar").contains("hh-host-memory");

            // 6. A host the last probe could not reach says why in words, and when it last answered; the host page
            //    names its checks and their verdicts in words too.
            Row unreachable = server(cordoned);
            unreachable.set(ServerModel.LAST_ERROR_KIND, HostProbe.FailureKind.DOCKER_ABSENT.token);
            unreachable.set(ServerModel.LAST_SEEN_AT, Now.instant());
            Models.get(ServerModel.class).save(unreachable);
            assertThat(say(ServerParts.statusCellOf(server(cordoned)).stateText()))
                .as("step 6: the probe failure in words, with its last contact").isEqualTo("Docker not found, last reached");
            unreachable = server(cordoned);
            unreachable.set(ServerModel.LAST_SEEN_AT, null);
            Models.get(ServerModel.class).save(unreachable);
            assertThat(say(ServerParts.statusCellOf(server(cordoned)).stateText()))
                .as("step 6: a host never reached names no last contact").isEqualTo("Docker not found");
            assertThat(say(HostProbe.FailureKind.labelOf("a_later_kind")))
                .as("step 6: a failure this build does not know keeps its stored spelling").isEqualTo("a_later_kind");
            PreflightCheckView failing = ServerOverviewState.preflightReport(server(waiting)).mustPass().get(0);
            assertThat(say(failing.label())).as("step 6: the host page names the check").isEqualTo("Firewall control");
            assertThat(say(failing.statusLabel())).as("step 6: and its verdict").isEqualTo("Failed");

            // 7. What each check found reads as the finding's words with its evidence, never the probe's token, in
            //    every shipped language; an advisory check that did not pass reads as advice, never as a failure.
            assertThat(say(failing.detail())).as("step 7: the finding in words, with the probe's own message")
                .isEqualTo("nftables refused a firewall change: nft: command not found");
            assertThat(failing.detail().resolve(LocaleChain.ofTags("nl"), Zenit.getMessageResolver()))
                .as("step 7: and in Dutch").isEqualTo("nftables weigerde een firewallwijziging: nft: command not found");
            PreflightCheckView advice = ServerOverviewState.preflightReport(server(waiting)).advice().get(0);
            assertThat(advice.status()).as("step 7: the advisory check stored a failure").isEqualTo("fail");
            assertThat(say(advice.statusLabel())).as("step 7: yet it reads as advice").isEqualTo("Advice");
            assertThat(advice.statusVariant()).as("step 7: in the warning tone").isEqualTo(BadgeVariant.WARNING);
            assertThat(say(advice.detail())).as("step 7: saying what it found")
                .isEqualTo("The probe instance never answered, so this is unknown");
            HostPreflightReportView report = ServerOverviewState.preflightReport(server(waiting));
            assertThat(say(report.summaryLabel())).as("step 7: the report's verdict is a capitalised word from copy")
                .isEqualTo("Failed");
            assertThat(report.summaryVariant()).isEqualTo(BadgeVariant.DESTRUCTIVE);

            // 8. A measured fact reads by its name in words and its value with its unit, through the byte formatter
            //    the memory bars use; the rendered page says the same.
            HostFactView memory = ServerOverviewState.preflightReport(server(admitted)).facts().stream()
                .filter(fact -> fact.name().equals(HostPreflight.MEM_TOTAL_FACT)).findFirst().orElseThrow();
            assertThat(say(memory.label())).as("step 8: the memory fact is named in words").isEqualTo("Memory");
            assertThat(memory.value()).as("step 8: as a size, never a byte count").isEqualTo("16.0 GB");
            String waitingPage = adminGet("/admin/" + HohenheimSlugs.SERVERS + "/" + waiting + "/page/overview").body();
            assertThat(waitingPage).as("step 8: the host page draws the finding")
                .contains("nftables refused a firewall change")
                .as("step 8: and the advice's").contains("The probe instance never answered")
                .as("step 8: and the report's verdict apart from its time").contains("data-preflight-verdict");
            String admittedPage = adminGet("/admin/" + HohenheimSlugs.SERVERS + "/" + admitted + "/page/overview")
                .body();
            assertThat(admittedPage).as("step 8: the measured memory as a size").contains("16.0 GB")
                .as("step 8: never its raw bytes").doesNotContain(String.valueOf(16L * 1024 * 1024 * 1024));
        } finally {
            for (Runnable step : cleanup) {
                step.run();
            }
        }
    }

    @Test
    void aHostThePlacementGateRefusesSaysSoEverywhere() throws Exception {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            // 1. An admitted host whose memory reading is older than the freshness bound (Starfleet's local host, last
            //    measured 2026-08-29): the chooser never picks it, so it does not take new apps.
            int stale = HostFixtures.admittedIncusHost(PREFIX + "stale");
            cleanup.add(() -> deleteServer(stale));
            HostPreflight.store(PREFIX + "stale", new HostPreflight.Report(List.of(),
                Map.of(HostPreflight.MEM_TOTAL_FACT, 16L * 1024 * 1024 * 1024), true,
                Now.instant().minus(Duration.ofDays(40)), null));
            Microcopy refusal = InstancePlacement.hostRefusal(server(stale));
            assertThat(refusal).as("step 1: the placement gate refuses it").isNotNull();
            assertThat(refusal.key()).as("step 1: for its stale memory reading").isEqualTo("host_capacity_unproven");

            // 2. The Hosts list's state, the attention item and the host page say the gate's verdict, once each, with
            //    the action that clears it (a fresh check re-measures) and a link to the host's Overview.
            StateLineCell cell = ServerParts.stateCellOf(server(stale));
            assertThat(say(cell.label())).as("step 2: it never reads as taking new apps")
                .isEqualTo("Cannot take new apps");
            assertThat(cell.variant()).as("step 2: in the warning tone").isEqualTo(BadgeVariant.WARNING);
            assertThat(cell.detail()).as("step 2: why is the band's item, never repeated word for word in the row")
                .isNull();
            assertThat(say(refusal)).as("step 2: the gate's words name no button and join no clauses with ';'")
                .isEqualTo("The memory of " + PREFIX + "stale was measured too long ago to place a new app there");
            AttentionItem item = hostItem(PREFIX + "stale");
            assertThat(item).as("step 2: the host tier raises it").isNotNull();
            assertThat(say(item.title())).as("step 2: titled as an admitted host that takes nothing new, never as one"
                + " that cannot run apps yet").isEqualTo(PREFIX + "stale takes no new apps");
            assertThat(item.title().resolve(LocaleChain.ofTags("nl"), Zenit.getMessageResolver()))
                .as("step 2: and in Dutch").isEqualTo(PREFIX + "stale neemt geen nieuwe apps aan");
            assertThat(say(item.detail())).as("step 2: in the same words").isEqualTo(say(refusal));
            assertThat(say(item.action())).as("step 2: offering a fresh check").isEqualTo("Check again");
            assertThat(item.target().toUrl()).as("step 2: on the host's Overview, never its Configuration form")
                .isEqualTo("/admin/" + HohenheimSlugs.SERVERS + "/" + stale + "/open");
            String stalePage = adminGet("/admin/" + HohenheimSlugs.SERVERS + "/" + stale + "/page/overview").body();
            assertThat(stalePage).as("step 2: the host page wears the same state").contains("Cannot take new apps")
                .doesNotContain("Takes new apps");
            assertThat(stalePage).as("step 2: its capacity says when memory was last measured")
                .contains("Memory last measured ").contains("too long ago to place new apps here")
                .as("step 2: and lists no empty facts after it").doesNotContain("Nothing to show")
                .as("step 2: and names no button in prose").doesNotContain("Check again measures");

            // 3. The cause on Starfleet (DEP10): only a full preflight wrote the memory reading, so a host the hourly
            //    sweep reached every hour still went stale. The heartbeat now records the daemon's memory total: the
            //    reading is fresh again, while the checks, their time and the preflight verdict stay as stored.
            Instant preflightAt = server(stale).get(ServerModel.PROBED_AT);
            HostProbe.recordSuccess(PREFIX + "stale", Map.of("MemTotal", 16L * 1024 * 1024 * 1024));
            assertThat(HostPreflight.factMeasuredAt(server(stale), HostPreflight.MEM_TOTAL_FACT))
                .as("step 3: the heartbeat measured the memory now")
                .isAfter(Now.instant().minus(Duration.ofMinutes(1)));
            assertThat((Instant) server(stale).get(ServerModel.PROBED_AT))
                .as("step 3: the preflight's own time is untouched").isEqualTo(preflightAt);
            assertThat(InstancePlacement.hostRefusal(server(stale))).as("step 3: the gate places on it again").isNull();
            assertThat(say(ServerParts.stateCellOf(server(stale)).label())).as("step 3: it takes new apps")
                .isEqualTo("Takes new apps");
            assertThat(hostItem(PREFIX + "stale")).as("step 3: and raises nothing").isNull();
            // A quarantined host's answer proves nothing about which machine gave it, so it measures nothing.
            Row quarantined = server(stale);
            quarantined.set(ServerModel.QUARANTINED_AT, Now.instant());
            Models.get(ServerModel.class).save(quarantined);
            Instant measured = HostPreflight.factMeasuredAt(server(stale), HostPreflight.MEM_TOTAL_FACT);
            HostProbe.recordSuccess(PREFIX + "stale", Map.of("MemTotal", 8L * 1024 * 1024 * 1024));
            assertThat(HostPreflight.factMeasuredAt(server(stale), HostPreflight.MEM_TOTAL_FACT))
                .as("step 3: a quarantined host's heartbeat measures nothing").isEqualTo(measured);
            Row released = server(stale);
            released.set(ServerModel.QUARANTINED_AT, null);
            Models.get(ServerModel.class).save(released);

            // 4. A required check that no longer passes refuses it in words: the check's name and what it found, never
            //    the check's token or the old "FAILED" English.
            HostPreflight.store(PREFIX + "stale", new HostPreflight.Report(List.of(
                HostPreflight.Check.of("nftables", HostPreflight.STATUS_FAIL, true,
                    PreflightFinding.NFT_REFUSED.with("error", "sudo: a password is required"))),
                Map.of(), false, Now.instant(), null));
            Microcopy failing = InstancePlacement.hostRefusal(server(stale));
            assertThat(failing).as("step 4: the gate refuses the failing check").isNotNull();
            assertThat(failing.key()).as("step 4: as a check that must pass now")
                .isEqualTo("host_preflight_check_now_required");
            assertThat(say(failing)).as("step 4: naming the check in words")
                .contains("Firewall control")
                .as("step 4: with what it found").contains("nftables refused a firewall change: sudo: a password is"
                    + " required")
                .as("step 4: never the token or the old English").doesNotContain("nftables check")
                .doesNotContain("FAILED");
            assertThat(failing.resolve(LocaleChain.ofTags("nl"), Zenit.getMessageResolver()))
                .as("step 4: and in Dutch").contains("Beheer van de firewall");
            assertThat(say(hostItem(PREFIX + "stale").action())).as("step 4: fixed, then checked again")
                .isEqualTo("Check again");

            // 5. A host nobody ever checked says so, in the list and in its item, with Check and admit.
            Row fresh = Models.get(ServerModel.class).createEmptyRow();
            fresh.set(ServerModel.NAME, PREFIX + "fresh");
            fresh.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
            Models.get(ServerModel.class).save(fresh);
            int never = Models.get(ServerModel.class).findByName(PREFIX + "fresh").get(ServerModel.ID);
            cleanup.add(() -> deleteServer(never));
            StateLineCell waiting = ServerParts.stateCellOf(server(never));
            assertThat(say(waiting.label())).as("step 5: a new host waits").isEqualTo("Waiting for its checks");
            assertThat(waiting.detail()).as("step 5: why is its item's, never repeated in the row").isNull();
            AttentionItem neverItem = hostItem(PREFIX + "fresh");
            assertThat(say(neverItem.title())).as("step 5: a host never admitted cannot run apps yet")
                .isEqualTo(PREFIX + "fresh cannot run apps yet");
            assertThat(say(neverItem.detail())).as("step 5: its item says the same").isEqualTo("Never checked yet");
            assertThat(say(neverItem.action())).as("step 5: with Check and admit").isEqualTo("Check and admit");

            // 6. The host page reads its words from copy: who may run here without "(operator risk)", the accepted risk
            //    without its warning version, and when the daemon was last seen.
            HostPreflight.store(PREFIX + "stale", new HostPreflight.Report(List.of(
                new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "fake daemon")),
                Map.of(), true, Now.instant(), null));
            String page = adminGet("/admin/" + HohenheimSlugs.SERVERS + "/" + stale + "/page/overview").body();
            assertThat(page).as("step 6: the posture in the board's words").contains("Shared containers")
                .doesNotContain("operator risk");
            assertThat(page).as("step 6: the accepted risk names who accepted it").contains("Accepted by ")
                .doesNotContain("(warning v");
            assertThat(say(ServerParts.statusCellOf(server(stale)).stateText()))
                .as("step 6: the daemon line says the time after it is when it was seen").isEqualTo("Incus, seen");
        } finally {
            for (Runnable step : cleanup) {
                step.run();
            }
        }
    }

    /** @return the host tier's item titled for this host (waiting or refusing), null when none is */
    private static AttentionItem hostItem(String name) {
        for (AttentionItem candidate : AttentionCollector.hosts()) {
            String title = say(candidate.title());
            if (title.equals(name + " cannot run apps yet") || title.equals(name + " takes no new apps")) {
                return candidate;
            }
        }
        return null;
    }

    private static Row server(int id) {
        return Models.get(ServerModel.class).findById(id);
    }

    private static void setAdmission(int id, String admission) {
        Row row = server(id);
        row.set(ServerModel.ADMISSION, admission);
        Models.get(ServerModel.class).save(row);
    }

    private static void forgetMemoryReading(int id) {
        Row row = server(id);
        Map<String, Object> capabilities = new LinkedHashMap<>();
        if (row.get(ServerModel.CAPABILITIES) instanceof Map<?, ?> stored) {
            stored.forEach((key, value) -> capabilities.put(String.valueOf(key), value));
        }
        capabilities.remove(HostPreflight.MEM_TOTAL_FACT);
        row.set(ServerModel.CAPABILITIES, capabilities);
        Models.get(ServerModel.class).save(row);
    }

    private static void deleteServer(int id) {
        Row row = server(id);
        if (row != null) {
            HardDeletes.row(Models.get(ServerModel.class), row);
        }
    }

    private static String say(Microcopy copy) {
        return copy == null ? "" : copy.resolve(EN, Zenit.getMessageResolver());
    }
}
