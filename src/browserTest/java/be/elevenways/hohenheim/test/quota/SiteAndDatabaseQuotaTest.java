package be.elevenways.hohenheim.test.quota;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.quota.DatabaseQuota;
import be.elevenways.hohenheim.server.quota.SiteQuota;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The two count dimensions an instance quota structurally cannot express: SITES (eight of
 * eleven site types run no container, so counting instances counts a minority) and MANAGED
 * DATABASES (an instance slot is a workload slot an owner also spends on game servers).
 *
 * Both are proven the same way: the charge is transactional at the write funnel, racing
 * creates cannot both spend the last slot, the refusal is NAMED with its numbers, and every
 * terminating path hands the slot back -- sites through the deleted_at TRANSITION
 * (SiteResource.deleteRow stamps it through save(); no remove hook ever fires on the real
 * path) and databases through the remove PAIRING (they have no deleted_at at all).
 *
 * AIDEV-NOTE: the site race pins TWO racers against a budget of exactly one site. A green
 * run does not prove the threads interleaved -- the STATE assertions (one live row,
 * used == limit) are what fail under a lost race either way. Raise the racer count if this
 * ever needs to be more aggressive; never weaken the state assertions to a status code.
 */
class SiteAndDatabaseQuotaTest extends HohenheimTestBase {

    private static final String SITE_PREFIX = "quota-site-";
    private static final String DB_PREFIX = "quotadb-";

    private static final String SITE_BUCKET = SiteQuota.bucketKeyOf("");
    private static final String DATABASE_BUCKET = DatabaseQuota.bucketKeyOf("");

    private Integer previousSiteCap;
    private Integer previousDatabaseCap;

