package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.activity.ActivityRecordCell;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.AdminActivityResource;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.ActivityResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.Accountability;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin activity log as an operator reads it: background noise out of the way but
 * reachable, verbs as words, and the record a row names both linked and filterable.
 *
 * AIDEV-NOTE: the seeded rows are written straight into zenit_activity rather than through
 * ActivityLog, because the point is the READING surface -- the origin, verb and model of
 * each row have to be chosen, and ActivityLog derives all three from whatever lane calls it.
 */
class AdminActivityListTest extends HohenheimTestBase {

    private static final String OPERATOR_TITLE = "hh-activity-operator-subject";
    private static final String BACKGROUND_TITLE = "hh-activity-background-subject";
    private static final String UNLINKABLE_TITLE = "hh-activity-unlinkable-subject";
    private static final String NARROWED_TITLE = "hh-activity-narrowed-subject";

    /** A verb no catalog declares anywhere, so its cell must fall open to this text. */
    private static final String UNREGISTERED_VERB = "hh_unregistered_verb";

    private static final String OPERATOR_RECORD_ID = "4242";
    private static final String BACKGROUND_RECORD_ID = "4243";
    private static final String UNLINKABLE_RECORD_ID = "4244";
    private static final String NARROWED_RECORD_ID = "918273";

    @Test
    void activityListJourney() throws Exception {

        // 1. The hohenheim resource still describes the SAME columns the framework
        //    resource does: its spec is a copy (TableSpec has no toBuilder), so a column
        //    added upstream has to fail here rather than silently vanish from the panel.
        AdminActivityResource resource = adminActivityResource();
        List<String> ours = resource.tableSpec().columns().stream().map(ColumnSpec::name).toList();
        List<String> framework = new ActivityResource().tableSpec().columns()
            .stream().map(ColumnSpec::name).toList();
        assertThat(ours)
            .as("step 1: the panel's activity columns match the framework's")
            .isEqualTo(framework);

        // 2. Every filter an operator needs to reach ONE record is declared: the model,
        //    the record id, and the origin that flips the default scope.
        List<String> filters = resource.tableSpec().filters().stream()
            .map(FilterSpec::name).toList();
        assertThat(filters)
            .as("step 2: model, record and origin are all filterable")
            .contains(ActivityModel.MODEL.getName(), ActivityModel.RECORD_ID.getName(),
                ActivityModel.ORIGIN.getName());

        seedActivityRows();

        // 3. The default list is what a PERSON did: the system-origin sweep is not on it.
        HttpResponse<String> defaultList = adminGet("/admin/activity");
        assertThat(defaultList.statusCode()).as("step 3: the activity list renders").isEqualTo(200);
        assertThat(defaultList.body())
            .as("step 3: operator activity is on the default list")
            .contains(OPERATOR_TITLE);
        assertThat(defaultList.body())
            .as("step 3: background activity is hidden by default")
            .doesNotContain(BACKGROUND_TITLE);

        // 4. The origin filter genuinely flips it: naming the system origin shows the
        //    sweeps, so the default is a starting point and never a cage.
        HttpResponse<String> systemList = adminGet("/admin/activity?filter.origin="
            + Accountability.ORIGIN_SYSTEM);
        assertThat(systemList.statusCode()).as("step 4: the filtered list renders").isEqualTo(200);
        assertThat(systemList.body())
            .as("step 4: the origin filter reveals background activity")
            .contains(BACKGROUND_TITLE);
        assertThat(systemList.body())
            .as("step 4: the origin filter narrows to that origin")
            .doesNotContain(OPERATOR_TITLE);

        // 5. A verb leaves the resource as the SHARED localized label, not as the raw
        //    snake_case token the column used to print.
        Object verbCell = resource.cellValue(rowFor(OPERATOR_RECORD_ID),
            column(resource, ActivityModel.ACTION.getName()));
        assertThat(verbCell)
            .as("step 5: the verb cell is the localized label")
            .isInstanceOf(Microcopy.class);
        assertThat(((Microcopy) verbCell).key())
            .as("step 5: the label is keyed by the stored verb")
            .isEqualTo("created");

        // 6. An unregistered verb still says what happened: the shared label falls open to
        //    the raw verb, so a new hohenheim action is never a blank cell.
        Object unknownCell = resource.cellValue(rowFor(UNLINKABLE_RECORD_ID),
            column(resource, ActivityModel.ACTION.getName()));
        assertThat(((Microcopy) unknownCell).key())
            .as("step 6: an unregistered verb keeps its own text")
            .isEqualTo(UNREGISTERED_VERB);
        assertThat(defaultList.body())
            .as("step 6: and that text is what the list prints")
            .contains(UNREGISTERED_VERB);

        // 7. A record a registered resource serves is a LINK to that record; the label is
        //    the title the row stored, never a fresh lookup.
        Object linked = resource.cellValue(rowFor(OPERATOR_RECORD_ID),
            column(resource, ActivityModel.RECORD_ID.getName()));
        assertThat(linked).as("step 7: the record cell is structured").isInstanceOf(ActivityRecordCell.class);
        ActivityRecordCell linkedCell = (ActivityRecordCell) linked;
        assertThat(linkedCell.label())
            .as("step 7: the link reads as the stored record title")
            .isEqualTo(OPERATOR_TITLE);
        assertThat(linkedCell.url())
            .as("step 7: and points at that record's admin page")
            .isEqualTo("/admin/servers/" + OPERATOR_RECORD_ID);

        // 8. A record no resource serves stays plain text -- named, but not linked.
        ActivityRecordCell orphan = (ActivityRecordCell) resource.cellValue(
            rowFor(UNLINKABLE_RECORD_ID), column(resource, ActivityModel.RECORD_ID.getName()));
        assertThat(orphan.label())
            .as("step 8: an unlinkable record is still named")
            .isEqualTo(UNLINKABLE_TITLE);
        assertThat(orphan.url())
            .as("step 8: and carries no link")
            .isNull();

        // 9. The record filter narrows the log to the history of ONE record.
        HttpResponse<String> narrowed = adminGet("/admin/activity?filter.record_id="
            + NARROWED_RECORD_ID);
        assertThat(narrowed.statusCode()).as("step 9: the record-filtered list renders").isEqualTo(200);
        assertThat(narrowed.body())
            .as("step 9: the named record's activity is on it")
            .contains(NARROWED_TITLE);
        assertThat(narrowed.body())
            .as("step 9: and nothing else is")
            .doesNotContain(OPERATOR_TITLE)
            .doesNotContain(UNLINKABLE_TITLE);
    }

