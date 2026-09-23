package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.cms.DnsZoneResource;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A zone write that does not carry the SOA fields leaves them alone: the coerced map is
 * partial, and the zone's validator used to normalize an absent soa_primary_ns/soa_contact
 * to a blank and write it back on every edit that did not submit them.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class DnsZoneSoaPartialWriteTest extends HohenheimTestBase {

    private static final String ORIGIN = "soa-partial.example";

    /** The columns every zone save moves on purpose: the bumped serial and the timestamp. */
    private static final Set<String> MOVED_BY_ANY_SAVE = Set.of(
        DnsZoneModel.SERIAL.getName(), DnsZoneModel.UPDATED_AT.getName());

    private static int zoneId;
    private static AccessContext admin;

    @BeforeAll
    static void seed() {
        Row user = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        admin = AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(user.get(UserModel.ID), "Test Admin")));

        Model zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, ORIGIN);
        zone.set(DnsZoneModel.SOA_PRIMARY_NS, "ns1." + ORIGIN);
        zone.set(DnsZoneModel.SOA_CONTACT, "hostmaster@" + ORIGIN);
        zone.set(DnsZoneModel.ROLE, DnsZoneModel.ROLE_PRIMARY);
        zone.set(DnsZoneModel.ENABLED, true);
        zones.save(zone);
        zoneId = zone.get(DnsZoneModel.ID);
    }

    @Test
    void aZoneWriteWithoutTheSoaFieldsKeepsThem() {
        Model zones = Models.get(DnsZoneModel.class);
        DnsZoneResource resource = new DnsZoneResource();

        // 1. A one-entry immutable write (the inline cell lane's shape, and what a form
        //    that did not render the SOA entries submits) changes that column only.
        Map<String, Object> before = InlineCellIsolationTest.storedValues(zones, zoneId);
        resource.updateRow(zones.findById(zoneId),
            Map.of(DnsZoneModel.DEFAULT_TTL.getName(), 7200), admin);
        Map<String, Object> after = InlineCellIsolationTest.storedValues(zones, zoneId);
        assertThat(after.get(DnsZoneModel.DEFAULT_TTL.getName()))
            .as("step 1: the submitted column was written").isEqualTo(7200);
        for (Map.Entry<String, Object> column : before.entrySet()) {
            if (column.getKey().equals(DnsZoneModel.DEFAULT_TTL.getName())
                    || MOVED_BY_ANY_SAVE.contains(column.getKey())) {
                continue;
            }
            assertThat(String.valueOf(after.get(column.getKey())))
                .as("step 1: '%s' was left alone by a write that did not carry it", column.getKey())
                .isEqualTo(String.valueOf(column.getValue()));
        }

        // 2. A write that DOES carry the SOA contact still normalizes it (trimmed).
        resource.updateRow(zones.findById(zoneId),
            Map.of(DnsZoneModel.SOA_CONTACT.getName(), "  dns@" + ORIGIN + " "), admin);
        Row trimmed = zones.findById(zoneId);
        assertThat((String) trimmed.get(DnsZoneModel.SOA_CONTACT))
            .as("step 2: a carried contact is normalized").isEqualTo("dns@" + ORIGIN);
        assertThat((String) trimmed.get(DnsZoneModel.SOA_PRIMARY_NS))
            .as("step 2: the primary NS it did not carry is untouched").isEqualTo("ns1." + ORIGIN);

        // 3. A carried blank is a deliberate clear, never confused with absence.
        resource.updateRow(zones.findById(zoneId),
            Map.of(DnsZoneModel.SOA_PRIMARY_NS.getName(), ""), admin);
        assertThat((String) zones.findById(zoneId).get(DnsZoneModel.SOA_PRIMARY_NS))
            .as("step 3: a submitted blank clears the primary NS").isNullOrEmpty();

        // 4. And a carried malformed value is still refused on its own field.
        assertThatThrownBy(() -> resource.updateRow(zones.findById(zoneId),
                Map.of(DnsZoneModel.SOA_CONTACT.getName(), "not an address"), admin))
            .as("step 4: a malformed carried contact is refused")
            .isInstanceOf(Violations.class);
        assertThat((String) zones.findById(zoneId).get(DnsZoneModel.SOA_CONTACT))
            .as("step 4: and the stored contact survives the refusal").isEqualTo("dns@" + ORIGIN);
    }
}
