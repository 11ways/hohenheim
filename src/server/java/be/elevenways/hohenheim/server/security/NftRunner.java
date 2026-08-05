package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * THE {@code nft} invocation seam of the application: one interface, one production
 * implementation, two policy owners on top of it ({@link NftService} for IP bans,
 * {@link WorkloadNetworkPolicy} for per-workload network policy).
 *
 * AIDEV-NOTE: stdout is CAPTURED, not discarded, because a policy that only knows the
 * exit code cannot verify anything -- {@link WorkloadNetworkPolicy} reads its chains back
 * out of the kernel and parses them, and "nft exited 0" and "the rule is in the kernel"
 * are independent facts on a host where another tool flushes tables. Stdin exists so a
 * whole ruleset can be applied through {@code nft -f -} as ONE transaction; issuing
 * flush-then-add as separate commands leaves a window in which a running workload has no
 * policy at all.
 *
 * AIDEV-NOTE: this interface says nothing about failure handling on purpose. Its two
 * consumers disagree deliberately and that disagreement is the point: NftService logs and
 * continues (a dev machine without sudo must still run), WorkloadNetworkPolicy throws and
 * refuses the deploy. Do not "unify" them.
 */
public interface NftRunner {

    /**
     * @param nftArgs argv AFTER the {@code nft} prefix
     * @param stdin   ruleset text for {@code nft -f -}, or null when the command carries
     *                everything in argv
     */
    @NonNull Result run(@NonNull List<String> nftArgs, @Nullable String stdin);

    /** Longer budget for the ssh lane: connection setup rides the same clock. */
    long SSH_TIMEOUT_SECONDS = 15;

    /**
     * THE runner for one inventoried host: local sudo, or the same sudo command over the
     * pinned+identified ssh lane for an SSH-mode host.
     *
     * AIDEV-NOTE: nft rules only isolate anything when they land in the kernel of the
     * host the WORKLOAD runs on. Building a policy applier around a plain {@code Sudo}
     * for a remote server applies and "verifies" the rules on the CONTROLLER's kernel
     * while the remote workload starts wide open -- that exact silent-success defect
     * shipped once, which is why this factory exists and why per-server callers must
     * never construct {@link Sudo} directly.
     */
    static @NonNull NftRunner forServer(@NonNull Row server) {
        if (ServerModel.MODE_SSH.equals(server.get(ServerModel.MODE))) {
            return (args, stdin) -> {
                List<String> argv = new ArrayList<>(HostKeys.sshArgv(server,
                    List.of("sudo", "-n", "--", "nft")));
                argv.addAll(args);
                return Sudo.execute(argv, stdin, SSH_TIMEOUT_SECONDS);
            };
        }
        return new Sudo();
    }

    record Result(int exitCode, @NonNull String stdout, @NonNull String stderr) {
        public boolean ok() {
            return exitCode == 0;
        }

        /** The failure text a thrown contract reports: stderr, or stdout when nft was quiet. */
        public @NonNull String failureText() {
            String text = stderr.trim();
            return text.isEmpty() ? stdout.trim() : text;
        }
    }

    /**
     * Executes {@code sudo -n -- nft <args>} as root. This is the ONE root-command seam in
     * the app; site processes keep going through SystemUsers (which refuses root by design
     * and must stay that way).
     */
    final class Sudo implements NftRunner {

        private static final long COMMAND_TIMEOUT_SECONDS = 10;

        @Override
        public @NonNull Result run(@NonNull List<String> nftArgs, @Nullable String stdin) {
            List<String> argv = new ArrayList<>(List.of("/usr/bin/sudo", "-n", "--", "nft"));
            argv.addAll(nftArgs);
            return execute(argv, stdin, COMMAND_TIMEOUT_SECONDS);
        }

        /** Run an argv with an optional stdin body, never blocking longer than the timeout. */
        public static @NonNull Result execute(@NonNull List<String> argv, @Nullable String stdin,
                                              long timeoutSeconds) {
            try {
                Process process = new ProcessBuilder(argv).start();
                if (stdin != null) {
                    try (OutputStream out = process.getOutputStream()) {
                        out.write(stdin.getBytes(StandardCharsets.UTF_8));
                    }
                } else {
                    process.getOutputStream().close();
                }
                // Read both pipes BEFORE waiting: a full pipe buffer deadlocks the child,
                // which on a 10s timeout looks exactly like "nft hung".
                String out = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
                String err = new String(process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return new Result(-1, out, "timed out after " + timeoutSeconds + "s");
                }
                return new Result(process.exitValue(), out, err);
            } catch (IOException e) {
                return new Result(-1, "", String.valueOf(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(-1, "", "interrupted");
            }
        }
    }
}
