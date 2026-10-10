package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.host.HostPreflightReportView;
import be.elevenways.hohenheim.host.PreflightCheckView;
import be.elevenways.hohenheim.host.PreflightStatus;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostFact;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.host.PreflightFinding;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A host waiting for admission has ONE verb, Check and admit: it runs every check, admits only when the required ones
 * pass, and otherwise says which must pass; the host page puts those first, each with how to fix it.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
class HostCheckAndAdmitJourneyTest extends HohenheimTestBase {

    private static final LocaleChain EN = LocaleChain.ofTags("en");
    private static final LocaleChain NL = LocaleChain.ofTags("nl");

    @Test
    void aWaitingHostIsCheckedAndAdmittedThroughOneVerb() {
        Row host = sshHost("check-journey");
        PanelAction<Row> check = action("check_host");

        // 1. The two old buttons are gone; the one verb is offered as "Check and admit" to a host waiting for it.
        assertThat(ServerLifecycleActions.placed()).as("step 1: Preflight and Admit no longer exist")
            .noneMatch(action -> action.id().equals(HohenheimIds.id("admit_server"))
                || action.id().equals(HohenheimIds.id("preflight_server")));
        assertThat(check.labelFor(host).key()).as("step 1: offered as check-and-admit").isEqualTo("check_and_admit");

        // 2. Checking a host it cannot reach (an unpinned SSH host) is refused naming the check that must pass; the
        //    report is stored anyway, and the host stays waiting.
        @SuppressWarnings("unchecked")
        Operation<Row, Void, ?> operation = (Operation<Row, Void, ?>) check.operation();
        assertThat(catchThrowable(() -> OperationPipeline.invoke(OperationRequest.of(operation,
                CmsPlacementSurface.ADMIN_ACTION).caller(TenantConduits.operator()).subjects(List.of(host)))))
            .as("step 2: a failing required check refuses admission and names it")
            .isInstanceOfSatisfying(Violations.class, violations -> assertThat(violations.all())
                .anySatisfy(violation -> {
                    assertThat(violation.message().key()).isEqualTo("host_check_failed");
                    assertThat((List<?>) violation.message().args().asMap().get("checks"))
                        .as("step 2: the failed check by its words, never its code")
                        .anySatisfy(named -> assertThat(((Microcopy) named).key()).isEqualTo("check_daemon"));
                }));
        Row stored = Models.get(ServerModel.class).findById(host.get(ServerModel.ID));
        assertThat((String) stored.get(ServerModel.ADMISSION)).as("step 2: still waiting")
            .isEqualTo(ServerModel.ADMISSION_BLOCKED);
        assertThat(stored.get(ServerModel.PROBED_AT)).as("step 2: the report was stored").isNotNull();

        // 3. The host page lists the failed required check first under Must pass, with how to fix it.
        HostPreflightReportView report = ServerOverviewState.preflightReport(stored);
        assertThat(report.mustPass()).as("step 3: the failed check leads Must pass").isNotEmpty();
        PreflightCheckView first = report.mustPass().get(0);
        assertThat(first.name()).as("step 3: the unreachable daemon leads").isEqualTo("daemon");
        assertThat(first.label().key()).as("step 3: named in words").isEqualTo("check_daemon");
        assertThat(first.fix()).as("step 3: it says how to fix it").isNotNull();
        assertThat(first.fix().key()).isEqualTo("fix_daemon");
        assertThat(first.detail().key()).as("step 3: what it found is a worded finding, never a probe token")
            .isEqualTo(PreflightFinding.DAEMON_UNREACHABLE.copy().key());
        Microcopy failure = HostProbe.FailureKind.labelOf(stored.get(ServerModel.LAST_ERROR_KIND));
        assertThat(failure.isLiteral()).as("step 3: the probe failure is one this build words").isFalse();
        assertThat(say(first.detail(), EN)).as("step 3: led by the probe failure in words")
            .startsWith(say(failure, EN) + ": ");
        assertThat(say(first.detail(), NL)).as("step 3: and in Dutch from the Dutch catalog")
            .startsWith(say(failure, NL) + ": ")
            .isNotEqualTo(say(first.detail(), EN));
        assertThat(say(PreflightFinding.wordsOf("a_later_finding", null, "stored words"), EN))
            .as("step 3: a finding this build does not know reads as its stored text").isEqualTo("stored words");
        assertThat(report.advice()).as("step 3: advice never holds a required check")
            .noneMatch(PreflightCheckView::required);

        // 4. An admitted host is only checked again: its verb says so, and checking never changes admission there.
        stored.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        assertThat(check.labelFor(stored).key()).as("step 4: an admitted host is checked again")
            .isEqualTo("check_again");
    }

