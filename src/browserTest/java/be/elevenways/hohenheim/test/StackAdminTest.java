package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.server.cms.StackParts;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.stack.StackInstances;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The stack admin surface end to end: every page RENDERS (the service and file
 * forms carry RelationPicks whose record sources come from the CMS auto-glue --
 * a missing source is a 500, and nothing else covers these pages), records
 * validate, and deleting cascades to the child rows that have no FK cascade.
 * Writes go through the registered StackParts entries (PanelResourceCalls), the
 * lane their forms post through. The daemon is {@link FakeDockerDaemon}: a delete's runtime
 * teardown is part of the story, and it must never need a real Docker host.
 */
class StackAdminTest extends HohenheimTestBase {

    private static FakeDockerDaemon daemon;

    @BeforeAll
    static void installDaemon() {
        daemon = new FakeDockerDaemon();
        daemon.install();
    }

    @AfterAll
    static void restoreDaemon() {
        FakeDockerDaemon.restore();
        if (daemon != null) {
            daemon.close();
            daemon = null;
        }
    }

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

        // The form's own POST, kept on purpose: where a create LANDS is the route's answer.
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

        Map<String, Object> web = service(stackId, "web");
        web.put("mounts", records(Map.of("type", "volume", "name", "data", "container_path", "/data")));
        web.put("ports", records(Map.of("container_port", "80", "host_port", "8099", "protocol", "tcp")));
        PanelResourceCalls.create(ADMIN, SERVICES, web, operator());

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

        assertThat(refusal(() -> PanelResourceCalls.create(ADMIN, FILES, file(serviceId, "/data/app.conf"),
                operator())).message().key())
            .as("step 5: a file under a volume mount must be refused as shadowed")
            .isEqualTo("file_path_shadowed");
        assertThat(filesOf(serviceId)).as("step 5: and not stored").isEqualTo(0);

        PanelResourceCalls.create(ADMIN, FILES, file(serviceId, " /etc/app.conf "), operator());
        assertThat(filesOf(serviceId)).as("step 5: a file outside the mounts is accepted").isEqualTo(1);
        assertThat((String) Models.get(StackFileModel.class).find()
                .where(StackFileModel.STACK_SERVICE_ID.eq(serviceId)).first().get(StackFileModel.CONTAINER_PATH))
            .as("step 5: its path stored trimmed by the shared container file rule").isEqualTo("/etc/app.conf");
        assertThat(refusal(() -> PanelResourceCalls.create(ADMIN, FILES, file(serviceId, "etc/relative.conf"),
                operator())).message().key())
            .as("step 5: a relative path is the shared rule's refusal").isEqualTo("file_path_absolute");

        // 6. The mirror of the file-side shadow refusal: adding the MOUNT after the file
        //    must be refused exactly like adding the file after the mount.
        assertThat(refusal(() -> PanelResourceCalls.patch(ADMIN, SERVICES, serviceId, Map.of("mounts", records(
                Map.of("type", "volume", "name", "data", "container_path", "/data"),
                Map.of("type", "volume", "name", "etc", "container_path", "/etc"))), operator())).message().key())
            .as("step 6: the shadowing mount is refused").isEqualTo("mount_shadows_file");
        Row edited = Models.get(StackServiceModel.class).findById(serviceId);
        assertThat(edited.getRecords(StackServiceModel.MOUNTS))
            .as("step 6: keeping the original single mount")
            .hasSize(1);

        // 7. Deleting the stack (its delete_stack operation) tears its runtime down on the daemon,
        //    then cascades to its services and their files.
        PanelResourceCalls.delete(ADMIN, STACKS, stackId, operator());

