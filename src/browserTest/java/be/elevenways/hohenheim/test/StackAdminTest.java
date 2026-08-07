package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.ports.PortLedger;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stack admin surface end to end: every page RENDERS (the service and file
 * forms carry RelationPicks whose record sources come from the CMS auto-glue --
 * a missing source is a 500, and nothing else covers these pages), records
 * validate, and deleting cascades to the child rows that have no FK cascade.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StackAdminTest extends HohenheimTestBase {

    private static Integer stackId;
    private static Integer serviceId;

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void stackListAndFormRender() throws Exception {
        navigateToApp("/admin/stacks");
        waitForHydration();
        assertThat(page.locator("body").textContent()).doesNotContain("Internal Server Error");

        navigateToApp("/admin/stacks/new");
        waitForHydration();
        assertThat(page.locator("form").count()).isGreaterThan(0);

        var response = postForm("/admin/stacks/new",
            "name=admin-test-stack&enabled=false&enabled=true&server_id="
            + ServerModel.localServerId());
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row stack = Models.get(StackModel.class).find()
            .where(StackModel.NAME.eq("admin-test-stack")).first();
        assertThat(stack).isNotNull();
        stackId = stack.get(StackModel.ID);
    }

    /** The service form's RelationPick needs a registered record source for the stack model. */
    @Test
    @Order(3)
    void serviceFormRendersAndCreates() throws Exception {
        navigateToApp("/admin/stack-services/new?stack_id=" + stackId);
        waitForHydration();
        assertThat(page.locator("form").count()).isGreaterThan(0);

        var response = postForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=web&enabled=false&enabled=true&image=alpine%3Alatest"
            + "&command=&restart_policy=no"
            + "&mounts.0.type=volume&mounts.0.name=data&mounts.0.container_path=%2Fdata"
            + "&ports.0.container_port=80&ports.0.host_port=8099&ports.0.protocol=tcp");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row service = Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.NAME.eq("web")).first();
        assertThat(service).isNotNull();
        serviceId = service.get(StackServiceModel.ID);

        navigateToApp("/admin/stack-services/" + serviceId);
        waitForHydration();
        assertThat(page.content()).contains("alpine:latest");
    }

    @Test
    @Order(4)
    void servicesTabRendersLiveState() {
        navigateToApp("/admin/stacks/" + stackId + "/page/services");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("web");
        // The container does not exist, so the state badge renders the LOCALIZED
        // "missing" label rather than the raw state token.
        assertThat(body).contains("Missing");
        assertThat(body).doesNotContain("- Services");
    }

    @Test
    @Order(5)
    void deploymentsTabRenders() {
        navigateToApp("/admin/stacks/" + stackId + "/page/deployments");
        waitForHydration();
        assertThat(page.locator("body").textContent()).doesNotContain("Internal Server Error");
    }

    /** The file form's RelationPick targets the SERVICE model's record source. */
    @Test
    @Order(6)
    void fileFormRendersAndValidatesAgainstMounts() throws Exception {
        navigateToApp("/admin/stack-files/new?stack_service_id=" + serviceId);
        waitForHydration();
        assertThat(page.locator("form").count()).isGreaterThan(0);

        // /data is a volume mount, so a file there would be shadowed at container start.
        postForm("/admin/stack-files/new",
            "stack_service_id=" + serviceId + "&container_path=%2Fdata%2Fapp.conf"
            + "&content=secret%3D1&mode=0600");
        assertThat(Models.get(StackFileModel.class).find()
            .where(StackFileModel.STACK_SERVICE_ID.eq(serviceId)).count())
            .as("a file under a volume mount must be refused")
            .isEqualTo(0);

        var accepted = postForm("/admin/stack-files/new",
            "stack_service_id=" + serviceId + "&container_path=%2Fetc%2Fapp.conf"
            + "&content=secret%3D1&mode=0600");
        assertThat(accepted.statusCode()).isIn(200, 302, 303);
        assertThat(Models.get(StackFileModel.class).find()
            .where(StackFileModel.STACK_SERVICE_ID.eq(serviceId)).count()).isEqualTo(1);
    }

    // AIDEV-NOTE: since C3 the sibling-row scan this test originally pinned is GONE --
    // what refuses the duplicate now is the PORT LEDGER's exclusivity (the "web" service's
    // saved claim in port_allocations). The test is kept as the stack-vs-stack face of that
    // exclusivity; the cross-AUTHORITY faces are the two tests directly below.
    @Test
    @Order(7)
    void duplicateHostPortAcrossServicesIsRefused() throws Exception {
        postForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=other&enabled=false&enabled=true&image=alpine%3Alatest"
            + "&command=&restart_policy=no"
            + "&ports.0.container_port=80&ports.0.host_port=8099&ports.0.protocol=tcp");

        assertThat(Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.NAME.eq("other")).count())
            .as("two services cannot publish the same host port")
            .isEqualTo(0);
    }

    /**
     * THE decisive cross-authority case: the pre-C3 validator scanned sibling STACK rows
     * only, so a collision with a managed database's port was structurally invisible to
     * it. The ledger claim below is the shape C4's record-after path will write; the
     * assertion is the named conflict AND the resulting state, never a bare status code.
     */
    @Test
    @Order(12)
    void stackPortCollidingWithAManagedDatabaseClaimIsRefused() throws Exception {
        DatabaseModel databases = Models.get(DatabaseModel.class);
        Row db = databases.createEmptyRow();
        db.set(DatabaseModel.NAME, "ledgerdb");
        db.set(DatabaseModel.ENGINE, "postgres");
        db.set(DatabaseModel.DB_USER, "appuser");
        db.set(DatabaseModel.DB_PASSWORD, "pw");
        db.set(DatabaseModel.DB_NAME, "appdb");
        db.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        databases.save(db);
        PortLedger.claim(ServerModel.localServerId(), "", 8210, "tcp",
            DatabaseModel.MODEL_ID, db.get(DatabaseModel.ID), null);

        var response = postForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=dbclash&enabled=false&enabled=true"
            + "&image=alpine%3Alatest&command=&restart_policy=no"
            + "&ports.0.container_port=80&ports.0.host_port=8210&ports.0.protocol=tcp");

        assertThat(Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.NAME.eq("dbclash")).count())
            .as("a stack service cannot seize a port the ledger records for a managed database")
            .isEqualTo(0);
        assertThat(response.body())
            .as("the refusal names the holding database, not a bare status")
            .contains("ledgerdb");
        // AIDEV-NOTE: which ARBITER answered, pinned. Refusing at all only proves the
        // ledger's unique index (that survives deleting the pre-write read -- observed
        // counterfactual), so the friendly field-pathed read is pinned by its own copy;
        // the backstop's copy is port_held_race.
        assertThat(response.body())
            .as("the friendly pre-write ledger read answered, not the unique-index backstop")
            .contains("already claimed by");
    }

    /** The same cross-authority refusal against a DOCKER SITE's recorded publication. */
    @Test
    @Order(13)
    void stackPortCollidingWithADockerSiteClaimIsRefused() throws Exception {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, "ledgersite");
        site.set(SiteModel.SLUG, "ledgersite");
        site.set(SiteModel.SITE_TYPE, "docker");
        site.set(SiteModel.ENABLED, false);
        sites.save(site);
        PortLedger.claim(ServerModel.localServerId(), "0.0.0.0", 8211, null,
            SiteModel.MODEL_ID, site.get(SiteModel.ID), null);

        var response = postForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=siteclash&enabled=false&enabled=true"
            + "&image=alpine%3Alatest&command=&restart_policy=no"
            + "&ports.0.container_port=80&ports.0.host_port=8211&ports.0.protocol=tcp");

        assertThat(Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.NAME.eq("siteclash")).count())
            .as("a stack service cannot seize a port the ledger records for a docker site")
            .isEqualTo(0);
        assertThat(response.body())
            .as("the refusal names the holding site (0.0.0.0 + null protocol folded canonically)")
            .contains("ledgersite");
    }

    @Test
    @Order(8)
    void unknownDependencyIsRefused() throws Exception {
        postForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=dependent&enabled=false&enabled=true&image=alpine%3Alatest"
            + "&command=&restart_policy=no"
            + "&depends_on.0.service=nope&depends_on.0.condition=started");

        assertThat(Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.NAME.eq("dependent")).count())
            .as("a dependency naming no sibling service can never be satisfied")
            .isEqualTo(0);
    }

    /**
     * Validation runs on the TRIMMED value and the trimmed value is what is stored:
     * "web2 " passing the pattern check while " web2 " lands raw in the row is how
     * invalid Docker names (and unmatchable sibling checks) used to ship.
     */
    @Test
    @Order(9)
    void trailingWhitespaceServiceNameIsStoredTrimmed() throws Exception {
        var response = postForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=web2%20&enabled=false&enabled=true"
            + "&image=alpine%3Alatest&command=&restart_policy=no");
        assertThat(response.statusCode()).isIn(200, 302, 303);
        assertThat(Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.NAME.eq("web2")).count())
            .as("the canonical trimmed name is the stored name")
            .isEqualTo(1);

        postForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=%20web2&enabled=false&enabled=true"
            + "&image=alpine%3Alatest&command=&restart_policy=no");
        assertThat(Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.NAME.eq("web2")).count())
            .as("a whitespace variant is the same name and must be refused")
            .isEqualTo(1);
    }

    /** Docker rejects zero-period healthchecks at container create -- exactly the
     *  deploy-time failure form validation exists to prevent. */
    @Test
    @Order(10)
    void zeroHealthIntervalIsRefused() throws Exception {
        postForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=sick&enabled=false&enabled=true"
            + "&image=alpine%3Alatest&command=&restart_policy=no"
            + "&health_cmd=true&health_interval_seconds=0");
        assertThat(Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.NAME.eq("sick")).count())
            .as("a zero healthcheck interval must fail the form, not the deploy")
            .isEqualTo(0);
    }

    /**
     * The capability declaration is a CLOSED choice at the form, not free text: a
     * declarable name is stored, an escape is refused before it ever reaches a daemon.
     *
     * AIDEV-NOTE: the POSITIVE anchor is the whole point. A form that refused every
     * capability would pass a refusal-only test and quietly make the field useless, which
     * is the "knob nobody can set is theater" outcome this mechanism was built to avoid.
     */
    @Test
    @Order(14)
    void aDeclarableCapabilityIsStoredAndAnEscapeIsRefusedAtTheForm() throws Exception {
        // 1. THE POSITIVE ANCHOR: a name on the allow-list lands on the record.
        postForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=capok&enabled=false&enabled=true"
            + "&image=alpine%3Alatest&command=&restart_policy=no"
            + "&capabilities=NET_RAW&capabilities=");
        Row stored = Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.NAME.eq("capok")).first();
        assertThat(stored)
            .as("step 1: a declarable capability must not block the save").isNotNull();
        assertThat((List<String>) stored.get(StackServiceModel.CAPABILITIES))
            .as("step 1: the declaration is stored as declared")
            .containsExactly("NET_RAW");

        // 2. THE REFUSAL: an escape never becomes a row at all.
        postForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=capbad&enabled=false&enabled=true"
            + "&image=alpine%3Alatest&command=&restart_policy=no"
            + "&capabilities=SYS_ADMIN&capabilities=");
        assertThat(Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.NAME.eq("capbad")).count())
            .as("step 2: SYS_ADMIN must fail the form, not the deploy")
            .isEqualTo(0);
    }

    /**
     * The mirror of the file-side shadow refusal: adding the MOUNT after the file
     * must be refused exactly like adding the file after the mount (the service has
     * a staged file at /etc/app.conf from the earlier test).
     */
    @Test
    @Order(11)
    void mountShadowingAStagedFileIsRefused() throws Exception {
        navigateToApp("/admin/stack-services/" + serviceId);
        waitForHydration();
        String snapshot = page.locator("input[name='cms__snapshot']").inputValue();

        postForm("/admin/stack-services/" + serviceId,
            "stack_id=" + stackId + "&name=web&enabled=false&enabled=true"
            + "&image=alpine%3Alatest&command=&restart_policy=no"
            + "&mounts.0.type=volume&mounts.0.name=data&mounts.0.container_path=%2Fdata"
            + "&mounts.1.type=volume&mounts.1.name=etc&mounts.1.container_path=%2Fetc"
            + "&cms__snapshot=" + java.net.URLEncoder.encode(snapshot, java.nio.charset.StandardCharsets.UTF_8));

        Row service = Models.get(StackServiceModel.class).findById(serviceId);
        assertThat(service.getRecords(StackServiceModel.MOUNTS))
            .as("the shadowing mount must be refused, keeping the original single mount")
            .hasSize(1);
    }

    @Test
    @Order(99)
    void deletingTheStackCascadesToServicesAndFiles() throws Exception {
        var response = postForm("/admin/stacks/" + stackId + "/delete", "");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        assertThat(Models.get(StackModel.class).findById(stackId)).isNull();
        assertThat(Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.STACK_ID.eq(stackId)).count()).isEqualTo(0);
        assertThat(Models.get(StackFileModel.class).find()
            .where(StackFileModel.STACK_SERVICE_ID.eq(serviceId)).count())
            .as("config files (encrypted secrets) must not outlive their stack")
            .isEqualTo(0);
    }
}