    @Test
    void eachHostIsOfferedTheTrustVerbsOfItsOwnLanesOnly() {
        Row docker = sshHost("lanes-docker");
        Row incus = sshHost("lanes-incus");
        incus.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        incus.set(ServerModel.INCUS_URL, "https://lanes-incus.invalid:8443");
        Models.get(ServerModel.class).save(incus);
        incus = Models.get(ServerModel.class).findById(incus.get(ServerModel.ID));
        Row local = Models.get(ServerModel.class).findById(ServerModel.localServerId());

        // 1. An SSH host is offered the host-key scan, and none of the Incus verbs.
        assertThat(offer("scan_host_key", docker)).as("step 1: an SSH host scans its host key")
            .isInstanceOf(OperationPipeline.Offer.Available.class);
        assertThat(offer("scan_incus_cert", docker)).as("step 1: a Docker host has no Incus certificate")
            .isInstanceOf(OperationPipeline.Offer.Hidden.class);
        assertThat(offer("reap_controller_objects", docker)).as("step 1: nor Incus controller objects to reap")
            .isInstanceOf(OperationPipeline.Offer.Hidden.class);

        // 2. An Incus host over HTTPS is offered its certificate scan and the reaper.
        assertThat(offer("scan_incus_cert", incus)).as("step 2: an Incus host scans its certificate")
            .isInstanceOf(OperationPipeline.Offer.Available.class);
        assertThat(offer("reap_controller_objects", incus)).as("step 2: and may reap its controller objects")
            .isInstanceOf(OperationPipeline.Offer.Available.class);

        // 3. The local host has no SSH lane, so no host-key verb.
        assertThat(offer("scan_host_key", local)).as("step 3: the local host scans no SSH key")
            .isInstanceOf(OperationPipeline.Offer.Hidden.class);
    }

    @SuppressWarnings("unchecked")
    private static OperationPipeline.Offer offer(String id, Row host) {
        PanelAction<Row> placed = Stream.concat(ServerLifecycleActions.placed().stream(),
                ServerTrustActions.placed().stream())
            .filter(action -> action.id().equals(HohenheimIds.id(id))).findFirst().orElseThrow();
        return OperationPipeline.offer((Operation<Row, ?, ?>) placed.operation(), TenantConduits.operator(), host);
    }

    @Test
    void everyDeclaredCheckIsNamedAndSaysHowToFixItInBothCatalogs() throws IOException {
        Set<String> checks = new TreeSet<>(HostPreflight.DOCKER_BATTERY);
        checks.addAll(IncusPreflight.BATTERY);
        for (String language : List.of("en", "nl")) {
            String catalog = catalog(language);
            // 1. One name and one how-to-fix sentence per check the batteries can store, in every shipped language.
            for (String name : checks) {
                assertThat(catalog).as("step 1: " + language + " says how to fix " + name)
                    .contains("\"fix_" + name + "\"");
                assertThat(catalog).as("step 1: " + language + " names " + name)
                    .contains("\"" + HostPreflight.checkLabel(name).key() + "\"");
            }
            // 2. Every verdict a check can carry, and every way a probe can fail, reads as words.
            for (PreflightStatus status : PreflightStatus.values()) {
                assertThat(catalog).as("step 2: " + language + " words the verdict " + status)
                    .contains("\"" + status.label().key() + "\"");
            }
            for (HostProbe.FailureKind kind : HostProbe.FailureKind.values()) {
                assertThat(catalog).as("step 2: " + language + " words the probe failure " + kind)
                    .contains("\"" + kind.label().key() + "\"");
            }
            // 3. Every finding a check can store and every fact a battery measures reads as words.
            for (PreflightFinding finding : PreflightFinding.values()) {
                assertThat(catalog).as("step 3: " + language + " words the finding " + finding)
                    .contains("\"" + finding.copy().key() + "\"");
            }
            for (HostFact fact : HostFact.values()) {
                assertThat(catalog).as("step 3: " + language + " names the fact " + fact)
                    .contains("\"" + fact.label().key() + "\"");
            }
        }
    }

    private static String say(Microcopy copy, LocaleChain locales) {
        return copy.resolve(locales, Zenit.getMessageResolver());
    }

    private static PanelAction<Row> action(String id) {
        return ServerLifecycleActions.placed().stream()
            .filter(action -> action.id().equals(HohenheimIds.id(id)))
            .findFirst().orElseThrow();
    }

    private static Row sshHost(String name) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        row.set(ServerModel.MODE, ServerModel.MODE_SSH);
        row.set(ServerModel.SSH_TARGET, "operator@" + name + ".invalid");
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
        Models.get(ServerModel.class).save(row);
        return Models.get(ServerModel.class).findById(row.get(ServerModel.ID));
    }

    private static String catalog(String language) throws IOException {
        try (InputStream input = HostCheckAndAdmitJourneyTest.class
                .getResourceAsStream("/META-INF/microcopy/" + language + ".json")) {
            assertThat(input).as("the " + language + " catalog ships").isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
