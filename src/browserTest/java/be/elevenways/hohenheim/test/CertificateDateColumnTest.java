package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The certificate list reads its dates ABSOLUTE first and sorts by them.
 *
 * Pinned defect: every date cell rendered through the shared zenitcms:cell/datetime,
 * which emits a lone pl-relative-time -- so the list said "in 2 months" and hid the day
 * in a hover title -- and no date column carried sortable(), so a crafted
 * {@code ?sort=expires_on} was dropped by ListQueryState and the rows came back in the
 * default created_at order.
 */
class CertificateDateColumnTest extends HohenheimTestBase {

    /** Shared by both rows, so the list can be narrowed to exactly this journey. */
    private static final String MARKER = "ZzDateJourney";

    private static final String EARLY_NAME = MARKER + "-early";
    private static final String LATE_NAME = MARKER + "-late";

    /** Whole minutes: the wall-clock reading is "yyyy-MM-dd HH:mm" and must match exactly. */
    private static final Instant EARLY_EXPIRY = Instant.parse("2031-03-04T05:06:00Z");
    private static final Instant LATE_EXPIRY = Instant.parse("2032-07-08T09:10:00Z");

    private static final String EARLY_WALL = "2031-03-04 05:06";
    private static final String LATE_WALL = "2032-07-08 09:10";

    private static Integer earlyId;
    private static Integer lateId;

    @BeforeAll
    static void seed() {
        // The EARLY expiry is created FIRST, so the default created_at DESC order is the
        // exact opposite of the expires_on ascending order: nothing but an honoured sort
        // can produce the expected sequence.
        earlyId = createCertificate(EARLY_NAME, EARLY_EXPIRY, Instant.parse("2026-01-01T00:00:00Z"));
        lateId = createCertificate(LATE_NAME, LATE_EXPIRY, Instant.parse("2026-01-02T00:00:00Z"));
    }

    @AfterAll
    static void cleanUp() {
        Model certificates = Models.get(CertificateModel.class);
        if (earlyId != null) {
            certificates.delete(earlyId);
        }
        if (lateId != null) {
            certificates.delete(lateId);
        }
    }

    private static Integer createCertificate(String name, Instant expiresOn, Instant createdAt) {
        Model certificates = Models.get(CertificateModel.class);
        Row row = certificates.createEmptyRow();
        row.set(CertificateModel.NICE_NAME, name);
        row.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
        row.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        row.set(CertificateModel.EXPIRES_ON, expiresOn);
        row.set(CertificateModel.CREATED_AT, createdAt);
        certificates.save(row);
        return row.get(CertificateModel.ID);
    }

    /** The narrowed list: only this journey's two certificates, in the asked order. */
    private HttpResponse<String> journeyList(String extraQuery) throws Exception {
        return adminGet("/admin/certificates?filter.nice_name=" + MARKER + extraQuery);
    }

    @Test
    void theListReadsExpiryAbsoluteFirstAndHonoursASortOnIt() throws Exception {

        // 1. The cell carries the ABSOLUTE wall-clock reading as its text, not only a
        //    relative one hidden behind a tooltip.
        HttpResponse<String> list = journeyList("");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body())
            .as("step 1: the expiry cell reads as a calendar day and time")
            .contains(EARLY_WALL)
            .contains(LATE_WALL);

        // 2. The relative reading is still there, live, beside it.
        assertThat(list.body())
            .as("step 2: the relative reading survives as a live pl-relative-time")
            .contains("<pl-relative-time")
            .contains(EARLY_EXPIRY.toString())
            .contains(LATE_EXPIRY.toString());

        // 3. The column offers the sort itself: the header links to it.
        assertThat(list.body())
            .as("step 3: the expiry column header offers its own sort link")
            .contains("sort=expires_on");

        // 4. The default order is created_at DESC: the LATER-expiring row was created last.
        assertThat(list.body().indexOf(LATE_NAME))
            .as("step 4: the default sort still leads with the newest certificate")
            .isLessThan(list.body().indexOf(EARLY_NAME));

        // 5. ?sort=expires_on is HONOURED and reverses that order -- it used to be
        //    dropped, because no date column was sortable.
        String ascending = journeyList("&sort=expires_on").body();
        assertThat(ascending.indexOf(EARLY_NAME))
            .as("step 5: ascending expiry puts the soonest certificate first")
            .isLessThan(ascending.indexOf(LATE_NAME));

        // 6. And the direction is the reader's to choose.
        String descending = journeyList("&sort=expires_on&dir=desc").body();
        assertThat(descending.indexOf(LATE_NAME))
            .as("step 6: descending expiry puts the furthest certificate first")
            .isLessThan(descending.indexOf(EARLY_NAME));
    }
}
