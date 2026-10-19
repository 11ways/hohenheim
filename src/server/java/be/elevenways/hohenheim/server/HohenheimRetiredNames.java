package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.site.UnknownHostAnswer;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.zenit.notifications.NotificationSettings;
import be.elevenways.zenit.server.orm.DatasourceFactory;
import be.elevenways.zenit.server.setting.RetiredConfiguration;
import be.elevenways.zenit.server.setting.RetiredName;
import be.elevenways.zenit.server.setting.ServerSettings;

import java.util.List;

/**
 * The configuration names Hohenheim stopped reading, refused at every boot.
 *
 * AIDEV-NOTE: Hohenheim loaded zenit-comms' private context from settings/comms.dry and COMMS__* because nothing
 * else did. zenit-notifications reads the framework's context now, so its keys live under notifications.* in
 * settings/local.dry (or ZENIT__NOTIFICATIONS__*), and a transport chain left in the old file would silently stop
 * being read. A comms.* key already in local.dry moves through NotificationsRetiredNames. Hohenheim's own
 * settings/hohenheim.dry and HOHENHEIM__* fed a private context the same way until hohenheim.* joined that chain.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@BlastAutoLoad
public final class HohenheimRetiredNames {

    /**
     * The old notifications settings file and environment prefix this host loaded by hand, and Hohenheim's own: each
     * file is adopted into settings/local.dry under its group (notifications.*, hohenheim.*), each prefix refused
     * naming its ZENIT__ spelling. The fallback address became the forward answer's target, and its presence meant
     * that answer, which its move writes where the file chose none. The database file became zenit's database.url,
     * its relative path resolved against the settings root (an old hohenheim.dry carried "hohenheim.db").
     *
     * AIDEV-NOTE: HohenheimSettings.Database.PATH stays declared only as the in-process fallback
     * (HohenheimDatabase.fallbackUrl, set by the test lanes); no settings source carries it past boot, which moves it
     * out of local.dry and refuses it anywhere else.
     */
    public static final List<RetiredName> RETIRED = RetiredConfiguration.declare(
        RetiredName.adoptedFile("settings/comms.dry", NotificationSettings.ROOT),
        RetiredName.variablePrefix("COMMS", NotificationSettings.ROOT),
        RetiredName.adoptedFile("settings/hohenheim.dry", HohenheimSettings.HOHENHEIM),
        RetiredName.variablePrefix("HOHENHEIM", HohenheimSettings.HOHENHEIM),
        RetiredName.movedSetting("hohenheim.proxy.fallback_address", HohenheimSettings.Proxy.UNKNOWN_HOSTS_FORWARD)
            .implying(HohenheimSettings.Proxy.UNKNOWN_HOSTS, UnknownHostAnswer.FORWARD.storageKey()),
        RetiredName.movedSetting("hohenheim.database.path", ServerSettings.Database.URL)
            .converting(DatasourceFactory.SQLITE_FILE_URL));

    private HohenheimRetiredNames() {
    }
}
