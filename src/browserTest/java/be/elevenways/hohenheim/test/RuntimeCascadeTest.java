package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.InstanceTemplateVolumeModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.hohenheim.server.cms.InstanceTemplateResource;
import be.elevenways.hohenheim.server.cms.RuntimeImageResource;
import be.elevenways.hohenheim.server.cms.ServerResource;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.stack.StackInstances;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Nothing in the runtime tier survives its parent dangling, on the MODEL funnel rather than
 * on one admin button: a certificate releases the domains that pinned it, a stack takes its
 * services, files and history with it, and a database, a template and a runtime image
 * refuse to go while a live workload still names them; a DNS peer, an auth provider, a
 * system user and a migration-target host refuse to go while a zone, a site or rule, the
 * Spamservice installation or an in-flight migration still names them.
 *
 * AIDEV-NOTE: every delete here goes through {@code Models.get(...).delete(row)}, never a
 * resource, because the resources already cascaded by hand and the finding was precisely
 * that every OTHER writer did not. The daemon-facing halves (container teardown) are not
 * exercised: there is no host here, and the row-level integrity is what the funnel owns.
 */
class RuntimeCascadeTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    /**
     * A certificate pinned by two domain rows: deleting it clears the pin on both, keeps
     * the rows, and leaves a sibling certificate's pin alone.
     */
    @Test
    void aDeletedCertificateReleasesTheDomainsThatPinnedIt() {
        Db.run(datasource, () -> {
            int doomedId = certificate("cascade-doomed", "a.cascade.test, b.cascade.test");
            int keptId = certificate("cascade-kept", "c.cascade.test");
            int siteId = site("cascade-tls");
            int a = domain(siteId, "a.cascade.test", doomedId);
            int b = domain(siteId, "b.cascade.test", doomedId);
            int c = domain(siteId, "c.cascade.test", keptId);

            // 1. The pins are stored as written.
            assertThat(domainRow(a).get(SiteDomainModel.CERTIFICATE_ID))
                .as("step 1: the first domain pins the doomed certificate").isEqualTo(doomedId);

            // 2. Deleting the certificate releases every row that pinned it -- the row
            //    used to keep claiming a certificate nothing could load.
            Models.get(CertificateModel.class).delete(certificateRow(doomedId));
            assertThat(domainRow(a).get(SiteDomainModel.CERTIFICATE_ID))
                .as("step 2: the first pin is cleared").isNull();
            assertThat(domainRow(b).get(SiteDomainModel.CERTIFICATE_ID))
                .as("step 2: and so is the second").isNull();

            // 3. The domain rows themselves survive: the hostnames are the site's, and
            //    platform selection now covers them.
            assertThat(domainRow(a)).as("step 3: the domain row is kept").isNotNull();

            // 4. A pin on a certificate that is NOT being deleted is untouched.
            assertThat(domainRow(c).get(SiteDomainModel.CERTIFICATE_ID))
                .as("step 4: the sibling pin survives").isEqualTo(keptId);
        });
    }

    /**
     * A stack with two services, a file on one and two deployment snapshots: deleting the
     * stack takes all of it, and a neighbouring stack keeps everything.
     */
    @Test
    void aStackTakesItsServicesFilesAndDeploymentsWithIt() {
        Db.run(datasource, () -> {
            int doomed = stack("cascade-doomed");
            int web = service(doomed, "web");
            int db = service(doomed, "db");
            file(web, "/etc/web.conf");
            deployment(doomed);
            deployment(doomed);

            int neighbour = stack("cascade-neighbour");
            int neighbourService = service(neighbour, "web");
            int neighbourFile = file(neighbourService, "/etc/web.conf");
            deployment(neighbour);

            // 1. Everything is stored as written.
            assertThat(countWhere(StackServiceModel.class, StackServiceModel.STACK_ID, doomed))
                .as("step 1: two services").isEqualTo(2);
            assertThat(countWhere(StackDeploymentModel.class, StackDeploymentModel.STACK_ID, doomed))
                .as("step 1: two deployment snapshots").isEqualTo(2);

            // 2. Deleting the STACK through the model takes the services, the file that
            //    hung off one of them and the deployment history -- the CMS button used to
            //    be the only lane that did.
            Models.get(StackModel.class).delete(Models.get(StackModel.class).findById(doomed));
            assertThat(countWhere(StackServiceModel.class, StackServiceModel.STACK_ID, doomed))
                .as("step 2: no service is left naming the stack").isZero();
            assertThat(countWhere(StackFileModel.class, StackFileModel.STACK_SERVICE_ID, web))
                .as("step 2: the file died with its service").isZero();
            assertThat(countWhere(StackDeploymentModel.class, StackDeploymentModel.STACK_ID, doomed))
                .as("step 2: the deployment history died with the stack").isZero();
            assertThat(Models.get(StackServiceModel.class).findById(db))
                .as("step 2: the second service is gone too").isNull();

            // 3. The neighbour keeps its service, its file and its history.
            assertThat(Models.get(StackServiceModel.class).findById(neighbourService))
                .as("step 3: the neighbour's service survives").isNotNull();
            assertThat(Models.get(StackFileModel.class).findById(neighbourFile))
                .as("step 3: and its file").isNotNull();
            assertThat(countWhere(StackDeploymentModel.class, StackDeploymentModel.STACK_ID, neighbour))
                .as("step 3: and its history").isEqualTo(1);
        });
    }

    /**
     * A service whose lowered workload is still live refuses to go -- a direct delete would
     * leave a running container attributed to nothing -- and goes once the workload is gone.
     */
    @Test
    void aServiceStillRunningAWorkloadRefusesToGo() {
        Db.run(datasource, () -> {
            int stackId = stack("cascade-running");
            int serviceId = service(stackId, "app");
            int fileId = file(serviceId, "/etc/app.conf");
            int instanceId = ownedInstance(serviceId, "cascade-running-app");
            Model services = Models.get(StackServiceModel.class);

            // 1. With the workload live, the delete is refused BY NAME and nothing moves.
            assertThatThrownBy(() -> services.delete(services.findById(serviceId)))
                .as("step 1: a service with a live workload refuses to go")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("app");
            assertThat(services.findById(serviceId)).as("step 1: the service is kept").isNotNull();
            assertThat(Models.get(StackFileModel.class).findById(fileId))
                .as("step 1: and its file was not swept ahead of the refusal").isNotNull();

            // 2. The same refusal reaches a STACK delete, because its cascade runs through
            //    the service hook.
            Model stacks = Models.get(StackModel.class);
            assertThatThrownBy(() -> stacks.delete(stacks.findById(stackId)))
                .as("step 2: the stack cannot take a running service with it")
                .isInstanceOf(Violations.class);
            assertThat(stacks.findById(stackId)).as("step 2: the stack is kept").isNotNull();

            // 3. Once the workload is torn down (soft-deleted, as InstanceService.destroy
            //    does), the service goes and takes its file.
            softDeleteOwned(serviceId, instanceId);
            services.delete(services.findById(serviceId));
            assertThat(services.findById(serviceId)).as("step 3: the service is gone").isNull();
            assertThat(Models.get(StackFileModel.class).findById(fileId))
                .as("step 3: and its file with it").isNull();
        });
    }

    /**
     * A database attached to a live workload refuses to go naming that workload; once the
     * workload is gone the delete takes the stale attachment rows along.
     */
    @Test
    void aDatabaseRefusesWhileAttachedAndDropsItsLinksAfter() {
        Db.run(datasource, () -> {
            int databaseId = database("cascade-db");
            int instanceId = instance("cascade-db-consumer", null, null);
            int linkId = link(instanceId, databaseId);
            Model databases = Models.get(DatabaseModel.class);

            // 1. Attached to a live workload: refused, naming the workload.
            assertThatThrownBy(() -> databases.delete(databases.findById(databaseId)))
                .as("step 1: a database in use refuses to go")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("cascade-db-consumer");
            assertThat(databases.findById(databaseId)).as("step 1: the database is kept").isNotNull();
            assertThat(Models.get(InstanceDatabaseModel.class).findById(linkId))
                .as("step 1: and the attachment is kept").isNotNull();

            // 2. The workload is destroyed (soft delete): the link is debris now, and the
            //    database takes it along instead of leaving it naming a dead engine.
            softDelete(instanceId);
            databases.delete(databases.findById(databaseId));
            assertThat(databases.findById(databaseId)).as("step 2: the database is gone").isNull();
            assertThat(Models.get(InstanceDatabaseModel.class).findById(linkId))
                .as("step 2: and the attachment row with it").isNull();
        });
    }

    /**
     * A template with a live instance is offered dead with the count and refused at the
     * funnel; a template nothing runs takes its variables, files and volumes with it.
     */
    @Test
    void aTemplateRefusesWhileInstancedAndTakesItsContentsWithIt() {
        Db.run(datasource, () -> {
            int templateId = template("cascade-template", null);
            int variableId = templateVariable(templateId, "PORT");
            int fileId = templateFile(templateId, "/etc/app.conf");
            int volumeId = templateVolume(templateId, "data");
            int instanceId = instance("cascade-from-template", templateId, null);
            Model templates = Models.get(InstanceTemplateModel.class);
            InstanceTemplateResource resource = new InstanceTemplateResource();
            AccessContext operator = AccessContext.of(TenantConduits.stubFor(null));

            // 1. The resource offers the delete DEAD with the count on screen.
            Microcopy reason = resource.deleteUnavailableReason(templates.findById(templateId), operator);
            assertThat(reason).as("step 1: a template in use is offered dead").isNotNull();
            assertThat(reason.key()).as("step 1: with the in-use reason").isEqualTo("delete_in_use");
            assertThat(reason.filters().get("scope")).as("step 1: in the template's own words")
                .isEqualTo("instance_template");
            assertThat(reason.args().get("count")).as("step 1: naming the count").isEqualTo(1L);

            // 2. The funnel refuses a direct delete the same way, naming the template.
            assertThatThrownBy(() -> templates.delete(templates.findById(templateId)))
                .as("step 2: the funnel refuses too")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("cascade-template");
            assertThat(Models.get(InstanceTemplateVariableModel.class).findById(variableId))
                .as("step 2: the variable was not swept ahead of the refusal").isNotNull();

            // 3. With the instance gone the delete is offered live and takes the contents.
            softDelete(instanceId);
            assertThat(resource.deleteUnavailableReason(templates.findById(templateId), operator))
                .as("step 3: nothing runs from it any more").isNull();
            templates.delete(templates.findById(templateId));
            assertThat(Models.get(InstanceTemplateVariableModel.class).findById(variableId))
                .as("step 3: the variable died with the template").isNull();
            assertThat(Models.get(InstanceTemplateFileModel.class).findById(fileId))
                .as("step 3: and the file").isNull();
            assertThat(Models.get(InstanceTemplateVolumeModel.class).findById(volumeId))
                .as("step 3: and the volume declaration").isNull();
        });
    }

    /** A runtime image still named by a live instance or a template refuses to go. */
    @Test
    void aRuntimeImageRefusesWhileReferenced() {
        Db.run(datasource, () -> {
            int imageId = runtimeImage("cascade-image");
            int templateId = template("cascade-image-template", imageId);
            int instanceId = instance("cascade-in-image", null, imageId);
            Model images = Models.get(RuntimeImageModel.class);
            RuntimeImageResource resource = new RuntimeImageResource();
            AccessContext operator = AccessContext.of(TenantConduits.stubFor(null));

            // 1. Offered dead with both counts.
            Microcopy reason = resource.deleteUnavailableReason(images.findById(imageId), operator);
            assertThat(reason).as("step 1: an image in use is offered dead").isNotNull();
            assertThat(reason.filters().get("scope")).isEqualTo("runtime_image");
            assertThat(reason.args().get("instances")).as("step 1: one live instance").isEqualTo(1L);
            assertThat(reason.args().get("templates")).as("step 1: one template").isEqualTo(1L);

            // 2. The funnel refuses a direct delete, naming the image.
            assertThatThrownBy(() -> images.delete(images.findById(imageId)))
                .as("step 2: the funnel refuses")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("cascade-image");

            // 3. Releasing the instance is not enough while a template still layers on it.
            softDelete(instanceId);
            assertThatThrownBy(() -> images.delete(images.findById(imageId)))
                .as("step 3: a template alone still holds the image")
                .isInstanceOf(Violations.class);

            // 4. With the template gone as well, the image goes.
            Models.get(InstanceTemplateModel.class).delete(
                Models.get(InstanceTemplateModel.class).findById(templateId));
            images.delete(images.findById(imageId));
            assertThat(images.findById(imageId)).as("step 4: the image is gone").isNull();
        });
    }

    /**
     * A DNS peer a secondary zone replicates from refuses to go naming the zone; once the
     * zone is repointed the peer goes, clearing the stale pointer a primary zone kept and
     * taking its zone links with it.
     */
    @Test
    void aDnsPeerRefusesWhileASecondaryReplicatesFromItAndTakesItsLinksWithIt() {
        Db.run(datasource, () -> {
            int doomedId = peer("cascade-doomed-peer");
            int keptId = peer("cascade-kept-peer");
            int secondaryId = zone("replica.cascade.test", DnsZoneModel.ROLE_SECONDARY, doomedId);
            int stalePrimaryId = zone("stale.cascade.test", DnsZoneModel.ROLE_PRIMARY, doomedId);
            int primaryId = zone("primary.cascade.test", DnsZoneModel.ROLE_PRIMARY, null);
            int doomedLink = zonePeer(primaryId, doomedId);
            int keptLink = zonePeer(primaryId, keptId);
            Model peers = Models.get(DnsPeerModel.class);

            // 1. A secondary zone still replicates from the peer: refused BY NAME, and the
            //    zone links were not swept ahead of the refusal.
            assertThatThrownBy(() -> peers.delete(peers.findById(doomedId)))
                .as("step 1: a peer a secondary replicates from refuses to go")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("replica.cascade.test");
            assertThat(peers.findById(doomedId)).as("step 1: the peer is kept").isNotNull();
            assertThat(Models.get(DnsZonePeerModel.class).findById(doomedLink))
                .as("step 1: the zone link was not swept ahead of the refusal").isNotNull();

            // 2. Repointed at another primary, the secondary no longer holds it: the peer
            //    goes, the secondary keeps its new pointer, the stale pointer a PRIMARY
            //    zone carried is cleared and the link died with the peer.
            Row secondary = Models.get(DnsZoneModel.class).findById(secondaryId);
            secondary.set(DnsZoneModel.PRIMARY_PEER_ID, keptId);
            Models.get(DnsZoneModel.class).save(secondary);
            peers.delete(peers.findById(doomedId));
            assertThat(peers.findById(doomedId)).as("step 2: the peer is gone").isNull();
            assertThat(Models.get(DnsZoneModel.class).findById(secondaryId).get(DnsZoneModel.PRIMARY_PEER_ID))
                .as("step 2: the repointed secondary keeps its new primary").isEqualTo(keptId);
            assertThat(Models.get(DnsZoneModel.class).findById(stalePrimaryId).get(DnsZoneModel.PRIMARY_PEER_ID))
                .as("step 2: a primary zone's stale pointer is cleared, never refused").isNull();
            assertThat(Models.get(DnsZonePeerModel.class).findById(doomedLink))
                .as("step 2: the zone link died with the peer").isNull();

            // 3. The link to the surviving peer is untouched.
            assertThat(Models.get(DnsZonePeerModel.class).findById(keptLink))
                .as("step 3: the sibling link survives").isNotNull();
        });
    }

    /**
     * A site auth provider gating a live site, and one an access rule names, both refuse
     * to go -- the proxy fails those sites CLOSED once the provider is gone -- and the
     * provider goes once nothing names it.
     */
    @Test
    void anAuthProviderRefusesWhileASiteOrARuleNamesIt() {
        Db.run(datasource, () -> {
            int providerId = authProvider("cascade-gate");
            int siteId = site("cascade-gated");
            Row gated = Models.get(SiteModel.class).findById(siteId);
            gated.set(SiteModel.AUTH_PROVIDER_ID, providerId);
            Models.get(SiteModel.class).save(gated);
            Model providers = Models.get(SiteAuthProviderModel.class);

            // 1. A live site names it as its login gate: refused by name.
            assertThatThrownBy(() -> providers.delete(providers.findById(providerId)))
                .as("step 1: a provider gating a live site refuses to go")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("cascade-gate");
            assertThat(providers.findById(providerId)).as("step 1: the provider is kept").isNotNull();

            // 2. The site is trashed (deleted_at, as SiteResource does): a soft-deleted site
            //    no longer holds the provider, but an access rule naming it still does.
            gated.set(SiteModel.DELETED_AT, Instant.now());
            Models.get(SiteModel.class).save(gated);
            int listId = accessList("cascade-list");
            int ruleId = providerRule(listId, providerId);
            assertThatThrownBy(() -> providers.delete(providers.findById(providerId)))
                .as("step 2: a rule naming the provider still holds it")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("cascade-gate");

            // 3. With the rule gone the provider goes.
            Models.get(AccessRuleModel.class).delete(Models.get(AccessRuleModel.class).findById(ruleId));
            providers.delete(providers.findById(providerId));
            assertThat(providers.findById(providerId)).as("step 3: the provider is gone").isNull();
        });
    }

    /**
     * The system user the Spamservice installation runs as refuses to go; a user nothing
     * runs as goes.
     */
    @Test
    void aSystemUserRefusesWhileTheSpamserviceInstallationRunsAsIt() {
        Db.run(datasource, () -> {
            int userId = systemUser("cascade-spam", 1500);
            int otherId = systemUser("cascade-idle", 1501);
            Model users = Models.get(SystemUserModel.class);
            Row installation = spamserviceInstallation();
            installation.set(SpamserviceInstallationModel.SYSTEM_USER_ID, userId);
            Models.get(SpamserviceInstallationModel.class).save(installation);

            // 1. The installation runs as it: refused by name.
            assertThatThrownBy(() -> users.delete(users.findById(userId)))
                .as("step 1: the Spamservice user refuses to go")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("cascade-spam");
            assertThat(users.findById(userId)).as("step 1: the user is kept").isNotNull();

            // 2. A user nothing runs as goes.
            users.delete(users.findById(otherId));
            assertThat(users.findById(otherId)).as("step 2: an unreferenced user goes").isNull();

            // 3. Reassigned, the former user goes too.
            installation.set(SpamserviceInstallationModel.SYSTEM_USER_ID, null);
            Models.get(SpamserviceInstallationModel.class).save(installation);
            users.delete(users.findById(userId));
            assertThat(users.findById(userId)).as("step 3: released, the user goes").isNull();
        });
    }

    /**
     * A host that is the DESTINATION of an in-flight cold migration refuses to go naming
     * the workload: mid-flight the record still names its source host, so the ownership
     * count alone never sees the destination.
     */
    @Test
    void aMigrationTargetHostRefusesToGoWhileTheMigrationIsInFlight() {
        Db.run(datasource, () -> {
            int sourceId = server("cascade-source");
            int targetId = server("cascade-target");
            int instanceId = instance("cascade-mover", null, null);
            Model servers = Models.get(ServerModel.class);
            ServerResource resource = new ServerResource();
            AccessContext operator = AccessContext.of(TenantConduits.stubFor(null));
            // The window opens the way InstanceOperationGuard opens it: one set-based
            // statement that fires no write hooks and leaves SERVER_ID on the source.
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(instanceId))
                .assign(InstanceModel.SERVER_ID, sourceId)
                .assign(InstanceModel.STATUS, InstanceModel.STATUS_MIGRATING)
                .assign(InstanceModel.MIGRATE_TARGET_ID, targetId)
                .updateAll();

            // 1. The resource offers the target's delete DEAD, naming the workload.
            Microcopy reason = resource.deleteUnavailableReason(servers.findById(targetId), operator);
            assertThat(reason).as("step 1: a migration target is offered dead").isNotNull();
            assertThat(reason.key()).as("step 1: with the migrating reason").isEqualTo("delete_migrating");
            assertThat(reason.args().get("instance")).as("step 1: naming the workload")
                .isEqualTo("cascade-mover");

            // 2. The funnel refuses a direct delete the same way.
            assertThatThrownBy(() -> servers.delete(servers.findById(targetId)))
                .as("step 2: the funnel refuses too")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("cascade-mover");
            assertThat(servers.findById(targetId)).as("step 2: the host is kept").isNotNull();

            // 3. The window settled without a handoff (the rollback half): the target
            //    holds nothing and goes.
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(instanceId))
                .assign(InstanceModel.STATUS, "stopped")
                .assign(InstanceModel.MIGRATE_TARGET_ID, (Object) null)
                .updateAll();
            assertThat(resource.deleteUnavailableReason(servers.findById(targetId), operator))
                .as("step 3: nothing is moving onto it any more").isNull();
            servers.delete(servers.findById(targetId));
            assertThat(servers.findById(targetId)).as("step 3: the host is gone").isNull();

            // 4. The source still owns the workload and refuses as before.
            assertThatThrownBy(() -> servers.delete(servers.findById(sourceId)))
                .as("step 4: the source host still owns the workload")
                .isInstanceOf(Violations.class);
        });
    }

    // -- fixtures ---------------------------------------------------------------

    private static int peer(String name) {
        Row row = Models.get(DnsPeerModel.class).createEmptyRow();
        row.set(DnsPeerModel.NAME, name);
        row.set(DnsPeerModel.TRANSFER_HOST, "192.0.2.10");
        Models.get(DnsPeerModel.class).save(row);
        return row.get(DnsPeerModel.ID);
    }

    private static int zone(String origin, String role, Integer primaryPeerId) {
        Row row = Models.get(DnsZoneModel.class).createEmptyRow();
        row.set(DnsZoneModel.ORIGIN, origin);
        row.set(DnsZoneModel.ROLE, role);
        row.set(DnsZoneModel.PRIMARY_PEER_ID, primaryPeerId);
        row.set(DnsZoneModel.ENABLED, true);
        Models.get(DnsZoneModel.class).save(row);
        return row.get(DnsZoneModel.ID);
    }

    private static int zonePeer(int zoneId, int peerId) {
        Row row = Models.get(DnsZonePeerModel.class).createEmptyRow();
        row.set(DnsZonePeerModel.ZONE_ID, zoneId);
        row.set(DnsZonePeerModel.PEER_ID, peerId);
        Models.get(DnsZonePeerModel.class).save(row);
        return row.get(DnsZonePeerModel.ID);
    }

    private static int authProvider(String name) {
        Row row = Models.get(SiteAuthProviderModel.class).createEmptyRow();
        row.set(SiteAuthProviderModel.NAME, name);
        row.set(SiteAuthProviderModel.PROVIDER_TYPE, BasicAuthProviderType.ID.toString());
        row.set(SiteAuthProviderModel.CONFIG, Map.of());
        Models.get(SiteAuthProviderModel.class).save(row);
        return row.get(SiteAuthProviderModel.ID);
    }

    private static int accessList(String name) {
        Row row = Models.get(AccessListModel.class).createEmptyRow();
        row.set(AccessListModel.NAME, name);
        Models.get(AccessListModel.class).save(row);
        return row.get(AccessListModel.ID);
    }

    /** A provider leaf naming one provider, switched on so its data is complete. */
    private static int providerRule(int listId, int providerId) {
        Row row = Models.get(AccessRuleModel.class).createEmptyRow();
        row.set(AccessRuleModel.ACCESS_LIST_ID, listId);
        row.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_AUTH_PROVIDER);
        row.set(AccessRuleModel.ENABLED, true);
        row.set(AccessRuleModel.DATA, Map.of(AccessRuleModel.PROVIDER_ID.getName(), providerId));
        Models.get(AccessRuleModel.class).save(row);
        return row.get(AccessRuleModel.ID);
    }

    private static int systemUser(String name, int uid) {
        Row row = Models.get(SystemUserModel.class).createEmptyRow();
        row.set(SystemUserModel.NAME, name);
        row.set(SystemUserModel.UID, uid);
        row.set(SystemUserModel.GID, uid);
        row.set(SystemUserModel.HOME, "/home/" + name);
        Models.get(SystemUserModel.class).save(row);
        return row.get(SystemUserModel.ID);
    }

    /** The seeded singleton installation row, created here when the seed did not run. */
    private static Row spamserviceInstallation() {
        Row row = Models.get(SpamserviceInstallationModel.class).installation();
        if (row != null) {
            return row;
        }
        row = Models.get(SpamserviceInstallationModel.class).createEmptyRow();
        row.set(SpamserviceInstallationModel.ID, SpamserviceInstallationModel.SINGLETON_ID);
        row.set(SpamserviceInstallationModel.ENABLED, false);
        Models.get(SpamserviceInstallationModel.class).save(row);
        return row;
    }

    private static int server(String name) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, name);
        Models.get(ServerModel.class).save(row);
        return row.get(ServerModel.ID);
    }

    private static int certificate(String niceName, String domains) {
        Row row = Models.get(CertificateModel.class).createEmptyRow();
        row.set(CertificateModel.NICE_NAME, niceName);
        row.set(CertificateModel.DOMAIN_NAMES_TEXT, domains);
        row.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
        row.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        Models.get(CertificateModel.class).save(row);
        return row.get(CertificateModel.ID);
    }

    private static Row certificateRow(int id) {
        return Models.get(CertificateModel.class).findById(id);
    }

    private static int site(String slug) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp/" + slug));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, true);
        Models.get(SiteModel.class).save(row);
        return row.get(SiteModel.ID);
    }

    private static int domain(int siteId, String hostname, int certificateId) {
        Row row = Models.get(SiteDomainModel.class).createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, siteId);
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        row.set(SiteDomainModel.CERTIFICATE_ID, certificateId);
        Models.get(SiteDomainModel.class).save(row);
        return row.get(SiteDomainModel.ID);
    }

    private static Row domainRow(int id) {
        return Models.get(SiteDomainModel.class).findById(id);
    }

    private static int stack(String name) {
        Row row = Models.get(StackModel.class).createEmptyRow();
        row.set(StackModel.NAME, name);
        Models.get(StackModel.class).save(row);
        return row.get(StackModel.ID);
    }

    private static int service(int stackId, String name) {
        Row row = Models.get(StackServiceModel.class).createEmptyRow();
        row.set(StackServiceModel.STACK_ID, stackId);
        row.set(StackServiceModel.NAME, name);
        row.set(StackServiceModel.IMAGE, "alpine:latest");
        Models.get(StackServiceModel.class).save(row);
        return row.get(StackServiceModel.ID);
    }

    private static int file(int serviceId, String path) {
        Row row = Models.get(StackFileModel.class).createEmptyRow();
        row.set(StackFileModel.STACK_SERVICE_ID, serviceId);
        row.set(StackFileModel.CONTAINER_PATH, path);
        row.set(StackFileModel.CONTENT, "secret=1");
        Models.get(StackFileModel.class).save(row);
        return row.get(StackFileModel.ID);
    }

    private static void deployment(int stackId) {
        Row row = Models.get(StackDeploymentModel.class).createEmptyRow();
        row.set(StackDeploymentModel.STACK_ID, stackId);
        row.set(StackDeploymentModel.STATUS, StackDeploymentModel.STATUS_SUCCESS);
        Models.get(StackDeploymentModel.class).save(row);
    }

    /** A plain container workload, optionally created from a template or inside an image. */
    private static int instance(String name, Integer templateId, Integer imageId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        fillInstance(row, name);
        row.set(InstanceModel.TEMPLATE_ID, templateId);
        row.set(InstanceModel.RUNTIME_IMAGE_ID, imageId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static void fillInstance(Row row, String name) {
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
        row.set(InstanceModel.STATUS, "stopped");
    }

    /** The lowered workload of one stack service, attributed the way the stack tier does it. */
    private static int ownedInstance(int serviceId, String name) {
        int[] id = new int[1];
        OwnedInstances.inScopeUnchecked(StackInstances.SOURCE, StackServiceModel.MODEL_ID, serviceId, () -> {
            Row row = Models.get(InstanceModel.class).createEmptyRow();
            fillInstance(row, name);
            Models.get(InstanceModel.class).save(row);
            id[0] = row.get(InstanceModel.ID);
        });
        return id[0];
    }

    /** What InstanceService.destroy leaves behind for an owned row: a soft delete in the owner's scope. */
    private static void softDeleteOwned(int serviceId, int instanceId) {
        OwnedInstances.inScopeUnchecked(StackInstances.SOURCE, StackServiceModel.MODEL_ID, serviceId,
            () -> softDelete(instanceId));
    }

    private static void softDelete(int instanceId) {
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        row.set(InstanceModel.DELETED_AT, Instant.now());
        Models.get(InstanceModel.class).save(row);
    }

    private static int database(String name) {
        Row row = Models.get(DatabaseModel.class).createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, "postgres");
        row.set(DatabaseModel.DB_USER, "app");
        row.set(DatabaseModel.DB_NAME, name.replace('-', '_'));
        Models.get(DatabaseModel.class).save(row);
        return row.get(DatabaseModel.ID);
    }

    private static int link(int instanceId, int databaseId) {
        Row row = Models.get(InstanceDatabaseModel.class).createEmptyRow();
        row.set(InstanceDatabaseModel.INSTANCE_ID, instanceId);
        row.set(InstanceDatabaseModel.DATABASE_ID, databaseId);
        Models.get(InstanceDatabaseModel.class).save(row);
        return row.get(InstanceDatabaseModel.ID);
    }

    private static int template(String name, Integer imageId) {
        Row row = Models.get(InstanceTemplateModel.class).createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        row.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
        row.set(InstanceTemplateModel.RUNTIME_IMAGE_ID, imageId);
        Models.get(InstanceTemplateModel.class).save(row);
        return row.get(InstanceTemplateModel.ID);
    }

    private static int templateVariable(int templateId, String key) {
        Row row = Models.get(InstanceTemplateVariableModel.class).createEmptyRow();
        row.set(InstanceTemplateVariableModel.TEMPLATE_ID, templateId);
        row.set(InstanceTemplateVariableModel.KEY, key);
        row.set(InstanceTemplateVariableModel.TYPE, "hohenheim:string");
        row.set(InstanceTemplateVariableModel.SETTINGS, Map.of());
        Models.get(InstanceTemplateVariableModel.class).save(row);
        return row.get(InstanceTemplateVariableModel.ID);
    }

    private static int templateFile(int templateId, String path) {
        Row row = Models.get(InstanceTemplateFileModel.class).createEmptyRow();
        row.set(InstanceTemplateFileModel.TEMPLATE_ID, templateId);
        row.set(InstanceTemplateFileModel.CONTAINER_PATH, path);
        row.set(InstanceTemplateFileModel.CONTENT, "key=value");
        Models.get(InstanceTemplateFileModel.class).save(row);
        return row.get(InstanceTemplateFileModel.ID);
    }

    private static int templateVolume(int templateId, String name) {
        Row row = Models.get(InstanceTemplateVolumeModel.class).createEmptyRow();
        row.set(InstanceTemplateVolumeModel.TEMPLATE_ID, templateId);
        row.set(InstanceTemplateVolumeModel.NAME, name);
        row.set(InstanceTemplateVolumeModel.CONTAINER_PATH, "/data");
        Models.get(InstanceTemplateVolumeModel.class).save(row);
        return row.get(InstanceTemplateVolumeModel.ID);
    }

    private static int runtimeImage(String name) {
        Row row = Models.get(RuntimeImageModel.class).createEmptyRow();
        row.set(RuntimeImageModel.NAME, name);
        row.set(RuntimeImageModel.DOCKER_IMAGE, "alpine:latest");
        row.set(RuntimeImageModel.ENABLED, true);
        Models.get(RuntimeImageModel.class).save(row);
        return row.get(RuntimeImageModel.ID);
    }

    private static long countWhere(Class<? extends Model> modelClass, Field<Integer, ?> field, int value) {
        return Models.get(modelClass).find().where(field.eq(value)).count();
    }
}
