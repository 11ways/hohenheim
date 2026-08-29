package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;

/**
 * The peer API response to a transfer-key negotiation: the key name both sides
 * now hold, plus the transfer endpoint the receiver now files the caller under.
 * The secret is deliberately never echoed -- the caller minted it.
 *
 * AIDEV-NOTE: transfer_host/transfer_port are what the receiver's row CARRIES, not
 * what was announced, and transfer_kept says the two disagreed and the receiver's
 * own value stood -- so an announcer can tell "the peer will pull from us" apart
 * from "the peer still points at something else".
 */
@HawkeyeClass
public record DnsPeerKeyResponse(String status, String key_name, String peer,
                                 String transfer_host, Integer transfer_port,
                                 Boolean transfer_kept) {
}
