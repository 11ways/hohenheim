package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.util.List;

/**
 * One preview deployment of a git-sourced site: a bounded-lifetime, quota-charged
 * environment built from one ref/commit, served on a GENERATED hostname. The preview
 * owns its runtime the way a site owns its releases -- an attributed instance row, an
 * attributed generated {@code site_domains} row and attributed DNS rows -- so cleanup
 * is deterministic: the sweep removes exactly its own output and nothing hand-authored.
 */
public class PreviewDeploymentModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "preview_deployment");
    public static final Schema SCHEMA = new Schema();

    public static final String STATUS_DEPLOYING = "deploying";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_DESTROYED = "destroyed";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField APPLICATION_ID = SCHEMA.addField(IntegerField.builder().name("application_id")
        .required()
        .label(HohenheimFormCopy.label("application"))
        .build());

    /** The git ref (branch) this preview builds; user data, never localized. */
    public static final StringField REF = SCHEMA.addField(StringField.builder().name("ref")
        .required()
        .label(HohenheimFormCopy.label("preview_ref"))
        .build());

    /** Pull/merge request number when webhook-created; null for manual previews. */
    public static final IntegerField PR_NUMBER = SCHEMA.addField(
        IntegerField.builder().name("pr_number").visibleIn(EditView.EDIT, EditView.DETAIL).build());

    public static final StringField HEAD_SHA = SCHEMA.addField(
        StringField.builder().name("head_sha").filterable(false).visibleIn(EditView.EDIT, EditView.DETAIL).build());

    /** The generated hostname this preview serves on; derived, never submitted. */
    public static final StringField HOSTNAME = SCHEMA.addField(
        StringField.builder().name("hostname").visibleIn(EditView.EDIT, EditView.DETAIL).build());

    public static final EnumField STATUS = SCHEMA.addField(EnumField.builder("status")
        .value(STATUS_DEPLOYING, v -> v.displayName("Deploying")
            .label(statusLabel(STATUS_DEPLOYING)).icon("rotate").color("info"))
        .value(STATUS_RUNNING, v -> v.displayName("Running")
            .label(statusLabel(STATUS_RUNNING)).icon("circle-play").color("success"))
        .value(STATUS_FAILED, v -> v.displayName("Failed")
            .label(statusLabel(STATUS_FAILED)).icon("circle-xmark").color("destructive"))
        .value(STATUS_EXPIRED, v -> v.displayName("Expired")
            .label(statusLabel(STATUS_EXPIRED)).icon("hourglass-end").color("secondary"))
        .value(STATUS_DESTROYED, v -> v.displayName("Destroyed")
            .label(statusLabel(STATUS_DESTROYED)).icon("trash").color("secondary"))
        .defaultValue(STATUS_DEPLOYING)
        .label(HohenheimFormCopy.label("status"))
        .visibleIn(EditView.EDIT, EditView.DETAIL)
        .build());

    /** The translation token for a preview status; the key IS the stored value. */
    private static Microcopy statusLabel(String status) {
        return Microcopy.of(status).withFilter("scope", "preview_status");
    }

    /** Hard end of life; enforcement is the expiry sweep, never advisory. */
    public static final DateTimeField EXPIRES_AT = SCHEMA.addField(
        DateTimeField.builder().name("expires_at").visibleIn(EditView.EDIT, EditView.DETAIL).build());

    /** The owned instance backing this preview; explicit so destroy needs no scan. */
    public static final IntegerField INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("instance_id").visibleIn(EditView.EDIT, EditView.DETAIL).build());

    /** The quota ledger bucket this preview is charged against (owner-pack derived). */
    public static final StringField QUOTA_BUCKET = SCHEMA.addField(
        StringField.builder().name("quota_bucket").filterable(false).build());

    public static final TextField LAST_ERROR = SCHEMA.addField(
        TextField.builder().name("last_error").filterable(false).visibleIn(EditView.EDIT, EditView.DETAIL).build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());
    public static final DateTimeField DELETED_AT = SCHEMA.addField(DateTimeField.builder().name("deleted_at").build());

    /** The application this is a preview OF; its releases and this one share a source. */
    public static final BelongsTo<InstanceModel> APPLICATION = SCHEMA.addRelation(
        BelongsTo.to(InstanceModel.class)
            .name("application")
            .localKey(APPLICATION_ID)
            .remoteKey(InstanceModel.ID)
            .build());

    static {
        SCHEMA.setDisplayFields(HOSTNAME);
        SCHEMA.addLifecycleField(DELETED_AT);
    }

    /** Live (not torn down) previews of one site, newest first. */
    public List<Row> findLiveByApplicationId(int applicationId) {
        return find()
            .where(APPLICATION_ID.eq(applicationId))
            .where(DELETED_AT.isNull())
            .orderBy(ID, SortOrder.DESC)
            .all();
    }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "PreviewDeployment"; }
    @Override public String getTableName() { return "preview_deployments"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
