package be.elevenways.hohenheim.test.network;

import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.server.security.ProcessNetworkPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A throwaway network namespace with REAL nftables in it AND several REAL uids inside it,
 * so {@code meta skuid} matching can be exercised honestly.
 *
 * AIDEV-NOTE: {@link PrivateNetns} deliberately cannot do this and the process-isolation
 * slice was blocked on saying so: {@code unshare -rn} maps exactly ONE uid (the caller's)
 * to root, so every process in it has the same identity and a uid-keyed rule cannot be
 * distinguished from a blanket drop. This fixture asks for the user's SUBUID range instead
 * (/etc/subuid + the setuid newuidmap helper, the same machinery rootless containers use),
 * which maps a whole block of inner uids, so a test can run one process as the "site" uid
 * and another as a control and watch the kernel treat them differently.
 *
 * AIDEV-NOTE: the uid a rule must name is the INNER one. nftables renders {@code meta
 * skuid} through the user namespace of the netns that owns the hook, so inside this fixture
 * the numbers are the inner uids -- which is exactly what makes the test honest: the
 * production applier is handed the same kind of number it is handed in production (a site's
 * uid) and the kernel matches on it. MEASURED, not assumed: a rule naming the outer
 * (subuid-mapped) value counts zero packets while the inner one counts them.
 */
public final class UidMappedNetns implements AutoCloseable {

    private static final int HOLD_SECONDS = 900;
    private static final long COMMAND_TIMEOUT_SECONDS = 20;

    /** Inner uids start at 1: 0 stays the fixture's own root (the mapped caller). */
    private static final int FIRST_MAPPED_INNER_UID = 1;

    private final List<Process> holders = new ArrayList<>();
    private final long hostPid;

    public UidMappedNetns() throws IOException {
        Range range = subuidRange();
        if (range == null) {
            throw new IOException("no /etc/subuid range for this user");
        }
        this.hostPid = hold(List.of("unshare", "--user", "--net",
            "--map-users=0:" + selfUid() + ":1",
            "--map-groups=0:" + selfGid() + ":1",
            "--map-users=" + FIRST_MAPPED_INNER_UID + ":" + range.start + ":" + range.count,
            "--map-groups=" + FIRST_MAPPED_INNER_UID + ":" + range.start + ":" + range.count,
            "sleep", String.valueOf(HOLD_SECONDS)));
    }

    /** @return whether this machine can build the fixture at all */
    public static boolean available() {
        if (subuidRange() == null) {
            return false;
        }
        if (!run(List.of("sh", "-c", "command -v nft && command -v nsenter && command -v ip"
            + " && command -v python3 && command -v setpriv && command -v newuidmap"), null).ok()) {
            return false;
        }
        Range range = subuidRange();
        return run(List.of("unshare", "--user", "--net",
            "--map-users=0:" + selfUid() + ":1",
            "--map-groups=0:" + selfGid() + ":1",
            "--map-users=" + FIRST_MAPPED_INNER_UID + ":" + range.start + ":" + range.count,
            "--map-groups=" + FIRST_MAPPED_INNER_UID + ":" + range.start + ":" + range.count,
            "true"), null).ok();
    }

    /** @return the highest inner uid this fixture can run a process as */
    public int highestMappedUid() {
        Range range = subuidRange();
        return range == null ? 0 : FIRST_MAPPED_INNER_UID + range.count - 1;
    }

    /** @return an ENABLED production applier bound to this namespace's kernel and resolv.conf */
    public ProcessNetworkPolicy enforcingPolicy(Path resolvConf) {
        return new ProcessNetworkPolicy(nftRunner(), () -> true, resolvConf);
    }

    /** @return a DISABLED production applier: the pre-enforcement host shape */
    public ProcessNetworkPolicy disabledPolicy(Path resolvConf) {
        return new ProcessNetworkPolicy(nftRunner(), () -> false, resolvConf);
    }

    /** @return an nft runner that applies to THIS namespace's kernel tables */
    public NftRunner nftRunner() {
        return (args, stdin) -> run(enter(prefixed("nft", args)), stdin);
    }

    /** Run a command as the fixture's inner root. */
    public NftRunner.Result asRoot(String... argv) {
        return run(enter(List.of(argv)), null);
    }

    /** Run a command as one of the mapped inner uids. */
    public NftRunner.Result asUid(int uid, String... argv) {
        List<String> full = new ArrayList<>(List.of("setpriv",
            "--reuid=" + uid, "--regid=" + uid, "--clear-groups"));
        full.addAll(List.of(argv));
        return run(enter(full), null);
    }

    /** Run fixture setup as inner root, throwing when it fails: setup must not limp. */
    public void setup(String... argv) throws IOException {
        NftRunner.Result result = asRoot(argv);
        if (!result.ok()) {
            throw new IOException("fixture setup failed: " + String.join(" ", argv) + " -> "
                + result.failureText());
        }
    }

    /**
     * Try to open a TCP connection AS the given inner uid.
     *
     * @return one of REACHABLE, BLOCKED (no answer at all) or REFUSED (the peer answered)
     */
    public String probe(int uid, String address, int port) {
        NftRunner.Result result = asUid(uid, "python3", "-c",
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

    /**
     * Send a real DNS query AS the given inner uid and wait for the answer.
     *
     * @return RESOLVED when the resolver answered, BLOCKED when nothing came back
     */
    public String resolve(int uid, String resolver) {
        NftRunner.Result result = asUid(uid, "python3", "-c",
            "import socket,sys\n"
            + "q = bytes([0x12,0x34,1,0,0,1,0,0,0,0,0,0]) + b'\\x07example\\x03com\\x00'"
            + " + bytes([0,1,0,1])\n"
            + "s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)\n"
            + "s.settimeout(3)\n"
            + "try:\n"
            + "    s.sendto(q, (sys.argv[1], 53))\n"
            + "    data, _ = s.recvfrom(512)\n"
            + "    print('RESOLVED' if data[:2] == q[:2] else 'GARBAGE')\n"
            + "except socket.timeout:\n"
            + "    print('BLOCKED')\n"
            + "except OSError as e:\n"
            + "    print('REFUSED:' + e.__class__.__name__)\n",
            resolver);
        String answer = result.stdout().trim();
        return answer.isEmpty() ? "ERROR:" + result.failureText() : answer;
    }

    /** Start a background TCP listener on one address inside the fixture. */
    public void listen(String address, int port) throws IOException {
        hold(enter(List.of("python3", "-c",
            "import socket,time\n"
            + "s = socket.socket()\n"
            + "s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)\n"
            + "s.bind(('" + address + "', " + port + "))\n"
            + "s.listen(8)\n"
            + "time.sleep(" + HOLD_SECONDS + ")\n")));
    }

    /** Start a background UDP responder that answers DNS queries with their own header id. */
    public void listenDns(String address) throws IOException {
        hold(enter(List.of("python3", "-c",
            "import socket\n"
            + "s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)\n"
            + "s.bind(('" + address + "', 53))\n"
            + "while True:\n"
            + "    data, peer = s.recvfrom(512)\n"
            + "    s.sendto(data[:2] + bytes([0x81,0x80]) + data[4:], peer)\n")));
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
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!process.isAlive()) {
            throw new IOException("namespace holder died immediately: " + String.join(" ", argv));
        }
        return process.pid();
    }

    private List<String> enter(List<String> argv) {
        List<String> full = new ArrayList<>(List.of("nsenter", "-t", String.valueOf(this.hostPid),
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

    private record Range(int start, int count) {}

    /** The caller's /etc/subuid allocation, or null when it has none. */
    private static Range subuidRange() {
        String user = System.getProperty("user.name");
        try {
            for (String line : Files.readAllLines(Path.of("/etc/subuid"))) {
                String[] parts = line.trim().split(":");
                if (parts.length != 3) {
                    continue;
                }
                if (parts[0].equals(user) || parts[0].equals(String.valueOf(selfUid()))) {
                    return new Range(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                }
            }
        } catch (IOException | RuntimeException unusable) {
            return null;
        }
        return null;
    }

    private static int selfUid() {
        return idField("Uid:");
    }

    private static int selfGid() {
        return idField("Gid:");
    }

    private static int idField(String prefix) {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith(prefix)) {
                    return Integer.parseInt(line.substring(prefix.length()).trim().split("\\s+")[0]);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return -1;
        }
        return -1;
    }
}
