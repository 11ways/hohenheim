package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.migration.M011_ReviewHardening;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.setting.SettingGroup;
import be.elevenways.zenit.test.support.SettingAuthorityTestSupport;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Every setting this host's settings page can reach declares who may read and change it; a role holding both tiers
 * and the settings leaves the system tier's holders are granted keeps every one, and the ordinary admin tier changes
 * Hohenheim's own settings only.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimSettingAuthorityTest extends SettingAuthorityTestSupport {

    @Override
    protected @NonNull String settingsPackage() {
        return "be.elevenways";
    }

    @Override
    protected @NonNull Collection<String> systemGrants() {
        List<String> grants = new ArrayList<>(List.of(HohenheimSources.ADMIN_SYSTEM.value(),
            HohenheimSources.ADMIN_ACCESS.value()));
        grants.addAll(M011_ReviewHardening.SYSTEM_SETTINGS_GRANTS);
        return grants;
    }

    @Override
    protected Permission ordinaryTier() {
        return HohenheimSources.ADMIN_ACCESS;
    }

    @Override
    protected @NonNull List<SettingGroup> ordinaryTierGroups() {
        return List.of(HohenheimSettings.HOHENHEIM);
    }
}
