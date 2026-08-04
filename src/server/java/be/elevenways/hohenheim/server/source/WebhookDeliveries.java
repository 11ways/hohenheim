package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.model.WebhookDeliveryModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * The replay claim over webhook deliveries: INSERT-FIRST against the unique
 * (site_id, delivery_key) index, so of two racing identical deliveries exactly one
 * wins -- the loser's insert fails on the index, never on a stale read.
 */
final class WebhookDeliveries {

    /** Accepted deliveries kept per site; older rows are pruned on new claims. */
    private static final int KEEP_PER_SITE = 200;

    private WebhookDeliveries() {
    }

    /**
     * Claim one delivery id for one site.
     *
     * @return the claimed row, or null when this delivery was already processed
     */
    static @Nullable Row claim(int siteId, @NonNull String deliveryKey, @Nullable String event) {
        WebhookDeliveryModel model = Models.get(WebhookDeliveryModel.class);
        Row row = model.createEmptyRow();
        row.set(WebhookDeliveryModel.SITE_ID, siteId);
        row.set(WebhookDeliveryModel.DELIVERY_KEY, deliveryKey);
        row.set(WebhookDeliveryModel.EVENT, event);
        row.set(WebhookDeliveryModel.RECEIVED_AT, Instant.now());
        try {
            model.save(row);
        } catch (RuntimeException duplicate) {
            // The unique index is the arbiter; any insert failure on an existing key is
            // a replay. A genuinely broken datasource fails the fresh-key insert too,
            // which surfaces as a 500 at the handler -- never as a silent dedupe.
            if (model.find()
                    .where(WebhookDeliveryModel.SITE_ID.eq(siteId))
                    .where(WebhookDeliveryModel.DELIVERY_KEY.eq(deliveryKey))
                    .first() != null) {
                return null;
            }
            throw duplicate;
        }
        prune(model, siteId);
        return row;
    }

    /** Stamp what the delivery caused; diagnostics only, degrades to a log line. */
    static void stampAction(@Nullable Row claimed, @NonNull String action) {
        if (claimed == null) {
            return;
        }
        try {
            claimed.set(WebhookDeliveryModel.ACTION, action);
            Models.get(WebhookDeliveryModel.class).save(claimed);
        } catch (RuntimeException e) {
            Blast.log("GIT WEBHOOK: could not stamp delivery action -", e.getMessage());
        }
    }

    private static void prune(@NonNull WebhookDeliveryModel model, int siteId) {
        try {
            List<Row> stale = model.find()
                .where(WebhookDeliveryModel.SITE_ID.eq(siteId))
                .orderBy(WebhookDeliveryModel.ID, SortOrder.DESC)
                .offset(KEEP_PER_SITE)
                .limit(1000)
                .all();
            for (Row old : stale) {
                model.delete(old.get(WebhookDeliveryModel.ID));
            }
        } catch (RuntimeException e) {
            Blast.log("GIT WEBHOOK: delivery prune failed for site", siteId, "-", e.getMessage());
        }
    }
}