    @AfterEach
    void cleanUp() {
        Model sites = Models.get(SiteModel.class);
        for (Row row : sites.find().withTrashed()
                .where(SiteModel.NAME.startsWith(SITE_PREFIX)).all()) {
            sites.find().where(SiteModel.ID.eq(row.get(SiteModel.ID))).delete();
        }
        Model databases = Models.get(DatabaseModel.class);
        for (Row row : databases.find().where(DatabaseModel.NAME.startsWith(DB_PREFIX)).all()) {
            databases.find().where(DatabaseModel.ID.eq(row.get(DatabaseModel.ID))).delete();
        }
        if (this.previousSiteCap != null) {
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Quota.MAX_SITES_PER_OWNER, this.previousSiteCap);
            this.previousSiteCap = null;
        }
        if (this.previousDatabaseCap != null) {
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Quota.MAX_DATABASES_PER_OWNER, this.previousDatabaseCap);
            this.previousDatabaseCap = null;
        }
    }

    @Test
    void aSiteCountCapBindsRacingCreatesAndComesBackOnEveryExit() throws Exception {
        this.previousSiteCap = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Quota.MAX_SITES_PER_OWNER);
        long baseline = Quotas.usedOf(SITE_BUCKET);

        // 1. A site with NO container (a proxy site -- one of the eight types the instance
        //    quota can never see) is charged a site slot and stamped with the bucket.
        HttpResponse<String> first = postCreate(SITE_PREFIX + "one");
        assertThat(first.statusCode()).as("step 1: the create succeeded").isIn(200, 302, 303);
        Row created = siteNamed(SITE_PREFIX + "one");
        assertThat(created).as("step 1: the site row exists").isNotNull();
        assertThat((String) created.get(SiteModel.QUOTA_BUCKET))
            .as("step 1: stamped with the bucket it was charged to").isEqualTo(SITE_BUCKET);
        assertThat(Quotas.usedOf(SITE_BUCKET))
            .as("step 1: a containerless site still spends a slot").isEqualTo(baseline + 1);

        // 2. Cap the owner with EXACTLY one slot left, then race two creates through the
        //    real create submit.
        long limit = Quotas.usedOf(SITE_BUCKET) + 1;
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Quota.MAX_SITES_PER_OWNER, (int) limit);

        CyclicBarrier barrier = new CyclicBarrier(2);
        HttpResponse<?>[] responses = new HttpResponse<?>[2];
        Throwable[] failures = new Throwable[2];
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int slot = i;
            Thread worker = new Thread(() -> {
                try {
                    barrier.await();
                    responses[slot] = postCreate(SITE_PREFIX + "race" + slot);
                } catch (Throwable error) {
                    failures[slot] = error;
                }
            });
            worker.start();
            threads.add(worker);
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(failures).as("step 2: both submits completed").containsOnlyNulls();

        // 3. STATE first: exactly one racing row landed and the ledger is exactly full.
        List<Row> raced = liveSitesNamed(SITE_PREFIX + "race");
        assertThat(raced).as("step 3: exactly one racing create landed").hasSize(1);
        assertThat(Quotas.usedOf(SITE_BUCKET))
            .as("step 3: used == limit, not limit + 1").isEqualTo(limit);

        int refusals = 0;
        int successes = 0;
        for (HttpResponse<?> response : responses) {
            if (String.valueOf(response.body()).contains("Site quota reached")) {
                refusals++;
            } else if (response.statusCode() == 200 || response.statusCode() == 302
                    || response.statusCode() == 303) {
                successes++;
            }
        }
        assertThat(refusals).as("step 3: exactly one loser, refused by the NAMED violation")
            .isEqualTo(1);
        assertThat(successes).as("step 3: exactly one winner").isEqualTo(1);

        // 4. THE LOCKOUT TEST: the trash write SiteResource.deleteRow performs is a
        //    deleted_at stamp through save(), so the release must ride that transition.
        Row winner = raced.get(0);
        winner.set(SiteModel.DELETED_AT, Instant.now());
        Models.get(SiteModel.class).save(winner);
        assertThat(Quotas.usedOf(SITE_BUCKET))
            .as("step 4: the trash transition hands the slot back").isEqualTo(limit - 1);

        // 5. POSITIVE ANCHOR: the freed slot admits exactly one more create, and the one
        //    after that is refused again -- released headroom is a slot, never a reset.
        HttpResponse<String> replacement = postCreate(SITE_PREFIX + "replacement");
        assertThat(replacement.body()).as("step 5: the freed slot admits a new site")
            .doesNotContain("Site quota reached");
        assertThat(Quotas.usedOf(SITE_BUCKET))
            .as("step 5: and the ledger is full again").isEqualTo(limit);
        assertThat(postCreate(SITE_PREFIX + "toomany").body())
            .as("step 5: the next create is refused by name").contains("Site quota reached");

        // 6. An UNTRASH is a new claim on headroom, so with the ledger full it is refused
        //    rather than quietly reviving a site over the cap.
        Row trashed = Models.get(SiteModel.class).find().withTrashed()
            .where(SiteModel.ID.eq(winner.get(SiteModel.ID))).first();
        trashed.set(SiteModel.DELETED_AT, (Instant) null);
        assertThat(violationKeyOf(catchThrowable(() -> Models.get(SiteModel.class).save(trashed))))
            .as("step 6: reviving a trashed site over the cap is refused by name")
            .isEqualTo("site_quota_reached");

        // 7. The hard-delete pairing releases too (tests and future bulk cleanup).
        Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq(SITE_PREFIX + "replacement")).delete();
        assertThat(Quotas.usedOf(SITE_BUCKET))
            .as("step 7: a hard delete hands the slot back as well").isEqualTo(limit - 1);
    }

    @Test
    void aDatabaseCountCapBindsIndependentlyOfTheInstanceSlotTheEngineSpends() {
        this.previousDatabaseCap = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Quota.MAX_DATABASES_PER_OWNER);
        long baseline = Quotas.usedOf(DATABASE_BUCKET);

        // 1. The two dimensions are DIFFERENT buckets, so a database charge can never be
        //    read as an instance charge or vice versa.
        assertThat(DATABASE_BUCKET)
            .as("step 1: the database dimension has its own bucket namespace")
            .isNotEqualTo(be.elevenways.hohenheim.server.instance.InstanceQuota.bucketKeyOf(""));

        // 2. A database record spends a database slot and is stamped with the bucket.
        Row one = saveDatabase(DB_PREFIX + "one");
        assertThat((String) one.get(DatabaseModel.QUOTA_BUCKET))
            .as("step 2: stamped with the bucket it was charged to").isEqualTo(DATABASE_BUCKET);
        assertThat(Quotas.usedOf(DATABASE_BUCKET))
            .as("step 2: one record, one slot").isEqualTo(baseline + 1);

        // 3. THE ITEM'S POINT: cap the DATABASE count with the instance count left wide
        //    open. "N databases per tenant" is refused by the database dimension alone --
        //    the instance slot the engine container spends cannot express this.
        long limit = Quotas.usedOf(DATABASE_BUCKET);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Quota.MAX_DATABASES_PER_OWNER, (int) limit);
        assertThat(violationKeyOf(catchThrowable(() -> saveDatabase(DB_PREFIX + "over"))))
            .as("step 3: the database count refuses by name")
            .isEqualTo("database_quota_reached");
        assertThat(databaseNamed(DB_PREFIX + "over"))
            .as("step 3: and nothing was persisted -- a refusal that still wrote the row "
                + "would pass a status-only test").isNull();
        assertThat(Quotas.usedOf(DATABASE_BUCKET))
            .as("step 3: the refused create spent nothing").isEqualTo(limit);

        // 4. THE LOCKOUT TEST: databases have no deleted_at, so the ONE release lane is the
        //    remove pairing -- the same criteria delete TenantDatabases.abandon uses to
        //    compensate a half-finished allocation.
        Models.get(DatabaseModel.class).find()
            .where(DatabaseModel.ID.eq(one.get(DatabaseModel.ID))).delete();
        assertThat(Quotas.usedOf(DATABASE_BUCKET))
            .as("step 4: the hard delete hands the slot back").isEqualTo(limit - 1);

        // 5. POSITIVE ANCHOR: the freed slot admits a new allocation, so the cap is not a
        //    one-way ratchet.
        Row replacement = saveDatabase(DB_PREFIX + "replacement");
        assertThat((Integer) replacement.get(DatabaseModel.ID))
            .as("step 5: the freed slot admits a new database").isNotNull();
        assertThat(Quotas.usedOf(DATABASE_BUCKET))
            .as("step 5: and the ledger is exactly full again").isEqualTo(limit);
    }

    // -- helpers --------------------------------------------------------------

    private static Row saveDatabase(String name) {
        Row row = Models.get(DatabaseModel.class).createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, "postgres");
        row.set(DatabaseModel.DB_USER, "app");
        row.set(DatabaseModel.DB_NAME, name.replace('-', '_'));
        return Models.get(DatabaseModel.class).save(row);
    }

    private static Row databaseNamed(String name) {
        return Models.get(DatabaseModel.class).find()
            .where(DatabaseModel.NAME.eq(name)).first();
    }

    private static Row siteNamed(String name) {
        return Models.get(SiteModel.class).find().where(SiteModel.NAME.eq(name)).first();
    }

    private static List<Row> liveSitesNamed(String prefix) {
        return Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.startsWith(prefix))
            .and(SiteModel.DELETED_AT.isNull())
            .all();
    }

    private HttpResponse<String> postCreate(String name) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + "/admin/sites/new"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(
                "name=" + name + "&upstream_kind=hohenheim%3Aaddress"
                    + "&settings.forward_host=127.0.0.1&settings.forward_port=8080"))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String violationKeyOf(Throwable thrown) {
        assertThat(thrown)
            .as("the write was refused with Violations (a write that SUCCEEDS here means the"
                + " quota let it through)")
            .isInstanceOf(Violations.class);
        return ((Violations) thrown).all().get(0).message().key();
    }
}
