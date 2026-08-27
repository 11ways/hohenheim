package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * One stack deploy attempt: status, captured log, and the fully resolved spec
 * snapshot (DRY, encrypted at rest because it embeds file contents and registry
 * credentials). Rollback re-deploys a previous successful snapshot verbatim.
 */
public class StackDeploymentModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "stack_deployment");
    public static final Schema SCHEMA = new Schema();

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField STACK_ID = SCHEMA.addField(IntegerField.builder().name("stack_id").build());

    public static final EnumField STATUS = SCHEMA.addField(EnumField.builder("status")
        .value(STATUS_RUNNING, v -> v.displayName("Running")
            .label(statusLabel(STATUS_RUNNING)).icon("rotate").color("warning"))
        .value(STATUS_SUCCESS, v -> v.displayName("Success")
            .label(statusLabel(STATUS_SUCCESS)).icon("circle-check").color("success"))
        .value(STATUS_FAILED, v -> v.displayName("Failed")
            .label(statusLabel(STATUS_FAILED)).icon("circle-xmark").color("destructive"))
        .build());

    /** The translation token for a stack deployment status; the key IS the stored value. */
    private static Microcopy statusLabel(String status) {
        return Microcopy.of(status).withFilter("scope", "stack_deploy_status");
    }

    public static final StringField REASON = SCHEMA.addField(StringField.builder().name("reason").build());
    public static final TextField ERROR = SCHEMA.addField(TextField.builder().name("error").build());
    public static final TextField LOG = SCHEMA.addField(TextField.builder().name("log").build());

    public static final TextField SPEC = SCHEMA.addField(TextField.builder().name("spec")
        .encrypted()
        .build());

    public static final DateTimeField STARTED_AT = SCHEMA.addField(DateTimeField.builder().name("started_at").build());
    public static final DateTimeField FINISHED_AT = SCHEMA.addField(DateTimeField.builder().name("finished_at").build());
    public static final IntegerField DURATION_MS = SCHEMA.addField(IntegerField.builder().name("duration_ms").build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    /** Newest-first deployment history of a stack. */
    public List<Row> findByStackId(int stackId, int limit) {
        return find().where(STACK_ID.eq(stackId)).orderBy(ID, SortOrder.DESC).limit(limit).all();
    }

    /** The newest successful deployment carrying a spec snapshot, or null. */
    public Row findLatestSuccessful(int stackId) {
        return find().where(STACK_ID.eq(stackId))
            .where(STATUS.eq(STATUS_SUCCESS))
            .where(SPEC.isNotNull())
            .orderBy(ID, SortOrder.DESC)
            .first();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "StackDeployment"; }

    @Override
    public String getTableName() { return "stack_deployments"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
