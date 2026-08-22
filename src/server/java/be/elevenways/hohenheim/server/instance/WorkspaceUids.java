package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * THE derivation of the host uid a workspace runs as: {@code volume_uid_base + instance id}.
 *
 * AIDEV-NOTE: a NUMBER, never an account. The deleted host-user lane created a real unix
 * user per workload and had to keep /etc/passwd, the checkout, the process and the cgroup
 * agreeing about it; here the only thing that exists is the number stamped on the container
 * and on the files it writes into its volume. Nothing on the host resolves it to a name,
 * which is the point -- there is no login, no shell and no home directory to take over.
 *
 * AIDEV-NOTE: the derivation is ARITHMETIC on the instance id rather than a stored column
 * on purpose. A stored uid is a second authority that can disagree with the files already
 * on disk after a restore or a re-create, and the id it derives from is the primary key
 * that already never changes. The cost is that two workspaces can never share a uid and a
 * deleted one's uid is never reused -- both of which are properties we want.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class WorkspaceUids {

    /**
     * The first uid Incus hands unprivileged containers on both live hosts
     * ({@code /etc/subuid}: {@code root:1000000:1000000000}).
     */
    public static final int INCUS_SUBUID_START = 1000000;

    /** Below this are the host's own accounts and system users. */
    static final int LOWEST_SAFE_BASE = 100000;

    private WorkspaceUids() {
    }

    /**
     * The host uid of one workspace.
     *
     * @throws Violations when the configured base would collide with the host's own
     *         accounts or with Incus's subuid range -- a workspace sharing a uid with
     *         another container's root is an isolation failure that looks like nothing
     */
    public static int forInstance(int instanceId) {

        int base = configuredBase();

        if (base < LOWEST_SAFE_BASE || base >= INCUS_SUBUID_START) {
            throw Violations.ofForm(Microcopy.of("workspace_uid_base_invalid")
                .withFilter("scope", "violations")
                .withArg("base", String.valueOf(base))
                .withArg("lowest", String.valueOf(LOWEST_SAFE_BASE))
                .withArg("highest", String.valueOf(INCUS_SUBUID_START)));
        }

        int uid = base + instanceId;

        if (uid >= INCUS_SUBUID_START) {
            throw Violations.ofForm(Microcopy.of("workspace_uid_out_of_range")
                .withFilter("scope", "violations")
                .withArg("uid", String.valueOf(uid))
                .withArg("highest", String.valueOf(INCUS_SUBUID_START)));
        }

        return uid;
    }

    /**
     * The HOST uid that owns a workspace's volume directory on an Incus host.
     *
     * AIDEV-NOTE: an Incus container's uid is a NAMESPACE id, and the kernel checks a
     * bind-mounted host directory against the process's HOST uid -- which is the
     * container's idmap base plus that namespace id. Measured on nightstrom 2026-08-22:
     * a directory chowned to the namespace id is unreadable from inside (the workload's
     * own login shell fails on its own home), and one chowned to base+id works end to end.
     * Docker needs no such translation, which is why this is the one number that differs
     * between the two runtimes and why it is derived here rather than assumed anywhere.
     *
     * AIDEV-NOTE: {@code raw.idmap} would give host/namespace PARITY and was measured
     * first. It works, but ONLY if the host delegates the workspace uid window in
     * /etc/subuid, and adding a second range there makes Incus union both into one
     * default map with two entries for namespace id 0 -- after which EVERY container
     * without a raw.idmap of its own fails to start ("newuidmap: write to uid_map failed").
     * Parity is not worth breaking every other workload on the host.
     */
    public static int incusHostUid(int containerUid, int subuidBase) {
        return subuidBase + containerUid;
    }

    /**
     * The first host uid an Incus host delegates to root -- the base its containers are
     * mapped from.
     *
     * @throws Violations {@code workspace_host_no_subuid_range} when the host delegates
     *         nothing, which means no unprivileged container on it can be mapped at all
     */
    public static int incusSubuidBase(@NonNull String serverName, @NonNull HostShell shell) {

        HostShell.Result read = shell.run("cat /etc/subuid");

        if (read.ok()) {
            for (String line : read.text().split("\n")) {
                String[] parts = line.trim().split(":");
                if (parts.length < 3 || !"root".equals(parts[0].trim())) {
                    continue;
                }
                try {
                    return Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException notARange) {
                    // a comment or a malformed line delegates nothing
                }
            }
        }

        throw Violations.ofForm(Microcopy.of("workspace_host_no_subuid_range")
            .withFilter("scope", "violations")
            .withArg("name", serverName));
    }

    /** The configured first uid, or the shipped default when nothing is set. */
    public static int configuredBase() {
        Integer base = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.VOLUME_UID_BASE);
        return base == null ? 200000 : base;
    }
}
