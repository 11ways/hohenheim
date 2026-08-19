package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.hohenheim.server.instance.variable.StringVariableType;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.render.inline.InlineEditResult;
import be.elevenways.zenit.cms.common.render.inline.InlineEditState;
import be.elevenways.zenit.cms.common.render.inline.InlineEditSubmit;
import be.elevenways.zenit.cms.common.resource.Resource;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.LongField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.forms.common.choose.InlineCreateFieldState;
import be.elevenways.zenit.forms.common.render.FormOptionState;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE isolation guard for inline cell editing: every field any resource of either panel
 * declares inline-editable is committed on its own, and the commit moves that column and
 * NOTHING ELSE.
 *
 * AIDEV-NOTE: this exists because the inline lane hands {@code updateRow} a map holding
 * EXACTLY ONE entry, while an override written for the whole-form lane reads its siblings
 * straight off that map. The two failure modes are a refusal naming a field the operator
 * never touched, and -- the one nothing else would catch -- a SILENT blanking of a
 * sibling column, which is what an override doing {@code coerced.put(x, normalize(
 * coerced.get(x)))} does to a value the submit never carried. Both are invisible to a
 * test that only asserts "the edited value changed".
 *
 * The resource list is driven from the PANELS' OWN peer lists, never from a hand-written
 * one, so a declaration added tomorrow is exercised here without touching this file --
 * and a model this file has no fixture for FAILS rather than being skipped, because a
 * silently uncovered resource is exactly what this guard exists to prevent.
 */
class InlineCellIsolationTest extends HohenheimTestBase {

    private static final String PREFIX = "inliso-";

    /**
     * Columns every write moves by design, so they are not evidence of a leak: the ORM
     * stamps {@code updated_at} on every UPDATE.
     */
    static final Set<String> BOOKKEEPING = Set.of("updated_at");

    /**
     * How to move a value whose SHAPE the generic mutation would break, keyed
     * {@code model#column}. Each takes the value the editor is showing and returns a
     * different, still-valid one.
     *
     * AIDEV-NOTE: the generic mutation appends a character, which is right for a label and
     * wrong three ways here -- an octal mode and an A record's address stop parsing, and a
     * DNS name's SUFFIX is its zone, so appending moves the record out of every hosted
     * zone. They are functions rather than constants because one row is visited by BOTH
     * panels: a fixed replacement is a no-op the second time, and "the column moved" would
     * then fail on a write that was never asked for.
     */
    private static final Map<String, UnaryOperator<String>> RAW_OVERRIDES = Map.of(
        "hohenheim:instance_template_file#mode", InlineCellIsolationTest::flipMode,
        "hohenheim:instance_file#mode", InlineCellIsolationTest::flipMode,
        "hohenheim:dns_record#value",
            current -> "192.0.2.88".equals(current) ? "192.0.2.99" : "192.0.2.88",
        // Prepended, never appended: the /manage surface renders this name ABSOLUTE, and
        // its tail is the zone that must keep containing it.
        "hohenheim:dns_record#name", current -> "z" + current);

    /** @return the other of the two octal modes this test alternates between */
    private static String flipMode(String current) {
        return "0600".equals(current) ? "0644" : "0600";
    }

    /** One row per model, created on first use and reused by every panel that reaches it. */
    private static final Map<Identifier, Integer> ROWS = new LinkedHashMap<>();

