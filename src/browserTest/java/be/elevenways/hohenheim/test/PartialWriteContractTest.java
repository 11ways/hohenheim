package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.AuthProviderResource;
import be.elevenways.hohenheim.server.cms.CertificateResource;
import be.elevenways.hohenheim.server.cms.InstanceDeviceResource;
import be.elevenways.hohenheim.server.cms.InstanceScheduleResource;
import be.elevenways.hohenheim.server.cms.InstanceScheduleStepResource;
import be.elevenways.hohenheim.server.cms.ManageSiteResource;
import be.elevenways.hohenheim.server.cms.ServerResource;
import be.elevenways.hohenheim.server.cms.SiteResource;
import be.elevenways.hohenheim.server.schedule.InstanceSnapshotAction;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE partial-write guard for the {@code updateRow} overrides no resource declares an
 * inline cell for (yet): each is driven with a ONE-ENTRY, IMMUTABLE coerced map -- exactly
 * what {@code ResourcePageEndpoints}' inline cell lane sends -- and must write that column
 * and nothing else.
 *
 * AIDEV-NOTE: this is the sibling of {@link InlineCellIsolationTest}, which can only walk
 * resources that DECLARE {@code inlineEditableFields()} and is therefore blind to these.
 * Every case here is a real defect that shipped: a refusal naming a field the operator
 * never touched, or -- the SiteResource one -- a silent de-provisioning of a git-backed
 * site on a rename. Nothing in this file declares inline editing; making the overrides
 * correct and widening the feature are two decisions, and only the first is pinned here.
 */
class PartialWriteContractTest extends HohenheimTestBase {

    private static final String PREFIX = "partialw-";

    private static Integer gitSiteId;
    private static Integer serverId;
    private static Integer providerId;
    private static Integer certificateId;
    private static Integer instanceId;
    private static Integer scheduleId;
    private static Integer stepId;
    private static Integer deviceId;
    private static Long adminId;

