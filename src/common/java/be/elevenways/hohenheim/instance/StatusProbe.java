package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.ports.PortPurpose;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.field.EnumField;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * How the controller asks a running service for its small live figure ("8 of 20 players online"), the one declaring
 * home of that vocabulary, carried in the kind SETTINGS under {@link #SETTING} and therefore in every template's
 * settings baseline.
 *
 * AIDEV-NOTE: a probe is declared by the template (or the instance's own settings), never inferred from an app's name
 * or image. Absent means no figure; a token this build does not know also means no figure (fail closed: nothing is
 * sent to a port the build cannot speak to). A new protocol is one member here plus its case in the server's
 * InstanceFigures.read switch, which has no default and so breaks the build until it is written.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum StatusProbe {

    /** The Minecraft Java edition server-list ping (handshake plus status request) on the game port, over TCP. */
    MINECRAFT_JAVA("minecraft_java", "Minecraft server list (Java edition)", PortPurpose.GAME, PublishedPort.TCP);

    /** The settings key every kind that publishes ports declares this vocabulary under. */
    public static final String SETTING = "status_probe";

    private final @NonNull String token;
    private final @NonNull String displayName;
    private final @NonNull PortPurpose purpose;
    private final @NonNull String protocol;

    StatusProbe(@NonNull String token, @NonNull String displayName, @NonNull PortPurpose purpose,
                @NonNull String protocol) {
        this.token = token;
        this.displayName = displayName;
        this.purpose = purpose;
        this.protocol = protocol;
    }

    /** @return the stored settings value */
    public @NonNull String token() {
        return this.token;
    }

    public @NonNull String displayName() {
        return this.displayName;
    }

    public @NonNull Microcopy label() {
        return HohenheimMicrocopy.STATUS_PROBE.of(this.token);
    }

    /** @return the purpose of the published port the probe asks */
    public @NonNull PortPurpose purpose() {
        return this.purpose;
    }

    /** @return the transport the probe speaks, {@link PublishedPort#TCP} or {@link PublishedPort#UDP} */
    public @NonNull String protocol() {
        return this.protocol;
    }

    /**
     * The figure an answer reads as, in words.
     *
     * @param capacity what the service says it holds at most; 0 or less when it names no ceiling
     */
    public @NonNull Microcopy figure(int online, int capacity) {
        return switch (this) {
            case MINECRAFT_JAVA -> capacity > 0
                ? HohenheimMicrocopy.STATUS_PROBE.of("players_online").withArg("online", online)
                    .withArg("capacity", capacity)
                : HohenheimMicrocopy.STATUS_PROBE.of("players_online_uncapped").withArg("online", online);
        };
    }

    /** The settings field over this vocabulary; optional, since no probe is the default. */
    public static @NonNull EnumField field() {
        EnumField.Builder builder = EnumField.builder(SETTING);
        for (StatusProbe probe : values()) {
            builder.value(probe.token, value -> value.displayName(probe.displayName).label(probe.label()));
        }
        return builder
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of(SETTING))
            .help(HohenheimMicrocopy.HELP.of(SETTING)).build();
    }

    /** @return the matching probe, or null when the token is absent or unknown */
    public static @Nullable StatusProbe forToken(@Nullable Object token) {
        if (token == null) {
            return null;
        }
        String text = String.valueOf(token).trim();
        for (StatusProbe probe : values()) {
            if (probe.token.equals(text)) {
                return probe;
            }
        }
        return null;
    }

    /** @return the probe a settings map declares, null for none and for a token this build does not know */
    public static @Nullable StatusProbe declaredIn(@Nullable Map<String, ?> settings) {
        return settings == null ? null : forToken(settings.get(SETTING));
    }
}
