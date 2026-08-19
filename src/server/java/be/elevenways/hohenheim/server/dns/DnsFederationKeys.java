package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.InetAddress;
import java.util.Base64;
import java.util.Locale;

/**
 * Minting and installing the shared TSIG key two Hohenheim instances transfer
 * zones under: the same key material is written on both sides, so no operator
 * ever copies a secret between two admin panels.
 *
 * AIDEV-NOTE: the negotiation is SYMMETRIC -- this class is both the initiator's
 * mint ({@link #mintSecret}, {@link #keyNameFor}) and the receiver's install
 * ({@link #install}), because the endpoint an instance calls on its peer is the
 * one it exposes itself.
 */
public final class DnsFederationKeys {

    /** The only algorithm negotiation mints; the column still accepts the others by hand. */
    public static final String ALGORITHM = "hmac-sha256";

    private DnsFederationKeys() {}

    /**
     * @return a fresh 256-bit TSIG secret as standard base64, the encoding dnsjava
     *         and every other nameserver expects
     */
    public static @NonNull String mintSecret() {
        // SecureTokens is the one random source; TSIG wants padded standard base64
        // rather than the URL-safe alphabet, so the same bytes are re-encoded.
        byte[] entropy = Base64.getUrlDecoder().decode(SecureTokens.randomToken(32));
        return Base64.getEncoder().encodeToString(entropy);
    }

    /** @return the key name both sides store, derived from the two instance names */
    public static @NonNull String keyNameFor(@NonNull String localName, @NonNull String peerName) {
        return "xfer-" + label(localName) + "-" + label(peerName);
    }

    /**
     * @return the name this instance announces to peers: the configured federation
     *         name, else the system hostname, else a constant
     */
    public static @NonNull String localName() {
        String configured = HohenheimSettings.VALUES.getValue(HohenheimSettings.Dns.FEDERATION_NAME);
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            if (hostname != null && !hostname.isBlank()) {
                return hostname.trim();
            }
        }
        catch (Exception unresolvable) {
            // A host that cannot name itself still federates; it just gets the constant.
        }
        return "hohenheim";
    }

    /**
     * Writes a negotiated key onto the peer row this instance keeps for the other side,
     * creating that row when the other side is new here.
     *
     * A row is matched by the KEY NAME first (a re-negotiation rotates the secret of the
     * relationship that already exists) and by the announced name second; only a peer
     * neither identifies is created. A created row is a plain NAMESERVER peer: this
     * instance holds no admin credentials for the caller, and claiming otherwise would
     * make {@code DnsPeerApi.forPeer} promise a channel that does not exist.
     *
     * @return the peer row now holding the key
     */
    public static @NonNull Row install(@NonNull String peerName, @NonNull String keyName,
                                       @NonNull String algorithm, @NonNull String secret) {
        DnsPeerModel peers = Models.get(DnsPeerModel.class);
        Row peer = peers.findByTsigKeyName(keyName);
        if (peer == null) {
            peer = peers.findByName(peerName);
        }
        if (peer == null) {
            peer = peers.createEmptyRow();
            peer.set(DnsPeerModel.NAME, uniqueName(peers, peerName));
            peer.set(DnsPeerModel.PEER_TYPE, DnsPeerModel.TYPE_NAMESERVER);
            peer.set(DnsPeerModel.ENABLED, true);
        }
        peer.set(DnsPeerModel.TSIG_KEY_NAME, keyName);
        peer.set(DnsPeerModel.TSIG_ALGORITHM, algorithm);
        peer.set(DnsPeerModel.TSIG_SECRET, secret);
        peers.save(peer);
        return peer;
    }

    /** Peer names are unique in the schema, so a colliding announced name gets a suffix. */
    private static @NonNull String uniqueName(@NonNull DnsPeerModel peers, @NonNull String peerName) {
        String base = peerName.isBlank() ? "peer" : peerName.trim();
        String candidate = base;
        int suffix = 2;
        while (peers.findByName(candidate) != null) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    /** One DNS label: lowercase, alphanumerics and hyphens only, never empty. */
    private static @NonNull String label(@Nullable String value) {
        String lowered = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < lowered.length() && cleaned.length() < 40; i++) {
            char c = lowered.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                cleaned.append(c);
            }
            else if (cleaned.length() > 0 && cleaned.charAt(cleaned.length() - 1) != '-') {
                cleaned.append('-');
            }
        }
        while (cleaned.length() > 0 && cleaned.charAt(cleaned.length() - 1) == '-') {
            cleaned.setLength(cleaned.length() - 1);
        }
        return cleaned.length() == 0 ? "peer" : cleaned.toString();
    }
}
