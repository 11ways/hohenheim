package be.elevenways.hohenheim.test.network;

import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A throwaway network namespace with REAL nftables in it, plus a nested namespace that
 * plays the tenant container.
 *
 * AIDEV-NOTE: this exists because the host's nft needs a password for sudo on developer
 * machines, and the alternative -- skipping every network-policy test -- means a green
 * suite proves nothing. {@code unshare -rn} gives an unprivileged user namespace whose
 * netns we own completely: the kernel's own nftables, the kernel's own routing, real
 * packets. The rules applied here are the exact strings the production applier emits, run
 * through the production applier itself; only the process launcher differs. What it does
 * NOT prove is that the host's own netns accepts them -- that needs a host with
 * passwordless nft, and the test says so out loud rather than pretending.
 */
public final class PrivateNetns implements AutoCloseable {

    /** Seconds the namespace holders stay alive; the fixture is installed CLASS-wide in
     * several live suites (SiteReleaseLiveTest runs four full release journeys on one
     * fixture), so this must outlive a whole class, not one test. Holders are killed in
     * {@link #close()} either way. */
    private static final int HOLD_SECONDS = 1800;

    private static final long COMMAND_TIMEOUT_SECONDS = 20;

    private final List<Process> holders = new ArrayList<>();
    private final long hostPid;

    /**
     * Build a fixture and install its ENFORCING policy as the process-wide override, or
     * return null when this machine cannot build one -- callers assume on the result so
     * the skip is visible instead of a policy-less pass.
     */
    public static PrivateNetns installEnforcing() throws IOException {
        if (!available()) {
            return null;
        }
        PrivateNetns netns = new PrivateNetns();
        WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        return netns;
    }

    /** Undo {@link #installEnforcing}; a null fixture is a no-op. */
    public static void uninstall(PrivateNetns netns) {
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
        }
    }

    /** @return whether this machine can build the fixture at all */
    public static boolean available() {
        return run(List.of("unshare", "-rn", "true"), null).ok()
            && run(List.of("sh", "-c", "command -v nft && command -v nsenter && command -v ip"
                + " && command -v python3"), null).ok();
    }

    public PrivateNetns() throws IOException {
        this.hostPid = hold(List.of("unshare", "-rn", "sleep", String.valueOf(HOLD_SECONDS)));
    }

    /** @return the pid holding a nested namespace inside this one (a "container") */
    public long nested() throws IOException {
        return hold(enter(hostPid, List.of("unshare", "-n", "sleep", String.valueOf(HOLD_SECONDS))));
    }

    /** @return an ENABLED production policy applier bound to this namespace's kernel */
    public WorkloadNetworkPolicy enforcingPolicy() {
        return new WorkloadNetworkPolicy(nftRunner(), () -> true);
    }

    /** @return an nft runner that applies to THIS namespace's kernel tables */
    public NftRunner nftRunner() {
        return (args, stdin) -> {
            List<String> argv = enter(hostPid, prefixed("nft", args));
            return run(argv, stdin);
        };
    }

    /** Run a command in the fixture's own namespace, failing the caller on a non-zero exit. */
    public NftRunner.Result inHost(String... argv) {
        return run(enter(hostPid, List.of(argv)), null);
    }

    /** Run a command inside one of the nested namespaces. */
    public NftRunner.Result inNested(long pid, String... argv) {
        return run(enter(pid, List.of(argv)), null);
    }

    /** Run a command in the host namespace and throw when it fails: fixture setup must not limp. */
    public void setup(String... argv) throws IOException {
        NftRunner.Result result = inHost(argv);
        if (!result.ok()) {
            throw new IOException("fixture setup failed: " + String.join(" ", argv) + " -> "
                + result.failureText());
        }
    }

    /** Same, inside a nested namespace. */
    public void setupNested(long pid, String... argv) throws IOException {
        NftRunner.Result result = inNested(pid, argv);
        if (!result.ok()) {
            throw new IOException("fixture setup failed in nested ns: "
                + String.join(" ", argv) + " -> " + result.failureText());
        }
    }

    /**
     * Try to open a TCP connection from a nested namespace.
     *
     * @return one of REACHABLE, BLOCKED (no answer at all) or REFUSED (the peer answered)
     */
    public String probe(long pid, String address, int port) {
        NftRunner.Result result = inNested(pid, "python3", "-c",
            "import socket,sys\n"
            + "s = socket.socket(socket.AF_INET6 if ':' in sys.argv[1] else socket.AF_INET)\n"
            + "s.settimeout(3)\n"
            + "try:\n"
            + "    s.connect((sys.argv[1], int(sys.argv[2])))\n"
            + "    print('REACHABLE')\n"
            + "except socket.timeout:\n"
            + "    print('BLOCKED')\n"
            + "except OSError as e:\n"
            + "    print('REFUSED:' + e.__class__.__name__)\n",
            address, String.valueOf(port));
        String answer = result.stdout().trim();
        return answer.isEmpty() ? "ERROR:" + result.failureText() : answer;
    }

    /** Start a background TCP listener inside a nested namespace and leave it running. */
    public void listen(long pid, int port) throws IOException {
        hold(enter(pid, List.of("python3", "-c",
            "import socket,time\n"
            + "s = socket.socket(socket.AF_INET6)\n"
            + "s.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)\n"
            + "s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)\n"
            + "s.bind(('::', " + port + "))\n"
            + "s.listen(8)\n"
            + "time.sleep(" + HOLD_SECONDS + ")\n")));
    }

    /** Start a background TCP listener in the fixture's own (the "host") namespace. */
    public void listenInHost(int port) throws IOException {
        hold(enter(hostPid, List.of("python3", "-c",
            "import socket,time\n"
            + "s = socket.socket(socket.AF_INET6)\n"
            + "s.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)\n"
            + "s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)\n"
            + "s.bind(('::', " + port + "))\n"
            + "s.listen(8)\n"
            + "time.sleep(" + HOLD_SECONDS + ")\n")));
    }

    @Override
    public void close() {
        for (Process holder : this.holders) {
            holder.destroyForcibly();
        }
    }

    private long hold(List<String> argv) throws IOException {
        Process process = new ProcessBuilder(argv)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        this.holders.add(process);
        // unshare and nsenter exec their command in the SAME process, so the pid we get is
        // the pid whose /proc/<pid>/ns/* the next nsenter must target.
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!process.isAlive()) {
            throw new IOException("namespace holder died immediately: " + String.join(" ", argv));
        }
        return process.pid();
    }

    private static List<String> enter(long pid, List<String> argv) {
        List<String> full = new ArrayList<>(List.of("nsenter", "-t", String.valueOf(pid),
            "-U", "-n", "--preserve-credentials"));
        full.addAll(argv);
        return full;
    }

    private static List<String> prefixed(String command, List<String> args) {
        List<String> argv = new ArrayList<>();
        argv.add(command);
        argv.addAll(args);
        return argv;
    }

    private static NftRunner.Result run(List<String> argv, String stdin) {
        return NftRunner.Sudo.execute(argv, stdin, COMMAND_TIMEOUT_SECONDS);
    }
}
