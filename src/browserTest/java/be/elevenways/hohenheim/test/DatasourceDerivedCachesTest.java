package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.options.HostOptions;
import be.elevenways.hohenheim.server.security.BanService;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.TypeDefinition;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The process-wide caches derived from database rows follow the datasource they were read from.
 *
 * AIDEV-NOTE: the DNS zone view, the proxy's ban set and the host option registry each used to be filled once per
 * JVM, so a shared-JVM lane served the zones, bans and hosts of an earlier class's database after the swap (the
 * landing dashboard raised "no NS records" items for another class's zones). Step 2 performs no reload of any kind:
 * the swap alone must take the old rows out of service.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class DatasourceDerivedCachesTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static final String ZONE = "swap-derived.example";
    private static final String BANNED_IP = "198.51.100.77";
    private static final String HOST = "swap-derived-host";


    @Test
    void aReplacedDatabaseLeavesNoZoneBanOrHostInService() throws Exception {
        Boolean bansEnabled = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Security.BANS_ENABLED);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Security.BANS_ENABLED, true);
        try {
            // 1. The first database's zone, ban and host are served once read.
            DnsFixtures.createZone(ZONE);
            DnsZoneStore.INSTANCE.reload();
            BanService.INSTANCE.createBan(BANNED_IP, "swap", BanModel.SOURCE_MANUAL, null, Duration.ofHours(1));
            int serverId = host();
            HostOptions.refresh();
            assertThat(DnsZoneStore.INSTANCE.getZone(ZONE)).as("step 1: the zone is served").isNotNull();
            assertThat(BanService.INSTANCE.isBanned(BANNED_IP)).as("step 1: the ban is enforced").isTrue();
            assertThat(hostName(serverId)).as("step 1: the host is offered").startsWith(HOST);

            // 2. A fresh database takes all three out of service without any reload call.
            APP.renewDatabase();
            assertThat(DnsZoneStore.INSTANCE.getZone(ZONE)).as("step 2: the old zone is not served").isNull();
            assertThat(DnsZoneStore.INSTANCE.zones()).as("step 2: no zone view of the old database")
                .noneMatch(zone -> zone.getOriginString().equals(ZONE));
            assertThat(BanService.INSTANCE.isBanned(BANNED_IP)).as("step 2: the old ban is not enforced").isFalse();
            HostOptions.ensureFresh();
            assertThat(hostName(serverId)).as("step 2: the old host is not offered").doesNotStartWith(HOST);
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Security.BANS_ENABLED, bansEnabled);
        }
    }

    private static int host() {
        HostModel model = Models.get(HostModel.class);
        Row row = model.createEmptyRow();
        row.set(HostModel.NAME, HOST);
        row.set(HostModel.RUNTIME, HostModel.RUNTIME_DOCKER);
        row.set(HostModel.MODE, HostModel.MODE_LOCAL);
        model.save(row);
        return row.get(HostModel.ID);
    }

    /** @return the offered host's display name under that id, or "" when none is offered */
    private static String hostName(int serverId) {
        TypeDefinition entry = HostOptions.REGISTRY.get(HohenheimIds.id(String.valueOf(serverId)));
        return entry == null ? "" : entry.getDisplayName();
    }
}
