package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;

/**
 * The peer API response to a transfer-key negotiation: the key name both sides
 * now hold. The secret is deliberately never echoed -- the caller minted it.
 */
@HawkeyeClass
public record DnsPeerKeyResponse(String status, String key_name, String peer) {
}
