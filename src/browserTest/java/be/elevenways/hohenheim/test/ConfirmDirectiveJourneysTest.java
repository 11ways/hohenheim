package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.SiteHandlers;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two of hohenheim's destructive forms drive their REAL actions through the shell
 * confirmation dialog, declared with one {@code use:CmsConfirm.destructive} directive
 * (the site-access revoke and the managed-process kill; the rollback and DNS
 * remote-delete legs live in GitDeploymentFlowTest and DnsCentralEditTest, where
 * their fixtures already exist).
 *
 * AIDEV-NOTE: counterfactual (pre-conversion): the same forms carried a DEAD
 * data-confirm attribute that nothing read, so the click posted immediately --
 * the dialog assertion below fails and the cancel branch finds the action already
 * performed. The retired-attribute compile error is what keeps that spelling out.
 */
class ConfirmDirectiveJourneysTest extends HohenheimTestBase {

    private static final String DIALOG = ".pl-dialog-modal[data-open]";

    /** Revoking a site grant: dialog appears, cancel keeps the grant, confirm removes it. */
    @Test
    void accessRevokeConfirmsThroughTheShellDialog() {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Confirm Access Site");
        site.set(SiteModel.SLUG, "confirm-access-site");
        site.set(SiteModel.SITE_TYPE, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
        Integer siteId = site.get(SiteModel.ID);

        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "confirm-grantee@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Confirm Grantee");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        Integer userId = user.get(UserModel.ID);

        RecordGrants.grant("user", userId, SiteModel.MODEL_ID, siteId,
            HohenheimAccess.MANAGE, true);
        assertThat(RecordGrants.listForRecord(SiteModel.MODEL_ID, siteId)).hasSize(1);

        navigateToApp("/admin/sites/" + siteId + "/page/access");
        waitForHydration();

        String revokeButton = "form[action$='/access/remove'] pl-button";
        waitForSelector(revokeButton);

        // Cancel does NOT submit: the grant survives.
        click(revokeButton);
        assertIsVisible(DIALOG);
        click("[data-cms-confirm-cancel]");
        assertIsNotVisible(".pl-dialog-modal");
        page.waitForTimeout(400);
        assertThat(RecordGrants.listForRecord(SiteModel.MODEL_ID, siteId))
            .as("cancel must not revoke the grant")
            .hasSize(1);

        // Confirm DOES submit: the grant is actually revoked.
        click(revokeButton);
        assertIsVisible(DIALOG);
        click("[data-cms-confirm-ok]");
        page.waitForCondition(() ->
            RecordGrants.listForRecord(SiteModel.MODEL_ID, siteId).isEmpty());
    }

    /** Killing a managed process: dialog appears, cancel keeps it alive, confirm kills it. */
    @Test
    void processKillConfirmsThroughTheShellDialog() throws Exception {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Confirm Kill Site");
        site.set(SiteModel.SLUG, "confirm-kill-site");
        site.set(SiteModel.SITE_TYPE, "hohenheim:command");
        site.set(SiteModel.SETTINGS, Map.of(
            "start_command", "sleep 300",
            "wait_for_ready", false,
            "minimum_processes", 1));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
        Integer siteId = site.get(SiteModel.ID);

        var domainModel = Models.get(SiteDomainModel.class);
        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, "confirm-kill.test");
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domainModel.save(domain);

        ProxyServer previousProxy = ServerMain.getProxyServer();
        ProxyServer proxy = new ProxyServer();
        proxy.start();
        ServerMain.adoptProxyServer(proxy);

        try {
            ManagedProcessSiteHandler handler = awaitManagedHandler(siteId);
            handler.startMinimumServers();
            page.waitForCondition(() -> !handler.getProcesses().isEmpty());
            long pid = handler.getProcesses().get(0).pid();
            assertThat(ProcessHandle.of(pid)).as("the child spawned").isPresent();

            navigateToApp("/admin/sites/" + siteId + "/page/processes");
            waitForHydration();

            String killButton = "form[action$='/processes/" + pid + "/kill'] pl-button";
            waitForSelector(killButton);

            // Cancel does NOT submit: the child stays alive.
            click(killButton);
            assertIsVisible(DIALOG);
            click("[data-cms-confirm-cancel]");
            assertIsNotVisible(".pl-dialog-modal");
            page.waitForTimeout(400);
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .as("cancel must not kill the child")
                .isTrue();

            // Confirm DOES submit: the child is actually killed.
            click(killButton);
            assertIsVisible(DIALOG);
            click("[data-cms-confirm-ok]");
            page.waitForCondition(() ->
                !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            // Disable BEFORE stopping the proxy so nothing respawns the child, and other
            // classes' route reloads never resurrect this site.
            site.set(SiteModel.ENABLED, false);
            siteModel.save(site);
            proxy.stop();
            ServerMain.adoptProxyServer(previousProxy);
        }
    }

    private ManagedProcessSiteHandler awaitManagedHandler(Integer siteId) {
        page.waitForCondition(() -> SiteHandlers.managedProcess(siteId) != null);
        ManagedProcessSiteHandler handler = SiteHandlers.managedProcess(siteId);
        assertThat(handler).isNotNull();
        return handler;
    }
}
