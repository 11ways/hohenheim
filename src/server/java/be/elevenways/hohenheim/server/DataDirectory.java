package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.common.Zenit;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.nio.file.Path;

/**
 * THE controller's data directory ({@code hohenheim.storage.data_path}): the root every controller-local store resolves
 * under, from checkouts and artifacts to the SFTP spool, host keys and git SSH keys.
 *
 * AIDEV-NOTE: a blank stored path reads as the setting's own default and nothing else; the copies this replaced each
 * kept a fallback of their own and disagreed ("data" in some, "/opt/hohenheim/data" in others). The volume root is a
 * different question (a path on every host, {@code VolumeBackends.volumeRoot}) and keeps its own reading.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class DataDirectory {

    private DataDirectory() {
    }

    /** @return the data directory as the settings name it */
    public static @NonNull Path path() {
        String stored = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        return Path.of(stored == null || stored.isBlank()
            ? HohenheimSettings.Storage.DATA_PATH.getDefaultValue() : stored.strip());
    }

    /** @return a store under the data directory */
    public static @NonNull Path resolve(@NonNull String first, @NonNull String... more) {
        return path().resolve(Path.of(first, more));
    }
}
