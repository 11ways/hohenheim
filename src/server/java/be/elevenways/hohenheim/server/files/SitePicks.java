package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.EditContext;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.setting.SettingReference;
import be.elevenways.zenit.server.storage.DeclaredStorage;
import be.elevenways.zenit.server.storage.Storages;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE picker of the storages a person may keep a site's files in: this server's own site files, the storages this
 * server's settings declare (an administrator's alone) and the storage records the person may use, read through the
 * storage model's record source, so the picker and the Storages list answer to the one scope.
 *
 * AIDEV-NOTE: a submit naming a storage the picker does not offer the person is refused by the select itself, so a
 * site's manager may change "Files kept in" (SiteManagerSettings) without reaching another tenant's storage.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@BlastAutoLoad
public final class SitePicks {

    /** Auto-load sentinel; reading it runs the registration below. */
    public static final boolean LOADED = register();

    /** The storage records a picker offers, by name, through their source's scope. */
    private static final SettingReference STORAGES = new SettingReference(BackupTargetModel.MODEL_ID,
        BackupTargetModel.NAME);

    private SitePicks() {
    }

    private static boolean register() {
        FieldFormEntryRegistry.INSTANCE.register(SiteStorageField.class, SitePicks::picker);
        return true;
    }

    /** @return the picker over a storage name field, the one a site's form and a move's input draw */
    public static @NonNull Select<String> picker(@NonNull SiteStorageField field) {
        return Select.of(field).options(OptionSource.dynamic(SitePicks::offered)).build();
    }

    /** @return the storages the editing person may keep a site's files in, by name */
    static @NonNull List<FieldOption<String>> offered(@NonNull EditContext context) {
        return offered(context.accessContext());
    }

    /** @return the storages the person may keep a site's files in, by name: this server's own first */
    public static @NonNull List<FieldOption<String>> offered(@Nullable AccessContext access) {
        Map<String, FieldOption<String>> options = new LinkedHashMap<>();
        options.put(SiteFiles.STORAGE, FieldOption.of(SiteFiles.STORAGE,
            HohenheimMicrocopy.BACKUP_TARGET.of("site_files_own")));
        if (access != null && HohenheimAccess.isAdmin(access)) {
            // The storage lines of the settings, never a module's own home (media, content, the default store).
            for (DeclaredStorage declared : Storages.inSettings()) {
                options.putIfAbsent(declared.name(), FieldOption.of(declared.name(),
                    Microcopy.literal(declared.name())));
            }
        }
        STORAGES.offered(access).forEach((name, words) ->
            options.putIfAbsent(name, FieldOption.of(name, Microcopy.literal(words))));
        return new ArrayList<>(options.values());
    }

    /** @return whether the person may keep a site's files in the storage the name declares */
    public static boolean mayUse(@NonNull String name, @Nullable AccessContext access) {
        for (FieldOption<String> option : offered(access)) {
            if (name.equals(option.value())) {
                return true;
            }
        }
        return false;
    }
}
