package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stack admin surface end to end: every page RENDERS (the service and file
 * forms carry RelationPicks whose record sources come from the CMS auto-glue --
 * a missing source is a 500, and nothing else covers these pages), records
 * validate, and deleting cascades to the child rows that have no FK cascade.
 */
class StackAdminTest extends HohenheimTestBase {

    /**
     * The stack's own CRUD story in one pass: the list and create form render, the created
     * stack lands on its services tab, a service and a staged file are validated against its
     * mounts, and deleting the stack cascades to the child rows that have no FK cascade.
     *
     * AIDEV-NOTE: this was eleven @Order-coupled methods passing the stack and service ids
     * through statics, so running one alone NPE'd and one failure cascaded. The CRUD half is
     * this journey; the refusals that merely needed SOME stack are self-contained tests below,
     * each over a stack of its own.
     */
    @Test
    void theStackSurfaceRendersValidatesAndCascadesItsDelete() throws Exception {
        // 1. The list and the create form render; creating a stack lands on its SERVICES
        //    tab, not back on the form it just submitted: everything that makes a stack run
        //    is added there, and the tab's empty state is what says so.
        navigateToApp("/admin/stacks");
        waitForHydration();
        assertThat(page.locator("body").textContent())
            .as("step 1: the stack list renders").doesNotContain("Internal Server Error");

        navigateToApp("/admin/stacks/new");
        waitForHydration();
        assertThat(page.locator("form").count()).as("step 1: the create form renders").isGreaterThan(0);

        var response = adminPostForm("/admin/stacks/new",
            "name=admin-test-stack&enabled=false&enabled=true&server_id="
            + ServerModel.localServerId());
        assertThat(response.statusCode()).as("step 1: the stack is created").isIn(200, 302, 303);

        Row stack = Models.get(StackModel.class).find()
            .where(StackModel.NAME.eq("admin-test-stack")).first();
        assertThat(stack).as("step 1: the created stack exists").isNotNull();
        int stackId = stack.get(StackModel.ID);

        assertThat(response.headers().firstValue("Location").orElse(""))
            .as("step 1: the create lands on the services tab")
            .contains("/admin/stacks/" + stackId + "/page/services");

        var landing = adminGet("/admin/stacks/" + stackId + "/page/services");
        assertThat(landing.statusCode()).as("step 1: the services tab renders").isEqualTo(200);
        assertThat(landing.body()).as("step 1: and its empty state says what to do")
            .contains("A stack is composed of services")
            .contains("add-first-service-link");

        // 2. The service form's RelationPick needs a registered record source for the stack
        //    model; the service it creates renders its image back.
        navigateToApp("/admin/stack-services/new?stack_id=" + stackId);
        waitForHydration();
        assertThat(page.locator("form").count()).as("step 2: the service form renders").isGreaterThan(0);

        response = adminPostForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=web&enabled=false&enabled=true&image=alpine%3Alatest"
            + "&command=&restart_policy=no"
            + "&mounts.0.type=volume&mounts.0.name=data&mounts.0.container_path=%2Fdata"
            + "&ports.0.container_port=80&ports.0.host_port=8099&ports.0.protocol=tcp");
        assertThat(response.statusCode()).as("step 2: the service is created").isIn(200, 302, 303);

        Row service = serviceNamed(stackId, "web");
        assertThat(service).as("step 2: the created service exists").isNotNull();
        int serviceId = service.get(StackServiceModel.ID);

        navigateToApp("/admin/stack-services/" + serviceId);
        waitForHydration();
        assertThat(page.content()).as("step 2: the service renders its image").contains("alpine:latest");

