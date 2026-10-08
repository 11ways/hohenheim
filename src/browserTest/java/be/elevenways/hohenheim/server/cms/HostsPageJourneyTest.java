package be.elevenways.hohenheim.server.cms;

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
            assertThat(say(held.detail())).as("step 2: naming only the REQUIRED checks that failed, in words")
                .isEqualTo("1 required check failed: Firewall control");

            // 3. The same failure leads the list and the dashboard, as one attention item with its fix.
            AttentionItem item = null;
            for (AttentionItem candidate : AttentionCollector.hosts()) {
                if (say(candidate.title()).equals(PREFIX + "waiting cannot run apps yet")) {
                    item = candidate;
                }
            }
            assertThat(item).as("step 3: the host tier names the waiting host").isNotNull();
            assertThat(say(item.detail())).as("step 3: with the checks that keep it out")
                .isEqualTo("1 required check failed: Firewall control. Check and admit runs it again.");
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
            HttpResponse<String> list = adminGet("/admin/" + ServerParts.SLUG);
            assertThat(list.statusCode()).as("step 5: the Hosts list renders").isEqualTo(200);
            assertThat(list.body()).as("step 5: the admission in words")
                .contains("Takes new apps", "Takes no new apps", "Waiting for its checks")
                .as("step 5: why the waiting host waits").contains("1 required check failed: Firewall control")
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
            String waitingPage = adminGet("/admin/" + ServerParts.SLUG + "/" + waiting + "/page/overview").body();
            assertThat(waitingPage).as("step 8: the host page draws the finding")
                .contains("nftables refused a firewall change")
                .as("step 8: and the advice's").contains("The probe instance never answered")
                .as("step 8: and the report's verdict apart from its time").contains("data-preflight-verdict");
            String admittedPage = adminGet("/admin/" + ServerParts.SLUG + "/" + admitted + "/page/overview").body();
            assertThat(admittedPage).as("step 8: the measured memory as a size").contains("16.0 GB")
                .as("step 8: never its raw bytes").doesNotContain(String.valueOf(16L * 1024 * 1024 * 1024));
        } finally {
            for (Runnable step : cleanup) {
                step.run();
            }
        }
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
