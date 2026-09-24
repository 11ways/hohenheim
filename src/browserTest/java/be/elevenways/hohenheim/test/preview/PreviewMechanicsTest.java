package be.elevenways.hohenheim.test.preview;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.test.Poll;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.source.TestSources;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.server.preview.PreviewDeployments;
import be.elevenways.hohenheim.server.preview.PreviewDomains;
import be.elevenways.hohenheim.server.preview.PreviewQuota;
import be.elevenways.hohenheim.server.quota.OwnerQuota;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /** The APPLICATION a preview is built from; the site beside it lends the hostname. */
    private static Integer applicationId;

    @BeforeAll
    static void setUpSite() {
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Previews.BASE_DOMAIN, "preview.test");
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Preview Mechanics Site");
        site.set(SiteModel.SLUG, "prev-mech");
        
        site.set(SiteModel.SETTINGS, Map.of("image", "alpine", "container_port", 8080));
        
        Map<String, Object> sourceSettings = new LinkedHashMap<>();
        sourceSettings.put("repository_url", "/nonexistent/repo");
        TestSources.attachGitSource(site, sourceSettings);
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
        siteId = site.get(SiteModel.ID);
        applicationId = site.get(SiteModel.INSTANCE_ID);
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

        // 4. Truncation must NOT reintroduce collision: two pairs sharing a 63-char
        //    prefix (and a ref truncated away entirely by an over-long site slug) used
        //    to compose to the SAME label, a cross-tenant denial of preview creation
        //    because refuseRouteConflicts refuses the second row. A digest of the FULL
        //    composed label keeps them distinct across truncation.
        String shared = "s".repeat(70);
        assertThat(PreviewDeployments.hostnameFor(shared, "alpha", "preview.test"))
            .as("step 4: distinct refs under a truncating prefix stay distinct")
            .isNotEqualTo(PreviewDeployments.hostnameFor(shared, "beta", "preview.test"));
        assertThat(PreviewDeployments.hostnameFor("shop", "a".repeat(70), "preview.test"))
            .as("step 4: distinct over-long refs on one site stay distinct")
            .isNotEqualTo(PreviewDeployments.hostnameFor("shop", "b".repeat(70), "preview.test"));
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
        Row deadPreview = StoredRows.byId(Models.get(PreviewDeploymentModel.class), previewId);
        assertThat((Object) deadPreview.get(PreviewDeploymentModel.DELETED_AT))
            .as("step 5: the preview row soft-deleted").isNotNull();
        assertThat((String) deadPreview.get(PreviewDeploymentModel.STATUS))
            .isEqualTo(PreviewDeploymentModel.STATUS_DESTROYED);
    }

    @Test
    void thePreviewQuotaBindsAtomicallyAndReleasesOnTeardown() {
        Integer savedCap = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Previews.MAX_PER_OWNER);
        // AIDEV-NOTE: the cap is ONE slot above what the owner already holds, never a bare 1.
        // The application here has no grant, so its owner is the operator, whose bucket every
        // ungranted preview in this JVM shares: a preview another test left live used to fill
        // it and refuse step 1 before the quota under test was ever reached.
        String owner = Objects.requireNonNull(
            OwnerQuota.currentOwnerPack(InstanceModel.MODEL_ID, applicationId),
            "the application's owner is readable");
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Previews.MAX_PER_OWNER,
            Math.toIntExact(PreviewQuota.usedBy(owner) + 1));
        try {
            // 1. The next preview of this owner fits.
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
            Now.instant().minusSeconds(60));
        int previewId = expired.get(PreviewDeploymentModel.ID);
        PreviewDeployments.armExpiry(previewId, Now.instant().minusSeconds(60));
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
            Now.instant().plusSeconds(3600));
        int healthyId = healthy.get(PreviewDeploymentModel.ID);
        PreviewDeployments.armExpiry(healthyId, Now.instant().plusSeconds(3600));

        // 3. The FRAMEWORK sweeper (the exact call RunRecordSchedulesTask makes every
        //    minute) fires the due one-shot; the reached preview is fully reclaimed.
        new RecordSchedules(Datasources.getDefault()).runDue(null);

        Row dead = awaitDestroyed(previewId);
        assertThat((String) dead.get(PreviewDeploymentModel.STATUS))
            .as("step 3: expiry is stamped as EXPIRED, visibly")
            .isEqualTo(PreviewDeploymentModel.STATUS_EXPIRED);
        assertThat((Object) dead.get(PreviewDeploymentModel.DELETED_AT))
            .as("the reached preview is soft-deleted").isNotNull();
        assertThat(generatedDomainOf(previewId))
            .as("step 3: its generated hostname row is gone").isNull();
        assertThat(schedulesOf(previewId))
            .as("step 3: the dead preview left no schedule rows behind").isEmpty();

        // 4. The healthy preview survived, its one-shot still armed and unspent.
        Row alive = StoredRows.byId(Models.get(PreviewDeploymentModel.class), healthyId);
        assertThat((Object) alive.get(PreviewDeploymentModel.DELETED_AT))
            .as("step 4: the unexpired preview survived the sweep").isNull();
        List<Row> armed = schedulesOf(healthyId);
        assertThat(armed).as("step 4: its one-shot schedule is still armed").hasSize(1);
        assertThat((Object) armed.get(0).get(RecordScheduleModel.COMPLETED_AT))
            .as("step 4: and is not spent").isNull();

        // 5. A second sweep changes nothing: the healthy deadline is still ahead.
        new RecordSchedules(Datasources.getDefault()).runDue(null);
        alive = StoredRows.byId(Models.get(PreviewDeploymentModel.class), healthyId);
        assertThat((Object) alive.get(PreviewDeploymentModel.DELETED_AT))
            .as("step 5: still untouched after another sweep").isNull();

        // 6. Operator teardown reclaims the schedule with the record: soft delete
        //    fires no remove hooks, so destroy must (and does) delete it explicitly.
        PreviewDeployments.destroy(healthyId, "operator");
        assertThat(schedulesOf(healthyId))
            .as("step 6: teardown removed the armed schedule").isEmpty();
    }

    /**
     * The generated preview hostname is a REAL claim, driven end to end: reclaiming a
     * preview ledgers its hostname under the owner of the site that LENDS it, a stranger is
     * quarantined out of it, and the same owner's next preview retakes it.
     *
     * DECISION (2026-08-06): previews are quarantined, NOT exempted, even though the
     * hostname sits under a base domain we host and so carries no dangling third-party
     * CNAME. The reason is that the release path is the ordinary domain-row delete and the
     * claim path is the ordinary domain-row write -- exempting them would need a carve-out
     * in the write pipeline, and a quarantine with a hole shaped like "generated rows" is
     * worse than the small cost of a same-owner reclaim that is allowed anyway. What the
     * window actually costs a stranger is a slug+ref collision inside 30 days.
     */
    @Test
    void aReclaimedPreviewHostnameIsQuarantinedAgainstAnotherOwner() throws Exception {
        Integer savedWindow = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.RELEASE_QUARANTINE_DAYS);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Security.RELEASE_QUARANTINE_DAYS, 30);
        var domains = Models.get(SiteDomainModel.class);
        var sites = Models.get(SiteModel.class);
        String hostname = "prev-mech--quarantine-ref.preview.test";
        try {
            // 1. The site that lends the hostname belongs to a TENANT, so the ledger has
            //    an owner to record that is not the operator's empty set. The domain row
            //    hangs off the SITE, which is why the grant stays site-keyed here while
            //    the preview's own quota charge follows the APPLICATION.
            int owner = ApiSupport.user("preview-owner@test");
            RecordGrants.grant(GrantSubjectType.USER, owner, SiteModel.MODEL_ID, siteId,
                HohenheimAccess.MANAGE, true);

            // 2. A preview mints its hostname through the ordinary domain write pipeline.
            Row preview = newPreviewRow("quarantine-ref", hostname, null);
            int previewId = preview.get(PreviewDeploymentModel.ID);
            GeneratedRows.as(new GeneratedRows.Attribution(PreviewDomains.SOURCE,
                PreviewDeploymentModel.MODEL_ID.toString(), previewId), () -> {
                    Row generated = domains.createEmptyRow();
                    generated.set(SiteDomainModel.SITE_ID, siteId);
                    generated.set(SiteDomainModel.HOSTNAME, hostname);
                    generated.set(SiteDomainModel.MATCH_TYPE, "exact");
                    domains.save(generated);
                });
            assertThat((String) generatedDomainOf(previewId).get(SiteDomainModel.LIVE_ROUTE_KEY))
                .as("step 2: the generated row holds a real live route claim").isNotNull();

            // 3. Reclaiming the preview ledgers the release -- with the owner INTACT. A
            //    ledger row carrying an empty subject set reads as operator-owned and
            //    quarantines nobody, which no status-only assertion would notice.
            PreviewDeployments.destroy(previewId, "operator");
            Row ledgered = Models.get(ReleasedRouteClaimModel.class).find()
                .where(ReleasedRouteClaimModel.HOSTNAME.eq(hostname)).first();
            assertThat(ledgered)
                .as("step 3: the reclaimed preview hostname is ledgered").isNotNull();
            assertThat((String) ledgered.get(ReleasedRouteClaimModel.FORMER_SUBJECTS))
                .as("step 3: under the site's owner, not an empty operator set")
                .isEqualTo("user:" + owner);

            // 4. A stranger's site cannot take the freed preview hostname.
            Row raider = sites.createEmptyRow();
            raider.set(SiteModel.NAME, "Preview Raider");
            raider.set(SiteModel.SLUG, "prev-raider");
            raider.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
            raider.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
            raider.set(SiteModel.STATUS, "active");
            raider.set(SiteModel.ENABLED, true);
            sites.save(raider);
            RecordGrants.grant(GrantSubjectType.USER, ApiSupport.user("preview-raider@test"), SiteModel.MODEL_ID,
                raider.get(SiteModel.ID), HohenheimAccess.MANAGE, true);
            Row seize = domains.createEmptyRow();
            seize.set(SiteDomainModel.SITE_ID, raider.get(SiteModel.ID));
            seize.set(SiteDomainModel.HOSTNAME, hostname);
            seize.set(SiteDomainModel.MATCH_TYPE, "exact");
            assertThat(catchThrowable(() -> domains.save(seize)))
                .as("step 4: a stranger is refused the freed preview hostname")
                .isInstanceOf(Violations.class);
            assertThat(domains.find().where(SiteDomainModel.HOSTNAME.eq(hostname)).all())
                .as("step 4: and nothing was written").isEmpty();

            // 5. The SAME owner's next preview takes it straight back -- step 4 was the
            //    quarantine, not a preview that can never be redeployed.
            Row again = newPreviewRow("quarantine-ref-2", hostname, null);
            int againId = again.get(PreviewDeploymentModel.ID);
            GeneratedRows.as(new GeneratedRows.Attribution(PreviewDomains.SOURCE,
                PreviewDeploymentModel.MODEL_ID.toString(), againId), () -> {
                    Row generated = domains.createEmptyRow();
                    generated.set(SiteDomainModel.SITE_ID, siteId);
                    generated.set(SiteDomainModel.HOSTNAME, hostname);
                    generated.set(SiteDomainModel.MATCH_TYPE, "exact");
                    domains.save(generated);
                });
            assertThat((String) generatedDomainOf(againId).get(SiteDomainModel.LIVE_ROUTE_KEY))
                .as("step 5: the same owner redeploys onto its own hostname").isNotNull();
            PreviewDeployments.destroy(againId, "operator");
        } finally {
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Security.RELEASE_QUARANTINE_DAYS, savedWindow);
        }
    }

    /**
     * The ambient minute sweeper can win the lease race for a due one-shot; whoever
     * fires it, the destroyed STATE is what matters -- await it briefly.
     */
    private static Row awaitDestroyed(int previewId) {
        return Poll.value("preview " + previewId + " is soft-deleted", Duration.ofSeconds(10),
            Duration.ofMillis(100), () -> {
                Row row = StoredRows.byId(Models.get(PreviewDeploymentModel.class), previewId);
                return row != null && row.get(PreviewDeploymentModel.DELETED_AT) != null ? row : null;
            });
    }

    private static List<Row> schedulesOf(int previewId) {
        return Models.get(RecordScheduleModel.class)
            .findForRecord(PreviewDeploymentModel.MODEL_ID, previewId);
    }

    // -- helpers --------------------------------------------------------------

    /**
     * A preview's environment is stored as its instance's SECRET variables, never in
     * instances.settings: the deploy lane's own instance write does so, and the boot
     * backfill seals the plaintext copy an older controller left behind.
     */
    @Test
    void thePreviewEnvironmentIsSecretAndLegacyPlaintextIsSealed() throws Exception {
        Row preview = newPreviewRow("env-ref", "prev-mech--env-ref.preview.test", null);
        int previewId = preview.get(PreviewDeploymentModel.ID);
        GeneratedRows.Attribution attribution = new GeneratedRows.Attribution(PreviewDomains.SOURCE,
            PreviewDeploymentModel.MODEL_ID.toString(), previewId);
        Map<String, String> environment = Map.of("API_TOKEN", "preview-token-value", "MODE", "preview");
        Map<String, Object> desired = new LinkedHashMap<>();
        desired.put("image", "sha256:" + "a".repeat(64));
        desired.put("container_port", 8080);
        desired.put("environment_variables", environment);
        try {
            assertEnvironmentIsSealed(attribution, desired, environment);
        } finally {
            // A live preview holds a slot of its owner's preview quota: tear it down, or the
            // next test of this owner meets a bucket this one filled.
            PreviewDeployments.destroy(previewId, "operator");
        }
    }

    private static void assertEnvironmentIsSealed(GeneratedRows.Attribution attribution,
                                                  Map<String, Object> desired,
                                                  Map<String, String> environment) throws Exception {
        // 1. The deploy lane's instance write: the settings keep the spec, the environment
        //    lands in SECRET variables only.
        int[] instanceId = new int[1];
        GeneratedRows.as(attribution, () -> {
            Row instance = Models.get(InstanceModel.class).createEmptyRow();
            instance.set(InstanceModel.NAME, "preview-prev-mech--env-ref.preview.test");
            instance.set(InstanceModel.KIND, ReleaseKind.ID.toString());
            instance.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
            instance.set(InstanceModel.RUNTIME_ROLE, InstanceModel.ROLE_SERVING);
            instanceId[0] = PreviewDeployments.persistInstance(instance, desired);
        });
        assertThat(settingsOf(instanceId[0]))
            .as("step 1: no environment in instances.settings").doesNotContainKey("environment_variables")
            .as("step 1: the rest of the spec is stored").containsKey("container_port");
        assertSecretEnvironment(instanceId[0], environment, "step 1");

        // 2. An older controller's row: the environment copied into the settings in the clear.
        GeneratedRows.as(attribution, () -> {
            Row legacy = Models.get(InstanceModel.class).findById(instanceId[0]);
            Map<String, Object> settings = new LinkedHashMap<>(settingsOf(instanceId[0]));
            settings.put("environment_variables", environment);
            legacy.set(InstanceModel.SETTINGS, settings);
            Models.get(InstanceModel.class).save(legacy);
        });
        assertThat(settingsOf(instanceId[0])).as("step 2: the legacy shape is in place")
            .containsKey("environment_variables");

        // 3. The boot backfill seals it: the settings lose the environment, the values stay secret.
        assertThat(PreviewDeployments.sealPlaintextEnvironments())
            .as("step 3: at least this preview instance was sealed").isGreaterThanOrEqualTo(1);
        assertThat(settingsOf(instanceId[0]))
            .as("step 3: the plaintext environment is gone").doesNotContainKey("environment_variables")
            .as("step 3: and the spec survived the rewrite").containsKey("container_port");
        assertSecretEnvironment(instanceId[0], environment, "step 3");

        // 4. A second pass finds nothing left on this instance and rewrites none of its rows.
        List<Object> before = variableIds(instanceId[0]);
        PreviewDeployments.sealPlaintextEnvironments();
        assertThat(variableIds(instanceId[0])).as("step 4: the sealed rows are left alone").isEqualTo(before);
    }

    private static Map<String, Object> settingsOf(int instanceId) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (Models.get(InstanceModel.class).findById(instanceId).get(InstanceModel.SETTINGS)
                instanceof Map<?, ?> map) {
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        }
        return copy;
    }

    private static void assertSecretEnvironment(int instanceId, Map<String, String> environment,
                                                String step) {
        assertThat(new InstanceVariables().valuesFor(instanceId))
            .as(step + ": the container environment is exactly the preview's")
            .containsExactlyInAnyOrderEntriesOf(environment);
        for (Row variable : Models.get(InstanceVariableModel.class).findByInstanceId(instanceId)) {
            assertThat((Object) variable.get(InstanceVariableModel.KIND))
                .as(step + ": every value is stored SECRET").isEqualTo(InstanceVariableModel.KIND_SECRET);
        }
    }

    private static List<Object> variableIds(int instanceId) {
        List<Object> ids = new ArrayList<>();
        for (Row variable : Models.get(InstanceVariableModel.class).findByInstanceId(instanceId)) {
            ids.add(variable.get(InstanceVariableModel.ID));
        }
        return ids;
    }

    private static Row newPreviewRow(String ref, String hostname, Instant expiresAt) {
        var model = Models.get(PreviewDeploymentModel.class);
        Row preview = model.createEmptyRow();
        preview.set(PreviewDeploymentModel.APPLICATION_ID, applicationId);
        preview.set(PreviewDeploymentModel.REF, ref);
        preview.set(PreviewDeploymentModel.HOSTNAME, hostname);
        preview.set(PreviewDeploymentModel.STATUS, PreviewDeploymentModel.STATUS_RUNNING);
        preview.set(PreviewDeploymentModel.EXPIRES_AT,
            expiresAt != null ? expiresAt : Now.instant().plusSeconds(3600));
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