        // 3. The services tab renders live state. The container does not exist, so the
        //    state badge renders the LOCALIZED "missing" label rather than the raw token.
        navigateToApp("/admin/stacks/" + stackId + "/page/services");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).as("step 3: the service is listed").contains("web");
        assertThat(body).as("step 3: its state is the localized label").contains("Missing");
        assertThat(body).as("step 3: the tab title is not doubled").doesNotContain("- Services");

        // 4. The deployments tab renders.
        navigateToApp("/admin/stacks/" + stackId + "/page/deployments");
        waitForHydration();
        assertThat(page.locator("body").textContent())
            .as("step 4: the deployments tab renders").doesNotContain("Internal Server Error");

        // 5. The file form's RelationPick targets the SERVICE model's record source, and a
        //    file under a volume mount is refused: /data would shadow it at container start.
        navigateToApp("/admin/stack-files/new?stack_service_id=" + serviceId);
        waitForHydration();
        assertThat(page.locator("form").count()).as("step 5: the file form renders").isGreaterThan(0);

        adminPostForm("/admin/stack-files/new",
            "stack_service_id=" + serviceId + "&container_path=%2Fdata%2Fapp.conf"
            + "&content=secret%3D1&mode=0600");
        assertThat(filesOf(serviceId))
            .as("step 5: a file under a volume mount must be refused")
            .isEqualTo(0);

        var accepted = adminPostForm("/admin/stack-files/new",
            "stack_service_id=" + serviceId + "&container_path=%2Fetc%2Fapp.conf"
            + "&content=secret%3D1&mode=0600");
        assertThat(accepted.statusCode()).as("step 5: a file outside the mounts is accepted")
            .isIn(200, 302, 303);
        assertThat(filesOf(serviceId)).as("step 5: and stored").isEqualTo(1);

        // 6. The mirror of the file-side shadow refusal: adding the MOUNT after the file
        //    must be refused exactly like adding the file after the mount.
        navigateToApp("/admin/stack-services/" + serviceId);
        waitForHydration();
        String snapshot = page.locator("input[name='cms__snapshot']").inputValue();

        adminPostForm("/admin/stack-services/" + serviceId,
            "stack_id=" + stackId + "&name=web&enabled=false&enabled=true"
            + "&image=alpine%3Alatest&command=&restart_policy=no"
            + "&mounts.0.type=volume&mounts.0.name=data&mounts.0.container_path=%2Fdata"
            + "&mounts.1.type=volume&mounts.1.name=etc&mounts.1.container_path=%2Fetc"
            + "&cms__snapshot=" + URLEncoder.encode(snapshot, StandardCharsets.UTF_8));

        Row edited = Models.get(StackServiceModel.class).findById(serviceId);
        assertThat(edited.getRecords(StackServiceModel.MOUNTS))
            .as("step 6: the shadowing mount must be refused, keeping the original single mount")
            .hasSize(1);

        // 7. Deleting the stack cascades to its services and their files.
        response = adminPostForm("/admin/stacks/" + stackId + "/delete", confirmed(""));
        assertThat(response.statusCode()).as("step 7: the stack is deleted").isIn(200, 302, 303);

        assertThat(Models.get(StackModel.class).findById(stackId))
            .as("step 7: the stack row is gone").isNull();
        assertThat(Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.STACK_ID.eq(stackId)).count())
            .as("step 7: its services are gone").isEqualTo(0);
        assertThat(filesOf(serviceId))
            .as("step 7: config files (encrypted secrets) must not outlive their stack")
            .isEqualTo(0);
    }

    // AIDEV-NOTE: since C3 the sibling-row scan this test originally pinned is GONE --
    // what refuses the duplicate now is the PORT LEDGER's exclusivity (the first service's
    // saved claim in port_allocations). The test is kept as the stack-vs-stack face of that
    // exclusivity; the cross-AUTHORITY faces are the two tests directly below.
    @Test
    void duplicateHostPortAcrossServicesIsRefused() throws Exception {
        int stackId = createStack("admin-stack-dup-port");
        var first = adminPostForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=web&enabled=false&enabled=true&image=alpine%3Alatest"
            + "&command=&restart_policy=no"
            + "&ports.0.container_port=80&ports.0.host_port=8098&ports.0.protocol=tcp");
        assertThat(first.statusCode()).as("the first publisher of the port is accepted")
            .isIn(200, 302, 303);
        assertThat(serviceNamed(stackId, "web")).as("and stored").isNotNull();

        adminPostForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=other&enabled=false&enabled=true&image=alpine%3Alatest"
            + "&command=&restart_policy=no"
            + "&ports.0.container_port=80&ports.0.host_port=8098&ports.0.protocol=tcp");

        assertThat(serviceNamed(stackId, "other"))
            .as("two services cannot publish the same host port")
            .isNull();
    }

    /**
     * THE decisive cross-authority case: the pre-C3 validator scanned sibling STACK rows
     * only, so a collision with a managed database's port was structurally invisible to
     * it. The ledger claim below is the shape C4's record-after path will write; the
     * assertion is the named conflict AND the resulting state, never a bare status code.
     */
    @Test
    void stackPortCollidingWithAManagedDatabaseClaimIsRefused() throws Exception {
        int stackId = createStack("admin-stack-db-clash");
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

        var response = adminPostForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=dbclash&enabled=false&enabled=true"
            + "&image=alpine%3Alatest&command=&restart_policy=no"
            + "&ports.0.container_port=80&ports.0.host_port=8210&ports.0.protocol=tcp");

        assertThat(serviceNamed(stackId, "dbclash"))
            .as("a stack service cannot seize a port the ledger records for a managed database")
            .isNull();
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
    void stackPortCollidingWithADockerSiteClaimIsRefused() throws Exception {
        int stackId = createStack("admin-stack-site-clash");
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, "ledgersite");
        site.set(SiteModel.SLUG, "ledgersite");
        site.set(SiteModel.UPSTREAM_KIND, "docker");
        site.set(SiteModel.ENABLED, false);
        sites.save(site);
        PortLedger.claim(ServerModel.localServerId(), "0.0.0.0", 8211, null,
            SiteModel.MODEL_ID, site.get(SiteModel.ID), null);

        var response = adminPostForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=siteclash&enabled=false&enabled=true"
            + "&image=alpine%3Alatest&command=&restart_policy=no"
            + "&ports.0.container_port=80&ports.0.host_port=8211&ports.0.protocol=tcp");

        assertThat(serviceNamed(stackId, "siteclash"))
            .as("a stack service cannot seize a port the ledger records for a docker site")
            .isNull();
        assertThat(response.body())
            .as("the refusal names the holding site (0.0.0.0 + null protocol folded canonically)")
            .contains("ledgersite");
    }

    @Test
    void unknownDependencyIsRefused() throws Exception {
        int stackId = createStack("admin-stack-dependency");
        adminPostForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=dependent&enabled=false&enabled=true&image=alpine%3Alatest"
            + "&command=&restart_policy=no"
            + "&depends_on.0.service=nope&depends_on.0.condition=started");

        assertThat(serviceNamed(stackId, "dependent"))
            .as("a dependency naming no sibling service can never be satisfied")
            .isNull();
    }

    /**
     * Validation runs on the TRIMMED value and the trimmed value is what is stored:
     * "web2 " passing the pattern check while " web2 " lands raw in the row is how
     * invalid Docker names (and unmatchable sibling checks) used to ship.
     */
    @Test
    void trailingWhitespaceServiceNameIsStoredTrimmed() throws Exception {
        int stackId = createStack("admin-stack-trim");
        var response = adminPostForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=web2%20&enabled=false&enabled=true"
            + "&image=alpine%3Alatest&command=&restart_policy=no");
        assertThat(response.statusCode()).as("the untrimmed name is accepted").isIn(200, 302, 303);
        assertThat(servicesNamed(stackId, "web2"))
            .as("the canonical trimmed name is the stored name")
            .isEqualTo(1);

        adminPostForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=%20web2&enabled=false&enabled=true"
            + "&image=alpine%3Alatest&command=&restart_policy=no");
        assertThat(servicesNamed(stackId, "web2"))
            .as("a whitespace variant is the same name and must be refused")
            .isEqualTo(1);
    }

    /** Docker rejects zero-period healthchecks at container create -- exactly the
     *  deploy-time failure form validation exists to prevent. */
    @Test
    void zeroHealthIntervalIsRefused() throws Exception {
        int stackId = createStack("admin-stack-health");
        adminPostForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=sick&enabled=false&enabled=true"
            + "&image=alpine%3Alatest&command=&restart_policy=no"
            + "&health_cmd=true&health_interval_seconds=0");
        assertThat(serviceNamed(stackId, "sick"))
            .as("a zero healthcheck interval must fail the form, not the deploy")
            .isNull();
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
    void aDeclarableCapabilityIsStoredAndAnEscapeIsRefusedAtTheForm() throws Exception {
        int stackId = createStack("admin-stack-capabilities");
        // 1. THE POSITIVE ANCHOR: a name on the allow-list lands on the record.
        adminPostForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=capok&enabled=false&enabled=true"
            + "&image=alpine%3Alatest&command=&restart_policy=no"
            + "&capabilities=NET_RAW&capabilities=");
        Row stored = serviceNamed(stackId, "capok");
        assertThat(stored)
            .as("step 1: a declarable capability must not block the save").isNotNull();
        assertThat((List<String>) stored.get(StackServiceModel.CAPABILITIES))
            .as("step 1: the declaration is stored as declared")
            .containsExactly("NET_RAW");

        // 2. THE REFUSAL: an escape never becomes a row at all.
        adminPostForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=capbad&enabled=false&enabled=true"
            + "&image=alpine%3Alatest&command=&restart_policy=no"
            + "&capabilities=SYS_ADMIN&capabilities=");
        assertThat(serviceNamed(stackId, "capbad"))
            .as("step 2: SYS_ADMIN must fail the form, not the deploy")
            .isNull();
    }

    // -- fixtures -----------------------------------------------------------------

    /** Create a stack on the local host through the admin form; each test owns its own. */
    private int createStack(String name) throws Exception {
        var response = adminPostForm("/admin/stacks/new",
            "name=" + name + "&enabled=false&enabled=true&server_id=" + ServerModel.localServerId());
        assertThat(response.statusCode()).as("stack %s is created", name).isIn(200, 302, 303);
        Row stack = Models.get(StackModel.class).find().where(StackModel.NAME.eq(name)).first();
        assertThat(stack).as("stack %s exists", name).isNotNull();
        return stack.get(StackModel.ID);
    }

    /** The service of one stack stored under {@code name}, or null. */
    private static Row serviceNamed(int stackId, String name) {
        return Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.STACK_ID.eq(stackId))
            .where(StackServiceModel.NAME.eq(name))
            .first();
    }

    /** How many services of one stack are stored under {@code name}. */
    private static long servicesNamed(int stackId, String name) {
        return Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.STACK_ID.eq(stackId))
            .where(StackServiceModel.NAME.eq(name))
            .count();
    }

    /** How many files are staged on one service. */
    private static long filesOf(int serviceId) {
        return Models.get(StackFileModel.class).find()
            .where(StackFileModel.STACK_SERVICE_ID.eq(serviceId))
            .count();
    }
}
