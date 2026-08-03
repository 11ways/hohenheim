package be.elevenways.hohenheim.server.runtime;

/**
 * What a runtime daemon says about one workload. ABSENT ("this workload is gone") and
 * UNREACHABLE ("I could not ask the daemon") are DISTINCT identities on purpose:
 * conflating them is how an operator restarts a healthy host chasing a deleted
 * container, or trusts a "not running" that was really a network failure.
 *
 * AIDEV-NOTE: THE four-state vocabulary, shared by ManagedDatabase.LiveStatus and
 * InstanceStatus -- a second per-tier spelling of these states is how one tier's
 * "absent" quietly becomes another's "unreachable".
 */
public enum ContainerState { RUNNING, STOPPED, ABSENT, UNREACHABLE }
