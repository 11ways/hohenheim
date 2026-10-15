package be.elevenways.hohenheim.site;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.field.StoredEnum;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * What the proxy answers for a hostname no site serves (npmplus's "default site"): the one vocabulary the
 * {@code proxy.unknown_hosts} setting stores and the proxy's routing switches over.
 *
 * AIDEV-NOTE: every fact is an exhaustive switch on the member, so a sixth answer refuses to compile until it names
 * its target setting. An unknown or blank stored key reads as {@link #NOT_FOUND} ({@link #fromKeyOrDefault}), and an
 * answer whose target is missing or unusable answers the 404 page too: the closed failure serves nothing.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum UnknownHostAnswer implements StoredEnum {

    /** Hohenheim's own 404 page, carrying the operator's not-found message when one is set. */
    NOT_FOUND("not_found", "The 404 page", "circle-xmark", BadgeVariant.SECONDARY),

    /** Proxy the request to the fallback address, the Node original's "everything else goes to Apache". */
    FORWARD("forward", "Forward to an address", "route", BadgeVariant.INFO),

    /** A temporary redirect to one URL. */
    REDIRECT("redirect", "Redirect to a URL", "arrow-right", BadgeVariant.INFO),

    /** Serve one of the operator's own sites, through its own route (access list, gates, headers). */
    SITE("site", "One of your sites", "globe", BadgeVariant.PRIMARY),

    /** Close the connection without an answer (nginx's 444). */
    DROP("drop", "No response", "ban", BadgeVariant.WARNING);

    private final @NonNull String storageKey;
    private final @NonNull String label;
    private final @NonNull Icon icon;
    private final @NonNull BadgeVariant color;

    UnknownHostAnswer(@NonNull String storageKey, @NonNull String label, @NonNull String iconName,
                      @NonNull BadgeVariant color) {
        this.storageKey = storageKey;
        this.label = label;
        this.icon = Icon.of(iconName);
        this.color = color;
    }

    @Override
    public @NonNull String storageKey() {
        return this.storageKey;
    }

    @Override
    public @NonNull String label() {
        return this.label;
    }

    @Override
    public @NonNull Microcopy labelMicrocopy() {
        return HohenheimMicrocopy.UNKNOWN_HOST_ANSWER.of(this.storageKey);
    }

    @Override
    public @NonNull Microcopy description() {
        return HohenheimMicrocopy.UNKNOWN_HOST_ANSWER.of(this.storageKey + "_detail");
    }

    @Override
    public @NonNull Icon icon() {
        return this.icon;
    }

    @Override
    public @NonNull BadgeColor color() {
        return this.color;
    }

    /** @return the setting holding what this answer needs (an address, a URL, a site), or null when it needs none */
    public @Nullable SettingDefinition<String> target() {
        return switch (this) {
            case NOT_FOUND, DROP -> null;
            case FORWARD -> HohenheimSettings.Proxy.UNKNOWN_HOSTS_FORWARD;
            case REDIRECT -> HohenheimSettings.Proxy.UNKNOWN_HOSTS_REDIRECT;
            case SITE -> HohenheimSettings.Proxy.UNKNOWN_HOSTS_SITE;
        };
    }

    /** @return the answer stored under {@code key}; {@link #NOT_FOUND} for a blank or unknown one */
    public static @NonNull UnknownHostAnswer fromKeyOrDefault(@Nullable String key) {
        UnknownHostAnswer found = StoredEnum.fromKeyOrNull(values(), key);
        return found == null ? NOT_FOUND : found;
    }
}
