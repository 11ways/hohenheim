package be.elevenways.hohenheim.app;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The groups "Put something online" sorts its choices into (board Online-1); a kind names its own group.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public enum PutOnlineGroup {

    /** A template from the catalogue. */
    TEMPLATE,

    /** The operator's own code, image or files. */
    OWN_CODE,

    /** A whole machine: a Linux container, a virtual machine, a workspace. */
    MACHINE,

    /** An address that points somewhere else: a redirect, an existing service, encrypted traffic passed through. */
    ADDRESS;

    /** @return the stable token tests and the page's choice values use */
    public @NonNull String token() {
        return switch (this) {
            case TEMPLATE -> "templates";
            case OWN_CODE -> "code";
            case MACHINE -> "machine";
            case ADDRESS -> "addresses";
        };
    }

    /** @return the group's heading */
    public @NonNull Microcopy label() {
        return switch (this) {
            case TEMPLATE -> HohenheimMicrocopy.PUT_ONLINE.of("group_templates");
            case OWN_CODE -> HohenheimMicrocopy.PUT_ONLINE.of("group_code");
            case MACHINE -> HohenheimMicrocopy.PUT_ONLINE.of("group_machine");
            case ADDRESS -> HohenheimMicrocopy.PUT_ONLINE.of("group_addresses");
        };
    }
}
