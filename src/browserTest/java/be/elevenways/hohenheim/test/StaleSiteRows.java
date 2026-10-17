package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
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
 * Writes a stored site column raw in the bound datasource, as an older release or build left the row.
 *
 * AIDEV-NOTE: these are shapes today's rules refuse, so no save can write them, and since history v3 a set-based
 * assignment of a site's snapshot field is refused (SNAPSHOT_ASSIGNMENT) even past the behaviours. The row is written
 * through the frozen table instead, which is what such data is: no revision or change of today's ever held it. The
 * site model's query cache is dropped after, as its own write paths do.
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

    private static void write(int siteId, @NonNull Field<?, ?> column, @Nullable Object value) {
        IntegerField id = IntegerField.builder().name("id").build();
        FrozenModel sites = new FrozenModel("sites", id, column);
        int written = sites.find().where(id.eq(siteId)).assign(column, value).updateAll();
        if (written != 1) {
            throw new IllegalStateException("site " + siteId + " was not written");
        }
        Models.get(SiteModel.class).invalidateQueryCache();
    }
}
