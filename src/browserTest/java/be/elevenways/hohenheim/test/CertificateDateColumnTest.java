package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.wording.DateStyle;
import be.elevenways.protoblast.common.i18n.wording.Reader;
import be.elevenways.protoblast.common.i18n.wording.Wording;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.time.OfficeZone;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The certificate list reads its dates ABSOLUTE first and sorts by them.
 *
 * Pinned defect: every date cell rendered through the shared zenitcms:cell/datetime,
 * which emits a lone pl-relative-time -- so the list said "in 2 months" and hid the day
 * in a hover title -- and no date column carried sortable(), so a crafted
 * {@code ?sort=expires_on} was dropped by ListQueryState and the rows came back in the
 * default created_at order.
 *
 * AIDEV-NOTE: the request carries no zn-tz cookie, so the cell reads in the office zone (office.zone, Europe/Brussels
 * in default.dry), the zone an unknown viewer zone reads as, and names it, since it is not the reader's own; the JVM
 * default zone is forced to Asia/Kathmandu for step 1, so a render that fell back to the host's own zone fails here
 * even on a host running in Europe/Brussels.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class CertificateDateColumnTest extends HohenheimHttpTestBase {

    /** Shared by both rows, so the list can be narrowed to exactly this journey. */
    private static final String MARKER = "ZzDateJourney";

    private static final String EARLY_NAME = MARKER + "-early";
    private static final String LATE_NAME = MARKER + "-late";

    /** Whole minutes: the reading names the minute and must match exactly. */
    private static final Instant EARLY_EXPIRY = Instant.parse("2031-03-04T05:06:00Z");
    private static final Instant LATE_EXPIRY = Instant.parse("2032-07-08T09:10:00Z");

    /** The office zone the walls below are read in; step 1 proves default.dry still declares it. */
    private static final ZoneId OFFICE_ZONE = ZoneId.of("Europe/Brussels");

    /** UTC+05:45 all year, the forced host zone: neither UTC nor the office zone. */
    private static final String HOST_ZONE = "Asia/Kathmandu";

    /** The expiries' times of day in the office zone: CET (+01:00) in March, CEST (+02:00) in July. */
    private static final LocalTime EARLY_WALL = LocalTime.of(6, 6);
    private static final LocalTime LATE_WALL = LocalTime.of(11, 10);

    private static Integer earlyId;
    private static Integer lateId;

    @BeforeAll
    static void seed() {
        // The EARLY expiry is created FIRST, so created_at DESC is the exact opposite of the
        // expires_on ascending order: each asserted sequence can only come from the sort
        // that step asked for.
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
        //    relative one hidden behind a tooltip, read in the office zone: the request names
        //    no viewer zone, and the host's own zone (forced to another one here) never applies.
        assertThat(OfficeZone.office()).as("step 1: default.dry declares the office zone").isEqualTo(OFFICE_ZONE);
        HttpResponse<String> list;
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(HOST_ZONE));
        try {
            list = journeyList("");
        } finally {
            TimeZone.setDefault(original);
        }
        assertThat(list.statusCode()).isEqualTo(200);
        // A cookieless English reader: the office zone, which is not theirs, so the reading names it; the moment in
        // the style a moment reads in when nothing names one (DateStyle.ofMoment, protoblast 9da86c7e).
        Reader reader = Reader.of(LocaleChain.ofTags("en"), Zenit.getMessageResolver());
        DateStyle moment = DateStyle.ofMoment(Map.of());
        assertThat(reader.zoneIsReaders()).as("step 1: no stamp is no zone of the reader's own").isFalse();
        assertThat(list.body())
            .as("step 1: the expiry cell reads as a calendar day and time in the office zone, the zone named")
            .contains(Wording.moment(reader, EARLY_EXPIRY, moment))
            .contains(Wording.moment(reader, LATE_EXPIRY, moment))
            .contains(Wording.time(reader, EARLY_WALL) + " (" + OFFICE_ZONE.getId() + ")")
            .contains(Wording.time(reader, LATE_WALL) + " (" + OFFICE_ZONE.getId() + ")");

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

        // 4. The default order is "what expires next": expiry ascending, so the
        //    soonest-expiring certificate leads although it was created first.
        assertThat(list.body().indexOf(EARLY_NAME))
            .as("step 4: the default sort leads with the soonest-expiring certificate")
            .isLessThan(list.body().indexOf(LATE_NAME));

        // 5. ?sort=-expires_on is HONOURED and reverses that order -- a crafted sort
        //    on a date column used to be dropped, because no date column was sortable.
        String descending = journeyList("&sort=-expires_on").body();
        assertThat(descending).as("step 5: both rows are listed").contains(EARLY_NAME, LATE_NAME);
        assertThat(descending.indexOf(LATE_NAME))
            .as("step 5: descending expiry puts the furthest certificate first")
            .isLessThan(descending.indexOf(EARLY_NAME));

        // 6. Another date column's sort is honoured too: newest created first.
        String newest = journeyList("&sort=-created_at").body();
        assertThat(newest).as("step 6: both rows are listed").contains(EARLY_NAME, LATE_NAME);
        assertThat(newest.indexOf(LATE_NAME))
            .as("step 6: created_at descending puts the last-created certificate first")
            .isLessThan(newest.indexOf(EARLY_NAME));
    }
}
