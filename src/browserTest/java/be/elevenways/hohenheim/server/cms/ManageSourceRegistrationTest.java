package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.DatabaseDumpModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.registry.Registry;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.server.page.CmsRecordSources;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The /manage panel's record-source declarations against a FRESH registry, in both boot
 * orders and under the strict posture a debug server boots with.
 *
 * AIDEV-NOTE: this exists because a fresh install died in the MODULES boot stage with
 * "RecordSource 'hohenheim:site' replaces the framework-derived default but changes its
 * access gates" -- four explicit register(...) calls that predate zenit-cms deriving a
 * default for those models. No test caught it: every suite boots with strict registration
 * OFF (the refusal only slogs) and against a registry the previous class already filled,
 * so the derived-versus-explicit comparison never ran. Both halves are reproduced here.
 */
class ManageSourceRegistrationTest extends HohenheimTestBase {

    @BeforeAll
    static void seedOneSite() {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Source Registration Site");
        site.set(SiteModel.SLUG, "source-registration-site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
    }

    @Test
    void manageSourcesDeclareTheirNarrowingInBothBootOrders() {

        List<RecordSourceRegistry.Entry> registered = RecordSourceRegistry.INSTANCE.snapshot();
        boolean strictBefore = RecordSourceRegistry.INSTANCE.isStrictRegistration();

        try {
            // 1. A debug server's posture: a refused registration THROWS instead of
            //    slogging, which is exactly how the boot failure presented.
            RecordSourceRegistry.INSTANCE.setStrictRegistration(true);

            // 2. The production boot order: zenit-cms derives its defaults from the
            //    panels' RowResources first, then hohenheim declares its own sources
            //    over them.
            resetRegistry();
            deriveFrameworkDefaults();
            RecordSource<?> derived = RecordSourceRegistry.INSTANCE
                .requireDefaultFor(SiteModel.MODEL_ID);
            assertThat(derived.requiredPermissions())
                .as("step 2: zenit-cms must really derive a gated default for site,"
                    + " or this test proves nothing")
                .isNotEmpty();

            assertThatCode(ManagePanel::declareSources)
                .as("step 2: declaring the manage sources over the derived defaults"
                    + " must not be refused")
                .doesNotThrowAnyException();

            // 3. The reverse boot order: the explicit declarations land first and the
            //    derived defaults arrive after. The registry remembers the declared
            //    intent, so this order must be just as quiet.
            resetRegistry();
            ManagePanel.declareSources();
            assertThatCode(ManageSourceRegistrationTest::deriveFrameworkDefaults)
                .as("step 3: deriving the defaults after the explicit declarations"
                    + " must not be refused either")
                .doesNotThrowAnyException();

            // 4. The declaration that won is hohenheim's, not the derived one: no
            //    blanket permission, and the per-principal manage scope intact.
            RecordSource<?> siteSource = RecordSourceRegistry.INSTANCE
                .requireDefaultFor(SiteModel.MODEL_ID);
            assertThat(siteSource)
                .as("step 4: the explicit declaration must hold the site source")
                .isNotSameAs(derived);
            assertThat(siteSource.hasAccessCriteria())
                .as("step 4: the manage scope must survive the deliberate replacement")
                .isTrue();
            assertThat(siteSource.requiredPermissions())
                .as("step 4: the site source is gated by its scope, never by a"
                    + " blanket permission")
                .isEmpty();

            // 5. And the scope still BITES: a site exists, yet an anonymous audience
            //    reaches none of it through the source.
            assertThat(Models.get(SiteModel.class).find().count())
                .as("step 5: the seeded site must be readable without a scope")
                .isPositive();
            assertThat(siteSource.buildQuery(null, null, null, SortOrder.ASC, null, null)
                    .count())
                .as("step 5: an anonymous audience must reach no site through the"
                    + " manage-scoped source")
                .isZero();
        } finally {
            RecordSourceRegistry.INSTANCE.setStrictRegistration(strictBefore);
            RecordSourceRegistry.INSTANCE.restoreSnapshot(registered);
            // The memoization is process-wide: leave it claiming every panel again so a
            // later request never re-derives against the restored registry.
            deriveFrameworkDefaults();
        }
    }

    @Test
    void hohenheimSourcesRegisterAgainOverARestoredRegistry() {

        List<RecordSourceRegistry.Entry> registered = RecordSourceRegistry.INSTANCE.snapshot();
        boolean strictBefore = RecordSourceRegistry.INSTANCE.isStrictRegistration();

        try {
            // 1. A registry restored to empty (test isolation, a second boot in one JVM) holds none of the
            //    sources the first boot registered.
            resetRegistry();
            assertThat(RecordSourceRegistry.INSTANCE.byId(HohenheimSources.SPAMSERVICE_SYSTEM_USERS))
                .as("step 1: the restored registry holds no spamservice user source").isNull();

            // 2. Registering again installs them, and the handle reads the source installed.
            HohenheimSources.register();
            RecordSource<?> installed = RecordSourceRegistry.INSTANCE.byId(HohenheimSources.SPAMSERVICE_SYSTEM_USERS);
            assertThat(installed)
                .as("step 2: THE ASSERTION -- registering again installs the source over the restored registry")
                .isNotNull();
            assertThat(HohenheimSources.SPAMSERVICE_SYSTEM_USER_SOURCE.source())
                .as("step 2: the handle reads the source the registry installed").isSameAs(installed);

            // 3. A repeat registration keeps what it installed, even under the strict posture.
            RecordSourceRegistry.INSTANCE.setStrictRegistration(true);
            assertThatCode(HohenheimSources::register)
                .as("step 3: registering twice is never refused").doesNotThrowAnyException();
            assertThat(RecordSourceRegistry.INSTANCE.byId(HohenheimSources.SPAMSERVICE_SYSTEM_USERS))
                .as("step 3: the installed source stays").isSameAs(installed);
        } finally {
            RecordSourceRegistry.INSTANCE.setStrictRegistration(strictBefore);
            RecordSourceRegistry.INSTANCE.restoreSnapshot(registered);
            deriveFrameworkDefaults();
        }
    }

    /**
     * Every coded kind says who reads it on each role set a live host runs, and each viewer reads the same codes on
     * every one of them: an operator all, a tenant what his /manage panel reads, a stranger none.
     *
     * AIDEV-NOTE: a boot is process-global, so each role set is a replay of Hohenheim's share of one: its sources
     * dropped, its two panels built anew under the role snapshot, its registrations run in boot order and zenit-cms's
     * derivation over the new panels. The full set is the control: the real boot passes, so it must pass here too.
     */
    @Test
    void everyCodedKindIsReadAlikeOnEveryRoleSetALiveHostRuns() {
        Set<Role> booted = EnumSet.noneOf(Role.class);
        for (Role role : Role.values()) {
            if (HohenheimRoles.enabled(role)) {
                booted.add(role);
            }
        }
        List<RecordSourceRegistry.Entry> registered = RecordSourceRegistry.INSTANCE.snapshot();
        List<Registry.Entry<Panel>> panels = PanelRegistry.snapshot();
        int appId = saved(InstanceModel.class, row -> {
            row.set(InstanceModel.NAME, "coded-reads-app");
            row.set(InstanceModel.KIND, "hohenheim:docker_container");
            row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
            row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        });
        int backupId = saved(InstanceBackupModel.class, row -> {
            row.set(InstanceBackupModel.INSTANCE_ID, appId);
            row.set(InstanceBackupModel.STATUS, "complete");
        });
        int dumpId = saved(DatabaseDumpModel.class, row -> {
            row.set(DatabaseDumpModel.DATABASE_NAME, "coded_reads_db");
            row.set(DatabaseDumpModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
            row.set(DatabaseDumpModel.STATUS, "complete");
        });
        UUID tenantId = ApiSupport.user("coded-reads-tenant-" + UUID.randomUUID() + "@surface.test", "Coded Tenant");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, appId,
            HohenheimCapabilities.BACKUPS, true);
        UUID strangerId = ApiSupport.user("coded-reads-stranger-" + UUID.randomUUID() + "@surface.test",
            "Coded Stranger");
        Map<String, AccessContext> viewers = new LinkedHashMap<>();
        viewers.put("operator", TenantConduits.operator());
        viewers.put("tenant", AccessContext.of(TenantConduits.stubFor(new UserPrincipal(tenantId, "Coded Tenant"))));
        viewers.put("stranger",
            AccessContext.of(TenantConduits.stubFor(new UserPrincipal(strangerId, "Coded Stranger"))));
        String backup = String.valueOf(backupId);
        String dump = String.valueOf(dumpId);
        try {
            // 1. The booted host, every role on: each viewer's codes are what his own panel reads. The backup is
            //    read by the operator's admin list and the tenant's /manage list; the dump only by the admin list,
            //    which is also what the dump's derived reference source (its read before this landing) says.
            Map<String, Set<Identifier>> full = judgement(viewers, backupId, dumpId);
            assertThat(full).as("step 1: an operator reads both, a tenant his backup, a stranger nothing")
                .isEqualTo(Map.of("operator", Set.of(InstanceBackupModel.MODEL_ID, DatabaseDumpModel.MODEL_ID),
                    "tenant", Set.of(InstanceBackupModel.MODEL_ID), "stranger", Set.of()));
            Panel admin = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN));
            Panel manage = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.MANAGE));
            RecordSource<?> adminBackups = Objects.requireNonNull(CmsRecordSources.panelSource(admin,
                Objects.requireNonNull(admin.entryBySlug(HohenheimSlugs.INSTANCE_BACKUPS))));
            RecordSource<?> manageBackups = Objects.requireNonNull(CmsRecordSources.panelSource(manage,
                Objects.requireNonNull(manage.entryBySlug(HohenheimSlugs.INSTANCE_BACKUPS))));
            RecordSource<?> dumps = Objects.requireNonNull(
                RecordSourceRegistry.INSTANCE.referenceSourceFor(DatabaseDumpModel.MODEL_ID));
            assertThat(adminBackups.readableAmong(viewers.get("operator"), List.of(backup)))
                .as("step 1: the operator's admin list reads the backup").isNotEmpty();
            assertThat(manageBackups.readableAmong(viewers.get("tenant"), List.of(backup)))
                .as("step 1: the tenant's /manage list reads the backup").isNotEmpty();
            assertThat(manageBackups.readableAmong(viewers.get("stranger"), List.of(backup)))
                .as("step 1: a stranger's does not").isEmpty();
            assertThat(dumps.readableAmong(viewers.get("operator"), List.of(dump)))
                .as("step 1: the dump's own source admits the operator").isNotEmpty();
            assertThat(dumps.readableAmong(viewers.get("tenant"), List.of(dump)))
                .as("step 1: and no tenant").isEmpty();

            // 2. Each live role set: every coded kind it stores says who reads it, and every viewer reads exactly
            //    the codes the full host gives him.
            Map<String, Set<Role>> shapes = new LinkedHashMap<>();
            shapes.put("every role (the control)", EnumSet.allOf(Role.class));
            shapes.put("stacks off (Robbedoes)", EnumSet.complementOf(EnumSet.of(Role.STACKS)));
            shapes.put("dns, proxy and firewall (Kuifje)", EnumSet.of(Role.DNS, Role.PROXY, Role.FIREWALL));
            shapes.put("no role", EnumSet.noneOf(Role.class));
            for (Map.Entry<String, Set<Role>> shape : shapes.entrySet()) {
                replayBoot(shape.getValue(), registered, panels);
                assertThat(RecordCodes.kindsWithoutARead())
                    .as("step 2, " + shape.getKey() + ": THE ASSERTION -- no coded kind goes unread").isEmpty();
                assertThat(judgement(viewers, backupId, dumpId))
                    .as("step 2, " + shape.getKey() + ": each viewer reads the codes the full host gives him")
                    .isEqualTo(full);
                if (!shape.getValue().contains(Role.STACKS)) {
                    assertThat(RecordSourceRegistry.INSTANCE.referenceSourceFor(StackModel.MODEL_ID))
                        .as("step 2, " + shape.getKey() + ": the replay really left the stacks' surface out")
                        .isNull();
                }
            }
        } finally {
            roles(booted);
            PanelRegistry.restoreSnapshot(panels);
            RecordSourceRegistry.INSTANCE.restoreSnapshot(registered);
            deriveFrameworkDefaults();
            HardDeletes.byId(Models.get(InstanceBackupModel.class), backupId);
            HardDeletes.byId(Models.get(DatabaseDumpModel.class), dumpId);
            HardDeletes.byId(Models.get(InstanceModel.class), appId);
        }
    }

    /** @return per viewer, the kinds of the backup and the dump whose code he reads */
    private static Map<String, Set<Identifier>> judgement(Map<String, AccessContext> viewers, int backupId,
                                                         int dumpId) {
        Map<String, Set<Identifier>> read = new LinkedHashMap<>();
        viewers.forEach((name, viewer) -> {
            Set<Identifier> kinds = new HashSet<>();
            if (RecordCodes.code(InstanceBackupModel.MODEL_ID, String.valueOf(backupId), viewer) != null) {
                kinds.add(InstanceBackupModel.MODEL_ID);
            }
            if (RecordCodes.code(DatabaseDumpModel.MODEL_ID, String.valueOf(dumpId), viewer) != null) {
                kinds.add(DatabaseDumpModel.MODEL_ID);
            }
            read.put(name, kinds);
        });
        return read;
    }

    /**
     * Hohenheim's share of a boot under the role set, over the booted registries: its sources and its two panels
     * dropped, then built again in HohenheimHostWiring's order and derived by zenit-cms as CmsBoot does.
     */
    private static void replayBoot(Set<Role> enabled, List<RecordSourceRegistry.Entry> registered,
                                   List<Registry.Entry<Panel>> panels) {
        roles(enabled);
        List<RecordSourceRegistry.Entry> others = new ArrayList<>();
        for (RecordSourceRegistry.Entry entry : registered) {
            if (!HohenheimIds.PRODUCT.namespace().equals(entry.source().modelId().getNamespace())) {
                others.add(entry);
            }
        }
        List<Registry.Entry<Panel>> otherPanels = new ArrayList<>();
        for (Registry.Entry<Panel> entry : panels) {
            if (!(entry.value() instanceof HohenheimPanel) && !(entry.value() instanceof ManagePanel)) {
                otherPanels.add(entry);
            }
        }
        PanelRegistry.restoreSnapshot(otherPanels);
        RecordSourceRegistry.INSTANCE.restoreSnapshot(others);
        CmsRecordSources.resetForTests();
        HohenheimSources.register();
        Panel admin = new HohenheimPanel();
        Panel manage = new ManagePanel();
        AdminSources.declare();
        ManagePanel.declareSources();
        // Process-wide and declared by the real boot already, as on every host whatever its roles: a no-op here.
        RoleGatedKindReads.declare();
        CmsRecordSources.ensureRegistered(admin);
        CmsRecordSources.ensureRegistered(manage);
    }

    /** Declare the node's role set and snapshot it, the way a boot's settings load does. */
    private static void roles(Set<Role> enabled) {
        for (Role role : Role.values()) {
            Zenit.SETTINGS_VALUES.setValue(role.setting(), enabled.contains(role));
        }
        HohenheimRoles.capture();
    }

    /** @return the id of a row of the model saved with what {@code fill} sets */
    private static int saved(Class<? extends Model> model, Consumer<Row> fill) {
        Row row = Models.get(model).createEmptyRow();
        fill.accept(row);
        Models.get(model).save(row);
        return (Integer) Objects.requireNonNull(Models.get(model).getPrimaryKeyValue(row));
    }

    /** Empty the registry and the CMS derivation memo, the state a new process starts in. */
    private static void resetRegistry() {
        RecordSourceRegistry.INSTANCE.restoreSnapshot(List.of());
        CmsRecordSources.resetForTests();
    }

    /** Run zenit-cms's own derivation over every registered panel. */
    private static void deriveFrameworkDefaults() {
        CmsRecordSources.resetForTests();
        for (Panel panel : PanelRegistry.all()) {
            CmsRecordSources.ensureRegistered(panel);
        }
    }
}