        String network = WorkloadNetworks.networkName(StackInstances.networkHandle("admin-test-stack"));
        assertThat(daemon.callCount("api:DELETE /networks/" + network))
            .as("step 7: the teardown removes the stack's network on the daemon").isEqualTo(1);
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
    // what refuses the duplicate now is the declared-sibling check and the PORT LEDGER's
    // exclusivity. The test is kept as the stack-vs-stack face of that exclusivity; the
    // cross-AUTHORITY faces are the two tests directly below.
    @Test
    void duplicateHostPortAcrossServicesIsRefused() throws Exception {
        int stackId = createStack("admin-stack-dup-port");
        Map<String, Object> first = service(stackId, "web");
        first.put("ports", records(Map.of("container_port", "80", "host_port", "8098", "protocol", "tcp")));
        PanelResourceCalls.create(ADMIN, SERVICES, first, operator());
        assertThat(serviceNamed(stackId, "web")).as("the first publisher of the port is stored").isNotNull();

        Map<String, Object> other = service(stackId, "other");
        other.put("ports", records(Map.of("container_port", "80", "host_port", "8098", "protocol", "tcp")));
        assertThat(refusal(() -> PanelResourceCalls.create(ADMIN, SERVICES, other, operator())).path())
            .as("two services cannot publish the same host port").isEqualTo("ports.0.host_port");
        assertThat(serviceNamed(stackId, "other")).as("and the second is not stored").isNull();
    }

    /**
     * THE decisive cross-authority case: the pre-C3 validator scanned sibling STACK rows
     * only, so a collision with a managed database's port was structurally invisible to
     * it. The assertion is the named conflict AND the resulting state.
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

        Map<String, Object> clash = service(stackId, "dbclash");
        clash.put("ports", records(Map.of("container_port", "80", "host_port", "8210", "protocol", "tcp")));
        Violation refused = refusal(() -> PanelResourceCalls.create(ADMIN, SERVICES, clash, operator()));

        assertThat(serviceNamed(stackId, "dbclash"))
            .as("a stack service cannot seize a port the ledger records for a managed database")
            .isNull();
        // AIDEV-NOTE: which ARBITER answered, pinned. Refusing at all only proves the
        // ledger's unique index, so the friendly field-pathed read is pinned by its own
        // copy key; the backstop's copy is port_held_race.
        assertThat(refused.message().key())
            .as("the friendly pre-write ledger read answered, not the unique-index backstop")
            .isEqualTo("port_held");
        assertThat(String.valueOf(refused.message().args().get("holder")))
            .as("the refusal names the holding database, not a bare status")
            .contains("ledgerdb");
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

        Map<String, Object> clash = service(stackId, "siteclash");
        clash.put("ports", records(Map.of("container_port", "80", "host_port", "8211", "protocol", "tcp")));
        Violation refused = refusal(() -> PanelResourceCalls.create(ADMIN, SERVICES, clash, operator()));

        assertThat(serviceNamed(stackId, "siteclash"))
            .as("a stack service cannot seize a port the ledger records for a docker site")
            .isNull();
        assertThat(String.valueOf(refused.message().args().get("holder")))
            .as("the refusal names the holding site (0.0.0.0 + null protocol folded canonically)")
            .contains("ledgersite");
    }

    @Test
    void unknownDependencyIsRefused() throws Exception {
        int stackId = createStack("admin-stack-dependency");
        Map<String, Object> dependent = service(stackId, "dependent");
        dependent.put("depends_on", records(Map.of("service", "nope", "condition", "started")));
        assertThat(refusal(() -> PanelResourceCalls.create(ADMIN, SERVICES, dependent, operator())).path())
            .as("a dependency naming no sibling service can never be satisfied")
            .isEqualTo("depends_on.0.service");
        assertThat(serviceNamed(stackId, "dependent")).as("and the service is not stored").isNull();
    }

    /**
     * Validation runs on the TRIMMED value and the trimmed value is what is stored:
     * "web2 " passing the pattern check while " web2 " lands raw in the row is how
     * invalid Docker names (and unmatchable sibling checks) used to ship.
     */
    @Test
    void trailingWhitespaceServiceNameIsStoredTrimmed() throws Exception {
        int stackId = createStack("admin-stack-trim");
        PanelResourceCalls.create(ADMIN, SERVICES, service(stackId, "web2 "), operator());
        assertThat(servicesNamed(stackId, "web2"))
            .as("the canonical trimmed name is the stored name")
            .isEqualTo(1);

        assertThat(refusal(() -> PanelResourceCalls.create(ADMIN, SERVICES, service(stackId, " web2"), operator()))
                .message().key())
            .as("a whitespace variant is the same name and must be refused")
            .isEqualTo("service_name_taken");
        assertThat(servicesNamed(stackId, "web2")).as("so one service carries it").isEqualTo(1);
    }

