package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Writes a stored column of a site, or of one of its access rules, raw in the bound datasource, as an older release
 * or build left the row.
 *
 * AIDEV-NOTE: these are shapes today's rules refuse, so no save can write them, and since history v3 a set-based
 * assignment of a site's snapshot field is a HistoryMisuse (SNAPSHOT_ASSIGNMENT) even past the behaviours. The row
 * is written through the frozen table instead, which is what such data is: no revision or change of today's ever held
 * it. The written model's query cache is dropped after, as its own write paths do.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class StaleSiteRows {

    private StaleSiteRows() {
    }

    /** Stores the site's {@code instance_id}, null for none. */
    public static void instanceId(int siteId, @Nullable Integer instanceId) {
        write(siteId, IntegerField.builder().name("instance_id").nullable(true).build(), instanceId);
    }

    /** Stores the site's {@code upstream_kind} token as written. */
    public static void upstreamKind(int siteId, @NonNull String kind) {
        write(siteId, StringField.builder().name("upstream_kind").build(), Objects.requireNonNull(kind, "kind"));
    }

    /** Stores the site's {@code settings} map as written. */
    public static void settings(int siteId, @NonNull Map<String, Object> settings) {
        write(siteId, SchemaField.builder("settings").build(), Objects.requireNonNull(settings, "settings"));
    }

    /** Stores an access rule's {@code data} map as written, past the model's refusal of a data write it cannot seal. */
    public static void accessRuleData(int ruleId, @NonNull Map<String, Object> data) {
        write("access_rules", AccessRuleModel.class, ruleId, SchemaField.builder("data").build(),
            Objects.requireNonNull(data, "data"));
    }

    private static void write(int siteId, @NonNull Field<?, ?> column, @Nullable Object value) {
        write("sites", SiteModel.class, siteId, column, value);
    }

    private static void write(@NonNull String table, @NonNull Class<? extends Model> model, int rowId,
                              @NonNull Field<?, ?> column, @Nullable Object value) {
        IntegerField id = IntegerField.builder().name("id").build();
        FrozenModel rows = new FrozenModel(table, id, column);
        int written = rows.find().where(id.eq(rowId)).assign(column, value).updateAll();
        if (written != 1) {
            throw new IllegalStateException(table + " row " + rowId + " was not written");
        }
        Models.get(model).invalidateQueryCache();
    }
}
