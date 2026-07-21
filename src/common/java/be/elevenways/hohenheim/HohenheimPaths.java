package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.registry.Identifier;

/** Names the administrator-gated filesystem source exposed by Hohenheim. */
public final class HohenheimPaths {

    public static final Identifier SERVER_FILES = Identifier.of("hohenheim", "server_files");

    private HohenheimPaths() {
    }
}
