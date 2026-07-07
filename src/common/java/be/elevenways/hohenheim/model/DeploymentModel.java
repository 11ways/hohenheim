package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * One row per executed git deploy (or rollback) of a site: outcome, timing,
 * commit and the captured build log.
 */
public class DeploymentModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "deployment");
    public static final Schema SCHEMA = new Schema();

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField SITE_ID = SCHEMA.addField(IntegerField.builder().name("site_id").build());
    public static final StringField STATUS = SCHEMA.addField(StringField.builder().name("status").build());
    public static final StringField REASON = SCHEMA.addField(StringField.builder().name("reason").build());
    public static final StringField COMMIT_SHA = SCHEMA.addField(StringField.builder().name("commit_sha").build());
    public static final StringField SLOT = SCHEMA.addField(StringField.builder().name("slot").build());
    public static final StringField ERROR = SCHEMA.addField(StringField.builder().name("error").build());
    public static final TextField LOG = SCHEMA.addField(TextField.builder().name("log").build());
    public static final DateTimeField STARTED_AT = SCHEMA.addField(DateTimeField.builder().name("started_at").build());
    public static final DateTimeField FINISHED_AT = SCHEMA.addField(DateTimeField.builder().name("finished_at").build());
    public static final IntegerField DURATION_MS = SCHEMA.addField(IntegerField.builder().name("duration_ms").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    /** Newest-first deploy history of one site. */
    public List<Row> findBySiteId(int siteId, int limit) {
        return find()
            .where(SITE_ID.eq(siteId))
            .orderBy(ID, SortOrder.DESC)
            .limit(limit)
            .all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }
    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override
    public String getModelName() { return "Deployment"; }
    @Override
    public String getTableName() { return "deployments"; }
    @Override
    public Schema getSchema() { return SCHEMA; }
}
