package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.model.WebhookDeliveryModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The replay claim over webhook deliveries: INSERT-FIRST against the unique
 * (site_id, delivery_key) index, so of two racing identical deliveries exactly one
 * wins -- the loser's insert fails on the index, never on a stale read.
 */
final class WebhookDeliveries {

    /**
     * How long a claim is honoured. The ledger's whole job is refusing a REPLAY, so
     * retention has to be measured in the units replay protection is measured in: time.
     *
     * AIDEV-NOTE: this was a bare "keep the newest 200 per site" cap, which quietly made
     * the guarantee a function of TRAFFIC -- a site taking 200 deliveries in an afternoon
     * lost replay protection for that morning's, and re-delivering one redeployed. Rows
     * are now only prunable once they are older than every provider's retry horizon
     * (GitHub, GitLab and Gitea all give up within hours). There is deliberately no
     * count cap any more: a second knob that can shorten this window is the defect.
     */
    private static final Duration RETAIN = Duration.ofDays(30);

    /** Rows removed per claim; pruning is amortized, never one huge delete. */
    private static final int PRUNE_BATCH = 1000;

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
                .where(WebhookDeliveryModel.RECEIVED_AT.lt(Instant.now().minus(RETAIN)))
                .orderBy(WebhookDeliveryModel.ID, SortOrder.ASC)
                .limit(PRUNE_BATCH)
                .all();
            for (Row old : stale) {
                model.delete(old.get(WebhookDeliveryModel.ID));
            }
        } catch (RuntimeException e) {
            Blast.log("GIT WEBHOOK: delivery prune failed for site", siteId, "-", e.getMessage());
        }
    }
}
