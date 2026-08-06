package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.model.ControllerIdentityModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.security.SecureRandom;
import java.util.Map;

/**
 * THE durable identity of this controller: a short token minted once into the very
 * database that mints the record ids, and folded into every name this controller writes
 * onto a shared Docker daemon, Incus daemon, kernel or backup target.
 *
 * AIDEV-NOTE: the identity lives in the CONTROL-PLANE DATABASE, not in a setting file,
 * because the thing it disambiguates is the record id -- and record ids are allocated by
 * that database and nothing else. One database is one controller by definition: a second
 * install, a restored copy on new hardware, and each parallel test fork's fresh SQLite
 * are exactly the boundaries that must not share a namespace, and they are exactly the
 * boundaries a per-database row draws. A control-plane restore carries the row with it,
 * so a restored controller keeps naming (and keeps owning) its own workloads.
 *
 * AIDEV-NOTE: there is deliberately NO default, NO settings override and NO fallback
 * constant. Every one of those spellings is a shared value some install would silently
 * land on, and a shared value is the whole hazard: two controllers minting
 * {@code hohenheim-instance-1} for unrelated records made each one's ownership guard
 * unable to refuse the other's RUNNING workload (see OwnerLabels.removeIfOwnedBy and
 * docs/deploy-native.md). An unresolvable identity throws; it never degrades.
 */
public final class ControllerIdentity {

    /** Token alphabet: lowercase alphanumeric, the intersection every namer accepts. */
    // AIDEV-NOTE: Docker names allow far more, Incus instance names forbid underscores and
    // uppercase, nft identifiers forbid hyphens, and a bridge ifname caps at 15 characters.
    // Lowercase alnum is what survives all four, so the token is generated inside it rather
    // than sanitized per consumer.
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

    /** Token length; ~41 bits of entropy, short enough for a 15-char bridge ifname. */
    public static final int TOKEN_LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Memo, keyed on the datasource the read resolved to (test forks swap datasources). */
    private static Datasource cachedFor;
    private static String cachedToken;

    private ControllerIdentity() {
    }

    /**
     * This controller's namespace token, minting it on first use.
     *
     * @throws IllegalStateException when no control-plane database is reachable, so a
     *                               caller can never fall back to an unscoped name
     */
    public static synchronized @NonNull String token() {
        ControllerIdentityModel model;
        Datasource datasource;
        try {
            model = Models.get(ControllerIdentityModel.class);
            datasource = model.getResolvedDatasource();
        } catch (RuntimeException e) {
            throw unresolvable(e);
        }

        if (datasource == cachedFor && cachedToken != null) {
            return cachedToken;
        }

        String token;
        try {
            token = read(model);
            if (token == null) {
                token = mint(model);
            }
        } catch (RuntimeException e) {
            throw unresolvable(e);
        }

        cachedFor = datasource;
        cachedToken = token;
        return token;
    }

    /** Resolve the identity eagerly at boot, so the token is in the log before any deploy. */
    public static void resolve() {
        Blast.slog("hohenheim.controller_identity", Map.of("token", token()));
    }

    /** Drop the memo; the next {@link #token()} re-reads. Test lifecycle only. */
    public static synchronized void forgetForTest() {
        cachedFor = null;
        cachedToken = null;
    }

    private static String read(ControllerIdentityModel model) {
        Row row = model.find().first();
        if (row == null) {
            return null;
        }
        String token = row.get(ControllerIdentityModel.TOKEN);
        return token == null || token.isBlank() ? null : token;
    }

    private static @NonNull String mint(ControllerIdentityModel model) {
        Row row = model.createEmptyRow();
        row.set(ControllerIdentityModel.SINGLETON, 1);
        row.set(ControllerIdentityModel.TOKEN, generate());
        try {
            model.save(row);
        } catch (RuntimeException raced) {
            // The singleton unique index is the arbiter: another boot minted first, and
            // its token is the one every already-named resource carries.
            String existing = read(model);
            if (existing == null) {
                throw raced;
            }
            return existing;
        }
        String token = read(model);
        if (token == null) {
            throw new IllegalStateException("Minted a controller identity but it did not read"
                + " back from controller_identity; refusing to name anything on a shared daemon"
                + " without a namespace.");
        }
        return token;
    }

    /** A fresh token: lowercase alnum, first character a letter so no consumer sees an all-digit name. */
    static @NonNull String generate() {
        StringBuilder token = new StringBuilder(TOKEN_LENGTH);
        token.append(ALPHABET.charAt(10 + RANDOM.nextInt(ALPHABET.length() - 10)));
        for (int i = 1; i < TOKEN_LENGTH; i++) {
            token.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return token.toString();
    }

    private static IllegalStateException unresolvable(RuntimeException cause) {
        return new IllegalStateException("This controller has NO identity: the"
            + " controller_identity row could not be read or minted (" + cause.getMessage()
            + "). Every Docker/Incus/nftables name hohenheim writes is namespaced by it, and"
            + " there is deliberately no default -- an unscoped name would collide with any"
            + " other controller on the same daemon and make its ownership guard unable to"
            + " refuse. Boot the control-plane database (migrations included) first.", cause);
    }
}
