package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * The persisted console output of ONE workload episode of one instance -- what the
 * in-memory replay ring holds, written down so history survives a controller restart.
 *
 * AIDEV-NOTE: this is the deleted proclog tier's shape, deliberately, not a third one. Same
 * discipline end to end: one UPSERTED row per episode carrying a rolling buffer, a periodic
 * flush plus a final flush at exit, and a day-based {@code RetentionSweep} cleaner. It is a
 * separate TABLE only because the owner column differs (an instance, not a site+pid) and
 * because its sweeper is gated on a different role -- a nullable second owner on
 * {@code proclogs} would give one table two meanings and one sweeper two role gates.
 *
 * AIDEV-NOTE: {@code log_text} is written ALREADY REDACTED (ConsoleRedaction runs at
 * console ingest, before the ring this row is flushed from). A secret stored here would be
 * a leak even if every reader redacted, so redaction may never move to the read path.
 *
 * AIDEV-NOTE: the payload is the workload's stdout VERBATIM and is therefore UNTRUSTED
 * TEXT -- the same rule the proclog viewer follows. Every reader renders it as a text node;
 * it never reaches a raw-HTML render.
 */
public class InstanceLogModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "instance_log");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(
        IntegerField.builder().name("id").build());

    public static final IntegerField INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("instance_id").build());

    /** The workload handle this episode belonged to; a redeploy produces a new one. */
    public static final StringField HANDLE = SCHEMA.addField(
        StringField.builder().name("handle").build());

    public static final TextField LOG_TEXT = SCHEMA.addField(
        TextField.builder().name("log_text").build());

    public static final IntegerField LINE_COUNT = SCHEMA.addField(
        IntegerField.builder().name("line_count").build());

    public static final DateTimeField SAVED_AT = SCHEMA.addField(
        DateTimeField.builder().name("saved_at").build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());

    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    /** The stored episodes of one instance, newest first. */
    public List<Row> findByInstanceId(int instanceId, int limit) {
        return find().where(INSTANCE_ID.eq(instanceId))
            .orderBy(ID, SortOrder.DESC)
            .limit(limit)
            .all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "InstanceLog"; }

    @Override
    public String getTableName() { return "instance_logs"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