    @BeforeAll
    static void seed() throws Exception {
        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        adminId = ((Integer) admin.get(UserModel.ID)).longValue();

        Model sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, PREFIX + "git site");
        site.set(SiteModel.SLUG, PREFIX + "git-site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, new LinkedHashMap<>(
            Map.of("root_path", "/tmp/" + PREFIX + "site")));
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.ENABLED, false);
        sites.save(site);
        gitSiteId = site.get(SiteModel.ID);

        Model servers = Models.get(ServerModel.class);
        Row server = servers.createEmptyRow();
        server.set(ServerModel.NAME, PREFIX + "host");
        server.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        server.set(ServerModel.MODE, ServerModel.MODE_SSH);
        server.set(ServerModel.SSH_TARGET, "root@edge.example.test");
        servers.save(server);
        serverId = server.get(ServerModel.ID);

        Model providers = Models.get(SiteAuthProviderModel.class);
        Row provider = providers.createEmptyRow();
        provider.set(SiteAuthProviderModel.NAME, PREFIX + "provider");
        provider.set(SiteAuthProviderModel.PROVIDER_TYPE, "hohenheim:proteus");
        provider.set(SiteAuthProviderModel.CONFIG, new LinkedHashMap<>(Map.of(
            "endpoint", "https://auth.example.test",
            "realm_client", "testrealm",
            "access_key", "test-access-key",
            "authenticator", "password")));
        providers.save(provider);
        providerId = provider.get(SiteAuthProviderModel.ID);

        KeyPair keyPair = TlsCertificateTest.generateKeyPair();
        Model certificates = Models.get(CertificateModel.class);
        Row certificate = certificates.createEmptyRow();
        certificate.set(CertificateModel.NICE_NAME, PREFIX + "cert");
        certificate.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
        certificate.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        certificate.set(CertificateModel.CERTIFICATE_PEM, TlsCertificateTest.certToPem(
            TlsCertificateTest.generateSelfSignedCert(keyPair, "partialw.example.test")));
        certificate.set(CertificateModel.PRIVATE_KEY_PEM, TlsCertificateTest.keyToPem(keyPair));
        certificate.set(CertificateModel.AUTO_RENEW, false);
        certificates.save(certificate);
        certificateId = certificate.get(CertificateModel.ID);

        Model instances = Models.get(InstanceModel.class);
        Row instance = instances.createEmptyRow();
        instance.set(InstanceModel.NAME, PREFIX + "instance");
        instance.set(InstanceModel.KIND, "hohenheim:docker_container");
        instance.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instance.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        instance.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        instances.save(instance);
        instanceId = instance.get(InstanceModel.ID);

        Model schedules = Models.get(RecordScheduleModel.class);
        Row schedule = schedules.createEmptyRow();
        schedule.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
        schedule.set(RecordScheduleModel.RECORD_ID, String.valueOf(instanceId));
        schedule.set(RecordScheduleModel.NAME, PREFIX + "nightly");
        schedule.set(RecordScheduleModel.CRON, "0 4 * * *");
        schedule.set(RecordScheduleModel.TIMEZONE, "Europe/Brussels");
        schedule.set(RecordScheduleModel.ENABLED, false);
        // The admin's OWN id, so the deliberate run_as re-stamp is a no-op here and the
        // walk below can demand that literally nothing but the edited column moved.
        schedule.set(RecordScheduleModel.RUN_AS, adminId);
        schedules.save(schedule);
        scheduleId = schedule.get(RecordScheduleModel.ID);

        Model steps = Models.get(RecordScheduleStepModel.class);
        Row step = steps.createEmptyRow();
        step.set(RecordScheduleStepModel.SCHEDULE_ID, scheduleId);
        step.set(RecordScheduleStepModel.POSITION, 1);
        step.set(RecordScheduleStepModel.ACTION, InstanceSnapshotAction.ID.toString());
        step.set(RecordScheduleStepModel.OFFSET_SECONDS, 0);
        steps.save(step);
        stepId = step.get(RecordScheduleStepModel.ID);

        Model devices = Models.get(InstanceDeviceModel.class);
        Row device = devices.createEmptyRow();
        device.set(InstanceDeviceModel.INSTANCE_ID, instanceId);
        device.set(InstanceDeviceModel.NAME, PREFIX + "disk");
        device.set(InstanceDeviceModel.TYPE, InstanceDeviceModel.TYPE_DISK);
        device.set(InstanceDeviceModel.SIZE_GB, 4);
        devices.save(device);
        deviceId = device.get(InstanceDeviceModel.ID);
    }

    /** One override, driven with the one entry the inline cell lane would send. */
    private record Case(String who, RowResource resource, Model model, int id,
                        String column, Object value) {}

    private static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("admin/sites", new SiteResource(), Models.get(SiteModel.class),
            gitSiteId, SiteModel.NAME.getName(), PREFIX + "renamed site"));
        cases.add(new Case("manage/sites", new ManageSiteResource(), Models.get(SiteModel.class),
            gitSiteId, SiteModel.DESCRIPTION.getName(), "a note the operator typed"));
        cases.add(new Case("admin/servers", new ServerResource(), Models.get(ServerModel.class),
            serverId, ServerModel.NAME.getName(), PREFIX + "renamed-host"));
        cases.add(new Case("admin/auth-providers", new AuthProviderResource(),
            Models.get(SiteAuthProviderModel.class), providerId,
            SiteAuthProviderModel.NAME.getName(), PREFIX + "renamed provider"));
        cases.add(new Case("admin/certificates", new CertificateResource(),
            Models.get(CertificateModel.class), certificateId,
            CertificateModel.NICE_NAME.getName(), PREFIX + "renamed cert"));
        cases.add(new Case("admin/instance-schedules", new InstanceScheduleResource(),
            Models.get(RecordScheduleModel.class), scheduleId,
            RecordScheduleModel.NAME.getName(), PREFIX + "renamed schedule"));
        cases.add(new Case("admin/instance-schedule-steps", new InstanceScheduleStepResource(),
            Models.get(RecordScheduleStepModel.class), stepId,
            RecordScheduleStepModel.OFFSET_SECONDS.getName(), 30));
        return cases;
    }

    /**
     * Steps 1-3: every fixed override accepts a one-entry immutable map, writes that
     * column, and leaves every sibling exactly as it found it.
     */
    @Test
    void aOneEntryCellMapWritesItsOwnColumnAndNothingElse() {
        List<Case> cases = cases();

        // 1. The walk is hand-written (these resources declare no inline cells to derive
        //    it from), so a case list that quietly shrank would pass vacuously.
        assertThat(cases).as("step 1: every surveyed override is exercised").hasSize(7);

        for (Case one : cases) {
            Row before = one.model().findById(one.id());
            assertThat(before).as("step 1: " + one.who() + " has its fixture row").isNotNull();
            Map<String, Object> stored = InlineCellIsolationTest.storedValues(one.model(), one.id());

            // 2. The map is Map.of: ONE entry and IMMUTABLE, exactly what the cell lane
            //    hands over. An override that stages values by mutating it dies here.
            one.resource().updateRow(before, Map.of(one.column(), one.value()), admin());

            Map<String, Object> after = InlineCellIsolationTest.storedValues(one.model(), one.id());
            assertThat(String.valueOf(after.get(one.column())))
                .as("step 2: " + one.who() + " wrote '" + one.column() + "'")
                .isEqualTo(String.valueOf(one.value()));

            // 3. ...and moved nothing else. A sibling that changed was changed by a
            //    normalizer reading a key this write never carried -- silently, since the
            //    write itself reported success.
            for (Map.Entry<String, Object> column : stored.entrySet()) {
                if (column.getKey().equals(one.column())
                        || InlineCellIsolationTest.BOOKKEEPING.contains(column.getKey())) {
                    continue;
                }
                assertThat(String.valueOf(after.get(column.getKey())))
                    .as("step 3: " + one.who() + " left '" + column.getKey()
                        + "' alone while writing '" + one.column() + "'")
                    .isEqualTo(String.valueOf(column.getValue()));
            }
        }
    }

    /**
     * Steps 1-3: THE destructive one, spelled out. {@code normalizeSource} used to answer a
     * map that carries no source with {@code put("source", null)}, so renaming a
     * git-provisioned site de-provisioned it: no refusal, no log, the repository simply
     * gone from the record.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aSitesUpstreamSettingsSurviveWhenOnlyItsNameIsWritten() {
        Model sites = Models.get(SiteModel.class);
        Row before = sites.findById(gitSiteId);

        // 1. The fixture really carries upstream settings, or the claim below is vacuous.
        //    AIDEV-NOTE: this used to assert on the git SOURCE columns, which moved off the
        //    site with the upstream rename (phase-0 design section 3). The defect class is
        //    unchanged -- a one-entry cell write must not blank a polymorphic map it never
        //    mentioned -- so the subject moved to the settings map that is still here.
        Map<String, Object> settingsBefore =
            (Map<String, Object>) before.get(SiteModel.SETTINGS);
        assertThat(settingsBefore).as("step 1: with its upstream settings stored")
            .containsEntry("root_path", "/tmp/" + PREFIX + "site");

        // 2. A rename through the cell lane: one entry, immutable, no settings in sight.
        new SiteResource().updateRow(before, Map.of(SiteModel.NAME.getName(), PREFIX + "still git"),
            admin());

        Row after = sites.findById(gitSiteId);
        assertThat((String) after.get(SiteModel.NAME))
            .as("step 2: the rename landed").isEqualTo(PREFIX + "still git");

        // 3. And the upstream settings survived it, whole.
        assertThat((Map<String, Object>) after.get(SiteModel.SETTINGS))
            .as("step 3: with every upstream setting intact").isEqualTo(settingsBefore);
    }

    /**
     * Steps 1-2: a device resize is no longer refused as a rename it never asked for.
     *
     * AIDEV-NOTE: this asserts on the SHAPE of the refusal rather than on a stored value,
     * because {@code InstanceDeviceResource.updateRow} funnels into the daemon and there is
     * no container behind this fixture. What must never come back is the identity refusal:
     * the resize either reaches the daemon and fails there, or succeeds.
     */
    @Test
    void aPartialDeviceWriteIsNoLongerRefusedAsARenameOrRetype() {
        Row device = Models.get(InstanceDeviceModel.class).findById(deviceId);
        InstanceDeviceResource resource = new InstanceDeviceResource();

        // 1. The one entry the cell lane would send for a disk resize.
        Throwable refusal = null;
        try {
            resource.updateRow(device, Map.of(InstanceDeviceModel.SIZE_GB.getName(), 8), admin());
        } catch (Throwable thrown) {
            refusal = thrown;
        }

        // 2. Whatever the daemon-less environment answers, it is not the identity refusal
        //    this override used to produce for a write that carried no identity at all.
        if (refusal instanceof Violations violations) {
            assertThat(violations.all().stream().map(violation -> violation.fieldName()))
                .as("step 2: the resize is not refused as a rename/retype/rehome: " + violations)
                .doesNotContain("name", "type", "instance_id");
        }
    }

    private static AccessContext admin() {
        return AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(adminId, "Test Admin")));
    }
}
