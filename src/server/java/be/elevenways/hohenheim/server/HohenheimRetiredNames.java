package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.common.annotation.ZenitAutoLoad;
import be.elevenways.zenit.comms.CommsSettings;
import be.elevenways.zenit.server.setting.RetiredConfiguration;
import be.elevenways.zenit.server.setting.RetiredName;

import java.util.List;

/**
 * The configuration names Hohenheim stopped reading, refused at every boot.
 *
 * AIDEV-NOTE: Hohenheim loaded zenit-comms' private context from settings/comms.dry and COMMS__* because nothing
 * else did. zenit-comms reads the framework's context now, so its keys live under comms.* in settings/local.dry
 * (or ZENIT__COMMS__*), and a transport chain left in the old file would silently stop being read. Hohenheim's own
 * settings/hohenheim.dry and HOHENHEIM__* fed a private context the same way until hohenheim.* joined that chain.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@ZenitAutoLoad
public final class HohenheimRetiredNames {

    /**
     * The comms settings file and environment prefix this host loaded by hand, and Hohenheim's own: its file is
     * adopted into settings/local.dry under hohenheim.*, its prefix refused naming ZENIT__HOHENHEIM__*.
     */
    public static final List<RetiredName> RETIRED = RetiredConfiguration.declare(
        RetiredName.file("settings/comms.dry", CommsSettings.ROOT),
        RetiredName.variablePrefix("COMMS", CommsSettings.ROOT),
        RetiredName.adoptedFile("settings/hohenheim.dry", HohenheimSettings.HOHENHEIM),
        RetiredName.variablePrefix("HOHENHEIM", HohenheimSettings.HOHENHEIM));

    private HohenheimRetiredNames() {
    }
}