    /** The panel's own activity peer -- never a fresh instance, the registered one. */
    private static AdminActivityResource adminActivityResource() {
        Panel panel = PanelRegistry.getBySlug("admin");
        assertThat(panel).as("the admin panel is registered").isNotNull();
        for (PanelPeer peer : panel.peers()) {
            if (peer instanceof AdminActivityResource activity) {
                return activity;
            }
        }
        throw new AssertionError("the admin panel exposes no activity resource");
    }

    private static ColumnSpec column(AdminActivityResource resource, String name) {
        ColumnSpec column = resource.tableSpec().column(name);
        assertThat(column).as("the activity table declares a '" + name + "' column").isNotNull();
        return column;
    }

    private static Row rowFor(String recordId) {
        Row row = new ActivityModel().find()
            .where(ActivityModel.RECORD_ID.eq(recordId))
            .first();
        assertThat(row).as("the seeded activity row for record " + recordId).isNotNull();
        return row;
    }

    /**
     * Four rows spanning both lanes, both link states and a verb nobody declared.
     *
     * AIDEV-NOTE: the timestamps are far in the future on purpose -- the list is sorted
     * created_at DESC and paginated, and every other test in this suite writes activity
     * rows too, so "the first page" is the only place these can be asserted about.
     */
    private static void seedActivityRows() {
        String serverModel = ServerModel.MODEL_ID.toString();
        write(serverModel, OPERATOR_RECORD_ID, OPERATOR_TITLE, "created",
            Accountability.ORIGIN_WEB, Instant.parse("2999-01-01T00:00:04Z"));
        write(serverModel, BACKGROUND_RECORD_ID, BACKGROUND_TITLE, "reconciled",
            Accountability.ORIGIN_SYSTEM, Instant.parse("2999-01-01T00:00:03Z"));
        write("hohenheim:no-such-model", UNLINKABLE_RECORD_ID, UNLINKABLE_TITLE, UNREGISTERED_VERB,
            Accountability.ORIGIN_WEB, Instant.parse("2999-01-01T00:00:02Z"));
        write(serverModel, NARROWED_RECORD_ID, NARROWED_TITLE, "created",
            Accountability.ORIGIN_WEB, Instant.parse("2999-01-01T00:00:01Z"));
    }

    private static void write(String model, String recordId, String title, String action,
                              String origin, Instant createdAt) {
        ActivityModel activities = new ActivityModel();
        Row row = activities.createEmptyRow();
        row.set(ActivityModel.MODEL, model);
        row.set(ActivityModel.RECORD_ID, recordId);
        row.set(ActivityModel.RECORD_TITLE, title);
        row.set(ActivityModel.ACTION, action);
        row.set(ActivityModel.ORIGIN, origin);
        row.set(ActivityModel.CREATED_AT, createdAt);
        activities.save(row);
    }
}
