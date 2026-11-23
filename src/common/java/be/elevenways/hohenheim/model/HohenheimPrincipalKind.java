package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.security.PrincipalKind;
import be.elevenways.zenit.common.security.PrincipalKinds;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The principals Hohenheim records beside core's and zenit-auth's: a build hook and a linked server, each named by its
 * record.
 *
 * AIDEV-NOTE: neither carries authority of its own (core's StoredRecordPrincipal). A hook is only WHO a deploy is
 * accountable to, so a release started by a hook names the hook instead of a person or the bare system; a linked
 * server is which server a link key proved, the identity of the work its call starts here.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@BlastAutoLoad
public enum HohenheimPrincipalKind implements PrincipalKind {

    /** A build hook's secret URL; its reference id is the hook's id. */
    BUILD_HOOK("build_hook"),

    /** Another Hohenheim presenting its link key; its reference id is its linked server row's id. */
    LINKED_SERVER("linked_server");

    static {
        PrincipalKinds.declare(values());
    }

    private final Identifier id;

    HohenheimPrincipalKind(@NonNull String path) {
        this.id = HohenheimIds.id(path);
    }

    @Override
    public @NonNull Identifier id() {
        return this.id;
    }

    @Override
    public @NonNull Microcopy label() {
        return switch (this) {
            case BUILD_HOOK -> HohenheimMicrocopy.BUILD_HOOK.of("build_hook_singular");
            case LINKED_SERVER -> HohenheimMicrocopy.LINKED_SERVER.of("linked_server_singular");
        };
    }

    @Override
    public boolean anonymous() {
        return false;
    }

    @Override
    public boolean account() {
        return false;
    }

    @Override
    public @NonNull Identifier credentialModel() {
        return switch (this) {
            case BUILD_HOOK -> BuildHookModel.MODEL_ID;
            case LINKED_SERVER -> LinkedServerModel.MODEL_ID;
        };
    }

    /** Both are machines: a URL the owner pointed somewhere, another server. */
    @Override
    public boolean human() {
        return false;
    }
}
