package be.elevenways.hohenheim.model;

import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * One stack deploy attempt: status, captured log, and the fully resolved spec
 * snapshot (DRY, encrypted at rest because it embeds file contents and registry
 * credentials). Rollback re-deploys a previous successful snapshot verbatim.
 */
public class StackDeploymentModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("stack_deployment");
    public static final Schema SCHEMA = new Schema();

    /** The statuses a deploy attempt stores in {@link #STATUS}; a success stays stored as "success". */
    public static final OperationLifecycle LIFECYCLE = OperationLifecycle.of(OperationStatus.RUNNING,
        OperationStatus.SUCCEEDED, OperationStatus.FAILED).storing(OperationStatus.SUCCEEDED, "success");

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField STACK_ID = SCHEMA.addField(IntegerField.builder().name("stack_id").build());

    /** The stack this history belongs to, declared so its delete takes the history along (StackCascades). */
    public static final BelongsTo<StackModel> STACK = SCHEMA.addRelation(
        BelongsTo.to(StackModel.class)
            .name("stack")
            .localKey(STACK_ID)
            .remoteKey(StackModel.ID)
            .build());

    public static final EnumField STATUS = SCHEMA.addField(LIFECYCLE.field("status"));

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
        return this.history(stackId).limit(limit).all();
    }

    /** Keep the newest {@code keep} deployments of one stack and delete the rest. */
    public void pruneHistory(int stackId, int keep) {
        Retention.keepNewest(this, this.history(stackId), ID, keep);
    }

    /** One stack's deployments, newest first. */
    private QueryBuilder<Row> history(int stackId) {
        return find().where(STACK_ID.eq(stackId)).orderBy(ID, SortOrder.DESC);
    }

    /** The newest successful deployment carrying a spec snapshot, or null. */
    public Row findLatestSuccessful(int stackId) {
        return this.history(stackId)
            .where(STATUS.eq(LIFECYCLE.stored(OperationStatus.SUCCEEDED)))
            .where(SPEC.isNotNull())
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
