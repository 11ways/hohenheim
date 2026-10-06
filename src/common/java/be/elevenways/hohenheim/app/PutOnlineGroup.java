package be.elevenways.hohenheim.app;

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
            case TEMPLATE -> Microcopy.of("group_templates").withFilter("scope", "put_online");
            case OWN_CODE -> Microcopy.of("group_code").withFilter("scope", "put_online");
            case MACHINE -> Microcopy.of("group_machine").withFilter("scope", "put_online");
            case ADDRESS -> Microcopy.of("group_addresses").withFilter("scope", "put_online");
        };
    }
}
