package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.registry.StoredIdChains;
import be.elevenways.protoblast.common.registry.StoredTypeMigrations;

/**
 * The hohenheim stored-id rename chain: every id this repo once stored under another spelling resolves through it.
 *
 * AIDEV-NOTE: CHAIN is the forcing field the autoload scanner picks (the first non-constant static field), so it
 * needs no LOADED field; the static block runs in the same initializer, before any stored id is read. The zenit-dev
 * rename tool appends to the marked region; a line there is never removed while a stored value may still spell it.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@BlastAutoLoad
public final class HohenheimStoredIds {

    public static final StoredTypeMigrations CHAIN = StoredIdChains.declare(HohenheimIds.id("hohenheim_stored_ids"));

    static {
        StoredTypeMigrations migrations = CHAIN;
        // zenit-dev rename: begin
        // No hohenheim id that is ever stored changed spelling: the sweep renamed only panel page, role owner and
        // registry ids, which are never stored, so their lines were dropped (G12).
        // zenit-dev rename: end
    }

    private HohenheimStoredIds() {
    }
}