    /** Docker rejects zero-period healthchecks at container create -- exactly the
     *  deploy-time failure form validation exists to prevent. */
    @Test
    void zeroHealthIntervalIsRefused() throws Exception {
        int stackId = createStack("admin-stack-health");
        Map<String, Object> sick = service(stackId, "sick");
        sick.put("health_cmd", "true");
        sick.put("health_interval_seconds", "0");
        assertThat(refusal(() -> PanelResourceCalls.create(ADMIN, SERVICES, sick, operator())).message().key())
            .as("a zero healthcheck interval must fail the form, not the deploy")
            .isEqualTo("health_positive");
        assertThat(serviceNamed(stackId, "sick")).as("and the service is not stored").isNull();
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
        Map<String, Object> capable = service(stackId, "capok");
        capable.put("capabilities", List.of("NET_RAW"));
        PanelResourceCalls.create(ADMIN, SERVICES, capable, operator());
        Row stored = serviceNamed(stackId, "capok");
        assertThat(stored)
            .as("step 1: a declarable capability must not block the save").isNotNull();
        assertThat((List<String>) stored.get(StackServiceModel.CAPABILITIES))
            .as("step 1: the declaration is stored as declared")
            .containsExactly("NET_RAW");

        // 2. THE REFUSAL: an escape never becomes a row at all.
        Map<String, Object> escape = service(stackId, "capbad");
        escape.put("capabilities", List.of("SYS_ADMIN"));
        assertThat(refusal(() -> PanelResourceCalls.create(ADMIN, SERVICES, escape, operator())).fieldName())
            .as("step 2: SYS_ADMIN must fail the form, not the deploy")
            .isEqualTo("capabilities");
        assertThat(serviceNamed(stackId, "capbad")).as("step 2: and no row is stored").isNull();
    }

    // -- fixtures -----------------------------------------------------------------

    private static final String ADMIN = HohenheimSlugs.ADMIN;
    private static final String STACKS = StackParts.SLUG;
    private static final String SERVICES = StackParts.SERVICES_SLUG;
    private static final String FILES = StackParts.FILES_SLUG;

    private static AccessContext operator() {
        return TenantConduits.operator();
    }

    /** Create a stack on the local host through the stack entry's create; each test owns its own. */
    private int createStack(String name) {
        Object key = PanelResourceCalls.create(ADMIN, STACKS, new LinkedHashMap<>(Map.of("name", name,
            "enabled", "true", "server_id", String.valueOf(ServerModel.localServerId()))), operator());
        Row stack = Models.get(StackModel.class).find().where(StackModel.NAME.eq(name)).first();
        assertThat(stack).as("stack %s exists", name).isNotNull();
        assertThat(String.valueOf(key)).as("stack %s is the created key", name)
            .isEqualTo(String.valueOf((Object) stack.get(StackModel.ID)));
        return stack.get(StackModel.ID);
    }

    /** The raw values of an enabled alpine service of one stack, ready for more entries. */
    private static Map<String, Object> service(int stackId, String name) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("stack_id", String.valueOf(stackId));
        values.put("name", name);
        values.put("enabled", "true");
        values.put("image", "alpine:latest");
        values.put("restart_policy", "no");
        return values;
    }

    /** The raw values of a config file of one service. */
    private static Map<String, Object> file(int serviceId, String path) {
        return new LinkedHashMap<>(Map.of("stack_service_id", String.valueOf(serviceId), "container_path", path,
            "content", "secret=1", "mode", "0600"));
    }

    /** Raw sub-record rows as a posted form scopes them: by index. */
    @SafeVarargs
    private static Map<String, Object> records(Map<String, Object>... rows) {
        Map<String, Object> scoped = new LinkedHashMap<>();
        for (int index = 0; index < rows.length; index++) {
            scoped.put(String.valueOf(index), new LinkedHashMap<>(rows[index]));
        }
        return scoped;
    }

    /** The one violation a refused write raised. */
    private static Violation refusal(Runnable write) {
        Violations refused = catchThrowableOfType(write::run, Violations.class);
        assertThat((Throwable) refused).as("the write is refused").isNotNull();
        return refused.all().getFirst();
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
