package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.ui.Icon;

import java.util.ArrayList;
import java.util.List;

/**
 * CMS panel served at /admin. Constructed explicitly in ServerMain (not
 * {@code @ZenitAutoLoad}) because its resources reach server services.
 */
public final class HohenheimPanel extends Panel {

    public static final Permission ACCESS = Permission.of("hohenheim.admin.access");

    /** Proxy configuration group: sites, certificates, access control. */
    public static final NavGroup PROXY_GROUP =
        NavGroup.of("proxy", Microcopy.of("hohenheim.nav.proxy"), 300, Icon.of("globe"));

    /** Infrastructure group: databases, servers, notifications. */
    public static final NavGroup INFRA_GROUP =
        NavGroup.of("infra", Microcopy.of("hohenheim.nav.infra"), 200, Icon.of("server"));

    public HohenheimPanel() {
        super(Identifier.of("hohenheim", "admin"), "admin", Microcopy.of("hohenheim.admin.title"), ACCESS);
    }

    @Override
    public List<PanelPeer> peers() {
        List<PanelPeer> peers = new ArrayList<>();
        // The dashboard comes first: the panel landing soft-redirects to the
        // first accessible DashboardPanelPeer.
        peers.add(new AdminDashboard());
        peers.add(new SiteResource());
        peers.add(new SiteDomainResource());
        peers.add(new CertificateResource());
        peers.add(new AccessListResource());
        peers.add(new AuthProviderResource());
        peers.add(new DatabaseResource());
        peers.add(new ServerResource());
        peers.add(new NotificationChannelResource());
        peers.add(new AuditLogResource());
        peers.add(new SettingsPage());
        peers.add(new CertificateRequestPage());
        return peers;
    }
}
