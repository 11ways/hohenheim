package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.server.quota.OwnerBudget;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.cms.AccessListParts;
import be.elevenways.hohenheim.server.cms.SiteWrites;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.source.GitCheckout;
import be.elevenways.hohenheim.server.util.FileTrees;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.panel.PartsReads;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.activity.ActivityAction;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.orm.history.ChangeOp;
import be.elevenways.zenit.common.orm.history.HistoryRefusalReason;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Sites and instances soft-delete through zenit's SoftDeleteBehaviour: a trashed record leaves
 * every default read, keeps its unique slug, comes back through the behaviour's restore with its
 * quota re-booked, and only a forceDelete removes it physically.
 *
 * AIDEV-NOTE: the behaviour replaced a hand-rolled soft delete (a deleted_at stamp plus ~90
 * hand-spelled {@code deleted_at IS NULL} filters). These journeys pin what the switch must not
 * move -- the quota transition, the delete activity, the slug uniqueness -- and the two readings
 * it corrected: the access-list delete dialog named trashed sites as "gated", and an application
 * destroy was recorded as a bare "update" instead of the destroy it is.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class SoftDeleteJourneyTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.standalone();

    private static final String PREFIX = "softdel-";
    private static final String SITE_BUCKET = OwnerBudget.SITES.bucketOf("");

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = APP.database();
    }

    @Test
    void aTrashedSiteIsHiddenKeepsItsSlugRestoresAndPurges() {
        Db.run(datasource, () -> {
            SiteModel sites = Models.get(SiteModel.class);
            // The admin panel's own site entry, as its record page reads it.
            @SuppressWarnings("unchecked")
            Panel panel = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN));
            PanelResource<Row> resource = (PanelResource<Row>) panel.entryBySlug(HohenheimSlugs.SITES);
            DeleteConfirmation<Row> lists = AccessListParts.admin().deleteConfirmation();

            // 1. A live site gated by an access list: charged one site slot, and the list's
            //    delete dialog names it as a site the delete would open.
            int listId = accessList(PREFIX + "list");
            long beforeCreate = Quotas.usedOf(SITE_BUCKET);
            int siteId = site(PREFIX + "journey", listId);
            assertThat(Quotas.usedOf(SITE_BUCKET))
                .as("step 1: a live site spends a slot").isEqualTo(beforeCreate + 1);
            assertThat(gatingKey(lists, listId))
                .as("step 1: the list's dialog names the live site it gates")
                .isEqualTo("delete_confirm_gating");

            // 2. The admin delete trashes it through the behaviour: gone from every default
            //    read (find, count, the record page), still stored, the slot handed back and
            //    the delete recorded as the soft delete it is.
            long beforeTrash = Quotas.usedOf(SITE_BUCKET);
            PanelResourceCalls.delete(HohenheimSlugs.ADMIN, HohenheimSlugs.SITES, siteId, TenantConduits.operator());
            assertThat(sites.findById(siteId))
                .as("step 2: a default find no longer sees the trashed site").isNull();
            assertThat(sites.find().where(SiteModel.ID.eq(siteId)).count())
                .as("step 2: nor does a default count").isZero();
            Row inTrash = PartsReads.loadRow(panel, resource, siteId, TenantConduits.operator());
            assertThat(inTrash).as("step 2: the admin record page opens it from the Trash").isNotNull();
            assertThat(resource.isArchived(inTrash)).as("step 2: as an archived, read-only record").isTrue();
            Row trashed = StoredRows.byId(sites, siteId);
            assertThat(trashed).as("step 2: the row is still stored").isNotNull();
            assertThat((Object) trashed.get(SiteModel.DELETED_AT))
                .as("step 2: stamped trashed").isNotNull();
            assertThat(Quotas.usedOf(SITE_BUCKET))
                .as("step 2: the trash transition handed the slot back").isEqualTo(beforeTrash - 1);
            List<RecordedChanges.Recorded> deletes = activityFor(Models.get(SiteModel.class), siteId,
                ZenitActivityAction.TRASHED);
            assertThat(deletes).as("step 2: the trash is recorded as one trashed fact").hasSize(1);
            assertThat(deletes.get(0).op())
                .as("step 2: on the soft delete's own update").isEqualTo(ChangeOp.UPDATE);

            // 3. A set-based update of a site's content is refused outright: every content field is a revision
            //    snapshot field, so no revision would hold the value and each record must be saved on its own.
            //    What a set-based update may still assign (the reporting key, kept out of revisions and history)
            //    is scoped like a find: neither reaches the trashed row.
            DomainRefusal refused = catchThrowableOfType(DomainRefusal.class, () -> sites.find()
                .where(SiteModel.ID.eq(siteId)).assign(SiteModel.DESCRIPTION, "touched").bypassBehaviours()
                .updateAll());
            assertThat(refused).as("step 3: a set-based update of a snapshot field is refused").isNotNull();
            assertThat(refused.reason())
                .as("step 3: as a snapshot assignment").isEqualTo(HistoryRefusalReason.SNAPSHOT_ASSIGNMENT);
            assertThat(StoredRows.byId(sites, siteId).get(SiteModel.DESCRIPTION))
                .as("step 3: and the refused update wrote nothing").isNotEqualTo("touched");
            assertThat(sites.find().where(SiteModel.ID.eq(siteId)).count())
                .as("step 3: a find matches no trashed row").isZero();
            assertThat(sites.find().where(SiteModel.ID.eq(siteId)).assign(SiteModel.SECURITY_REPORT_TOKEN, null)
                    .bypassBehaviours().updateAll())
                .as("step 3: a set-based update it allows matches no trashed row either").isZero();

            // 4. BUG FIXED: a trashed site gates nothing, so the list's dialog stops naming it.
            assertThat(gatingKey(lists, listId))
                .as("step 4: the list's dialog no longer names the trashed site")
                .isEqualTo("delete_confirm_named");

            // 5. Deleting it again is a no-op, and its slug stays taken: the unique index
            //    sees the trashed row, so a second site with that slug is refused.
            assertThat(sites.delete(siteId))
                .as("step 5: a trashed row is invisible to a second delete").isFalse();
            assertThat(catchThrowable(() -> site(PREFIX + "journey", null)))
                .as("step 5: the trashed site still owns its slug").isNotNull();
            assertThat(sites.find().withTrashed().where(SiteModel.SLUG.eq(PREFIX + "journey")).count())
                .as("step 5: and exactly one row carries it").isEqualTo(1);

            // 6. The Trash's restore (the admin site entry's placed restore operation, core's archive restore) brings
            //    it back: visible, the slot re-booked, the restore on the record's history, and the list gating it
            //    again.
            long beforeRestore = Quotas.usedOf(SITE_BUCKET);
            PanelResourceCalls.invoke(HohenheimSlugs.ADMIN, HohenheimSlugs.SITES, SiteWrites.RESTORE.id(), siteId,
                null, TenantConduits.operator());
            Row restored = sites.findById(siteId);
            assertThat(restored).as("step 6: a default find sees the restored site").isNotNull();
            assertThat((Object) restored.get(SiteModel.DELETED_AT))
                .as("step 6: no longer stamped").isNull();
            assertThat(Quotas.usedOf(SITE_BUCKET))
                .as("step 6: the restore re-booked its slot").isEqualTo(beforeRestore + 1);
            assertThat(activityFor(Models.get(SiteModel.class), siteId, ZenitActivityAction.UNTRASHED))
                .as("step 6: the restore is recorded as one").hasSize(1);
            assertThat(gatingKey(lists, listId))
                .as("step 6: the restored site is gated by the list again")
                .isEqualTo("delete_confirm_gating");

            // 7. PURGE: trash it again, then forceDelete removes the row physically -- and a
            //    purge of a trashed row releases nothing twice.
            assertThat(sites.delete(siteId)).as("step 7: trashed again").isTrue();
            long beforePurge = Quotas.usedOf(SITE_BUCKET);
            assertThat(SiteModel.SOFT_DELETE.forceDelete(StoredRows.byId(sites, siteId)))
                .as("step 7: forceDelete removes the trashed row").isTrue();
            assertThat(StoredRows.byId(sites, siteId))
                .as("step 7: nothing is stored any more").isNull();
            assertThat(Quotas.usedOf(SITE_BUCKET))
                .as("step 7: the purge of a trashed row moves no quota").isEqualTo(beforePurge);

            Models.get(AccessListModel.class).delete(listId);
        });
    }

    @Test
    void destroyingAnApplicationRecordsTheDestroyAndRemovesItsCheckout() throws Exception {
        Path dataRoot = Files.createTempDirectory("softdel-data");
        String savedDataPath = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, dataRoot.toString());
        try {
            Db.run(datasource, () -> destroyJourney(dataRoot));
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, savedDataPath);
            FileTrees.deleteQuietly(dataRoot);
        }
    }

    private static void destroyJourney(Path dataRoot) {
        Model instances = Models.get(InstanceModel.class);
        int applicationId = application(PREFIX + "application");
        int neighbourId = application(PREFIX + "neighbour");
        try {
            Path checkout = checkout(applicationId);
            Path neighbour = checkout(neighbourId);

            // 1. An application has no container, so its destroy is the release lane
            //    followed by the trash write.
            new InstanceService().destroy(applicationId);
            assertThat(instances.findById(applicationId))
                .as("step 1: the destroyed application leaves the default reads").isNull();
            assertThat((Object) StoredRows.byId(instances, applicationId).get(InstanceModel.DELETED_AT))
                .as("step 1: and is kept trashed").isNotNull();

            // 2. BUG FIXED: the application lane saved deleted_at bare, recorded as an
            //    "update"; it now takes the one trash write every destroy shares. (The destroy's fenced status
            //    stamps, destroying and then stopped, are status writes like every operation's, not the trash.)
            List<RecordedChanges.Recorded> deletes = activityFor(Models.get(InstanceModel.class), applicationId,
                ZenitActivityAction.TRASHED);
            assertThat(deletes).as("step 2: the destroy is recorded as one trash").hasSize(1);
            assertThat(RecordedChanges.of(Models.get(InstanceModel.class), applicationId, ChangeOp.UPDATE).stream()
                    .filter(recorded -> recorded.facts().isEmpty()
                        && recorded.delta().containsKey(InstanceModel.DELETED_AT.getName()))
                    .toList())
                .as("step 2: and never as a bare update").isEmpty();

            // 3. BUG FIXED: the plain (soft) delete removes the git checkout, which used to stay on the
            //    controller forever; another application's checkout, and so the shared folder, stay.
            assertThat(checkout).as("step 3: the destroyed application's checkout is gone").doesNotExist();
            assertThat(neighbour).as("step 3: another application's checkout is untouched").isDirectory();

            // 4. Destroying it again refuses (there is no live record) and writes nothing.
            assertThat(catchThrowable(() -> new InstanceService().destroy(applicationId)))
                .as("step 4: a trashed record is not destroyed twice")
                .isInstanceOf(Violations.class);
            assertThat(activityFor(Models.get(InstanceModel.class), applicationId,
                    ZenitActivityAction.TRASHED))
                .as("step 4: and nothing more is recorded").hasSize(1);

            // 5. Delete with data on a record an older controller trashed with its checkout left behind
            //    removes that checkout too.
            checkout(applicationId);
            new InstanceService().destroyWithData(applicationId);
            assertThat(checkout).as("step 5: Delete with data removes a leftover checkout").doesNotExist();

            // 6. Delete with data on a live application removes its checkout, and with the last one gone the
            //    empty checkouts folder goes too; the data directory itself stays.
            new InstanceService().destroyWithData(neighbourId);
            assertThat(neighbour).as("step 6: Delete with data removes the checkout").doesNotExist();
            assertThat(dataRoot.resolve("checkouts")).as("step 6: the empty checkouts folder is pruned")
                .doesNotExist();
            assertThat(dataRoot).as("step 6: the data directory stays").isDirectory();
        } finally {
            HardDeletes.byId(instances, applicationId);
            HardDeletes.byId(instances, neighbourId);
        }
    }

    // -- fixtures ---------------------------------------------------------------

    private static int site(String slug, Integer accessListId) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp/" + slug));
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.ENABLED, false);
        row.set(SiteModel.ACCESS_LIST_ID, accessListId);
        Models.get(SiteModel.class).save(row);
        return row.get(SiteModel.ID);
    }

    private static int accessList(String name) {
        Row row = Models.get(AccessListModel.class).createEmptyRow();
        row.set(AccessListModel.NAME, name);
        Models.get(AccessListModel.class).save(row);
        return row.get(AccessListModel.ID);
    }

    /** A release-managed application with no release history (the DeployControlSettleTest shape). */
    private static int application(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    /** A checkout as a deploy leaves it: the owner's directory holding a clone. */
    private static Path checkout(int applicationId) {
        Path directory = GitCheckout.directoryFor(InstanceModel.MODEL_ID, applicationId).toPath();
        try {
            Files.createDirectories(directory.resolve(".git"));
            Files.writeString(directory.resolve("index.html"), "<h1>checkout</h1>");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return directory;
    }

    private static String gatingKey(DeleteConfirmation<Row> lists, int listId) {
        PanelRequest request = new PanelRequest(Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN)),
            TenantConduits.stubFor(null), AccessContext.anonymous(), null);
        return lists.forRow(Models.get(AccessListModel.class).findById(listId), request).body().key();
    }

    private static List<RecordedChanges.Recorded> activityFor(Model model, int recordId, ActivityAction kind) {
        return RecordedChanges.withFact(model, recordId, kind);
    }
}