    /**
     * Step 1-4: walk every inline declaration of both panels, commit each field alone
     * through the real cell endpoint, and prove the write is confined to that column.
     */
    @Test
    void everyInlineCellWritesExactlyItsOwnColumnAndNoOther() throws Exception {
        List<Target> targets = declaredTargets();

        // 1. The wave shipped inline editing on a meaningful number of surfaces; a walk
        //    that silently found nothing would pass every later assertion vacuously.
        assertThat(targets).as("step 1: both panels expose their inline-editable resources")
            .hasSizeGreaterThan(10);

        Set<String> covered = new TreeSet<>();

        for (Target target : targets) {
            Model model = target.resource().model();
            String who = target.panel() + "/" + target.resource().slug();

            // 2. Every declaring resource must be reachable with a real row. A model with
            //    no fixture here is an UNCOVERED declaration, which is a failure of this
            //    guard rather than a reason to move on.
            Integer id = rowFor(model);
            assertThat(id).as("step 2: " + who + " has a fixture row for "
                    + model.getModelId() + "; add one to this test when declaring inline"
                    + " editing on a new model").isNotNull();

            for (Field<?, ?> field : target.resource().inlineEditableFields()) {
                String name = field.getName();
                Map<String, Object> before = storedValues(model, id);

                InlineEditState state = cellState(target, id);
                assertThat(state.editableFields())
                    .as("step 3: " + who + " offers the '" + name + "' cell it declared")
                    .contains(name);

                // The value the EDITOR is showing, not the raw column: a resource whose
                // valuesFromRow reshapes the value (the /manage DNS name renders absolute
                // while the column stores it relative to its zone) is edited in that shape.
                Object shown = state.values().containsKey(name)
                    ? state.values().get(name) : before.get(name);
                Object raw = mutatedRaw(model, state, field, shown);
                InlineEditResult result = cellSubmit(target, id,
                    new InlineEditSubmit(name, raw, state.snapshot()));
                assertThat(result.succeeded())
                    .as("step 3: " + who + " committed '" + name + "' = " + raw + ": "
                        + result.violations())
                    .isTrue();

                Map<String, Object> after = storedValues(model, id);

                // 4. The named column moved. Not "equals the submitted raw value": a
                //    resource is allowed to CANONICALISE what it stores (a trimmed path, a
                //    zone-relative owner name), and that is the write landing, not a leak.
                assertThat(String.valueOf(after.get(name)))
                    .as("step 4: " + who + " wrote '" + name + "' (submitted " + raw + ")")
                    .isNotEqualTo(String.valueOf(before.get(name)));

                // ...and no other stored column did. This is the whole point: a sibling
                // that moved was moved by an updateRow reading a key the submit never
                // carried, and nothing in the response would have said so.
                for (Map.Entry<String, Object> column : before.entrySet()) {
                    if (column.getKey().equals(name) || BOOKKEEPING.contains(column.getKey())) {
                        continue;
                    }
                    assertThat(String.valueOf(after.get(column.getKey())))
                        .as("step 4: " + who + " left '" + column.getKey()
                            + "' alone while writing '" + name + "'")
                        .isEqualTo(String.valueOf(column.getValue()));
                }
                covered.add(who + "#" + name);
            }
        }

        assertThat(covered).as("step 4: every declared cell was exercised")
            .hasSizeGreaterThan(20);
    }

    // --- the walk ---------------------------------------------------------------------

    /** One (panel, resource) pair per resource that declares an inline cell. */
    private record Target(String panel, Resource<?> resource) {}

    private static List<Target> declaredTargets() {
        List<Target> targets = new ArrayList<>();
        for (String slug : List.of("admin", ManagePanel.SLUG)) {
            Panel panel = PanelRegistry.getBySlug(slug);
            assertThat(panel).as("the '" + slug + "' panel is registered").isNotNull();
            for (PanelPeer peer : panel.peers()) {
                if (peer instanceof Resource<?> resource
                        && !resource.inlineEditableFields().isEmpty()) {
                    targets.add(new Target(slug, resource));
                }
            }
        }
        return targets;
    }

