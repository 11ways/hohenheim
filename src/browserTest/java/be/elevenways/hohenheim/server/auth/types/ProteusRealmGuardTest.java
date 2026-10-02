package be.elevenways.hohenheim.server.auth.types;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.util.BlastLog;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import be.elevenways.zenit.test.support.OutboundFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard a site auth provider's Proteus realm calls ride: the public internet, or the private networks on the
 * operator's explicit opt-in; this host and the link-local metadata address are refused either way. A provider left
 * failing closed by the opt-in being off is named in the log at startup and on save.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class ProteusRealmGuardTest {

    private static boolean initialized = false;

    @BeforeAll
    static void boot() throws Exception {
        if (initialized) return;
        initialized = true;
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterEach
    void reset() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.ProxyAuth.PROTEUS_ALLOW_PRIVATE_NETWORKS, false);
    }

    @Test
    void aProviderRealmRidesThePublicInternetUnlessTheOperatorAdmitsPrivateNetworks() {
        // 1. By default the realm guard is the public internet: loopback, metadata and the LAN are refused.
        OutboundUrlGuard guard = ProteusAuthProviderType.realmGuard();
        assertThat(guard).as("step 1: the default guard").isSameAs(OutboundUrlGuard.PUBLIC_INTERNET);
        assertThat(guard.problemOf("http://127.0.0.1:8080/")).as("step 1: loopback refused").isNotNull();
        assertThat(guard.problemOf("http://169.254.169.254/")).as("step 1: metadata refused").isNotNull();
        assertThat(guard.problemOf("http://10.0.0.5/")).as("step 1: a private network refused").isNotNull();

        // 2. The operator's opt-in admits the private networks, and nothing more.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.ProxyAuth.PROTEUS_ALLOW_PRIVATE_NETWORKS, true);
        guard = ProteusAuthProviderType.realmGuard();
        assertThat(guard).as("step 2: the opted-in guard").isSameAs(OutboundUrlGuard.PRIVATE_NETWORKS);
        assertThat(guard.problemOf("http://10.0.0.5/")).as("step 2: a private network admitted").isNull();
        assertThat(guard.problemOf("http://127.0.0.1:8080/")).as("step 2: loopback still refused").isNotNull();
        assertThat(guard.problemOf("http://169.254.169.254/")).as("step 2: metadata still refused").isNotNull();
    }

    @Test
    void aPrivateNetworkRealmIsNamedInTheLogWhileTheOptInIsOff() {
        ProteusRealmOptInWarnings.install();
        List<String> logged = new ArrayList<>();
        BlastLog.LogSink previous = BlastLog.getLogSink();
        BlastLog.setLogSink(args -> logged.add(String.valueOf(args[0])));
        try {
            // 1. Saving a provider whose realm is on the LAN logs a warning naming it, its origin and the setting.
            saveProvider("Intranet realm", "http://10.0.0.5:3000/");
            assertThat(logged).as("step 1: one warning on save").anySatisfy(line -> assertThat(line)
                .startsWith("WARNING: site auth provider 'Intranet realm' (Proteus realm) points at")
                .contains("http://10.0.0.5:3000")
                .contains("hohenheim.proxy_auth.proteus_allow_private_networks = true"));

            // 2. A public realm and a loopback realm (no setting admits it) get none.
            logged.clear();
            saveProvider("Public realm", "http://93.184.216.34/");
            saveProvider("Loopback realm", "http://127.0.0.1:3000/");
            assertThat(logged).as("step 2: no warning").noneMatch(line -> line.contains("'Public realm'")
                || line.contains("'Loopback realm'"));

            // 3. The startup scan names the LAN provider again, and only it.
            assertThat(ProteusRealmOptInWarnings.scan().stream().filter(line -> !line.contains("Hanging")))
                .as("step 3: the scan").singleElement().asString().contains("'Intranet realm'");

            // 4. With the opt-in on, nothing waits and nothing is logged.
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.ProxyAuth.PROTEUS_ALLOW_PRIVATE_NETWORKS, true);
            assertThat(ProteusRealmOptInWarnings.scan()).as("step 4: the scan with the opt-in on")
                .noneMatch(line -> line.contains("points at"));
        } finally {
            BlastLog.setLogSink(previous);
        }
    }

    private static void saveProvider(String name, String endpoint) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ProteusAuthProviderType.ENDPOINT, endpoint);
        config.put(ProteusAuthProviderType.REALM_CLIENT, "rc");
        config.put(ProteusAuthProviderType.ACCESS_KEY, "key");
        Row provider = Models.get(SiteAuthProviderModel.class).createEmptyRow();
        provider.set(SiteAuthProviderModel.NAME, name);
        provider.set(SiteAuthProviderModel.PROVIDER_TYPE, ProteusAuthProviderType.ID.toString());
        provider.set(SiteAuthProviderModel.CONFIG, config);
        provider.set(SiteAuthProviderModel.CREATED_AT, Now.instant());
        Models.get(SiteAuthProviderModel.class).save(provider);
    }

    /**
     * The proxy boot never waits on DNS: the startup scan is a background job that finishes once a hanging resolver
     * answers, and a save under that resolver returns after the bounded check, logging "could not check".
     */
    @Test
    void theStartupScanAndASaveNeverWaitOnAHangingResolver() throws Exception {
        ProteusRealmOptInWarnings.install();
        saveProvider("Hanging realm", "http://realm.hanging.example:3000/");
        InetAddress lan = InetAddress.getByAddress("realm.hanging.example", new byte[] {10, 0, 0, 9});
        List<String> logged = new CopyOnWriteArrayList<>();
        BlastLog.LogSink previous = BlastLog.getLogSink();
        BlastLog.setLogSink(args -> logged.add(String.valueOf(args[0])));
        try (OutboundFixture hanging = OutboundFixture.pendingResolution("realm.hanging.example", lan)) {
            // 1. Starting the scan returns at once; the scan itself is still waiting on DNS.
            CompletableFuture<List<String>> scan = ProteusRealmOptInWarnings.startScan();
            assertThat(scan).as("step 1: the scan has not finished").isNotDone();

            // 2. A save returns after the bounded check, naming the provider it could not check.
            saveProvider("Hanging realm 2", "http://realm.hanging.example:4000/");
            assertThat(logged).as("step 2: could not check").anySatisfy(line -> assertThat(line)
                .startsWith("WARNING: could not check whether site auth provider 'Hanging realm 2'"));

            // 3. Once DNS answers, the background scan finishes and names the LAN realm.
            hanging.release();
            assertThat(scan.get(30, TimeUnit.SECONDS)).as("step 3: the scan names the LAN realm")
                .anySatisfy(line -> assertThat(line).contains("'Hanging realm'"));
        } finally {
            BlastLog.setLogSink(previous);
        }
    }
}
