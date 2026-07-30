package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.SiteResource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three route-ownership invariants that the CMS read-then-write checks cannot hold on
 * their own: a simultaneous enable must leave exactly one owner and TELL the loser, a
 * deleted site must own nothing, and the domain refusal must fire for writers that never
 * touch SiteDomainResource.
 */
class RouteOwnershipInvariantTest extends HohenheimTestBase {

    private final List<Row> createdSites = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        SiteEnableWriteBarrier.clear();
        Model siteModel = Models.get(SiteModel.class);
        Model domainModel = Models.get(SiteDomainModel.class);
        for (Row site : this.createdSites) {
            Integer siteId = site.get(SiteModel.ID);
            if (siteId == null) {
                continue;
            }
            for (Row domain : domainModel.find().where(SiteDomainModel.SITE_ID.eq(siteId)).all()) {
                domainModel.delete(domain);
            }
            siteModel.delete(site);
        }
        this.createdSites.clear();
    }

    /** Persist an active static site; enabled per the flag. */
    private Row site(String name, String slug, boolean enabled) {
        Model siteModel = Models.get(SiteModel.class);
        Row row = siteModel.createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.SITE_TYPE, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.ENABLED, enabled);
        siteModel.save(row);
        this.createdSites.add(row);
        return row;
    }

    private static Row domain(Row site, String hostname) {
        Model domainModel = Models.get(SiteDomainModel.class);
        Row row = domainModel.createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domainModel.save(row);
        return row;
    }

    /**
     * The route-ownership set the proxy builds its table from: enabled, non-deleted sites
     * claiming the hostname. COUNTING owners is what reveals a takeover -- the dispatcher
     * resolves first-wins and would silently hide the second owner.
     */
    private static long liveOwnersOf(String hostname) {
        Model domainModel = Models.get(SiteDomainModel.class);
        long owners = 0;
        for (Row site : Models.get(SiteModel.class).findEnabled()) {
            for (Row domain : domainModel.find()
                    .where(SiteDomainModel.SITE_ID.eq(site.get(SiteModel.ID))).all()) {
                if (hostname.equalsIgnoreCase(SiteDomainModel.canonicalHostname(
                        domain.get(SiteDomainModel.HOSTNAME), domain.get(SiteDomainModel.MATCH_TYPE)))) {
                    owners++;
                    break;
                }
            }
        }
        return owners;
    }

    private static boolean hasViolation(Violations violations, String field, String key) {
        for (Violation violation : violations.all()) {
            if (field.equals(violation.fieldName()) && key.equals(violation.message().key())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void twoSitesEnablingOneHostnameAtTheSameInstantLeaveExactlyOneOwner() throws Exception {
        String contested = "race.example.com";
        Model siteModel = Models.get(SiteModel.class);

        // 1. Two tenants stage a site on the SAME hostname. Both are disabled, which is
        //    legal by design (drafts and clones must be allowed to sit on a hostname),
        //    so neither claims a route yet.
        Row siteA = site("Race Tenant A", "race-tenant-a", false);
        Row siteB = site("Race Tenant B", "race-tenant-b", false);
        domain(siteA, contested);
        domain(siteB, contested);
        assertThat(liveOwnersOf(contested))
            .as("step 1: two staged sites on one hostname claim nothing").isEqualTo(0);

        // 2. Tenant A starts enabling. The barrier parks it AFTER its conflict scan has
        //    passed (B is still disabled) and BEFORE it claims anything, which is the
        //    exact window the pre-fix code lost: a read that is already stale.
        Throwable[] failureOfA = new Throwable[1];
        Thread enableA = new Thread(() -> {
            Row replay = siteModel.findById(siteA.get(SiteModel.ID));
            replay.set(SiteModel.ENABLED, true);
            siteModel.save(replay);
        }, "race-enable-a");
        enableA.setUncaughtExceptionHandler((thread, error) -> failureOfA[0] = error);

        SiteEnableWriteBarrier.Coordinator coordinator = new SiteEnableWriteBarrier.Coordinator();
        coordinator.holdWriteOf(enableA);
        SiteEnableWriteBarrier.install(coordinator);
        enableA.start();
        assertThat(coordinator.awaitParked())
            .as("step 2: tenant A must reach the barrier past its conflict scan").isTrue();

        // 3. Tenant B enables while A is parked. B's own scan also passes -- A is still
        //    disabled in the database -- so B goes live. This is the second half of the
        //    race: both tenants have now passed a scan that says the hostname is free.
        Row liveB = siteModel.findById(siteB.get(SiteModel.ID));
        liveB.set(SiteModel.ENABLED, true);
        siteModel.save(liveB);
        assertThat((Boolean) siteModel.findById(siteB.get(SiteModel.ID)).get(SiteModel.ENABLED))
            .as("step 3: tenant B wins the race and is live").isTrue();

        // 4. Release A. Its scan said the hostname was free, but the unique live-route
        //    claim has already been taken, so its write is REFUSED -- and refused with the
        //    real route-conflict violation, not a driver error the tenant cannot read.
        coordinator.releaseHeldWriter();
        enableA.join(15_000);
        assertThat(enableA.isAlive()).as("step 4: tenant A's write must complete").isFalse();
        assertThat(failureOfA[0])
            .as("step 4: the loser of the race is TOLD, never silently dropped")
            .isInstanceOf(Violations.class);
        assertThat(hasViolation((Violations) failureOfA[0], "enabled", "enable_route_conflict"))
            .as("step 4: the refusal is the route conflict, anchored on 'enabled'").isTrue();

        // 5. The invariant that actually matters: the hostname has exactly ONE live owner,
        //    and it is the tenant that won.
        assertThat(liveOwnersOf(contested))
            .as("step 5: exactly one live owner of the contested hostname").isEqualTo(1);
        assertThat((Boolean) siteModel.findById(siteA.get(SiteModel.ID)).get(SiteModel.ENABLED))
            .as("step 5: the losing tenant stayed disabled").isFalse();
    }

    @Test
    void aDeletedSitesHostnameBecomesClaimableAgain() {
        String hostname = "abandoned.example.com";
        Model siteModel = Models.get(SiteModel.class);

        // 1. A tenant runs a live site on the hostname; it is the sole owner.
        Row original = site("Abandoned Original", "abandoned-original", true);
        Row originalDomain = domain(original, hostname);
        assertThat(liveOwnersOf(hostname)).as("step 1: the original owns the hostname").isEqualTo(1);

        // 2. The tenant deletes the site through the real CMS delete path -- a SOFT delete
        //    that stamps deleted_at and deliberately leaves enabled=true.
        new SiteResource().deleteRow(siteModel.findById(original.get(SiteModel.ID)),
            AccessContext.anonymous());
        Row deleted = siteModel.findById(original.get(SiteModel.ID));
        assertThat((Instant) deleted.get(SiteModel.DELETED_AT))
            .as("step 2: the delete stamped deleted_at").isNotNull();
        assertThat((Boolean) deleted.get(SiteModel.ENABLED))
            .as("step 2: the delete left enabled untouched -- the trap this test guards")
            .isTrue();

        // 3. A deleted record owns nothing: its route claim is released, so the hostname
        //    is free even though the row still says enabled.
        assertThat((String) Models.get(SiteDomainModel.class)
                .findById(originalDomain.get(SiteDomainModel.ID)).get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 3: the deleted site's domain row holds no claim").isNull();
        assertThat(liveOwnersOf(hostname)).as("step 3: the hostname has no live owner").isEqualTo(0);

        // 4. A new site takes the abandoned hostname and goes live. Before the fix this
        //    was refused forever, naming a site that appears in no UI.
        Row successor = site("Abandoned Successor", "abandoned-successor", false);
        domain(successor, hostname);
        Row liveSuccessor = siteModel.findById(successor.get(SiteModel.ID));
        liveSuccessor.set(SiteModel.ENABLED, true);
        siteModel.save(liveSuccessor);
        assertThat(liveOwnersOf(hostname))
            .as("step 4: the successor is the single live owner").isEqualTo(1);

        // 5. And the successor really holds the claim, so the next contender is refused.
        Row successorDomain = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.SITE_ID.eq(successor.get(SiteModel.ID))).first();
        assertThat((String) successorDomain.get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 5: the successor holds the live route claim").isNotNull();
    }

    @Test
    void theDomainRouteRefusalFiresOnAWriteThatNeverTouchesTheResource() {
        String hostname = "direct-save.example.com";
        Model domainModel = Models.get(SiteDomainModel.class);

        // 1. An established tenant owns the hostname on a live site.
        Row incumbent = site("Direct Incumbent", "direct-incumbent", true);
        domain(incumbent, hostname);
        assertThat(liveOwnersOf(hostname)).as("step 1: the incumbent owns the hostname").isEqualTo(1);

        // 2. A second live site tries to take it with a DIRECT model save -- no CMS
        //    resource, no form, no coerced value map. That is the write shape a seeder, an
        //    import, an API writeback or a revision restore uses, and it is exactly what a
        //    refusal living in SiteDomainResource.persistRow could never see.
        Row challenger = site("Direct Challenger", "direct-challenger", true);
        Row stolen = domainModel.createEmptyRow();
        stolen.set(SiteDomainModel.SITE_ID, challenger.get(SiteModel.ID));
        stolen.set(SiteDomainModel.HOSTNAME, hostname);
        stolen.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        assertThatThrownBy(() -> domainModel.save(stolen))
            .as("step 2: the write pipeline refuses the takeover, whoever the writer is")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "hostname", "route_taken_other_site"))
                    .as("step 2: the refusal names the route conflict on 'hostname'").isTrue());

        // 3. Nothing was persisted and the incumbent is still the sole owner.
        assertThat(domainModel.find()
                .where(SiteDomainModel.SITE_ID.eq(challenger.get(SiteModel.ID))).count())
            .as("step 3: the challenger persisted no domain row").isEqualTo(0);
        assertThat(liveOwnersOf(hostname))
            .as("step 3: the incumbent is still the single live owner").isEqualTo(1);

        // 4. Positive control: the same direct save succeeds on a free hostname, so step 2
        //    was the route invariant and not a broken write.
        Row allowed = domainModel.createEmptyRow();
        allowed.set(SiteDomainModel.SITE_ID, challenger.get(SiteModel.ID));
        allowed.set(SiteDomainModel.HOSTNAME, "direct-save-free.example.com");
        allowed.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domainModel.save(allowed);
        assertThat((String) domainModel.findById(allowed.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 4: an accepted route on a live site claims its key").isNotNull();
    }
}
