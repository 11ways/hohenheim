package be.elevenways.hohenheim.server.docker;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * THE reading of the {@code image}, {@code tag} and {@code command} kind settings every
 * container kind declares ({@code DockerContainerKind}, {@code ReleaseKind}), so an operator's
 * container and an application's release container never disagree about what they name.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class ContainerSettings {

    private ContainerSettings() {
    }

    /** The image reference: {@code image:tag}, or the image as is when it already names a tag or none is set. */
    public static @NonNull String imageReference(@NonNull Map<String, Object> settings) {
        String image = text(settings.get("image"));
        String tag = text(settings.get("tag"));
        return tag.isEmpty() || image.contains(":") ? image : image + ":" + tag;
    }

    /** The command split on whitespace, or null when none is set (the image's own command runs). */
    public static @Nullable List<String> commandLine(@NonNull Map<String, Object> settings) {
        String command = text(settings.get("command"));
        return command.isEmpty() ? null : List.of(command.split("\\s+"));
    }

    private static @NonNull String text(@Nullable Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
