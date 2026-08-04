package be.elevenways.hohenheim.test.preview;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.server.preview.PreviewDeployments;
import be.elevenways.hohenheim.server.preview.PreviewDomains;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The daemon-less half of the preview contract: deterministic hostnames, the generated
 * -domain attribution guard (a hand-authored row is never adopted or deleted; the
 * attribution cannot be submitted), the atomic per-owner quota, and expiry as a
 * one-shot record schedule the framework sweeper enforces.
 */
class PreviewMechanicsTest extends HohenheimTestBase {

    private static Integer siteId;

    @BeforeAll
    static void setUpSite() {
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Previews.BASE_DOMAIN, "preview.test");
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Preview Mechanics Site");
        site.set(SiteModel.SLUG, "prev-mech");
        site.set(SiteModel.SITE_TYPE, "hohenheim:docker");
        site.set(SiteModel.SETTINGS, Map.of("image", "alpine", "container_port", 8080));
        site.set(SiteModel.SOURCE, SiteModel.SOURCE_GIT);
        Map<String, Object> sourceSettings = new LinkedHashMap<>();
        sourceSettings.put("repository_url", "/nonexistent/repo");
        site.set(SiteModel.SOURCE_SETTINGS, sourceSettings);
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
        siteId = site.get(SiteModel.ID);
    }

    @Test
    void generatedHostnamesAreDeterministicAndLabelSafe() {
        // 1. Deterministic: same inputs, same hostname, forever.
        String first = PreviewDeployments.hostnameFor("shop", "feature/login-2", "preview.test");
        assertThat(first).isEqualTo("shop--feature-login-2.preview.test");
        assertThat(PreviewDeployments.hostnameFor("shop", "feature/login-2", "preview.test"))
            .as("step 1: the derivation is a pure function").isEqualTo(first);

        // 2. Distinct (site, ref) pairs cannot collide: the double hyphen separator
        //    cannot occur inside either slugged half.
        assertThat(PreviewDeployments.hostnameFor("shop-a", "b", "preview.test"))
            .isNotEqualTo(PreviewDeployments.hostnameFor("shop", "a-b", "preview.test"));

        // 3. A hostile ref folds to a valid DNS label: lowercase, no specials,
        //    at most 63 chars, never ending on a hyphen.
        String hostile = PreviewDeployments.hostnameFor("shop",
            "Feature/../;DROP TABLE//" + "x".repeat(100), "preview.test");
        String label = hostile.substring(0, hostile.indexOf('.'));
        assertThat(label).matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?");
        assertThat(label.length()).isLessThanOrEqualTo(63);
    }

    @Test
    void generatedDomainRowsAreSelfScopedAndHandRowsAreUntouchable() throws Exception {
        var domains = Models.get(SiteDomainModel.class);

        // 1. A hand-authored domain row on the same site.
        Row handRow = domains.createEmptyRow();
        handRow.set(SiteDomainModel.SITE_ID, siteId);
        handRow.set(SiteDomainModel.HOSTNAME, "hand.preview.test");
        handRow.set(SiteDomainModel.MATCH_TYPE, "exact");
        domains.save(handRow);

        // 2. A caller CANNOT hand-write the attribution: the write is refused, never
        //    silently stripped -- so nothing hand-authored can masquerade as generated.
        Row fake = domains.createEmptyRow();
        fake.set(SiteDomainModel.SITE_ID, siteId);
        fake.set(SiteDomainModel.HOSTNAME, "fake.preview.test");
        fake.set(SiteDomainModel.MATCH_TYPE, "exact");
        fake.set(SiteDomainModel.GENERATED_BY, PreviewDomains.SOURCE);
        fake.set(SiteDomainModel.GENERATED_FOR_MODEL,
            PreviewDeploymentModel.MODEL_ID.toString());
        fake.set(SiteDomainModel.GENERATED_FOR_ID, 12345);
        Throwable refused = catchThrowable(() -> domains.save(fake));
        assertThat(refused).as("step 2: submitted attribution is refused")
            .isInstanceOf(Violations.class);

        // 3. A preview row with a GENERATED domain row, written inside the system scope
        //    (exactly what the deploy lane does).
        Row preview = newPreviewRow("scope-ref", "prev-mech--scope-ref.preview.test", null);
        int previewId = preview.get(PreviewDeploymentModel.ID);
        GeneratedRows.as(new GeneratedRows.Attribution(PreviewDomains.SOURCE,
            PreviewDeploymentModel.MODEL_ID.toString(), previewId), () -> {
                Row generated = domains.createEmptyRow();
                generated.set(SiteDomainModel.SITE_ID, siteId);
                generated.set(SiteDomainModel.HOSTNAME, "prev-mech--scope-ref.preview.test");
                generated.set(SiteDomainModel.MATCH_TYPE, "exact");
                generated.set(SiteDomainModel.FORCE_SSL, false);
                generated.set(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT, true);
                domains.save(generated);
            });
        Row generated = generatedDomainOf(previewId);
        assertThat(generated).as("step 3: the generated row carries the attribution")
            .isNotNull();

        // 4. OUTSIDE the scope the generated row is read-only: edits and deletes refuse.
        Row stored = domains.findById(generated.get(SiteDomainModel.ID));
        stored.set(SiteDomainModel.HOSTNAME, "stolen.preview.test");
        assertThat(catchThrowable(() -> domains.save(stored)))
            .as("step 4: an attributed row cannot be edited outside the system scope")
            .isInstanceOf(Violations.class);
        Integer generatedId = generated.get(SiteDomainModel.ID);
        assertThat(catchThrowable(() -> domains.delete(generatedId)))
            .as("step 4: nor deleted")
            .isInstanceOf(Violations.class);

        // 5. Destroying the preview sweeps EXACTLY its own rows: the generated row
        //    dies, the hand-authored row (same site, no attribution) survives.
        PreviewDeployments.destroy(previewId, "operator");
        assertThat(generatedDomainOf(previewId))
            .as("step 5: the preview's generated row was reclaimed").isNull();
        assertThat(domains.findById(handRow.get(SiteDomainModel.ID)))
            .as("step 5: the hand-authored row is untouched").isNotNull();
        Row deadPreview = Models.get(PreviewDeploymentModel.class).findById(previewId);
        assertThat((Object) deadPreview.get(PreviewDeploymentModel.DELETED_AT))
            .as("step 5: the preview row soft-deleted").isNotNull();
        assertThat((String) deadPreview.get(PreviewDeploymentModel.STATUS))
            .isEqualTo(PreviewDeploymentModel.STATUS_DESTROYED);
    }

    @Test
    void thePreviewQuotaBindsAtomicallyAndReleasesOnTeardown() {
        Integer savedCap = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Previews.MAX_PER_OWNER);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Previews.MAX_PER_OWNER, 1);
        try {
            // 1. The first preview of this owner fits.
            Row first = newPreviewRow("quota-a", "prev-mech--quota-a.preview.test", null);
            assertThat((String) first.get(PreviewDeploymentModel.QUOTA_BUCKET))
                .as("step 1: the charge is stamped on the row")
                .startsWith("hohenheim:previews:");

            // 2. The second is REFUSED by the ledger, with the quota named.
            Throwable refused = catchThrowable(() ->
                newPreviewRow("quota-b", "prev-mech--quota-b.preview.test", null));
            assertThat(refused).as("step 2: over-cap preview refused")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("preview_quota_reached");

            // 3. Tearing the first down releases the slot; the second now fits.
            PreviewDeployments.destroy(first.get(PreviewDeploymentModel.ID), "operator");
            Row second = newPreviewRow("quota-b", "prev-mech--quota-b.preview.test", null);
            assertThat(second.get(PreviewDeploymentModel.ID))
                .as("step 3: the released slot is claimable again").isNotNull();
            PreviewDeployments.destroy(second.get(PreviewDeploymentModel.ID), "operator");
        } finally {
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Previews.MAX_PER_OWNER, savedCap);
        }
    }

    @Test
    void theOneShotExpiryScheduleEnforcesTheStoredDeadline() throws Exception {
        // 1. A preview whose lifetime ended while nothing was watching: its deadline
        //    is a ONE-SHOT record schedule in the database, not an in-memory timer.
        Row expired = newPreviewRow("stale-ref", "prev-mech--stale-ref.preview.test",
            Instant.now().minusSeconds(60));
        int previewId = expired.get(PreviewDeploymentModel.ID);
        PreviewDeployments.armExpiry(previewId, Instant.now().minusSeconds(60));
        var domains = Models.get(SiteDomainModel.class);
        GeneratedRows.as(new GeneratedRows.Attribution(PreviewDomains.SOURCE,
            PreviewDeploymentModel.MODEL_ID.toString(), previewId), () -> {
                Row generated = domains.createEmptyRow();
                generated.set(SiteDomainModel.SITE_ID, siteId);
                generated.set(SiteDomainModel.HOSTNAME, "prev-mech--stale-ref.preview.test");
                generated.set(SiteDomainModel.MATCH_TYPE, "exact");
                domains.save(generated);
            });

        // 2. One healthy preview beside it (deadline far away), to prove the sweep is
        //    not a broom.
        Row healthy = newPreviewRow("fresh-ref", "prev-mech--fresh-ref.preview.test",
            Instant.now().plusSeconds(3600));
        int healthyId = healthy.get(PreviewDeploymentModel.ID);
        PreviewDeployments.armExpiry(healthyId, Instant.now().plusSeconds(3600));

        // 3. The FRAMEWORK sweeper (the exact call RunRecordSchedulesTask makes every
        //    minute) fires the due one-shot; the reached preview is fully reclaimed.
        new RecordSchedules(Datasources.getDefault()).runDue(null);

        Row dead = awaitDestroyed(previewId);
        assertThat((String) dead.get(PreviewDeploymentModel.STATUS))
            .as("step 3: expiry is stamped as EXPIRED, visibly")
            .isEqualTo(PreviewDeploymentModel.STATUS_EXPIRED);
        assertThat((Object) dead.get(PreviewDeploymentModel.DELETED_AT)).isNotNull();
        assertThat(generatedDomainOf(previewId))
            .as("step 3: its generated hostname row is gone").isNull();
        assertThat(schedulesOf(previewId))
            .as("step 3: the dead preview left no schedule rows behind").isEmpty();

        // 4. The healthy preview survived, its one-shot still armed and unspent.
        Row alive = Models.get(PreviewDeploymentModel.class).findById(healthyId);
        assertThat((Object) alive.get(PreviewDeploymentModel.DELETED_AT))
            .as("step 4: the unexpired preview survived the sweep").isNull();
        List<Row> armed = schedulesOf(healthyId);
        assertThat(armed).as("step 4: its one-shot schedule is still armed").hasSize(1);
        assertThat((Object) armed.get(0).get(RecordScheduleModel.COMPLETED_AT))
            .as("step 4: and is not spent").isNull();

        // 5. A second sweep changes nothing: the healthy deadline is still ahead.
        new RecordSchedules(Datasources.getDefault()).runDue(null);
        alive = Models.get(PreviewDeploymentModel.class).findById(healthyId);
        assertThat((Object) alive.get(PreviewDeploymentModel.DELETED_AT))
            .as("step 5: still untouched after another sweep").isNull();

        // 6. Operator teardown reclaims the schedule with the record: soft delete
        //    fires no remove hooks, so destroy must (and does) delete it explicitly.
        PreviewDeployments.destroy(healthyId, "operator");
        assertThat(schedulesOf(healthyId))
            .as("step 6: teardown removed the armed schedule").isEmpty();
    }

    /**
     * The ambient minute sweeper can win the lease race for a due one-shot; whoever
     * fires it, the destroyed STATE is what matters -- await it briefly.
     */
    private static Row awaitDestroyed(int previewId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Row row = Models.get(PreviewDeploymentModel.class).findById(previewId);
            if (row != null && row.get(PreviewDeploymentModel.DELETED_AT) != null) {
                return row;
            }
            Thread.sleep(100);
        }
        return Models.get(PreviewDeploymentModel.class).findById(previewId);
    }

    private static List<Row> schedulesOf(int previewId) {
        return Models.get(RecordScheduleModel.class)
            .findForRecord(PreviewDeploymentModel.MODEL_ID, previewId);
    }

    // -- helpers --------------------------------------------------------------

    private static Row newPreviewRow(String ref, String hostname, Instant expiresAt) {
        var model = Models.get(PreviewDeploymentModel.class);
        Row preview = model.createEmptyRow();
        preview.set(PreviewDeploymentModel.SITE_ID, siteId);
        preview.set(PreviewDeploymentModel.REF, ref);
        preview.set(PreviewDeploymentModel.HOSTNAME, hostname);
        preview.set(PreviewDeploymentModel.STATUS, PreviewDeploymentModel.STATUS_RUNNING);
        preview.set(PreviewDeploymentModel.EXPIRES_AT,
            expiresAt != null ? expiresAt : Instant.now().plusSeconds(3600));
        model.save(preview);
        return preview;
    }

    private static Row generatedDomainOf(int previewId) {
        List<Row> rows = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.GENERATED_BY.eq(PreviewDomains.SOURCE))
            .where(SiteDomainModel.GENERATED_FOR_MODEL.eq(
                PreviewDeploymentModel.MODEL_ID.toString()))
            .where(SiteDomainModel.GENERATED_FOR_ID.eq(previewId))
            .all();
        return rows.isEmpty() ? null : rows.get(0);
    }

}
