package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.hohenheim.DisplayWidget;
import be.elevenways.hohenheim.HohenheimWidgets;
import be.elevenways.hohenheim.HostTrustLane;
import be.elevenways.hohenheim.OnboardingState;
import be.elevenways.hohenheim.WorkloadTier;
import be.elevenways.hohenheim.dns.DelegationVerdict;
import be.elevenways.hohenheim.host.HostState;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.widget.common.WidgetRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dashboard's rendered vocabularies -- attention severity, onboarding state, certificate coverage,
 * workload tier, trust lane and the display widget ids -- stay bound to their one declaring home.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class DashboardVocabularyDriftTest {

    private static final Path APP_SCSS = Path.of("src/common/scss/app.scss");

    @Test
    void attentionSeverityIsTheStylesheetVocabulary() throws IOException {
        String scss = Files.readString(APP_SCSS);

        // 1. Every data-severity value the stylesheet tints is a member's key, and every member is tinted.
        Set<String> styled = captures(scss, "\\[data-severity=\"([a-z_-]+)\"\\]");
        assertThat(styled).as("step 1: app.scss tints exactly the AttentionSeverity keys")
            .containsExactlyInAnyOrderElementsOf(keys(AttentionSeverity.values(), AttentionSeverity::key));

        // 2. A stored or legacy spelling resolves to its member.
        assertThat(AttentionSeverity.of("error"))
            .as("step 2: the rendered key maps back to its member").isEqualTo(AttentionSeverity.ERROR);

        // 3. An unknown spelling fails closed instead of rendering an untinted item.
        assertThatThrownBy(() -> AttentionSeverity.of("critical"))
            .as("step 3: an unknown severity is refused").isInstanceOf(IllegalArgumentException.class);

        // 4. Every DNS delegation verdict that raises an item names a severity the enum knows.
        for (DelegationVerdict verdict : DelegationVerdict.values()) {
            if (verdict.severity() != null) {
                assertThat(verdict.severity().key())
                    .as("step 4: %s raises a tinted severity", verdict).isIn(styled);
            }
        }
    }

    @Test
    void onboardingStateIsTheStylesheetVocabulary() throws IOException {
        String scss = Files.readString(APP_SCSS);

        // 1. Every data-state the checklist styles is a member's key.
        Set<String> styled = captures(scss, "\\.hh-onboarding-step\\[data-state=\"([a-z_-]+)\"\\]");
        assertThat(keys(OnboardingState.values(), OnboardingState::key))
            .as("step 1: every styled onboarding state is an OnboardingState key").containsAll(styled);

        // 2. Only an open step shows its own subject icon; a done step offers no link.
        assertThat(OnboardingState.TODO.marker()).as("step 2: an open step keeps its subject icon").isNull();
        assertThat(OnboardingState.DONE.actionable()).as("step 2: a done step offers no link").isFalse();
        assertThat(OnboardingState.BLOCKED.marker()).as("step 2: a blocked step states its state").isNotNull();
    }

    @Test
    void certificateCoverageCoversEveryCertificateStatus() {
        // 1. The covered states are exactly the certificate model's STATUS values.
        Set<String> covered = Arrays.stream(CertCoverage.values()).filter(CertCoverage::hasCertificate)
            .map(CertCoverage::key).collect(Collectors.toSet());
        assertThat(covered).as("step 1: CertCoverage derives from CertificateModel.STATUS")
            .containsExactlyInAnyOrderElementsOf(CertificateModel.STATUS.getValues().keySet());

        // 2. No certificate is NONE; a status nobody declared never claims coverage.
        assertThat(CertCoverage.ofCertificateStatus(null)).as("step 2: no certificate").isEqualTo(CertCoverage.NONE);
        assertThat(CertCoverage.ofCertificateStatus("revoked"))
            .as("step 2: an unknown status fails closed onto not-covered").isEqualTo(CertCoverage.ERROR);
    }

    @Test
    void hostVocabulariesCarryTheirOwnFacts() {
        // 1. The tokens are STORED/rendered facts (data-trust-lane, the lane's row-action ids),
        //    so they are pinned: a rename is a deliberate edit here, never a silent drift.
        assertThat(keys(WorkloadTier.values(), WorkloadTier::key)).as("step 1: the tier tokens")
            .containsExactlyInAnyOrder("instance", "stack", "database", "database_engine");
        assertThat(keys(HostTrustLane.values(), HostTrustLane::key)).as("step 1: the lane tokens")
            .containsExactlyInAnyOrder("host_key", "incus_cert");

        // 2. The host-status cell's emphasis is a fact on the member: only a quarantine is loud,
        //    only a silent host names its daemon beside the wording, only OK looks green.
        for (HostState state : HostState.values()) {
            assertThat(state.loud()).as("step 2: %s loud", state).isEqualTo(state == HostState.QUARANTINED);
            assertThat(state.namesDaemon()).as("step 2: %s names its daemon", state)
                .isEqualTo(state == HostState.SILENT);
            assertThat(state.dot().equals("online")).as("step 2: %s green", state).isEqualTo(state == HostState.OK);
        }
    }

    @Test
    void displayWidgetIdsAreStoredAndRegistered() {
        // 1. The stored ids never move: widget trees persisted by earlier versions reference them.
        List<DisplayWidget> widgets = List.of(HohenheimWidgets.ATTENTION, HohenheimWidgets.ONBOARDING,
            HohenheimWidgets.ONBOARDING_CHECKLIST, HohenheimWidgets.HOST_STATE, HohenheimWidgets.HOST_TRUST,
            HohenheimWidgets.HOST_PREFLIGHT, HohenheimWidgets.HOST_WORKLOADS, HohenheimWidgets.INSTANCE_ENDPOINTS);
        assertThat(widgets.stream().map(widget -> widget.id().toString()))
            .as("step 1: the stored widget type ids")
            .containsExactly(Identifier.of("hohenheim", "attention").toString(),
                Identifier.of("hohenheim", "onboarding").toString(),
                Identifier.of("hohenheim", "onboarding_checklist").toString(),
                Identifier.of("hohenheim", "host_state").toString(),
                Identifier.of("hohenheim", "host_trust").toString(),
                Identifier.of("hohenheim", "host_preflight").toString(),
                Identifier.of("hohenheim", "host_workloads").toString(),
                Identifier.of("hohenheim", "instance_endpoints").toString());

        // 2. Each one is registered as itself.
        for (DisplayWidget widget : widgets) {
            assertThat(WidgetRegistry.INSTANCE.get(widget.id())).as("step 2: %s is registered", widget.id())
                .isSameAs(widget);
            assertThat(widget.configSpec().entries()).as("step 2: %s is configless", widget.id()).isEmpty();
        }
    }

    private static Set<String> captures(String text, String regex) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(regex).matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static <E> Set<String> keys(E[] members, Function<E, String> key) {
        return Arrays.stream(members).map(key).collect(Collectors.toSet());
    }
}
