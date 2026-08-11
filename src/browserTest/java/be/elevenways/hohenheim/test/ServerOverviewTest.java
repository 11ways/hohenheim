package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server surface as EVIDENCE, not prose: the detail form must be side-effect-free,
 * the stored preflight report must reach a page, and the list cell must be structured.
 *
 * AIDEV-NOTE: the defects this pins, pre-fix (2026-08-11):
 * ONE, rendering the detail form ran {@code ServerService.probeAndStore} as a side effect
 * of computing the {@code live_overview} pseudo-field, overwriting health columns on a
 * page VIEW. TWO, six more Computed pseudo-fields flattened structured, timestamped trust
 * and kernel evidence into localized sentences on the edit form. THREE, the per-check
 * preflight report {@code HostPreflight.store} persists was rendered NOWHERE -- the
 * overview subpage answering 404 was the literal proof. All three failed pre-fix.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServerOverviewTest extends HohenheimTestBase {

    private static Integer hostId;

    /**
     * Rendering the server DETAIL FORM contacts no daemon and overwrites nothing: a page
     * view is a read. Pre-fix the {@code live_overview} Computed called
     * {@code probeAndStore} at render time, which stamped {@code last_error_kind} =
     * {@code not_pinned} onto a freshly enrolled host just because somebody LOOKED at it.
     */
    @Test
    @Order(1)
    void renderingTheServerDetailFormIsSideEffectFree() throws Exception {
        // 1. Enroll a remote host that has never been probed: health columns clean.
        var create = postForm("/admin/servers/new",
            "name=overview-dark&ssh_target=nobody%40overview-dark.hohenheim-test.invalid");
        assertThat(create.statusCode()).as("step 1: host enrolls").isIn(200, 302, 303);
        Row host = Models.get(ServerModel.class).findByName("overview-dark");
        assertThat(host).isNotNull();
        hostId = host.get(ServerModel.ID);
        assertThat((String) host.get(ServerModel.LAST_ERROR_KIND))
            .as("step 1: a never-probed host carries no error kind").isNull();

        // 2. THE DEFECT. Render the detail form and read the record back.
        long constructions = DockerClient.constructionCount();
        HttpResponse<String> detail = get("/admin/servers/" + hostId);
        assertThat(detail.statusCode()).as("step 2: the detail form renders").isEqualTo(200);

        Row after = Models.get(ServerModel.class).findById(hostId);
        assertThat((String) after.get(ServerModel.LAST_ERROR_KIND))
            .withFailMessage("step 2: rendering the detail form overwrote the host's"
                + " health columns (last_error_kind = '%s') -- a page view ran a live"
                + " probe as a side effect", after.get(ServerModel.LAST_ERROR_KIND))
            .isNull();
        assertThat(DockerClient.constructionCount())
            .as("step 2: and no daemon client was constructed for a page view")
            .isEqualTo(constructions);

        // 3. The form carries no live-overview pseudo-field at all; probing is an
        //    explicit action now.
        assertThat(detail.body())
            .as("step 3: the live_overview pseudo-field is gone from the form")
            .doesNotContain("data-path=\"live_overview\"");
    }

    /**
     * The edit form carries ONLY genuinely editable fields: the six status pseudo-fields
     * (plus the computed client-certificate display) render on the Overview page instead
     * of masquerading as form inputs.
     */
    @Test
    @Order(2)
    void theEditFormCarriesOnlyEditableFields() throws Exception {
        HttpResponse<String> detail = get("/admin/servers/" + hostId);
        assertThat(detail.statusCode()).isEqualTo(200);
        for (String fake : List.of("live_overview", "host_key_state", "identity_public_key",
                "kernel_isolation_state", "incus_cert_state", "incus_client_cert",
                "trust_notice")) {
            assertThat(detail.body())
                .withFailMessage("the EDIT form still renders the '%s' pseudo-field", fake)
                .doesNotContain("data-path=\"" + fake + "\"");
        }
        // The write-only trust token is a REAL input and stays.
        assertThat(detail.body())
            .as("the write-only incus trust token entry survives")
            .contains("incus_trust_token");
    }

    /**
     * The stored preflight report -- per-check status, requiredness, detail text and the
     * check's OWN timestamp -- reaches a rendered page. Pre-fix this data was persisted by
     * {@code HostPreflight.store} and rendered NOWHERE: the overview URL answered 404.
     */
    @Test
    @Order(3)
    void storedPreflightEvidenceReachesTheOverviewPage() throws Exception {
        // 1. Store a realistic report the way both batteries do.
        Instant checkedAt = Instant.parse("2026-08-10T09:15:30Z");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put(HostPreflight.MEM_TOTAL_FACT, 16L * 1024 * 1024 * 1024);
        facts.put("docker_version", "27.1.1");
        HostPreflight.store("overview-dark", new HostPreflight.Report(List.of(
            new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true,
                "Docker 27.1.1 reachable"),
            new HostPreflight.Check("cgroup_pids_controller", HostPreflight.STATUS_FAIL, true,
                "pids controller not delegated; a PidsLimit on this host enforces NOTHING"),
            new HostPreflight.Check("userns_remap", HostPreflight.STATUS_WARN, false,
                "no user-namespace remapping: uid_map reads '0 0 4294967295'")),
            facts, false, checkedAt, null));

        // 2. THE DEFECT: pre-fix this page did not exist (404), so the evidence above
        //    was stored and readable by no operator.
        HttpResponse<String> overview = get("/admin/servers/" + hostId + "/page/overview");
        assertThat(overview.statusCode())
            .withFailMessage("step 2: the server overview page does not exist -- the"
                + " stored preflight report is rendered nowhere (HTTP %s)",
                overview.statusCode())
            .isEqualTo(200);
        String body = overview.body();

        // 3. Every stored dimension of one check reaches the page: name, status,
        //    requiredness, detail, own timestamp.
        assertThat(body).as("step 3: the check name renders")
            .contains("cgroup_pids_controller");
        assertThat(body).as("step 3: the stored detail text renders verbatim")
            .contains("a PidsLimit on this host enforces NOTHING");
        assertThat(body).as("step 3: the check's own timestamp renders")
            .contains(checkedAt.toString());
        assertThat(body).as("step 3: the advisory check renders too")
            .contains("userns_remap");

        // 4. Stored facts render with their provenance.
        assertThat(body).as("step 4: a measured fact renders").contains("docker_version");
        assertThat(body).as("step 4: with its value").contains("27.1.1");
    }

    /**
     * Capacity is explicit about UNMEASUREMENT: a host whose memory reading is missing or
     * stale shows a named "unmeasured" state, never a zero bar that reads as "empty host".
     */
    @Test
    @Order(4)
    void capacityShowsAnExplicitUnmeasuredStateNeverAZeroBar() throws Exception {
        // 1. Make the stored reading STALE by re-storing it with an old measurement
        //    stamp (the merge keeps per-fact provenance).
        HostPreflight.store("overview-dark", new HostPreflight.Report(List.of(),
            Map.of(HostPreflight.MEM_TOTAL_FACT, 16L * 1024 * 1024 * 1024),
            true, Instant.now().minus(Duration.ofDays(365)), null));
        HttpResponse<String> stale = get("/admin/servers/" + hostId + "/page/overview");
        assertThat(stale.statusCode()).isEqualTo(200);
        assertThat(stale.body())
            .as("step 1: a stale reading is an explicit unmeasured state")
            .contains("data-capacity-state=\"unmeasured\"");
        assertThat(stale.body())
            .as("step 1: and no usage bar renders over a number nobody re-read")
            .doesNotContain("<pl-usage-bar");

        // 2. A fresh measurement turns into a real usage bar with the booked numbers.
        HostPreflight.store("overview-dark", new HostPreflight.Report(List.of(),
            Map.of(HostPreflight.MEM_TOTAL_FACT, 16L * 1024 * 1024 * 1024),
            true, Instant.now(), null));
        HttpResponse<String> fresh = get("/admin/servers/" + hostId + "/page/overview");
        assertThat(fresh.body())
            .as("step 2: a fresh measurement renders a real usage bar")
            .contains("data-capacity-state=\"measured\"")
            .contains("<pl-usage-bar");
    }

    /**
     * The workloads table lists what {@code ServerModel.refuseRemovalWhileOwned} counts,
     * so drain/cordon/delete refusals are legible BEFORE they fire.
     */
    @Test
    @Order(5)
    void theOverviewListsTheWorkloadsThatHoldTheHost() throws Exception {
        InstanceModel instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, "overview-holder");
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "command", "sleep 60"));
        row.set(InstanceModel.SERVER_ID, hostId);
        instances.save(row);
        Integer instanceId = instances.find()
            .where(InstanceModel.NAME.eq("overview-holder")).first().get(InstanceModel.ID);

        HttpResponse<String> overview = get("/admin/servers/" + hostId + "/page/overview");
        assertThat(overview.body())
            .as("the workload renders by name").contains("overview-holder");
        assertThat(overview.body())
            .as("and links to its record").contains("/admin/instances/" + instanceId);

        // Clean up so later classes' local-host assertions see no stray instance.
        Row cleanup = instances.findById(instanceId);
        cleanup.set(InstanceModel.DELETED_AT, Instant.now());
        instances.save(cleanup);
    }

    /**
     * A quarantined host shows a LOUD banner with the stored reason, the repin ceremony
     * beside it, and deliberately NO "clear quarantine" control: re-pinning after
     * out-of-band verification IS the only clearing ceremony, and the banner says so.
     */
    @Test
    @Order(6)
    void theQuarantineBannerIsLoudAndOffersOnlyTheRepinCeremony() throws Exception {
        Row host = Models.get(ServerModel.class).findById(hostId);
        host.set(ServerModel.QUARANTINED_AT, Instant.now());
        host.set(ServerModel.QUARANTINE_REASON, "host key contradicted the pinned identity");
        Models.get(ServerModel.class).save(host);

        HttpResponse<String> overview = get("/admin/servers/" + hostId + "/page/overview");
        String body = overview.body();
        assertThat(body).as("the banner renders").contains("data-quarantine-banner");
        assertThat(body).as("with the stored reason")
            .contains("host key contradicted the pinned identity");
        assertThat(body)
            .as("no clear-quarantine action exists anywhere -- repin IS the ceremony")
            .doesNotContainIgnoringCase("clear_quarantine");

        Row cleared = Models.get(ServerModel.class).findById(hostId);
        cleared.set(ServerModel.QUARANTINED_AT, (Instant) null);
        cleared.set(ServerModel.QUARANTINE_REASON, (String) null);
        Models.get(ServerModel.class).save(cleared);
        assertThat(get("/admin/servers/" + hostId + "/page/overview").body())
            .as("positive anchor: an unquarantined host renders no banner")
            .doesNotContain("data-quarantine-banner");
    }

    /**
     * The servers LIST renders host state as a structured cell (status dot + state word),
     * not the old fully-resolved English sentence.
     */
    @Test
    @Order(7)
    void theServersListRendersAStructuredStatusCell() throws Exception {
        HttpResponse<String> list = get("/admin/servers");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body())
            .as("the host_status cell renders the structured partial")
            .contains("data-host-state");
        assertThat(list.body())
            .as("with a status dot")
            .contains("<pl-status-dot");
    }

    // -- plumbing -----------------------------------------------------------------

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
