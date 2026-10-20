package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.cms.server.panel.PartsReads;
import be.elevenways.zenit.common.data.DataItem;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.history.HistoryEntry;
import be.elevenways.zenit.common.orm.history.HistoryText;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.test.support.DatasourceProbe;
import be.elevenways.zenit.test.support.HistoryTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A build reads as a deploy of its app wherever a page of them is titled (the builds list, the activity feed), with
 * its apps read once for the page and named only to a reader who may read them; and an app's live band says when its
 * last deploy finished, from builds and releases read once for the page, only when a healthy verdict asks.
 *
 * AIDEV-NOTE: every measured read counts the statements against the instances, builds and releases tables through
 * the default datasource's probe; each step measures one page of one, then of several, records, and the counts must
 * be equal.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class BuildDeployReadsJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "bdr-";

    @Test
    void aBuildIsADeployOfItsAppTitledOncePerPageAndOnlyForWhoMayReadTheApp(TestReporter reporter) throws Exception {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            List<Row> apps = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                apps.add(instance(cleanup, "app" + i, InstanceModel.STATUS_STOPPED));
            }
            List<Row> builds = new ArrayList<>();
            try (var ignored = HistoryTestSupport.optionalRecording()) {
                for (int i = 0; i < 6; i++) {
                    builds.add(build(cleanup, apps.get(i / 2), "sha" + i, Instant.parse("2026-09-01T10:00:00Z")));
                }
            }
            List<String> deploys = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                deploys.add(PREFIX + "app" + (i / 2) + " deploy");
            }
            BuildOperationModel model = Models.get(BuildOperationModel.class);
            RecordSource<?> source = Objects.requireNonNull(RecordSourceRegistry.INSTANCE.byId(
                BuildOperationModel.MODEL_ID), "the builds' record source");
            List<Object> ids = builds.stream().map(build -> (Object) build.get(BuildOperationModel.ID)).toList();

            // 1. The builds list's page of one build, then of six over three apps: each a deploy of its app, never
            //    its commit nor its id; the apps are read once for either page, and the page costs the same.
            AccessContext operator = TenantConduits.operator();
            Measured one = measure(() -> titlesOf(source.items(rows(source, ids.subList(0, 1)), null, operator)));
            assertThat(one.titles()).as("step 1: one build").containsExactly(deploys.get(0));
            assertThat(one.instanceReads()).as("step 1: one read of the apps").isEqualTo(1);
            AccessContext pageReader = TenantConduits.operator();
            Measured page = measure(() -> titlesOf(source.items(rows(source, ids), null, pageReader)));
            assertThat(page.titles()).as("step 1: six builds, each a deploy of its app")
                .containsExactlyElementsOf(deploys);
            assertThat(page.titles()).as("step 1: never a commit or an id").noneMatch(title -> title.contains("sha")
                || title.contains("#"));
            assertThat(page.instanceReads()).as("step 1: three apps, the same one read").isEqualTo(1);
            assertThat(page.roundTrips()).as("step 1: the page costs what one build costs (" + one.tables() + " / "
                + page.tables() + ")").isEqualTo(one.roundTrips());
            reporter.publishEntry("builds.list", "instances " + one.instanceReads() + "/" + page.instanceReads()
                + ", round trips " + one.roundTrips() + "/" + page.roundTrips() + " (1 build / 6 builds)");

            // 2. The activity feed's page (the Activity list's one History.entries read, then each line's heading)
            //    naming one build, then six: each line names the deploy, the apps read once for the page.
            List<Row> actions = new ArrayList<>();
            for (Row build : builds) {
                actions.add(HistoryTestSupport.actionsOf(model, build.get(BuildOperationModel.ID)).get(0));
            }
            // A first, unmeasured read: the feed's one-time table probes are no line's cost.
            AccessContext warmReader = TenantConduits.operator();
            measure(() -> subjects(actions, warmReader));
            AccessContext feedReader = TenantConduits.operator();
            Measured line = measure(() -> subjects(actions.subList(0, 1), feedReader));
            assertThat(line.titles()).as("step 2: the line names the deploy").containsExactly(deploys.get(0));
            AccessContext pageFeedReader = TenantConduits.operator();
            Measured feed = measure(() -> subjects(actions, pageFeedReader));
            assertThat(feed.titles()).as("step 2: every line names its deploy")
                .containsExactlyInAnyOrderElementsOf(deploys);
            assertThat(feed.instanceReads()).as("step 2: six lines, three apps, what one line reads")
                .isEqualTo(line.instanceReads());
            assertThat(feed.roundTrips()).as("step 2: the feed page costs what one line costs (" + line.tables()
                + " / " + feed.tables() + ")").isEqualTo(line.roundTrips());
            reporter.publishEntry("activity.feed", "instances " + line.instanceReads() + "/" + feed.instanceReads()
                + ", round trips " + line.roundTrips() + "/" + feed.roundTrips() + " (1 line / 6 lines)");

            // 3. A tenant who may read only the first app reads its deploys by name and every other build by its own
            //    fields, never an app it may not read.
            UUID tenantId = ApiSupport.user(PREFIX + "tenant@hohenheim.local", "Build tenant");
            RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID,
                apps.get(0).get(InstanceModel.ID), HohenheimCapabilities.MANAGE, true);
            AccessContext tenant = AccessContext.of(TenantConduits.stubFor(
                new UserPrincipal(tenantId, "Build tenant")));
            List<Row> fresh = new ArrayList<>();
            for (Object id : ids) {
                fresh.add(model.findById(id));
            }
            assertThat(model.resolveDisplayTitles(fresh, null, tenant)).as("step 3: only the readable app is named")
                .containsExactly(deploys.get(0), deploys.get(1), "sha2", "sha3", "sha4", "sha5");

            // 4. A build's own page (its heading, the title its crumb, delete confirmation and toasts share) names the
            //    app for a reader who may read it, and keeps the build's own fields for one who may not and for a
            //    lane with no reader.
            PanelEntry buildsEntry = Objects.requireNonNull(Objects.requireNonNull(
                PanelRegistry.getBySlug(HohenheimSlugs.ADMIN)).entryBySlug(HohenheimSlugs.BUILDS), "the builds");
            assertThat(PartsReads.recordTitleText(buildsEntry, fresh.get(0), TenantConduits.operator()))
                .as("step 4: the operator's heading names the app").isEqualTo(deploys.get(0));
            assertThat(PartsReads.recordTitleText(buildsEntry, fresh.get(2), tenant))
                .as("step 4: an app the tenant may not read is not named").isEqualTo("sha2");
            assertThat(model.getDisplayTitle(fresh.get(0), null, null)).as("step 4: no reader, no app")
                .isEqualTo("sha0");

            // 5. A build whose app is gone reads by its own fields, for the operator too.
            Row gone = instance(cleanup, "gone", InstanceModel.STATUS_STOPPED);
            Row orphan = build(cleanup, gone, "sha-gone", null);
            HardDeletes.row(Models.get(InstanceModel.class), gone);
            assertThat(model.resolveDisplayTitle(model.findById(orphan.get(BuildOperationModel.ID)), null,
                TenantConduits.operator())).as("step 5: the gone app's build").isEqualTo("sha-gone");
        } finally {
            runAll(cleanup);
        }
    }

    @Test
    void theLiveBandSaysWhenTheLastDeployFinishedReadOncePerPage(TestReporter reporter) {
        List<Runnable> cleanup = new ArrayList<>();
        HostFixtures.LocalHostState local = HostFixtures.captureLocal();
        try {
            HostFixtures.makeLocalPlaceable(16L * 1024);
            Instant early = Instant.parse("2026-09-01T10:00:00Z");
            Instant middle = Instant.parse("2026-09-05T10:00:00Z");
            Instant late = Instant.parse("2026-09-10T10:00:00Z");

            // A built app: its older build finished last, and a newer succeeded build never stamped a finish.
            Row built = instance(cleanup, "built", InstanceModel.STATUS_RUNNING);
            build(cleanup, built, "sha-old", late);
            build(cleanup, built, "sha-new", early);
            build(cleanup, built, "sha-unstamped", null);
            // A released app: a release alone.
            Row released = instance(cleanup, "released", InstanceModel.STATUS_RUNNING);
            release(cleanup, released, middle);
            // Both: a build and a later release.
            Row both = instance(cleanup, "both", InstanceModel.STATUS_RUNNING);
            build(cleanup, both, "sha-both", early);
            release(cleanup, both, middle);
            // Never deployed through Hohenheim.
            Row never = instance(cleanup, "never", InstanceModel.STATUS_RUNNING);

            // 1. One running app, then four: each band says when its last deploy finished, from the builds and the
            //    releases read once for the page.
            Health one = health(List.of(built));
            assertThat(one.deployed()).as("step 1: the build that finished last, not the newest id")
                .containsExactly(late);
            assertThat(one.reads()).as("step 1: one read of each").isEqualTo(Map.of("build_operations", 1,
                "release_operations", 1));
            Health four = health(List.of(built, released, both, never));
            assertThat(four.deployed()).as("step 1: build, release, the later of both, nothing")
                .containsExactly(late, middle, middle, null);
            assertThat(four.reads()).as("step 1: four apps, the same reads").isEqualTo(one.reads());
            reporter.publishEntry("instances.health", "deploy reads " + one.reads() + " / " + four.reads()
                + " (1 app / 4 apps)");

            // 2. Stopped apps never ask: no deploy is read for a page without a healthy running app.
            List<Row> stopped = new ArrayList<>();
            for (Row app : List.of(built, released)) {
                app.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
                Models.get(InstanceModel.class).save(app);
                stopped.add(app);
            }
            Health idle = health(stopped);
            assertThat(idle.deployed()).as("step 2: a stopped app's band names no deploy").containsExactly(null, null);
            assertThat(idle.reads()).as("step 2: nothing read").isEqualTo(Map.of("build_operations", 0,
                "release_operations", 0));
        } finally {
            runAll(cleanup);
            local.restore();
        }
    }

    // -- measuring ----------------------------------------------------------------------------------------------------

    /** The titles a page read, its reads of the apps, its round trips and its statements per table they read. */
    private record Measured(List<String> titles, int instanceReads, int roundTrips, Map<String, Integer> tables) {
    }

    private static final Pattern READ_TABLE = Pattern.compile("^SELECT .*? FROM \"?([a-z_]+)\"?");

    /**
     * Measures one read inside a request scope, as every list and feed is read.
     *
     * AIDEV-NOTE: the request scope matters: the stored microcopy catalog validates once per request
     * (TranslationScope.ofRequest), and once per resolved copy outside one, which a "{app} deploy" title per row would
     * count as a read per row that no request makes.
     */
    private static Measured measure(Supplier<List<String>> read) {
        DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Datasources.getDefault());
        List<List<String>> titles = new ArrayList<>();
        try (DatasourceProbe.Armed ignored = probe.recordStatements()) {
            probe.reset();
            TenantConduits.as(null, () -> titles.add(read.get()));
        }
        List<String> answered = titles.get(0);
        Map<String, Integer> tables = new TreeMap<>();
        for (String sql : probe.preparedStatements()) {
            Matcher table = READ_TABLE.matcher(sql);
            tables.merge(table.find() ? table.group(1) : sql.split(" ")[0], 1, Integer::sum);
        }
        return new Measured(answered, probe.queriesAgainst(InstanceModel.MODEL_ID), probe.roundTrips(), tables);
    }

    private record Health(List<Instant> deployed, Map<String, Integer> reads) {
    }

    /** The instances list's health column over {@code instances}, as its page reads it, counting the deploy reads. */
    private static Health health(List<Row> instances) {
        AccessContext operator = TenantConduits.operator();
        DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Datasources.getDefault());
        List<Instant> deployed = new ArrayList<>();
        try (DatasourceProbe.Armed ignored = probe.recordStatements()) {
            probe.reset();
            Function<Row, RecordHealth> verdicts = AppHealth.instances(false).read(instances, operator);
            for (Row instance : instances) {
                RecordHealth.Since since = verdicts.apply(instance).since();
                deployed.add(since == null ? null : since.at());
            }
        }
        Map<String, Integer> reads = new LinkedHashMap<>();
        reads.put("build_operations", probe.queriesAgainst(BuildOperationModel.MODEL_ID));
        reads.put("release_operations", probe.queriesAgainst(ReleaseOperationModel.MODEL_ID));
        return new Health(deployed, reads);
    }

    private static List<Row> rows(RecordSource<?> source, List<Object> ids) {
        return new ArrayList<>(source.resolveReferencedRows(ids, null, TenantConduits.operator()).values());
    }

    private static List<String> titlesOf(List<DataItem> items) {
        return items.stream().map(DataItem::title).toList();
    }

    /** The activity list's page: one History.entries read, then each line's heading names its record. */
    private static List<String> subjects(List<Row> actions, AccessContext reader) {
        List<String> subjects = new ArrayList<>();
        for (HistoryEntry entry : History.entries(actions, reader)) {
            subjects.add(HistoryText.heading(entry, reader).argument());
        }
        return subjects;
    }

    // -- fixtures -----------------------------------------------------------------------------------------------------

    private static Row instance(List<Runnable> cleanup, String name, String status) {
        return row(cleanup, Models.get(InstanceModel.class), Map.of(InstanceModel.NAME.getName(), PREFIX + name,
            InstanceModel.KIND.getName(), "hohenheim:docker_container",
            InstanceModel.SETTINGS.getName(), new LinkedHashMap<>(Map.of("image", "alpine")),
            InstanceModel.STATUS.getName(), status,
            InstanceModel.SERVER_ID.getName(), ServerModel.localServerId()));
    }

    /** A succeeded workspace build of {@code app} from commit {@code sha}, finished at {@code finished}. */
    private static Row build(List<Runnable> cleanup, Row app, String sha, Instant finished) {
        Map<String, Object> values = new LinkedHashMap<>(Map.of(
            BuildOperationModel.FOR_MODEL.getName(), InstanceModel.MODEL_ID.toString(),
            BuildOperationModel.FOR_ID.getName(), app.get(InstanceModel.ID),
            BuildOperationModel.BUILDER_KIND.getName(), BuildOperationModel.KIND_WORKSPACE,
            BuildOperationModel.STATUS.getName(), BuildOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED),
            BuildOperationModel.SOURCE_REF.getName(), sha));
        if (finished != null) {
            values.put(BuildOperationModel.FINISHED_AT.getName(), finished);
        }
        return row(cleanup, Models.get(BuildOperationModel.class), values);
    }

    /** A succeeded release of {@code app}, finished at {@code finished}. */
    private static void release(List<Runnable> cleanup, Row app, Instant finished) {
        row(cleanup, Models.get(ReleaseOperationModel.class), Map.of(
            ReleaseOperationModel.FOR_MODEL.getName(), InstanceModel.MODEL_ID.toString(),
            ReleaseOperationModel.FOR_ID.getName(), app.get(InstanceModel.ID),
            ReleaseOperationModel.KIND.getName(), ReleaseOperationModel.KIND_RELEASE,
            ReleaseOperationModel.STATUS.getName(), ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED),
            ReleaseOperationModel.FINISHED_AT.getName(), finished));
    }

    private static Row row(List<Runnable> cleanup, Model model, Map<String, Object> values) {
        Row row = model.createEmptyRow();
        values.forEach(row::set);
        model.save(row);
        cleanup.add(() -> HardDeletes.row(model, row));
        return row;
    }

    /** Undoes the fixtures, newest first. */
    private static void runAll(List<Runnable> cleanup) {
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            cleanup.get(i).run();
        }
    }
}
