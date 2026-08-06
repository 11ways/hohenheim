package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.ReleasedClaimResource;
import be.elevenways.hohenheim.server.cms.SiteResource;
import be.elevenways.hohenheim.server.proxy.ReleasedClaims;
import be.elevenways.hohenheim.server.proxy.RouteClaims;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.cms.common.action.ActionContext;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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
        SiteDomainWriteBarrier.clear();
        SiteWriteFault.disarm();
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
        // Tearing a live site down IS a release, so cleanup itself ledgers quarantine rows.
        // They would otherwise outlive this class and refuse another class's claim on the
        // same hostname -- correct behaviour, wrong scope for a fixture.
        Models.get(ReleasedRouteClaimModel.class).find().delete();
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
        return domain(site, hostname, SiteDomainModel.MATCH_EXACT, null);
    }

    private static Row domain(Row site, String hostname, String matchType, String listenOn) {
        Model domainModel = Models.get(SiteDomainModel.class);
        Row row = domainModel.createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, matchType);
        if (listenOn != null) {
            row.set(SiteDomainModel.LISTEN_ON, listenOn);
        }
        domainModel.save(row);
        return row;
    }

    /** Stored claim rows on the hostname: routing considers at most ONE legal, ever. */
    private static long storedClaimsOn(String hostname) {
        long claims = 0;
        for (Row row : Models.get(SiteDomainModel.class).find()
                .where(SiteDomainModel.HOSTNAME.eq(hostname)).all()) {
            if (row.get(SiteDomainModel.LIVE_ROUTE_KEY) != null) {
                claims++;
            }
        }
        return claims;
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
        //    passed (B is still disabled) and BEFORE it claims anything. Since the SQLite
        //    datasource rework, a site write runs on a DEDICATED connection whose
        //    transaction holds the database's single write lock from BEGIN, so A parks
        //    holding that lock: the scan it just ran can no longer go stale, because no
        //    other writer can commit until A finishes.
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

        // 3. Tenant B enables at the same instant. Its write must QUEUE behind A's held
        //    write lock -- it can neither interleave with A's half-done claim (the pre-fix
        //    shared-connection corruption) nor complete while A is parked.
        Throwable[] failureOfB = new Throwable[1];
        Thread enableB = new Thread(() -> {
            Row liveB = siteModel.findById(siteB.get(SiteModel.ID));
            liveB.set(SiteModel.ENABLED, true);
            siteModel.save(liveB);
        }, "race-enable-b");
        enableB.setUncaughtExceptionHandler((thread, error) -> failureOfB[0] = error);
        enableB.start();
        Thread.sleep(150);
        assertThat(enableB.isAlive())
            .as("step 3: tenant B must queue behind A's write transaction, not slip inside it")
            .isTrue();

        // 4. Release A: it entered first, so it wins -- its scan and its claim were one
        //    atomic serialized transaction.
        coordinator.releaseHeldWriter();
        enableA.join(15_000);
        assertThat(enableA.isAlive()).as("step 4: tenant A's write must complete").isFalse();
        assertThat(failureOfA[0]).as("step 4: the winner's enable must succeed").isNull();

        // 5. B's queued write now runs its conflict scan against A's COMMITTED claim and
        //    is REFUSED -- with the real route-conflict violation, not a driver error the
        //    tenant cannot read.
        enableB.join(15_000);
        assertThat(enableB.isAlive()).as("step 5: tenant B's write must complete").isFalse();
        assertThat(failureOfB[0])
            .as("step 5: the loser of the race is TOLD, never silently dropped")
            .isInstanceOf(Violations.class);
        assertThat(hasViolation((Violations) failureOfB[0], "enabled", "enable_route_conflict"))
            .as("step 5: the refusal is the route conflict, anchored on 'enabled'").isTrue();

        // 6. The invariant that actually matters: the hostname has exactly ONE live owner,
        //    and it is the tenant that won.
        assertThat(liveOwnersOf(contested))
            .as("step 6: exactly one live owner of the contested hostname").isEqualTo(1);
        assertThat((Boolean) siteModel.findById(siteA.get(SiteModel.ID)).get(SiteModel.ENABLED))
            .as("step 6: the winning tenant is live").isTrue();
        assertThat((Boolean) siteModel.findById(siteB.get(SiteModel.ID)).get(SiteModel.ENABLED))
            .as("step 6: the losing tenant stayed disabled").isFalse();
    }

    /**
     * The OPERATOR-scoped half of the release rule: two sites nobody was granted anything on
     * are the same owner, so a deleted site's hostname stays freely re-claimable. Every
     * assertion here predates the release quarantine and must keep passing verbatim -- that
     * is the proof the quarantine did not change operator behaviour.
     */
    @Test
    @DisplayName("an operator-owned deleted site's hostname becomes claimable again")
    void anOperatorOwnedDeletedSitesHostnameBecomesClaimableAgain() {
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

    /**
     * The listener-OVERLAP race the unique index alone can never refuse: an all-interfaces
     * row and a single-address row spell DIFFERENT claim keys, so if the conflict scan and
     * the row write are not one serialized transaction, two simultaneous writers both pass
     * their scan and the storage ends up holding two claims routing considers one route.
     */
    @Test
    void anAllInterfacesRowAndASingleAddressRowCannotBothClaimOneHostname() throws Exception {
        String contested = "overlap.example.com";
        Model domainModel = Models.get(SiteDomainModel.class);

        // 1. Two LIVE sites, no domains yet. One will claim the hostname on every
        //    interface, the other on a single pinned address -- overlapping listener
        //    sets, therefore ONE route, but two DIFFERENT unique keys.
        Row siteAny = site("Overlap Any", "overlap-any", true);
        Row sitePinned = site("Overlap Pinned", "overlap-pinned", true);

        // 2. Writer A (all interfaces) starts and is parked AFTER its conflict scan
        //    passed and BEFORE its row reaches the datasource.
        Throwable[] failureOfA = new Throwable[1];
        Thread writeA = new Thread(() -> {
            Row row = domainModel.createEmptyRow();
            row.set(SiteDomainModel.SITE_ID, siteAny.get(SiteModel.ID));
            row.set(SiteDomainModel.HOSTNAME, contested);
            row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
            domainModel.save(row);
        }, "overlap-write-any");
        writeA.setUncaughtExceptionHandler((thread, error) -> failureOfA[0] = error);

        SiteEnableWriteBarrier.Coordinator coordinator = new SiteEnableWriteBarrier.Coordinator();
        coordinator.holdWriteOf(writeA);
        SiteDomainWriteBarrier.install(coordinator);
        writeA.start();
        assertThat(coordinator.awaitParked())
            .as("step 2: writer A must reach the barrier past its conflict scan").isTrue();

        // 3. Writer B (pinned address) races in while A is parked. Its scan cannot see
        //    A's unwritten row, so only write-transaction serialization can save the
        //    invariant: B must queue behind A's held write lock.
        Throwable[] failureOfB = new Throwable[1];
        Thread writeB = new Thread(() -> {
            Row row = domainModel.createEmptyRow();
            row.set(SiteDomainModel.SITE_ID, sitePinned.get(SiteModel.ID));
            row.set(SiteDomainModel.HOSTNAME, contested);
            row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
            row.set(SiteDomainModel.LISTEN_ON, "127.0.0.1");
            domainModel.save(row);
        }, "overlap-write-pinned");
        writeB.setUncaughtExceptionHandler((thread, error) -> failureOfB[0] = error);
        writeB.start();
        Thread.sleep(150);

        // 4. Release A and let both writers finish (promptly: a queued writer times out
        //    on SQLite's busy_timeout if the winner parks too long).
        coordinator.releaseHeldWriter();
        writeA.join(15_000);
        writeB.join(15_000);
        assertThat(writeA.isAlive()).as("step 4: writer A must complete").isFalse();
        assertThat(writeB.isAlive()).as("step 4: writer B must complete").isFalse();

        // 5. THE invariant: the storage never holds two claims routing considers one
        //    route, no matter how the two writes interleaved.
        assertThat(storedClaimsOn(contested))
            .as("step 5: exactly one stored claim on the contested route -- an "
                + "all-interfaces and a single-address claim must never coexist")
            .isEqualTo(1);

        // 6. A entered first and holds the write lock across scan+write, so A wins and
        //    B is refused with the readable cross-site route conflict.
        assertThat(failureOfA[0]).as("step 6: the first writer's claim must succeed").isNull();
        assertThat(failureOfB[0])
            .as("step 6: the loser is TOLD, never silently accepted")
            .isInstanceOf(Violations.class);
        assertThat(hasViolation((Violations) failureOfB[0], "hostname", "route_taken_other_site"))
            .as("step 6: the refusal names the route conflict on 'hostname'").isTrue();
    }

    /**
     * Single-writer route-identity journey: wildcard/exact shadowing, the restore
     * transition, and a failed enable that must leave zero claims behind.
     */
    @Test
    void wildcardShadowingRestoreAndFailedWritesKeepRouteStorageExact() {
        String hostname = "identity.example.com";
        Model siteModel = Models.get(SiteModel.class);
        Model domainModel = Models.get(SiteDomainModel.class);

        // 1. A live incumbent owns the exact hostname.
        Row incumbent = site("Identity Incumbent", "identity-incumbent", true);
        Row incumbentDomain = domain(incumbent, hostname);
        assertThat(storedClaimsOn(hostname)).as("step 1: the incumbent claims the route").isEqualTo(1);

        // 2. Wildcard/exact shadowing: a WILDCARD row spelling the same literal hostname
        //    on another live site shadows the exact row in the tiered lookup -- one
        //    contested route, refused, match type deliberately not part of the identity.
        Row shadower = site("Identity Shadower", "identity-shadower", true);
        assertThatThrownBy(() -> domain(shadower, hostname, SiteDomainModel.MATCH_WILDCARD, null))
            .as("step 2: a wildcard row shadowing a live exact row is refused")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "hostname", "route_taken_other_site"))
                    .as("step 2: the refusal names the shadowed route").isTrue());

        // 3. The incumbent is soft-deleted; its claim is released and a successor takes
        //    the hostname live.
        new SiteResource().deleteRow(siteModel.findById(incumbent.get(SiteModel.ID)),
            AccessContext.anonymous());
        assertThat(storedClaimsOn(hostname)).as("step 3: the deleted site's claim is gone").isEqualTo(0);
        Row successor = site("Identity Successor", "identity-successor", true);
        domain(successor, hostname);
        assertThat(storedClaimsOn(hostname)).as("step 3: the successor claims the route").isEqualTo(1);

        // 4. RESTORE is a transition into the route table: un-deleting the incumbent
        //    while the successor holds the route must be refused, and the refusal must
        //    write NOTHING -- the incumbent stays trashed and claimless.
        Row trashed = siteModel.findById(incumbent.get(SiteModel.ID));
        trashed.set(SiteModel.DELETED_AT, (Instant) null);
        assertThatThrownBy(() -> siteModel.save(trashed))
            .as("step 4: restoring into a taken route is refused")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "enabled", "enable_route_conflict"))
                    .as("step 4: the refusal is the enable route conflict").isTrue());
        assertThat((Instant) siteModel.findById(incumbent.get(SiteModel.ID)).get(SiteModel.DELETED_AT))
            .as("step 4: the refused restore left the site trashed").isNotNull();
        assertThat(storedClaimsOn(hostname))
            .as("step 4: the successor is still the single claimant").isEqualTo(1);

        // 5. Once the successor is deleted, the SAME restore succeeds and re-claims.
        new SiteResource().deleteRow(siteModel.findById(successor.get(SiteModel.ID)),
            AccessContext.anonymous());
        Row restorable = siteModel.findById(incumbent.get(SiteModel.ID));
        restorable.set(SiteModel.DELETED_AT, (Instant) null);
        siteModel.save(restorable);
        assertThat((String) domainModel.findById(incumbentDomain.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 5: the restored incumbent holds the claim again").isNotNull();
        assertThat(storedClaimsOn(hostname)).as("step 5: exactly one claimant").isEqualTo(1);

        // 6. FAILED WRITES: a site write that dies AFTER the claim stamp must roll the
        //    claims back with the rest of the transaction -- a failed enable leaves a
        //    disabled site owning nothing.
        String faultHost = "fault.example.com";
        Row faulty = site("Identity Faulty", "identity-faulty", false);
        Row faultyDomain = domain(faulty, faultHost);
        Row enabling = siteModel.findById(faulty.get(SiteModel.ID));
        enabling.set(SiteModel.ENABLED, true);
        SiteWriteFault.arm();
        assertThatThrownBy(() -> siteModel.save(enabling))
            .as("step 6: the injected fault aborts the enable")
            .isInstanceOf(IllegalStateException.class);
        assertThat((Boolean) siteModel.findById(faulty.get(SiteModel.ID)).get(SiteModel.ENABLED))
            .as("step 6: the failed enable left the site disabled").isFalse();
        assertThat(storedClaimsOn(faultHost))
            .as("step 6: the failed write rolled its claim stamps back").isEqualTo(0);

        // 7. The rollback left a retryable state: the same enable succeeds once the
        //    fault is gone, and the claim lands.
        Row retry = siteModel.findById(faulty.get(SiteModel.ID));
        retry.set(SiteModel.ENABLED, true);
        siteModel.save(retry);
        assertThat((String) domainModel.findById(faultyDomain.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 7: the retried enable claims the route").isNotNull();
    }

    /** A tenant subject holding manage on the site, so the two sites have different owners. */
    private static int tenantOf(Row site, String email) {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.DISPLAY_NAME, email);
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        int userId = user.get(UserModel.ID);
        grantManage(site, userId);
        return userId;
    }

    /** Give an EXISTING tenant subject manage on another site -- the same owner, twice. */
    private static void grantManage(Row site, int userId) {
        RecordGrants.grant("user", userId, SiteModel.MODEL_ID, site.get(SiteModel.ID),
            HohenheimAccess.MANAGE, true);
    }

    /** The active quarantine row for a hostname's exact-match, path-less, all-interfaces route. */
    private static Row quarantineOn(String hostname) {
        return Models.get(ReleasedRouteClaimModel.class).find()
            .where(ReleasedRouteClaimModel.CLAIM_KEY.eq(
                RouteClaims.keyOf(hostname, SiteDomainModel.MATCH_EXACT, null, null)))
            .orderBy(ReleasedRouteClaimModel.ID, SortOrder.DESC)
            .first();
    }

    /**
     * The release-quarantine journey: a tenant's abandoned hostname stays reserved FOR THAT
     * TENANT, because the CNAME it pointed here from a zone we do not host is still live and
     * we cannot retract it. Walks the whole boundary -- what is stored, who is refused on the
     * write path, who is refused on the enable path, who may still take it.
     */
    @Test
    void aReleasedTenantHostnameIsQuarantinedAgainstEveryOtherOwner() {
        String hostname = "released.tenant.example.com";
        Model siteModel = Models.get(SiteModel.class);
        Model domainModel = Models.get(SiteDomainModel.class);

        // 1. Tenant A serves the hostname on a live site it holds manage on.
        Row tenantSiteA = site("Quarantine Tenant A", "quarantine-tenant-a", true);
        int tenantA = tenantOf(tenantSiteA, "quarantine-tenant-a@test");
        domain(tenantSiteA, hostname);
        assertThat(storedClaimsOn(hostname))
            .as("step 1: tenant A holds the live route claim").isEqualTo(1);
        assertThat(HohenheimAccess.manageSubjectsOf(tenantSiteA.get(SiteModel.ID)))
            .as("step 1: and the site's owner is the tenant subject, not the operator")
            .containsExactly("user:" + tenantA);

        // 2. Tenant A deletes the site through the REAL CMS delete path. The claim is
        //    released -- and the ledger must have captured WHO released it. The subject set
        //    is only knowable before zenit-auth's afterSave grant cleanup runs, so asserting
        //    the stored set (not merely a later refusal) is what proves the capture happened
        //    on the beforeWrite tier.
        new SiteResource().deleteRow(siteModel.findById(tenantSiteA.get(SiteModel.ID)),
            AccessContext.anonymous());
        assertThat(storedClaimsOn(hostname))
            .as("step 2: the deleted tenant site holds no claim").isEqualTo(0);
        Row quarantine = quarantineOn(hostname);
        assertThat(quarantine).as("step 2: the release was recorded in the ledger").isNotNull();
        assertThat((String) quarantine.get(ReleasedRouteClaimModel.FORMER_SUBJECTS))
            .as("step 2: the ledger stored the FORMER OWNER, captured before grant cleanup")
            .isEqualTo("user:" + tenantA);
        assertThat((Instant) quarantine.get(ReleasedRouteClaimModel.RELEASED_AT))
            .as("step 2: and when it was released -- the window is measured from here")
            .isNotNull();

        // 3. A DIFFERENT tenant claims the abandoned hostname on its own live site. This is
        //    the takeover: tenant A's CNAME still points here, so accepting this would hand
        //    A's visitors to B. Refused, and nothing persisted.
        Row tenantSiteB = site("Quarantine Tenant B", "quarantine-tenant-b", true);
        tenantOf(tenantSiteB, "quarantine-tenant-b@test");
        Row seizure = domainModel.createEmptyRow();
        seizure.set(SiteDomainModel.SITE_ID, tenantSiteB.get(SiteModel.ID));
        seizure.set(SiteDomainModel.HOSTNAME, hostname);
        seizure.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        assertThat(catchThrowable(() -> domainModel.save(seizure)))
            .as("step 3: a different owner is refused inside the quarantine window")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "hostname", "route_quarantined"))
                    .as("step 3: with the quarantine refusal, not a generic conflict").isTrue());
        assertThat(domainModel.find().where(SiteDomainModel.SITE_ID.eq(tenantSiteB.get(SiteModel.ID)))
                .count())
            .as("step 3: the refused takeover persisted NO row").isEqualTo(0);

        // 4. The two-step the write path deliberately allows: staging the hostname on a
        //    DISABLED site is legal (drafts and clones must be allowed to sit anywhere), so
        //    the ENABLE seam is where that path is judged. Without a check there the whole
        //    quarantine is bypassable by disabling first.
        Row stager = site("Quarantine Stager", "quarantine-stager", false);
        tenantOf(stager, "quarantine-stager@test");
        domain(stager, hostname);
        Row goingLive = siteModel.findById(stager.get(SiteModel.ID));
        goingLive.set(SiteModel.ENABLED, true);
        assertThat(catchThrowable(() -> siteModel.save(goingLive)))
            .as("step 4: enabling a site staged on a quarantined hostname is refused too")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "enabled", "enable_route_quarantined"))
                    .as("step 4: with the enable-anchored quarantine refusal").isTrue());
        assertThat((Boolean) siteModel.findById(stager.get(SiteModel.ID)).get(SiteModel.ENABLED))
            .as("step 4: the refused enable left the site disabled").isFalse();
        assertThat(storedClaimsOn(hostname))
            .as("step 4: and claimed nothing").isEqualTo(0);

        // 5. The owner that released it may always take it back: a fresh site carrying the
        //    SAME manage subject is the same owner, and the quarantine exists to protect it,
        //    never to lock it out.
        Row reclaim = site("Quarantine Tenant A Again", "quarantine-tenant-a-again", true);
        grantManage(reclaim, tenantA);
        Row reclaimed = domain(reclaim, hostname);
        assertThat((String) domainModel.findById(reclaimed.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 5: the former owner re-claims its own hostname and holds the route")
            .isNotNull();

        // 6. THE other half: two OPERATOR sites carry EMPTY subject sets, so a release by one
        //    and a claim by the other is the same owner. The ledger row must exist for this
        //    step to mean anything -- otherwise it only proves the mechanism never fired.
        String operatorHost = "released.operator.example.com";
        Row operatorFirst = site("Quarantine Operator First", "quarantine-op-first", true);
        domain(operatorFirst, operatorHost);
        new SiteResource().deleteRow(siteModel.findById(operatorFirst.get(SiteModel.ID)),
            AccessContext.anonymous());
        Row operatorQuarantine = quarantineOn(operatorHost);
        assertThat(operatorQuarantine)
            .as("step 6: an operator release is ledgered too").isNotNull();
        assertThat((String) operatorQuarantine.get(ReleasedRouteClaimModel.FORMER_SUBJECTS))
            .as("step 6: with an EMPTY owner set -- that is what operator-owned means")
            .isEmpty();
        Row operatorSecond = site("Quarantine Operator Second", "quarantine-op-second", true);
        Row operatorClaim = domain(operatorSecond, operatorHost);
        assertThat((String) domainModel.findById(operatorClaim.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 6: operator-to-operator re-claim stays allowed, ledger row and all")
            .isNotNull();
    }

    /**
     * The recorded ADMIN OVERRIDE: the quarantine has to be liftable, or an operator whose
     * tenant really did hand a hostname over has no move at all. Proves the surface renders,
     * that the handler re-checks admin itself (visibility is not authorization), and that
     * lifting actually frees the hostname.
     */
    @Test
    void onlyAnAdminCanLiftAQuarantineAndLiftingFreesTheHostname() throws Exception {
        String hostname = "override.tenant.example.com";
        Model siteModel = Models.get(SiteModel.class);
        Model domainModel = Models.get(SiteDomainModel.class);

        // 1. A tenant releases a hostname, which quarantines it.
        Row tenantSite = site("Override Tenant", "override-tenant", true);
        tenantOf(tenantSite, "override-tenant@test");
        domain(tenantSite, hostname);
        new SiteResource().deleteRow(siteModel.findById(tenantSite.get(SiteModel.ID)),
            AccessContext.anonymous());
        Row quarantine = quarantineOn(hostname);
        assertThat(quarantine).as("step 1: the release is quarantined").isNotNull();

        // 2. A different owner is refused while it stands.
        Row successor = site("Override Successor", "override-successor", true);
        tenantOf(successor, "override-successor@test");
        Row refused = domainModel.createEmptyRow();
        refused.set(SiteDomainModel.SITE_ID, successor.get(SiteModel.ID));
        refused.set(SiteDomainModel.HOSTNAME, hostname);
        refused.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        assertThat(catchThrowable(() -> domainModel.save(refused)))
            .as("step 2: the quarantine refuses the successor").isInstanceOf(Violations.class);

        // 3. The operator surface renders and names the hostname -- an override nobody can
        //    reach is the same as no override at all.
        HttpResponse<String> list = adminGet("/admin/released-claims");
        assertThat(list.statusCode()).as("step 3: the quarantine list renders").isEqualTo(200);
        assertThat(list.body()).as("step 3: and shows the quarantined hostname")
            .contains(hostname);

        // 4. Visibility is NOT authorization: invoking the action's handler directly with a
        //    non-admin context must refuse and leave the row standing. (The button is hidden
        //    for that context too, but a hidden button is not a guard.)
        RowAction<Row> lift = null;
        for (RowAction<Row> action : new ReleasedClaimResource().rowActions()) {
            if (Identifier.of("hohenheim", "lift_quarantine").equals(action.id())) {
                lift = action;
            }
        }
        assertThat(lift).as("step 4: the resource offers the lift action").isNotNull();
        assertThat(lift.isVisibleFor(quarantine, AccessContext.anonymous()))
            .as("step 4: the button is hidden for a non-admin").isFalse();
        ((RowAction.Invoke<Row>) lift).invoke(quarantine,
            ActionContext.of(AccessContext.anonymous()));
        assertThat(Models.get(ReleasedRouteClaimModel.class)
                .findById(quarantine.get(ReleasedRouteClaimModel.ID)))
            .as("step 4: a non-admin invoke lifted NOTHING").isNotNull();

        // 5. The admin lifts it through the real invoke route, and the row is gone.
        HttpResponse<String> lifted = adminPost("/admin/released-claims/"
            + quarantine.get(ReleasedRouteClaimModel.ID) + "/action/lift_quarantine", "");
        assertThat(lifted.statusCode()).as("step 5: the admin lift is accepted")
            .isIn(200, 302, 303);
        assertThat(quarantineOn(hostname)).as("step 5: the quarantine row is gone").isNull();

        // 5b. An override nobody can attribute is not an override: the lift must be in the
        //     activity log, naming the hostname AND the administrator who freed it.
        Row entry = Models.get(ActivityModel.class).find()
            .where(ActivityModel.ACTION.eq("quarantine_lifted"))
            .and(ActivityModel.DETAIL.eq(hostname))
            .orderBy(ActivityModel.ID, SortOrder.DESC).first();
        assertThat(entry).as("step 5b: the lift is recorded as its own action").isNotNull();
        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        assertThat((String) entry.get(ActivityModel.ACTOR))
            .as("step 5b: attributed to the administrator that invoked it")
            .isEqualTo(String.valueOf(admin.get(UserModel.ID)));

        // 6. And the hostname is claimable again by the very owner that was refused in
        //    step 2 -- so step 2 was the quarantine, not a broken write.
        Row claimed = domain(successor, hostname);
        assertThat((String) domainModel.findById(claimed.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 6: the successor now holds the route").isNotNull();
    }

    private HttpResponse<String> adminGet(String path) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            .send(HttpRequest.newBuilder().uri(URI.create(baseUrl() + path))
                .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> adminPost(String path, String body) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            .send(HttpRequest.newBuilder().uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
                .header("X-Csrf-Token", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://localhost:" + getServerPort();
    }

    /**
     * The two release paths the site-write restamp does NOT cover: deleting a domain row
     * (RowResource.deleteRow runs no write hook at all) and EDITING a live row's hostname
     * (the departing key is freed with nothing observing it). A ledger written at only some
     * release points would quarantine some hostnames and silently free others.
     */
    @Test
    void abandoningOneHostnameWithoutDeletingTheSiteIsLedgeredToo() {
        Model domainModel = Models.get(SiteDomainModel.class);

        // 1. A tenant serves two hostnames on one live site.
        String dropped = "dropped.tenant.example.com";
        String renamed = "renamed.tenant.example.com";
        Row tenantSite = site("Partial Release Tenant", "partial-release-tenant", true);
        int tenant = tenantOf(tenantSite, "partial-release@test");
        Row droppedRow = domain(tenantSite, dropped);
        Row renamedRow = domain(tenantSite, renamed);
        assertThat(storedClaimsOn(dropped) + storedClaimsOn(renamed))
            .as("step 1: both hostnames are claimed").isEqualTo(2);

        // 2. The tenant deletes ONE domain row -- the site keeps running. This is the most
        //    ordinary way a hostname is abandoned, and it runs no write hook.
        domainModel.delete(droppedRow);
        Row droppedQuarantine = quarantineOn(dropped);
        assertThat(droppedQuarantine)
            .as("step 2: deleting the domain row ledgers the release").isNotNull();
        assertThat((String) droppedQuarantine.get(ReleasedRouteClaimModel.FORMER_SUBJECTS))
            .as("step 2: with the owner intact -- the site's grants were never touched")
            .isEqualTo("user:" + tenant);

        // 3. The tenant renames the other row. The departing key is released just as
        //    completely as a delete: the old hostname stops routing here.
        Row renaming = domainModel.findById(renamedRow.get(SiteDomainModel.ID));
        renaming.set(SiteDomainModel.HOSTNAME, "kept.tenant.example.com");
        domainModel.save(renaming);
        Row renamedQuarantine = quarantineOn(renamed);
        assertThat(renamedQuarantine)
            .as("step 3: renaming a live row ledgers the hostname it left behind").isNotNull();
        assertThat((String) renamedQuarantine.get(ReleasedRouteClaimModel.FORMER_SUBJECTS))
            .as("step 3: owned by the same tenant").isEqualTo("user:" + tenant);
        assertThat(storedClaimsOn("kept.tenant.example.com"))
            .as("step 3: and the row claims its new route").isEqualTo(1);

        // 4. Both abandoned hostnames are now refused to a different owner.
        Row otherTenant = site("Partial Release Other", "partial-release-other", true);
        tenantOf(otherTenant, "partial-release-other@test");
        for (String abandoned : List.of(dropped, renamed)) {
            Row seizure = domainModel.createEmptyRow();
            seizure.set(SiteDomainModel.SITE_ID, otherTenant.get(SiteModel.ID));
            seizure.set(SiteDomainModel.HOSTNAME, abandoned);
            seizure.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
            assertThat(catchThrowable(() -> domainModel.save(seizure)))
                .as("step 4: %s is quarantined against a different owner", abandoned)
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(hasViolation(violations, "hostname", "route_quarantined"))
                        .as("step 4: with the quarantine refusal").isTrue());
        }

        // 5. Positive control: an unrelated hostname on the same site still saves, so step 4
        //    was the quarantine and not a broken write path.
        Row free = domain(otherTenant, "partial-release-free.example.com");
        assertThat((String) domainModel.findById(free.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 5: an unquarantined hostname is claimed normally").isNotNull();
    }

    /**
     * The orphan trap: a HARD site delete used to leave its domain rows behind holding a
     * live_route_key. The conflict scan skipped them (their site resolves to null, so they
     * read as not-live) but the UNIQUE index still enforced, so the next claimant got a
     * duplicate-key refusal naming a holder of "?" -- the hostname was permanently
     * unclaimable with nobody to point at.
     */
    @Test
    void hardDeletingASiteTakesItsDomainRowsWithIt() {
        String hostname = "orphan.example.com";
        Model siteModel = Models.get(SiteModel.class);
        Model domainModel = Models.get(SiteDomainModel.class);

        // 1. A live site claims the hostname.
        Row doomed = site("Orphan Doomed", "orphan-doomed", true);
        Integer doomedId = doomed.get(SiteModel.ID);
        domain(doomed, hostname);
        assertThat(storedClaimsOn(hostname)).as("step 1: the site claims the route").isEqualTo(1);

        // 2. HARD delete the site row (the shape only tests use today -- and the shape that
        //    left the debris).
        siteModel.delete(doomed);
        this.createdSites.removeIf(site -> doomedId.equals(site.get(SiteModel.ID)));
        assertThat(domainModel.find().where(SiteDomainModel.SITE_ID.eq(doomedId)).count())
            .as("step 2: the delete cascaded to the site's domain rows").isEqualTo(0);
        assertThat(storedClaimsOn(hostname))
            .as("step 2: so no orphan row keeps holding the claim").isEqualTo(0);

        // 3. The hostname is claimable again, by the unique index and by the scan alike --
        //    before the fix this threw a duplicate-key conflict naming site "?".
        Row successor = site("Orphan Successor", "orphan-successor", true);
        Row claim = domain(successor, hostname);
        assertThat((String) domainModel.findById(claim.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 3: the successor claims the freed route").isNotNull();
    }

    /**
     * The wildcard/exact takeover: hostname SETS that intersect are one contested host even
     * though they spell different claim keys, and the exact tier is consulted first. Proves
     * the refusal in both directions, that the refused write commits NOTHING, and that both
     * legitimate overrides -- same site, and same OWNER across sites -- stay allowed.
     */
    @Test
    void anExactRowUnderAnotherTenantsWildcardIsRefusedButAnOwnersOwnCarveOutIsNot() {
        Model siteModel = Models.get(SiteModel.class);
        Model domainModel = Models.get(SiteDomainModel.class);

        // 1. Tenant A serves a whole subdomain space through one wildcard row. It is a
        //    TENANT site: a subject other than the operator holds manage on it.
        Row tenantA = site("Overlap Tenant A", "overlap-tenant-a", true);
        tenantOf(tenantA, "overlap-tenant-a@test");
        domain(tenantA, "*.tenant-a.example.com", SiteDomainModel.MATCH_WILDCARD, null);
        assertThat(domainModel.find().where(SiteDomainModel.SITE_ID.eq(tenantA.get(SiteModel.ID)))
                .count())
            .as("step 1: tenant A holds its wildcard row").isEqualTo(1);

        // 2. A DIFFERENT tenant claims one host inside that space, as an EXACT row on its
        //    own site. The literal strings differ, so the claim keys differ and no unique
        //    index can refuse it -- and the dispatcher consults the exact tier BEFORE the
        //    wildcard tier, so committing this would hand tenant B traffic A was serving.
        Row tenantB = site("Overlap Tenant B", "overlap-tenant-b", true);
        tenantOf(tenantB, "overlap-tenant-b@test");
        Row seizure = domainModel.createEmptyRow();
        seizure.set(SiteDomainModel.SITE_ID, tenantB.get(SiteModel.ID));
        seizure.set(SiteDomainModel.HOSTNAME, "foo.tenant-a.example.com");
        seizure.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        Throwable seizureRefusal = catchThrowable(() -> domainModel.save(seizure));
        assertThat(seizureRefusal)
            .as("step 2: an exact host under another tenant's wildcard is a takeover")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "hostname", "route_overlaps_other_site"))
                    .as("step 2: the refusal names the overlap, not a generic error").isTrue());
        assertThat(domainModel.find().where(SiteDomainModel.SITE_ID.eq(tenantB.get(SiteModel.ID)))
                .count())
            .as("step 2: the refused takeover persisted NO row").isEqualTo(0);
        assertThat(storedClaimsOn("foo.tenant-a.example.com"))
            .as("step 2: and claimed nothing").isEqualTo(0);

        // 3. The reverse direction is the same takeover: tenant B owns an exact host,
        //    tenant A tries to cast a wildcard over it.
        domain(tenantB, "app.tenant-b.example.com");
        Row umbrella = domainModel.createEmptyRow();
        umbrella.set(SiteDomainModel.SITE_ID, tenantA.get(SiteModel.ID));
        umbrella.set(SiteDomainModel.HOSTNAME, "*.tenant-b.example.com");
        umbrella.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_WILDCARD);
        Throwable umbrellaRefusal = catchThrowable(() -> domainModel.save(umbrella));
        assertThat(umbrellaRefusal)
            .as("step 3: a wildcard swallowing another tenant's exact host is refused too")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "hostname", "route_overlaps_other_site"))
                    .as("step 3: the refusal names the overlap").isTrue());
        assertThat(domainModel.find().where(SiteDomainModel.SITE_ID.eq(tenantA.get(SiteModel.ID)))
                .count())
            .as("step 3: tenant A still holds only its own wildcard row").isEqualTo(1);

        // 4. Overriding one host inside your OWN wildcard, on your own site, stays allowed.
        Row override = domain(tenantA, "foo.tenant-a.example.com");
        assertThat((String) domainModel.findById(override.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 4: the same-site override is allowed and claims its own key").isNotNull();

        // 5. A genuinely disjoint wildcard is untouched by the rule -- the check is
        //    intersection, not "anything that looks similar".
        Row disjoint = domain(tenantB, "*.tenant-b-staging.example.com",
            SiteDomainModel.MATCH_WILDCARD, null);
        assertThat((String) domainModel.findById(disjoint.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 5: a disjoint hostname set is not a conflict").isNotNull();

        // 6. THE other half of the rule: two OPERATOR-owned sites (no manage grants at
        //    all) are the same owner, so a wildcard on one and a carve-out host on the
        //    other is a deliberate configuration and must NOT be refused. A carve-out
        //    needs two sites -- the upstream is a site-level setting.
        Row operatorWildcard = site("Overlap Operator Wildcard", "overlap-op-wild", true);
        domain(operatorWildcard, "*.operator.example.com", SiteDomainModel.MATCH_WILDCARD, null);
        Row operatorExact = site("Overlap Operator Exact", "overlap-op-exact", true);
        Row carveOut = domain(operatorExact, "app.operator.example.com");
        assertThat((String) domainModel.findById(carveOut.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 6: an operator's own cross-site carve-out is allowed and claims")
            .isNotNull();

        // 7. The ENABLE seam holds the same boundary: a third tenant may sit on an
        //    overlapping host while disabled, but going live is refused and it stays off.
        Row stager = site("Overlap Stager", "overlap-stager", false);
        tenantOf(stager, "overlap-stager@test");
        domain(stager, "deep.sub.tenant-a.example.com");
        Row goingLive = siteModel.findById(stager.get(SiteModel.ID));
        goingLive.set(SiteModel.ENABLED, true);
        Throwable enableRefusal = catchThrowable(() -> siteModel.save(goingLive));
        assertThat(enableRefusal)
            .as("step 7: enabling into another tenant's wildcard space is refused")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "enabled", "enable_route_overlap"))
                    .as("step 7: the refusal is the enable-time overlap").isTrue());
        assertThat((Boolean) siteModel.findById(stager.get(SiteModel.ID)).get(SiteModel.ENABLED))
            .as("step 7: the refused enable left the site disabled").isFalse();
        assertThat(storedClaimsOn("deep.sub.tenant-a.example.com"))
            .as("step 7: and claimed nothing").isEqualTo(0);
    }

    /**
     * The window itself: a quarantine that never expires is an unclaimable hostname forever,
     * and a setting that cannot turn the mechanism off is not a setting. Walks BOTH edges of
     * the configured window and the disabled case, on the real write path.
     */
    @Test
    void aQuarantineExpiresAtTheEndOfItsWindowAndZeroDaysTurnsItOff() {
        String hostname = "window.tenant.example.com";
        Model siteModel = Models.get(SiteModel.class);
        Model domainModel = Models.get(SiteDomainModel.class);
        Model ledger = Models.get(ReleasedRouteClaimModel.class);
        int days = ReleasedClaims.windowDays();
        assertThat(days).as("step 0: the quarantine is on by default").isGreaterThan(0);

        // 1. A tenant releases the hostname; the ledger row starts the clock.
        Row tenantSite = site("Window Tenant", "window-tenant", true);
        tenantOf(tenantSite, "window-tenant@test");
        domain(tenantSite, hostname);
        new SiteResource().deleteRow(siteModel.findById(tenantSite.get(SiteModel.ID)),
            AccessContext.anonymous());
        Row quarantine = quarantineOn(hostname);
        assertThat(quarantine).as("step 1: the release is ledgered").isNotNull();

        // 2. Backdated to just INSIDE the window, a different owner is still refused --
        //    the window is measured from released_at, not from "ever recorded".
        Row stranger = site("Window Stranger", "window-stranger", true);
        tenantOf(stranger, "window-stranger@test");
        backdate(quarantine, Duration.ofDays(days).minus(Duration.ofHours(1)));
        assertThat(ReleasedClaims.remainingDays(quarantineOn(hostname)))
            .as("step 2: the refusal message still counts at least one day left")
            .isGreaterThanOrEqualTo(1);
        assertThat(catchThrowable(() -> domainModel.save(claimRow(stranger, hostname))))
            .as("step 2: one hour before expiry the takeover is still refused")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "hostname", "route_quarantined"))
                    .as("step 2: with the quarantine refusal").isTrue());

        // 3. One minute PAST the window the same stranger may claim it: the reservation is
        //    time-boxed, so an abandoned hostname is not lost to everyone forever.
        backdate(quarantineOn(hostname), Duration.ofDays(days).plus(Duration.ofMinutes(1)));
        Row expired = claimRow(stranger, hostname);
        domainModel.save(expired);
        assertThat((String) domainModel.findById(expired.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 3: past the window the stranger claims the route").isNotNull();
        domainModel.delete(expired);

        // 4. With the window set to 0 the mechanism is OFF in both directions: an active
        //    row stops refusing, and a fresh release records nothing at all.
        String offHost = "window-off.tenant.example.com";
        Row offTenant = site("Window Off Tenant", "window-off-tenant", true);
        tenantOf(offTenant, "window-off-tenant@test");
        domain(offTenant, offHost);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.RELEASE_QUARANTINE_DAYS, 0);
        try {
            new SiteResource().deleteRow(siteModel.findById(offTenant.get(SiteModel.ID)),
                AccessContext.anonymous());
            assertThat(quarantineOn(offHost))
                .as("step 4: a release with the window disabled ledgers nothing").isNull();
            Row taker = site("Window Off Taker", "window-off-taker", true);
            tenantOf(taker, "window-off-taker@test");
            Row taken = claimRow(taker, offHost);
            domainModel.save(taken);
            assertThat((String) domainModel.findById(taken.get(SiteDomainModel.ID))
                    .get(SiteDomainModel.LIVE_ROUTE_KEY))
                .as("step 4: and a different owner claims the released hostname freely")
                .isNotNull();
        } finally {
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Security.RELEASE_QUARANTINE_DAYS, days);
        }

        // 5. Turning the window back on does NOT retroactively quarantine what it did not
        //    record: the ledger, not the setting, is the memory.
        assertThat(ledger.find()
                .where(ReleasedRouteClaimModel.HOSTNAME.eq(offHost)).count())
            .as("step 5: no ledger row exists for the hostname released while off")
            .isEqualTo(0);
    }

    /**
     * HARD delete versus soft delete. A soft delete releases the claim (RouteClaims.isLive
     * is the one definition) and is ledgered by the site restamp; a hard delete cascades to
     * the domain rows instead and must be ledgered by the beforeRemove hook -- with the
     * owner still readable. If zenit-auth's grant cleanup won the race, the row would carry
     * an EMPTY subject set, which reads as operator-owned and hands the hostname to anyone.
     */
    @Test
    void hardDeletingATenantSiteQuarantinesItsHostnameWithTheOwnerIntact() {
        String hostname = "hard-delete.tenant.example.com";
        Model siteModel = Models.get(SiteModel.class);
        Model domainModel = Models.get(SiteDomainModel.class);

        // 1. A tenant serves the hostname on a live site it holds manage on.
        Row tenantSite = site("Hard Delete Tenant", "hard-delete-tenant", true);
        int tenant = tenantOf(tenantSite, "hard-delete-tenant@test");
        Integer siteId = tenantSite.get(SiteModel.ID);
        domain(tenantSite, hostname);
        assertThat(storedClaimsOn(hostname))
            .as("step 1: the tenant holds the route claim").isEqualTo(1);

        // 2. The site row is HARD deleted, cascading to its domain rows.
        siteModel.delete(tenantSite);
        this.createdSites.removeIf(row -> siteId.equals(row.get(SiteModel.ID)));
        assertThat(domainModel.find().where(SiteDomainModel.SITE_ID.eq(siteId)).count())
            .as("step 2: the delete cascaded to the domain rows").isEqualTo(0);

        // 3. The cascade is a release, and the ledger captured the OWNER -- not an empty
        //    set. Asserting the stored set is the only way to see a lost owner capture;
        //    a refusal-only assertion passes either way for a tenant claimant.
        Row quarantine = quarantineOn(hostname);
        assertThat(quarantine).as("step 3: the hard delete ledgered the release").isNotNull();
        assertThat((String) quarantine.get(ReleasedRouteClaimModel.FORMER_SUBJECTS))
            .as("step 3: with the former owner intact, captured before grant cleanup")
            .isEqualTo("user:" + tenant);

        // 4. So a different owner is refused, and an OPERATOR site -- the claimant an
        //    emptied owner set would have waved through -- is refused too.
        Row otherTenant = site("Hard Delete Other", "hard-delete-other", true);
        tenantOf(otherTenant, "hard-delete-other@test");
        assertThat(catchThrowable(() -> domainModel.save(claimRow(otherTenant, hostname))))
            .as("step 4: another tenant is refused")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "hostname", "route_quarantined"))
                    .as("step 4: with the quarantine refusal").isTrue());
        Row operatorSite = site("Hard Delete Operator", "hard-delete-operator", true);
        assertThat(catchThrowable(() -> domainModel.save(claimRow(operatorSite, hostname))))
            .as("step 4: and an operator-owned site is refused as well")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "hostname", "route_quarantined"))
                    .as("step 4: with the quarantine refusal").isTrue());

        // 5. Positive control: the same operator site claims a free hostname, so step 4
        //    was the quarantine and not a broken write.
        Row free = domain(operatorSite, "hard-delete-free.example.com");
        assertThat((String) domainModel.findById(free.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 5: a free hostname is claimed normally").isNotNull();
    }

    /**
     * The ACTOR lane of the same-owner rule. A tenant's brand new site carries no manage
     * grant yet (the grant is applied after the record exists), so its claimant subject set
     * is EMPTY -- indistinguishable from an operator site by set comparison alone. Without
     * the actor check the quarantine would lock every tenant out of the hostname it just
     * released; with a sloppy one it would let a stranger in through the same hole.
     */
    @Test
    void theFormerOwnerReclaimsThroughAGrantlessNewSiteButNobodyElseDoes() {
        String hostname = "actor-lane.tenant.example.com";
        Model siteModel = Models.get(SiteModel.class);
        Model domainModel = Models.get(SiteDomainModel.class);

        // 1. Tenant A releases the hostname; the ledger remembers A.
        Row tenantSiteA = site("Actor Lane Tenant", "actor-lane-tenant", true);
        int tenantA = tenantOf(tenantSiteA, "actor-lane-tenant@test");
        domain(tenantSiteA, hostname);
        new SiteResource().deleteRow(siteModel.findById(tenantSiteA.get(SiteModel.ID)),
            AccessContext.anonymous());
        assertThat((String) quarantineOn(hostname).get(ReleasedRouteClaimModel.FORMER_SUBJECTS))
            .as("step 1: the ledger remembers tenant A").isEqualTo("user:" + tenantA);

        // 2. A GRANTLESS site -- empty claimant set, the operator shape -- is the vehicle for
        //    all three attempts, so only the acting identity differs between them.
        Row grantless = site("Actor Lane Grantless", "actor-lane-grantless", true);
        int stranger = tenantOf(site("Actor Lane Stranger Site", "actor-lane-stranger", false),
            "actor-lane-stranger@test");

        // 3. A stranger driving that site is refused: an empty claimant set is not a pass.
        assertThat(catchThrowable(() -> Accountability.runAs(
                Accountability.ofActor(String.valueOf(stranger), Accountability.ORIGIN_WEB),
                () -> domainModel.save(claimRow(grantless, hostname)))))
            .as("step 3: a stranger acting through a grantless site is refused")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "hostname", "route_quarantined"))
                    .as("step 3: with the quarantine refusal").isTrue());

        // 4. And an unattended SYSTEM write has no identity to match, so it fails closed.
        assertThat(catchThrowable(() -> Accountability.runAs(Accountability.SYSTEM,
                () -> domainModel.save(claimRow(grantless, hostname)))))
            .as("step 4: a system write is never the former owner")
            .isInstanceOf(Violations.class);
        assertThat(storedClaimsOn(hostname))
            .as("step 4: neither refusal claimed anything").isEqualTo(0);

        // 5. Tenant A itself, acting through that same grantless site, gets its hostname
        //    back -- the case the quarantine exists to protect, not to block.
        Row reclaimed = claimRow(grantless, hostname);
        Accountability.runAs(
            Accountability.ofActor(String.valueOf(tenantA), Accountability.ORIGIN_WEB),
            () -> domainModel.save(reclaimed));
        assertThat((String) domainModel.findById(reclaimed.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 5: the former owner re-claims through a grant-free site")
            .isNotNull();
    }

    /**
     * The quarantine judged as hostname SETS, not as claim keys. A released exact hostname
     * whose CNAME still points here is delivered to whoever routes it -- and a wildcard row
     * routes it just as completely as an exact row, while spelling a different claim key.
     * Refusing only the identical key would leave the takeover open through one glob.
     */
    @Test
    void aWildcardCannotSwallowAnotherTenantsReleasedHostnameOrViceVersa() {
        Model siteModel = Models.get(SiteModel.class);
        Model domainModel = Models.get(SiteDomainModel.class);

        // 1. Tenant A releases an EXACT hostname and, on a second site, a WILDCARD space.
        String exactHost = "shop.wildzone-a.test";
        String wildcardHost = "*.wildzone-b.test";
        Row exactSite = site("Wildzone Exact", "wildzone-exact", true);
        int tenantA = tenantOf(exactSite, "wildzone-a@test");
        domain(exactSite, exactHost);
        Row wildcardSite = site("Wildzone Wildcard", "wildzone-wild", true);
        grantManage(wildcardSite, tenantA);
        domain(wildcardSite, wildcardHost, SiteDomainModel.MATCH_WILDCARD, null);
        new SiteResource().deleteRow(siteModel.findById(exactSite.get(SiteModel.ID)),
            AccessContext.anonymous());
        new SiteResource().deleteRow(siteModel.findById(wildcardSite.get(SiteModel.ID)),
            AccessContext.anonymous());
        assertThat(quarantineOn(exactHost)).as("step 1: the exact release is ledgered").isNotNull();

        // 2. A different tenant claims the PARENT wildcard. Its claim key differs from the
        //    released one, but its hostname set contains the released host, and the
        //    dangling CNAME resolves straight into it.
        Row raider = site("Wildzone Raider", "wildzone-raider", true);
        tenantOf(raider, "wildzone-raider@test");
        Row swallow = claimRow(raider, "*.wildzone-a.test");
        swallow.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_WILDCARD);
        assertThat(catchThrowable(() -> domainModel.save(swallow)))
            .as("step 2: a wildcard swallowing a released hostname is refused")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "hostname", "route_quarantined"))
                    .as("step 2: with the quarantine refusal").isTrue());
        assertThat(storedClaimsOn("*.wildzone-a.test"))
            .as("step 2: and the refused wildcard claimed nothing").isEqualTo(0);

        // 3. The mirror image: an EXACT row carved out of a released wildcard space. The
        //    exact tier is consulted first, so this is the sharper half of the same attack.
        assertThat(catchThrowable(() -> domainModel.save(claimRow(raider, "shop.wildzone-b.test"))))
            .as("step 3: an exact host inside a released wildcard space is refused too")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "hostname", "route_quarantined"))
                    .as("step 3: with the quarantine refusal").isTrue());

        // 4. The ENABLE seam holds the same line: staging the swallow on a disabled site and
        //    going live is the documented two-step around the write path.
        Row stager = site("Wildzone Stager", "wildzone-stager", false);
        tenantOf(stager, "wildzone-stager@test");
        Row staged = claimRow(stager, "*.wildzone-a.test");
        staged.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_WILDCARD);
        domainModel.save(staged);
        Row goingLive = siteModel.findById(stager.get(SiteModel.ID));
        goingLive.set(SiteModel.ENABLED, true);
        assertThat(catchThrowable(() -> siteModel.save(goingLive)))
            .as("step 4: enabling the staged swallow is refused")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "enabled", "enable_route_quarantined"))
                    .as("step 4: with the enable-anchored quarantine refusal").isTrue());
        assertThat((Boolean) siteModel.findById(stager.get(SiteModel.ID)).get(SiteModel.ENABLED))
            .as("step 4: the refused enable left the site disabled").isFalse();

        // 5. Tenant A may still take its own space back through a wildcard, and an
        //    unrelated wildcard on a free space is claimed normally -- so steps 2-4 were
        //    the quarantine, not a blanket refusal of globs.
        Row reclaim = site("Wildzone Reclaim", "wildzone-reclaim", true);
        grantManage(reclaim, tenantA);
        Row own = claimRow(reclaim, "*.wildzone-a.test");
        own.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_WILDCARD);
        domainModel.save(own);
        assertThat((String) domainModel.findById(own.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 5: the former owner's own wildcard re-claim is allowed").isNotNull();
        Row free = claimRow(raider, "*.wildzone-free.test");
        free.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_WILDCARD);
        domainModel.save(free);
        assertThat((String) domainModel.findById(free.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 5: an unrelated wildcard is claimed normally").isNotNull();
    }

    /**
     * The REGEX tier of the same takeover, in both directions. A regex row spells a claim
     * key nothing else can equal and carries no glob for the set comparison to walk, so it
     * used to slip past the quarantine entirely -- and for a RELEASED hostname the "regex is
     * consulted last" bound is empty, because nothing claims it. ReleasedClaimRegexShadowTest
     * drives the routing half that makes this a takeover rather than a bookkeeping slip.
     */
    @Test
    void aRegexRowCannotShadowAReleasedHostnameInEitherDirection() {
        Model siteModel = Models.get(SiteModel.class);
        Model domainModel = Models.get(SiteDomainModel.class);

        // 1. Tenant A releases an EXACT hostname, and on a second site a REGEX space.
        String exactHost = "shop.regexzone-a.test";
        String regexSpace = "^(shop|www)\\.regexzone-b\\.test$";
        Row exactSite = site("Regexzone Exact", "regexzone-exact", true);
        int tenantA = tenantOf(exactSite, "regexzone-a@test");
        domain(exactSite, exactHost);
        Row regexSite = site("Regexzone Regex", "regexzone-regex", true);
        grantManage(regexSite, tenantA);
        domain(regexSite, regexSpace, SiteDomainModel.MATCH_REGEX, null);
        new SiteResource().deleteRow(siteModel.findById(exactSite.get(SiteModel.ID)),
            AccessContext.anonymous());
        new SiteResource().deleteRow(siteModel.findById(regexSite.get(SiteModel.ID)),
            AccessContext.anonymous());
        Row exactLedger = quarantineOn(exactHost);
        assertThat(exactLedger).as("step 1: the exact release is ledgered").isNotNull();
        assertThat((String) exactLedger.get(ReleasedRouteClaimModel.FORMER_SUBJECTS))
            .as("step 1: with the TENANT as former owner, not an empty operator set")
            .isEqualTo("user:" + tenantA);
        Row regexLedger = Models.get(ReleasedRouteClaimModel.class).find()
            .where(ReleasedRouteClaimModel.CLAIM_KEY.eq(
                RouteClaims.keyOf(regexSpace, SiteDomainModel.MATCH_REGEX, null, null)))
            .orderBy(ReleasedRouteClaimModel.ID, SortOrder.DESC).first();
        assertThat(regexLedger).as("step 1: and so is the regex release").isNotNull();
        assertThat((String) regexLedger.get(ReleasedRouteClaimModel.MATCH_TYPE))
            .as("step 1: the ledger REMEMBERS it was a regex -- the claim key cannot tell a "
                + "pattern apart from a hostname that merely looks like one")
            .isEqualTo(SiteDomainModel.MATCH_REGEX);

        // 2. Direction one: a stranger claims the released exact host as a REGEX row.
        Row raider = site("Regexzone Raider", "regexzone-raider", true);
        tenantOf(raider, "regexzone-raider@test");
        Row shadow = claimRow(raider, "^shop\\.regexzone-a\\.test$");
        shadow.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_REGEX);
        assertThat(catchThrowable(() -> domainModel.save(shadow)))
            .as("step 2: a regex row matching a released hostname is refused")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "hostname", "route_quarantined"))
                    .as("step 2: with the quarantine refusal, not some other 422").isTrue());
        assertThat(domainModel.find()
                .where(SiteDomainModel.HOSTNAME.eq("^shop\\.regexzone-a\\.test$")).all())
            .as("step 2: and the refused row was not written at all").isEmpty();

        // 3. Direction two: a stranger carves one exact host out of the released regex
        //    space. The exact tier is consulted FIRST, so this is the sharper half.
        assertThat(catchThrowable(() -> domainModel.save(claimRow(raider, "www.regexzone-b.test"))))
            .as("step 3: an exact host inside a released regex space is refused too")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "hostname", "route_quarantined"))
                    .as("step 3: with the quarantine refusal").isTrue());
        assertThat(storedClaimsOn("www.regexzone-b.test"))
            .as("step 3: and it claimed nothing").isEqualTo(0);

        // 4. The ENABLE seam: staging the shadow on a disabled site and going live is the
        //    documented two-step around the write path, and it is closed here too.
        Row stager = site("Regexzone Stager", "regexzone-stager", false);
        tenantOf(stager, "regexzone-stager@test");
        Row staged = claimRow(stager, "^shop\\.regexzone-a\\.test$");
        staged.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_REGEX);
        domainModel.save(staged);
        Row goingLive = siteModel.findById(stager.get(SiteModel.ID));
        goingLive.set(SiteModel.ENABLED, true);
        assertThat(catchThrowable(() -> siteModel.save(goingLive)))
            .as("step 4: enabling the staged regex shadow is refused")
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(hasViolation(violations, "enabled", "enable_route_quarantined"))
                    .as("step 4: with the enable-anchored quarantine refusal").isTrue());
        assertThat((Boolean) siteModel.findById(stager.get(SiteModel.ID)).get(SiteModel.ENABLED))
            .as("step 4: the refused enable left the site disabled").isFalse();

        // 5. Not a blanket refusal of regex rows: the former owner takes its own space back
        //    through a pattern, a host the released pattern does NOT match is free, and an
        //    unrelated pattern is claimed normally.
        Row reclaim = site("Regexzone Reclaim", "regexzone-reclaim", true);
        grantManage(reclaim, tenantA);
        Row own = claimRow(reclaim, "^shop\\.regexzone-a\\.test$");
        own.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_REGEX);
        domainModel.save(own);
        assertThat((String) domainModel.findById(own.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 5: the former owner's own regex re-claim is allowed").isNotNull();
        Row outside = claimRow(raider, "mail.regexzone-b.test");
        domainModel.save(outside);
        assertThat((String) domainModel.findById(outside.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 5: a hostname outside the released pattern is claimed normally").isNotNull();
        Row unrelated = claimRow(raider, "^api\\.regexzone-free\\.test$");
        unrelated.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_REGEX);
        domainModel.save(unrelated);
        assertThat((String) domainModel.findById(unrelated.get(SiteDomainModel.ID))
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 5: an unrelated regex row still claims its route").isNotNull();
    }

    /** An unsaved exact-match domain row claiming a hostname for a site. */
    private static Row claimRow(Row site, String hostname) {
        Row row = Models.get(SiteDomainModel.class).createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        return row;
    }

    /** Move a ledger row's release moment back, to sit on a chosen side of the window. */
    private static void backdate(Row quarantine, Duration age) {
        quarantine.set(ReleasedRouteClaimModel.RELEASED_AT, Instant.now().minus(age));
        Models.get(ReleasedRouteClaimModel.class).save(quarantine);
    }

    /**
     * Injects one failure into the SiteModel write pipeline AFTER the route-claim
     * restamp hook (registration order: the enable invariant is installed at boot, this
     * hook at test load), so a test can prove claim stamps roll back with their write.
     */
    static final class SiteWriteFault {

        private static volatile boolean armed;

        static {
            SiteModel.SCHEMA.addBeforeWriteHook(context -> {
                if (armed) {
                    armed = false;
                    throw new IllegalStateException("Injected site write fault");
                }
            });
        }

        private SiteWriteFault() {
        }

        static void arm() {
            armed = true;
        }

        static void disarm() {
            armed = false;
        }
    }
}
