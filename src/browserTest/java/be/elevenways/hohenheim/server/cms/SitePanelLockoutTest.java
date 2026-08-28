package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Switching a site off is confirmed, and the site that PROXIES the panel cannot be
 * switched off from inside it.
 *
 * AIDEV-NOTE: this pins the 2026-08-27 incident, where one click on the row menu of the
 * site serving admin.starfleet.life answered every later request with "404 - No site
 * configured" and the only way back was an ssh forward to the backend port. The last step
 * is the half that makes the refusal safe: a request that did NOT arrive through a site
 * (the recovery path) is refused nothing.
 */
class SitePanelLockoutTest extends HohenheimTestBase {

    /** The hostname the panel is pretended to be reached at. */
    private static final String PANEL_HOST = "panel.lockout.test";

    private static final String OTHER_HOST = "other.lockout.test";

    private static final Identifier TOGGLE = Identifier.of("hohenheim", "toggle_site");

    private static int panelSiteId;
    private static int otherSiteId;

    @BeforeAll
    static void seedSites() {
        panelSiteId = site("Lockout Panel", PANEL_HOST);
        otherSiteId = site("Lockout Other", OTHER_HOST);
    }

    /**
     * One walk through both directions and both arrival addresses.
     */
    @Test
    void thePanelsOwnSiteCannotBeSwitchedOffFromInsideThePanel() {
        RowAction.Invoke<Row> toggle = toggleAction();
        AccessContext atPanel = arrivingAt("https://" + PANEL_HOST);
        Row panelSite = siteRow(panelSiteId);

        // 1. Reached through its own hostname, the panel's site offers the disable DEAD,
        //    naming the address it would take offline.
        Microcopy refusal = toggle.unavailableReasonFor(panelSite, atPanel);
        assertThat(refusal)
            .as("step 1: the panel's own site refuses its disable")
            .isNotNull();
        assertThat(refusal.key())
            .as("step 1: with the lockout reason, not a generic one")
            .isEqualTo("toggle_self_lockout");
        assertThat(refusal.describe())
            .as("step 1: naming the hostname this request arrived on")
            .contains(PANEL_HOST);

        // 2. In the very same request, every OTHER site's toggle is live: the verdict is
        //    per row, never once for the table.
        assertThat(toggle.unavailableReasonFor(siteRow(otherSiteId), atPanel))
            .as("step 2: a site that does not serve this panel is switchable")
            .isNull();

        // 3. The refusal is about the arrival address, not about the site: reached at the
        //    backend directly -- the ssh-forward recovery path -- nothing is refused.
        assertThat(toggle.unavailableReasonFor(panelSite, arrivingAt("http://127.0.0.1:3000")))
            .as("step 3: the recovery path can still switch the panel's site back on")
            .isNull();

        // 4. And it is about the DISABLE direction only: an already-off site cannot be
        //    carrying this request, so enabling is never refused.
        Row disabled = siteRow(panelSiteId);
        disabled.set(SiteModel.ENABLED, false);
        assertThat(toggle.unavailableReasonFor(disabled, atPanel))
            .as("step 4: enabling is never a lockout")
            .isNull();

        // 5. The same fact refuses the DELETE, which is the outage without the second
        //    click that would undo it.
        SiteResource resource = new SiteResource();
        assertThat(resource.deleteUnavailableReason(panelSite, atPanel))
            .as("step 5: deleting the panel's own site is refused too")
            .isNotNull();
        assertThat(resource.deleteUnavailableReason(siteRow(otherSiteId), atPanel))
            .as("step 5: and only that one")
            .isNull();
    }

    /**
     * The dialog exists at all -- the incident's actual defect -- and says which hostnames
     * change state, in the direction the click is going.
     */
    @Test
    void theToggleAlwaysConfirmsAndNamesTheHostnames() {
        RowAction.Invoke<Row> toggle = toggleAction();

        // 1. There is a record-less fallback, so no surface can render this unconfirmed.
        assertThat(toggle.confirmation())
            .as("step 1: the toggle declares a static confirmation")
            .isNotNull();

        // 2. Switching a live site OFF names it and its hostnames.
        Row live = siteRow(otherSiteId);
        var disable = toggle.confirmationFor(live);
        assertThat(disable).as("step 2: a per-record confirmation is resolved").isNotNull();
        assertThat(disable.body().key())
            .as("step 2: the disable body, not the enable one")
            .isEqualTo("disable_confirm");
        assertThat(disable.body().describe())
            .as("step 2: naming the hostname that stops answering")
            .contains(OTHER_HOST);

        // 3. The other direction is a different sentence, not the same one reworded by the
        //    reader: the same row switched off confirms an ENABLE.
        live.set(SiteModel.ENABLED, false);
        assertThat(toggle.confirmationFor(live).body().key())
            .as("step 3: the enable direction has its own body")
            .isEqualTo("enable_confirm");

        // 4. A site with no hostname at all says so instead of rendering a dangling list.
        Row hostless = siteRow(site("Lockout Hostless", null));
        assertThat(toggle.confirmationFor(hostless).body().key())
            .as("step 4: no hostnames, no hostname list")
            .isEqualTo("disable_confirm_no_hostnames");
    }

    /**
     * The gate, not the dialog: an unconfirmed POST to the invoke endpoint does not switch
     * a site off. This is the click the incident was -- one activation, no proof, hostnames
     * out of the route table.
     */
    @Test
    void anUnconfirmedPostDoesNotSwitchASiteOff() throws Exception {
        int siteId = site("Lockout Proof", "proof.lockout.test");
        String path = "/admin/sites/" + siteId + "/action/toggle_site";

        // 1. Posted with no confirmation proof, the site stays exactly as it was.
        httpPostForm(path, "", sessionToken, csrfToken);
        assertThat((Boolean) siteRow(siteId).get(SiteModel.ENABLED))
            .as("step 1: an unconfirmed invoke leaves the site serving")
            .isTrue();

        // 2. The very same POST carrying the proof does switch it off, so step 1 is the
        //    confirmation refusing and not the action being broken.
        httpPostForm(path, confirmed(""), sessionToken, csrfToken);
        assertThat((Boolean) siteRow(siteId).get(SiteModel.ENABLED))
            .as("step 2: a confirmed invoke switches it off")
            .isFalse();
    }

    /** The action under test, read off the resource rather than rebuilt here. */
    @SuppressWarnings("unchecked")
    private static RowAction.Invoke<Row> toggleAction() {
        return (RowAction.Invoke<Row>) new SiteResource().rowActions().stream()
            .filter(action -> TOGGLE.equals(action.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the sites resource offers no toggle action"));
    }

    private static AccessContext arrivingAt(String origin) {
        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(admin.get(UserModel.ID), "Test Admin"), origin));
    }

    private static Row siteRow(int id) {
        return Models.get(SiteModel.class).findById(id);
    }

    /** An enabled site, optionally serving one hostname. */
    private static int site(String name, String hostname) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name.toLowerCase().replace(' ', '-'));
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:address");
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        int id = site.get(SiteModel.ID);
        if (hostname != null) {
            SiteDomainModel domains = Models.get(SiteDomainModel.class);
            Row domain = domains.createEmptyRow();
            domain.set(SiteDomainModel.SITE_ID, id);
            domain.set(SiteDomainModel.HOSTNAME, hostname);
            domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
            domains.save(domain);
        }
        return id;
    }
}