    /**
     * Every stored column of the row, as the database holds it.
     *
     * AIDEV-NOTE: table-stored and localized fields are skipped rather than read -- the
     * first throws on a plain {@code row.get}, the second needs a locale chain. No
     * resource in this wave declares either as inline-editable (the framework refuses a
     * localized one at registration), so the skip cannot hide a leak they would carry.
     */
    static Map<String, Object> storedValues(Model model, int id) {
        Row row = model.findById(id);
        assertThat(row).as("the fixture row of " + model.getModelId() + " still exists").isNotNull();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Field<?, ?>> entry : model.getSchema().getFields().entrySet()) {
            Field<?, ?> field = entry.getValue();
            if (field.isLocalized()
                    || (field instanceof SchemaField schema && schema.isTableStored())) {
                continue;
            }
            values.put(entry.getKey(), row.get(field));
        }
        return values;
    }

    /** A value the field will accept that is different from what is stored now. */
    private static Object mutatedRaw(Model model, InlineEditState state, Field<?, ?> field,
                                     Object current) {
        String name = field.getName();
        UnaryOperator<String> shaped = RAW_OVERRIDES.get(model.getModelId() + "#" + name);
        if (shaped != null) {
            return shaped.apply(String.valueOf(current));
        }
        for (InlineCreateFieldState rendered : state.fields()) {
            if (!rendered.name().equals(name) || rendered.options().isEmpty()) {
                continue;
            }
            for (FormOptionState option : rendered.options()) {
                if (option.value() != null && !String.valueOf(option.value()).equals(
                        String.valueOf(current))) {
                    return String.valueOf(option.value());
                }
            }
        }
        if (field instanceof BooleanField) {
            return String.valueOf(!Boolean.TRUE.equals(current));
        }
        if (field instanceof IntegerField || field instanceof LongField) {
            long base = current instanceof Number number ? number.longValue() : 0;
            return String.valueOf(base + 1);
        }
        return current == null || String.valueOf(current).isEmpty()
            ? PREFIX + "v" : current + "x";
    }

    // --- transport --------------------------------------------------------------------

    private InlineEditState cellState(Target target, int id) throws Exception {
        String path = "/" + target.panel() + "/" + target.resource().slug() + "/" + id
            + "/cell-state";
        HttpResponse<String> response = httpGet(path, sessionToken);
        assertThat(response.statusCode()).as("the cell state of " + path + " answered")
            .isEqualTo(200);
        return (InlineEditState) Zenit.DRY.parse(response.body());
    }

    private InlineEditResult cellSubmit(Target target, int id, InlineEditSubmit submit)
            throws Exception {
        String path = "/" + target.panel() + "/" + target.resource().slug() + "/" + id + "/cell";
        HttpResponse<String> response = httpPostDry(path, Zenit.DRY.stringify(submit),
            sessionToken, csrfToken);
        assertThat(response.statusCode()).as("the cell lane of " + path + " answered")
            .isEqualTo(200);
        return (InlineEditResult) Zenit.DRY.parse(response.body());
    }

    // --- fixtures ---------------------------------------------------------------------

    /** @return the shared fixture row id of this model, or null when none is declared */
    private static Integer rowFor(Model model) {
        Identifier id = model.getModelId();
        if (ROWS.containsKey(id)) {
            return ROWS.get(id);
        }
        Supplier<Integer> builder = FIXTURES.get(id);
        Integer rowId = builder == null ? null : builder.get();
        ROWS.put(id, rowId);
        return rowId;
    }

    /**
     * How this test makes ONE row of each model that declares inline editing.
     *
     * AIDEV-NOTE: keyed by MODEL, not by resource, so the admin resource and its /manage
     * mirror exercise the SAME row -- which is what makes a mirror that inherits a
     * declaration its narrowed form cannot serve fail here as well as at registration.
     */
    private static final Map<Identifier, Supplier<Integer>> FIXTURES = Map.ofEntries(
        Map.entry(InstanceSnapshotModel.MODEL_ID, InlineCellIsolationTest::snapshot),
        Map.entry(InstanceTemplateVariableModel.MODEL_ID, InlineCellIsolationTest::templateVariable),
        Map.entry(InstanceQuotaModel.MODEL_ID, InlineCellIsolationTest::quota),
        Map.entry(SiteDomainModel.MODEL_ID, InlineCellIsolationTest::domain),
        Map.entry(AccessListModel.MODEL_ID, InlineCellIsolationTest::accessList),
        Map.entry(ProjectModel.MODEL_ID, InlineCellIsolationTest::project),
        Map.entry(EnvironmentModel.MODEL_ID, InlineCellIsolationTest::environment),
        Map.entry(InstanceVariableModel.MODEL_ID, InlineCellIsolationTest::environmentVariable),
        Map.entry(InstanceTemplateFileModel.MODEL_ID, InlineCellIsolationTest::templateFile),
        Map.entry(InstanceFileModel.MODEL_ID, InlineCellIsolationTest::instanceFile),
        Map.entry(InstanceDatabaseModel.MODEL_ID, InlineCellIsolationTest::instanceDatabase),
        Map.entry(InstanceModel.MODEL_ID, InlineCellIsolationTest::instance),
        Map.entry(StackModel.MODEL_ID, InlineCellIsolationTest::stack),
        Map.entry(InstanceTemplateModel.MODEL_ID, InlineCellIsolationTest::template),
        Map.entry(GitProviderModel.MODEL_ID, InlineCellIsolationTest::gitProvider),
        Map.entry(NotificationChannelModel.MODEL_ID, InlineCellIsolationTest::notificationChannel),
        Map.entry(DnsPeerModel.MODEL_ID, InlineCellIsolationTest::dnsPeer),
        Map.entry(DnsRecordModel.MODEL_ID, InlineCellIsolationTest::dnsRecord));

    private static int instance() {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, PREFIX + "instance");
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        // AIDEV-NOTE: the host is SET here, and that is not decoration. InstanceCapacity
        // only stamps CAPACITY_MB when the row has one, so a hostless fixture is created
        // with a null stamp and then normalised to 0 by the FIRST save of any kind -- the
        // guard reads that as a leaked column, correctly, because it cannot tell a
        // one-time backfill from one. A real instance always carries a host: the create
        // form defaults it to the local daemon.
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static int snapshot() {
        Model snapshots = Models.get(InstanceSnapshotModel.class);
        Row row = snapshots.createEmptyRow();
        row.set(InstanceSnapshotModel.INSTANCE_ID, rowFor(Models.get(InstanceModel.class)));
        row.set(InstanceSnapshotModel.STATUS, InstanceSnapshotModel.STATUS_COMPLETE);
        row.set(InstanceSnapshotModel.NOTE, PREFIX + "note");
        row.set(InstanceSnapshotModel.TOTAL_BYTES, 4096L);
        row.set(InstanceSnapshotModel.CREATED_AT, Instant.now());
        snapshots.save(row);
        return row.get(InstanceSnapshotModel.ID);
    }

    private static int template() {
        Model templates = Models.get(InstanceTemplateModel.class);
        Row row = templates.createEmptyRow();
        row.set(InstanceTemplateModel.NAME, PREFIX + "template");
        row.set(InstanceTemplateModel.DESCRIPTION, "isolation fixture");
        row.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        row.set(InstanceTemplateModel.VERSION, 1);
        row.set(InstanceTemplateModel.READINESS_LINE, "ready");
        row.set(InstanceTemplateModel.STOP_COMMAND, "stop");
        templates.save(row);
        return row.get(InstanceTemplateModel.ID);
    }

    private static int templateVariable() {
        Model variables = Models.get(InstanceTemplateVariableModel.class);
        Row row = variables.createEmptyRow();
        row.set(InstanceTemplateVariableModel.TEMPLATE_ID,
            rowFor(Models.get(InstanceTemplateModel.class)));
        row.set(InstanceTemplateVariableModel.KEY, "INLISO_KEY");
        row.set(InstanceTemplateVariableModel.LABEL, "Isolation");
        row.set(InstanceTemplateVariableModel.DESCRIPTION, "fixture");
        row.set(InstanceTemplateVariableModel.TYPE, StringVariableType.ID.toString());
        row.set(InstanceTemplateVariableModel.REQUIRED, false);
        row.set(InstanceTemplateVariableModel.DEFAULT_VALUE, "zero");
        variables.save(row);
        return row.get(InstanceTemplateVariableModel.ID);
    }

    private static int templateFile() {
        Model files = Models.get(InstanceTemplateFileModel.class);
        Row row = files.createEmptyRow();
        row.set(InstanceTemplateFileModel.TEMPLATE_ID,
            rowFor(Models.get(InstanceTemplateModel.class)));
        row.set(InstanceTemplateFileModel.CONTAINER_PATH, "/etc/inliso.conf");
        row.set(InstanceTemplateFileModel.CONTENT, "key = value");
        row.set(InstanceTemplateFileModel.MODE, "0644");
        files.save(row);
        return row.get(InstanceTemplateFileModel.ID);
    }

    private static int instanceFile() {
        Model files = Models.get(InstanceFileModel.class);
        Row row = files.createEmptyRow();
        row.set(InstanceFileModel.INSTANCE_ID, rowFor(Models.get(InstanceModel.class)));
        row.set(InstanceFileModel.CONTAINER_PATH, "/etc/inliso-instance.conf");
        row.set(InstanceFileModel.CONTENT, "key = value");
        row.set(InstanceFileModel.MODE, "0644");
        files.save(row);
        return row.get(InstanceFileModel.ID);
    }

    private static int instanceDatabase() {
        Model databases = Models.get(DatabaseModel.class);
        Row database = databases.createEmptyRow();
        database.set(DatabaseModel.NAME, PREFIX + "db");
        database.set(DatabaseModel.ENGINE, "postgres");
        database.set(DatabaseModel.DB_NAME, PREFIX + "db");
        database.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        databases.save(database);

        Model links = Models.get(InstanceDatabaseModel.class);
        Row row = links.createEmptyRow();
        row.set(InstanceDatabaseModel.INSTANCE_ID, rowFor(Models.get(InstanceModel.class)));
        row.set(InstanceDatabaseModel.DATABASE_ID, database.get(DatabaseModel.ID));
        row.set(InstanceDatabaseModel.ENV_PREFIX, InstanceDatabaseModel.DEFAULT_PREFIX);
        links.save(row);
        return row.get(InstanceDatabaseModel.ID);
    }

    private static int quota() {
        Model quotas = Models.get(InstanceQuotaModel.class);
        Row row = quotas.createEmptyRow();
        row.set(InstanceQuotaModel.SUBJECTS, "user:" + PREFIX + "subject");
        row.set(InstanceQuotaModel.MAX_INSTANCES, 3);
        row.set(InstanceQuotaModel.MAX_MEMORY_MB, 2048);
        row.set(InstanceQuotaModel.MAX_DISK_GB, 20);
        row.set(InstanceQuotaModel.MAX_NICS, 2);
        row.set(InstanceQuotaModel.MAX_SITES, 5);
        row.set(InstanceQuotaModel.MAX_DATABASES, 4);
        quotas.save(row);
        return row.get(InstanceQuotaModel.ID);
    }

    private static int domain() {
        Model sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, PREFIX + "site");
        site.set(SiteModel.SLUG, PREFIX + "site");
        site.set(SiteModel.SITE_TYPE, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp/" + PREFIX + "site"));
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.ENABLED, true);
        sites.save(site);

        Model domains = Models.get(SiteDomainModel.class);
        Row row = domains.createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        row.set(SiteDomainModel.HOSTNAME, PREFIX + "domain.test");
        row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        row.set(SiteDomainModel.FORCE_SSL, false);
        row.set(SiteDomainModel.HSTS_ENABLED, false);
        row.set(SiteDomainModel.HSTS_SUBDOMAINS, false);
        row.set(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT, false);
        domains.save(row);
        return row.get(SiteDomainModel.ID);
    }

    private static int accessList() {
        Model lists = Models.get(AccessListModel.class);
        Row row = lists.createEmptyRow();
        row.set(AccessListModel.NAME, PREFIX + "list");
        row.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ANY);
        lists.save(row);
        return row.get(AccessListModel.ID);
    }

    private static int project() {
        Model projects = Models.get(ProjectModel.class);
        Row row = projects.createEmptyRow();
        row.set(ProjectModel.NAME, PREFIX + "project");
        row.set(ProjectModel.DESCRIPTION, "isolation fixture");
        projects.save(row);
        return row.get(ProjectModel.ID);
    }

    private static int environment() {
        Model environments = Models.get(EnvironmentModel.class);
        Row row = environments.createEmptyRow();
        row.set(EnvironmentModel.PROJECT_ID, rowFor(Models.get(ProjectModel.class)));
        row.set(EnvironmentModel.NAME, PREFIX + "environment");
        row.set(EnvironmentModel.DESCRIPTION, "isolation fixture");
        environments.save(row);
        return row.get(EnvironmentModel.ID);
    }

    private static int environmentVariable() {
        Model variables = Models.get(InstanceVariableModel.class);
        Row row = variables.createEmptyRow();
        row.set(InstanceVariableModel.ENVIRONMENT_ID, rowFor(Models.get(EnvironmentModel.class)));
        row.set(InstanceVariableModel.KEY, "INLISO_VAR");
        row.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_PLAIN);
        row.set(InstanceVariableModel.PLAIN_VALUE, "before");
        variables.save(row);
        return row.get(InstanceVariableModel.ID);
    }

    private static int stack() {
        Model stacks = Models.get(StackModel.class);
        Row row = stacks.createEmptyRow();
        row.set(StackModel.NAME, PREFIX + "stack");
        row.set(StackModel.DESCRIPTION, "isolation fixture");
        row.set(StackModel.ENABLED, false);
        stacks.save(row);
        return row.get(StackModel.ID);
    }

    private static int gitProvider() {
        Model providers = Models.get(GitProviderModel.class);
        Row row = providers.createEmptyRow();
        row.set(GitProviderModel.NAME, PREFIX + "provider");
        row.set(GitProviderModel.KIND, GitProviderModel.KIND_GITHUB);
        row.set(GitProviderModel.BASE_URL, "https://git.example.test");
        providers.save(row);
        return row.get(GitProviderModel.ID);
    }

    private static int notificationChannel() {
        Model channels = Models.get(NotificationChannelModel.class);
        Row row = channels.createEmptyRow();
        row.set(NotificationChannelModel.NAME, PREFIX + "channel");
        row.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
        row.set(NotificationChannelModel.FORMAT, NotificationChannelModel.FORMAT_SLACK);
        row.set(NotificationChannelModel.URL, "https://hooks.example.test/inliso");
        row.set(NotificationChannelModel.EVENTS, List.of());
        channels.save(row);
        return row.get(NotificationChannelModel.ID);
    }

    private static int dnsPeer() {
        Model peers = Models.get(DnsPeerModel.class);
        Row row = peers.createEmptyRow();
        row.set(DnsPeerModel.NAME, PREFIX + "peer");
        row.set(DnsPeerModel.TRANSFER_HOST, "ns.example.test");
        row.set(DnsPeerModel.TRANSFER_PORT, 53);
        row.set(DnsPeerModel.TSIG_KEY_NAME, "inliso-key");
        row.set(DnsPeerModel.TSIG_ALGORITHM, "hmac-sha256");
        row.set(DnsPeerModel.ENABLED, false);
        peers.save(row);
        return row.get(DnsPeerModel.ID);
    }

    private static int dnsRecord() {
        Model zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, "inliso.test");
        zone.set(DnsZoneModel.SOA_PRIMARY_NS, "ns1.inliso.test");
        zone.set(DnsZoneModel.SOA_CONTACT, "hostmaster@inliso.test");
        zone.set(DnsZoneModel.ROLE, DnsZoneModel.ROLE_PRIMARY);
        zone.set(DnsZoneModel.ENABLED, true);
        zones.save(zone);

        Model records = Models.get(DnsRecordModel.class);
        Row row = records.createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zone.get(DnsZoneModel.ID));
        row.set(DnsRecordModel.NAME, "inliso");
        row.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_A);
        row.set(DnsRecordModel.VALUE, "192.0.2.77");
        row.set(DnsRecordModel.TTL, 300);
        row.set(DnsRecordModel.ENABLED, true);
        records.save(row);
        return row.get(DnsRecordModel.ID);
    }
}
