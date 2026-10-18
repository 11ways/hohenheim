package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.instance.InstanceShell;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.common.orm.activity.FactShape;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.ChangeOp;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.history.HistoryText;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import be.elevenways.zenit.test.support.HistoryFixtureRows;
import be.elevenways.zenit.test.support.TestAccessContexts;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hohenheim's multi-key fact kinds read their typed bodies as the sentence the old log's detail said, in en and nl,
 * never as the plain "Words: value, value" fallback.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class HohenheimFactWordsTest {

    private static final LocaleChain EN = LocaleChain.ofTags("en");
    private static final LocaleChain NL = LocaleChain.ofTags("nl");

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void everyMultiKeyFactReadsAsTheSentenceItsOldDetailSaid() {
        Db.run(datasource, () -> {
            Model instances = Models.get(InstanceModel.class);
            int instance = instance();

            // 1. A restore to a new instance names its backup, what it could not restore and the ports it moved.
            assertThat(said(instances, instance, HohenheimActivityAction.RESTORED_BACKUP,
                Map.of(HohenheimActivityAction.BACKUP_KEY, 7), EN))
                .as("step 1: a complete restore").isEqualTo("Restored from backup #7");
            assertThat(said(instances, instance, HohenheimActivityAction.RESTORED_BACKUP,
                Map.of(HohenheimActivityAction.BACKUP_KEY, 7, HohenheimActivityAction.LOSSES_KEY, "config file /a"),
                EN)).as("step 1: its losses").isEqualTo("Restored from backup #7, except: config file /a");
            assertThat(said(instances, instance, HohenheimActivityAction.RESTORED_BACKUP,
                Map.of(HohenheimActivityAction.BACKUP_KEY, 7, HohenheimActivityAction.LOSSES_KEY, "config file /a",
                    HohenheimActivityAction.MOVED_PORTS_KEY, List.of("game 30000/tcp", "query 30001/udp")), EN))
                .as("step 1: and the host ports it moved")
                .isEqualTo("Restored from backup #7, except: config file /a; host ports this host already held, now"
                    + " picked by it: game 30000/tcp and query 30001/udp");
            assertThat(detail(instances, instance, NL)).as("step 1: in Dutch too")
                .isEqualTo("Teruggezet uit back-up #7, behalve: config file /a; hostpoorten die deze host al"
                    + " gebruikte, kiest hij nu zelf: game 30000/tcp en query 30001/udp");

            // 2. A status correction names both statuses as their labels, what the host reported, and a crash loop.
            assertThat(said(instances, instance, HohenheimActivityAction.RECONCILED, Map.of(
                    HohenheimActivityAction.FROM_KEY, InstanceModel.STATUS_RUNNING,
                    HohenheimActivityAction.TO_KEY, InstanceModel.STATUS_ERROR,
                    HohenheimActivityAction.DAEMON_STATE_KEY, HohenheimActivityAction.ObservedWorkload.ABSENT.token(),
                    HohenheimActivityAction.CRASH_LOOP_KEY, true), EN))
                .as("step 2: a crash-looping correction")
                .isEqualTo("Corrected from Running to Error: it died without an observed stop, and its host reports"
                    + " the workload absent; crash loop, automatic restarts suspended");
            assertThat(detail(instances, instance, NL)).as("step 2: in Dutch too")
                .isEqualTo("Gecorrigeerd van Actief naar Fout: de workload viel weg zonder waargenomen stop, en zijn"
                    + " host meldt hem afwezig; crashlus, automatische herstarts opgeschort");
            assertThat(said(instances, instance, HohenheimActivityAction.RECONCILED, Map.of(
                    HohenheimActivityAction.FROM_KEY, InstanceModel.STATUS_RUNNING,
                    HohenheimActivityAction.TO_KEY, InstanceModel.STATUS_STOPPED,
                    HohenheimActivityAction.DAEMON_STATE_KEY,
                    HohenheimActivityAction.ObservedWorkload.STOPPED.token(),
                    HohenheimActivityAction.CRASH_LOOP_KEY, false), EN))
                .as("step 2: one without a crash loop names none")
                .isEqualTo("Corrected from Running to Stopped: it died without an observed stop, and its host reports"
                    + " the workload stopped");

            // 3. A converted correction keeps its own stored text after the plain words.
            HistoryFixtureRows.action(datasource).change(InstanceModel.MODEL_ID, instance, ChangeOp.EVENT)
                .fact(HohenheimActivityAction.RECONCILED, Map.of(FactShape.LEGACY_DETAIL, "running -> error"))
                .done().write();
            assertThat(detail(instances, instance, EN)).as("step 3: a converted correction reads its old detail")
                .isEqualTo("Reconciled: running -> error");

            // 4. A settle after a controller restart, and a migration between hosts.
            assertThat(said(instances, instance, HohenheimActivityAction.SETTLED_INTERRUPTED, Map.of(
                    HohenheimActivityAction.FROM_KEY, InstanceModel.STATUS_RESTORING,
                    HohenheimActivityAction.TO_KEY, InstanceModel.STATUS_STOPPED), EN))
                .as("step 4: a settle").isEqualTo("Settled from Restoring to Stopped: a controller restart"
                    + " interrupted it");
            assertThat(detail(instances, instance, NL)).as("step 4: in Dutch too")
                .isEqualTo("Afgehandeld van Bezig met herstel naar Gestopt: een herstart van de controller onderbrak"
                    + " het");
            assertThat(said(instances, instance, HohenheimActivityAction.MIGRATED, Map.of(
                    HohenheimActivityAction.FROM_KEY, "alpha", HohenheimActivityAction.TO_KEY, "beta"), EN))
                .as("step 4: a migration").isEqualTo("Moved from alpha to beta");
            assertThat(detail(instances, instance, NL)).as("step 4: in Dutch too")
                .isEqualTo("Verhuisd van alpha naar beta");

            // 5. A shell names its shell and user, and a closed one why it ended, for every end reason there is.
            assertThat(said(instances, instance, HohenheimActivityAction.SHELL_OPEN, Map.of(
                    HohenheimActivityAction.SHELL_KEY, "/bin/sh", HohenheimActivityAction.RUN_USER_KEY, "1000"), EN))
                .as("step 5: an opened shell").isEqualTo("Opened /bin/sh as uid 1000");
            Set<String> reasons = new HashSet<>();
            for (InstanceShell.EndReason reason : InstanceShell.EndReason.values()) {
                String closed = said(instances, instance, HohenheimActivityAction.SHELL_CLOSE, Map.of(
                    HohenheimActivityAction.SHELL_KEY, "/bin/sh", HohenheimActivityAction.RUN_USER_KEY, "1000",
                    HohenheimActivityAction.CLOSED_BY_KEY, reason.name().toLowerCase(Locale.ROOT)), EN);
                assertThat(closed).as("step 5: %s says why it closed", reason)
                    .startsWith("Closed /bin/sh as uid 1000: ");
                reasons.add(closed);
            }
            assertThat(reasons).as("step 5: each end reason in its own words")
                .hasSize(InstanceShell.EndReason.values().length);
            assertThat(detail(instances, instance, NL)).as("step 5: in Dutch too")
                .isEqualTo("/bin/sh als uid 1000 gesloten: de workload stopte");

            // 6. A backup, a snapshot and a restore from either name the one they are.
            assertThat(said(instances, instance, HohenheimActivityAction.BACKUP,
                Map.of(HohenheimActivityAction.BACKUP_KEY, 4), EN)).as("step 6: a backup").isEqualTo("Backup #4 made");
            assertThat(said(instances, instance, HohenheimActivityAction.SNAPSHOT,
                Map.of(HohenheimActivityAction.SNAPSHOT_KEY, 5), EN)).as("step 6: a snapshot")
                .isEqualTo("Snapshot #5 made");
            assertThat(said(instances, instance, HohenheimActivityAction.RESTORED_SNAPSHOT,
                Map.of(HohenheimActivityAction.SNAPSHOT_KEY, 5), EN)).as("step 6: a snapshot restore")
                .isEqualTo("Restored from snapshot #5");
            assertThat(said(instances, instance, HohenheimActivityAction.RESTORED_SNAPSHOT,
                Map.of(HohenheimActivityAction.NATIVE_SNAPSHOT_KEY, "snap0"), EN)).as("step 6: a native one")
                .isEqualTo("Restored from native snapshot snap0");

            // 7. A drain counts what moved and what was refused, and says whether the host is empty.
            Model servers = Models.get(ServerModel.class);
            int host = HostFixtures.admittedIncusHost("words-host");
            assertThat(said(servers, host, HohenheimActivityAction.DRAINED, Map.of(HohenheimActivityAction.MOVED_KEY,
                    3, HohenheimActivityAction.REFUSED_KEY, 0, HohenheimActivityAction.COMPLETE_KEY, true), EN))
                .as("step 7: a complete drain").isEqualTo("Drained: 3 moved, 0 refused; the host holds none");
            assertThat(said(servers, host, HohenheimActivityAction.DRAINED, Map.of(HohenheimActivityAction.MOVED_KEY,
                    1, HohenheimActivityAction.REFUSED_KEY, 2, HohenheimActivityAction.COMPLETE_KEY, false), EN))
                .as("step 7: an incomplete one")
                .isEqualTo("Drain incomplete: 1 moved, 2 refused; the host still holds some");
            assertThat(detail(servers, host, NL)).as("step 7: in Dutch too")
                .isEqualTo("Leeghalen onvolledig: 1 verhuisd, 2 geweigerd; de host heeft er nog");

            // 8. A reap names every object it removed, in the reader's list grammar.
            assertThat(said(servers, host, HohenheimActivityAction.REAPED_CONTROLLER_OBJECTS,
                Map.of(HohenheimActivityAction.OBJECTS_KEY, List.of("acl hh-a", "network hh-b")), EN))
                .as("step 8: a reap").isEqualTo("Removed leftover controller objects: acl hh-a and network hh-b");
            assertThat(detail(servers, host, NL)).as("step 8: in Dutch too")
                .isEqualTo("Achtergebleven controllerobjecten verwijderd: acl hh-a en network hh-b");

            // 9. A certificate request says when it went through manual DNS-01, and a reissue what it covered.
            Model certificates = Models.get(CertificateModel.class);
            int certificate = certificate();
            assertThat(said(certificates, certificate, HohenheimActivityAction.REQUESTED, Map.of(), EN))
                .as("step 9: a plain request").isEqualTo("Requested");
            assertThat(said(certificates, certificate, HohenheimActivityAction.REQUESTED,
                Map.of(HohenheimActivityAction.MANUAL_KEY, true), EN)).as("step 9: a manual one")
                .isEqualTo("Requested through manual DNS-01");
            assertThat(said(certificates, certificate, HohenheimActivityAction.REISSUED, Map.of(
                    HohenheimActivityAction.HOSTNAMES_KEY, List.of("a.words.test", "b.words.test"),
                    HohenheimActivityAction.PREVIOUS_KEY, "old.words.test"), EN))
                .as("step 9: a reissue").isEqualTo("Reissued for a.words.test and b.words.test; it covered"
                    + " old.words.test before");

            // 10. A site switched off because its instance went says so; a copy names its original.
            Model sites = Models.get(SiteModel.class);
            int site = site("words-site");
            assertThat(said(sites, site, HohenheimActivityAction.DISABLED,
                Map.of(HohenheimActivityAction.STRANDED_KEY, true), EN)).as("step 10: a stranded site")
                .isEqualTo("Disabled: the instance it served was destroyed");
            assertThat(said(sites, site, HohenheimActivityAction.DISABLED, Map.of(), EN))
                .as("step 10: a plain disable").isEqualTo("Disabled");
            int copy = site("words-site-copy");
            assertThat(said(sites, copy, HohenheimActivityAction.CLONED, Map.of(HohenheimActivityAction.ORIGINAL_KEY,
                    new Change.Context(SiteModel.MODEL_ID, String.valueOf(site))), EN))
                .as("step 10: a copy names its original").isEqualTo("Copied from words-site");

            // 11. A re-pin tells the new fingerprint from the old one, and a posture acceptance labels both values.
            assertThat(said(servers, host, HohenheimActivityAction.HOST_KEY_REPINNED, Map.of(
                    HohenheimActivityAction.LANE_KEY, "ssh", HohenheimActivityAction.FINGERPRINT_KEY, "SHA256:new",
                    HohenheimActivityAction.PREVIOUS_KEY, "SHA256:old"), EN))
                .as("step 11: a re-pin").isEqualTo("Re-pinned ssh to the new fingerprint SHA256:new; the old one was"
                    + " SHA256:old");
            assertThat(detail(servers, host, NL)).as("step 11: in Dutch too")
                .isEqualTo("ssh opnieuw vastgezet op de nieuwe vingerafdruk SHA256:new; de oude was SHA256:old");
            assertThat(said(servers, host, HohenheimActivityAction.HOST_KEY_REPINNED, Map.of(
                    HohenheimActivityAction.LANE_KEY, "incus", HohenheimActivityAction.FINGERPRINT_KEY,
                    "SHA256:first"), EN))
                .as("step 11: a re-pin of a lane that held no pin names no old one")
                .isEqualTo("Re-pinned incus to the new fingerprint SHA256:first");
            assertThat(detail(servers, host, NL)).as("step 11: in Dutch too")
                .isEqualTo("incus opnieuw vastgezet op de nieuwe vingerafdruk SHA256:first");
            assertThat(said(servers, host, HohenheimActivityAction.POSTURE_RISK_ACCEPTED, Map.of(
                    HohenheimActivityAction.POSTURE_KEY, ServerModel.POSTURE_SHARED_CONTAINER,
                    HohenheimActivityAction.WARNING_VERSION_KEY, 1), EN))
                .as("step 11: a posture acceptance")
                .isEqualTo("Accepted the risk of the Shared containers posture, warning version 1");
            assertThat(detail(servers, host, NL)).as("step 11: in Dutch too")
                .isEqualTo("Risico van de houding Gedeelde containers aanvaard, waarschuwingsversie 1");

            // 12. A converted acceptance keeps its own stored text after the plain words.
            HistoryFixtureRows.action(datasource).change(ServerModel.MODEL_ID, host, ChangeOp.EVENT)
                .fact(HohenheimActivityAction.POSTURE_RISK_ACCEPTED,
                    Map.of(FactShape.LEGACY_DETAIL, "shared_container, 1"))
                .done().write();
            assertThat(detail(servers, host, EN)).as("step 12: a converted acceptance reads its old detail")
                .isEqualTo("Posture risk accepted: shared_container, 1");
        });
    }

    /** @return the record's newest detail line after recording the fact on it */
    private static @NonNull String said(@NonNull Model model, int id, @NonNull HohenheimActivityAction kind,
                                        @NonNull Map<String, Object> body, @NonNull LocaleChain locales) {
        History.event(model, id, Change.fact(kind, new LinkedHashMap<>(body)));
        return detail(model, id, locales);
    }

    private static @NonNull String detail(@NonNull Model model, int id, @NonNull LocaleChain locales) {
        AccessContext reader = TestAccessContexts.allAllowedReading(locales, ShippedCatalogs.shared());
        return HistoryText.detail(History.ofRecord(model, id, reader, null, 1).entries().get(0), reader);
    }

    private static int instance() {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, "words-instance");
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static int certificate() {
        Row row = Models.get(CertificateModel.class).createEmptyRow();
        row.set(CertificateModel.NICE_NAME, "words.test");
        row.set(CertificateModel.DOMAIN_NAMES_TEXT, "words.test");
        row.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
        row.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        Models.get(CertificateModel.class).save(row);
        return row.get(CertificateModel.ID);
    }

    private static int site(@NonNull String name) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, name);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, true);
        Models.get(SiteModel.class).save(row);
        return row.get(SiteModel.ID);
    }
}
