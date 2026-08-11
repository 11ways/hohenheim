package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One trust relationship of a host as STRUCTURED evidence for the overview page:
 * the pinned fingerprint, its confirmation state, the pin's own timestamp, any
 * offered-but-contradicting material, and the client credential the operator installs.
 *
 * @param laneId          the action-id lane token ({@code host_key} / {@code incus_cert})
 * @param pinned          whether the slot holds pinned material at all
 * @param fingerprint     the pinned fingerprint, {@code ""} when unpinned
 * @param verified        whether an operator confirmed the fingerprint out of band
 * @param pinnedAtIso     when the pin was established, null for a pre-stamp record
 * @param offeredFingerprint digest of contradicting material a rescan saw, {@code ""} when none
 * @param quarantined     whether the quarantine verdict is attributed to THIS slot
 * @param clientMaterial  the client public key / certificate to install, {@code ""} when unminted
 */
@HawkeyeClass
public record TrustLaneView(
    String laneId,
    boolean pinned,
    String fingerprint,
    boolean verified,
    @Nullable String pinnedAtIso,
    String offeredFingerprint,
    boolean quarantined,
    String clientMaterial
) {
}
