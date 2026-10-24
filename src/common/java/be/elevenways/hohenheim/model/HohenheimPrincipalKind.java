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
 * The principals Hohenheim records beside core's and zenit-auth's: a build hook, named by its record.
 *
 * AIDEV-NOTE: a hook carries no authority of its own (core's StoredRecordPrincipal); it is only WHO a deploy is
 * accountable to, so a release started by a hook names the hook instead of a person or the bare system.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@BlastAutoLoad
public enum HohenheimPrincipalKind implements PrincipalKind {

    /** A build hook's secret URL; its reference id is the hook's id. */
    BUILD_HOOK;

    static {
        PrincipalKinds.declare(values());
    }

    private final Identifier id = HohenheimIds.id("build_hook");

    @Override
    public @NonNull Identifier id() {
        return this.id;
    }

    @Override
    public @NonNull Microcopy label() {
        return HohenheimMicrocopy.BUILD_HOOK.of("build_hook_singular");
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
        return BuildHookModel.MODEL_ID;
    }

    /** A hook is a machine the owner pointed at a URL, never a person. */
    @Override
    public boolean human() {
        return false;
    }
}
