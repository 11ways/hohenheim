package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.zenit.common.security.SystemPurpose;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The purposes Hohenheim's system work runs under, each read once into the history action it opens.
 *
 * AIDEV-NOTE: none carries a permission, so moving a free-text system reason onto one changes no authority.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class HohenheimSystemPurposes {

    public static final @NonNull SystemPurpose BOOT = declare("boot");
    public static final @NonNull SystemPurpose SHUTDOWN = declare("shutdown");
    public static final @NonNull SystemPurpose GIT_POLL = declare("git_poll");
    public static final @NonNull SystemPurpose GIT_WEBHOOK = declare("git_webhook");
    /** A build hook's secret URL was POSTed; the hook is named on the deploys it starts. */
    public static final @NonNull SystemPurpose BUILD_HOOK = declare("build_hook");
    public static final @NonNull SystemPurpose RELEASE_DRAIN = declare("release_drain");
    public static final @NonNull SystemPurpose DATABASE_PROVISION = declare("database_provision");
    public static final @NonNull SystemPurpose TEMPLATE_INSTALL = declare("template_install");
    public static final @NonNull SystemPurpose INSTANCE_CONSOLE = declare("instance_console");
    public static final @NonNull SystemPurpose LIVE_FIGURE = declare("live_figure");
    public static final @NonNull SystemPurpose INSTANCE_RESIZE = declare("instance_resize");
    public static final @NonNull SystemPurpose FORCE_SSL_LATCH = declare("force_ssl_latch");
    public static final @NonNull SystemPurpose ACME = declare("acme");
    public static final @NonNull SystemPurpose STACK = declare("stack");
    public static final @NonNull SystemPurpose PREVIEW_DEPLOY = declare("preview_deploy");
    public static final @NonNull SystemPurpose BAN_WRITER = declare("ban_writer");
    public static final @NonNull SystemPurpose NFT_RESYNC = declare("nft_resync");
    public static final @NonNull SystemPurpose DNS_NOTIFY = declare("dns_notify");
    /** A host probe: the heartbeat, a failure and the quarantine a changed host key stamps. */
    public static final @NonNull SystemPurpose HOST_PROBE = declare("host_probe");
    /** The DNS server thread stamping a peer link's last transfer. */
    public static final @NonNull SystemPurpose DNS_FEDERATION = declare("dns_federation");
    /** The scan mirroring each host's /etc/passwd into system users. */
    public static final @NonNull SystemPurpose SYSTEM_USERS = declare("system_users");

    private HohenheimSystemPurposes() {
    }

    private static @NonNull SystemPurpose declare(@NonNull String name) {
        return SystemPurpose.declare(HohenheimIds.id(name));
    }
}
