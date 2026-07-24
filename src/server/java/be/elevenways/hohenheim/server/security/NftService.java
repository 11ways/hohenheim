package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Kernel-level ban enforcement via nftables: Hohenheim owns the
 * {@code inet hohenheim} table with per-protocol timeout sets and ONE drop
 * rule per family scoped to the configured TCP ports (never every port --
 * the unscoped-fail2ban-jail incident is the reason this scoping exists).
 * Auto-bans get per-element timeouts so the KERNEL expires them; the bans
 * table stays the source of truth via {@link #resync}.
 *
 * All nft failures are logged loudly but NEVER thrown: dev machines without
 * sudo/nft must run fine, which is also why everything is gated on the
 * {@code security.bans.nftables_enabled}-style setting
 * ({@code security.nftables_enabled}, default false).
 *
 * AIDEV-NOTE: commands run as ROOT via {@code sudo -n -- nft ...},
 * deliberately NOT through SystemUsers.executionBuilder (which exists to DROP
 * privilege and refuses uid 0). nft only works as root; the sudoers rule on
 * the VPS already allows it. On nft 0.9.x (Debian 11) {@code add table/chain/set}
 * are idempotent for identical definitions, but {@code add rule} APPENDS, so
 * setup flushes the owned chain before re-adding the rules.
 */
public class NftService {

    /** One nft invocation: argv AFTER the sudo/nft prefix. */
    public interface CommandRunner {
        Result run(@NonNull List<String> nftArgs);
    }

    public record Result(int exitCode, @NonNull String stderr) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    static final String TABLE = "hohenheim";
    static final String CHAIN = "banned";
    static final String SET_V4 = "banned_v4";
    static final String SET_V6 = "banned_v6";
    private static final long COMMAND_TIMEOUT_SECONDS = 10;

    private final CommandRunner runner;
    private final BooleanSupplier enabled;

    public NftService() {
        this(new SudoNftRunner(),
            () -> Boolean.TRUE.equals(
                HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.NFTABLES_ENABLED)));
    }

    /** Test constructor: inject the executor and the enable gate. */
    public NftService(@NonNull CommandRunner runner, @NonNull BooleanSupplier enabled) {
        this.runner = runner;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled.getAsBoolean();
    }

    /**
     * Idempotent boot setup: create the owned table, chain and timeout sets,
     * then flush the chain and re-add the two port-scoped drop rules.
     */
    public synchronized void setup(@NonNull List<Integer> ports) {
        if (!isEnabled()) {
            return;
        }
        String portSet = portSetLiteral(ports);
        run(List.of("add", "table", "inet", TABLE));
        run(List.of("add", "chain", "inet", TABLE, CHAIN,
            "{ type filter hook input priority -10 ; policy accept ; }"));
        run(List.of("add", "set", "inet", TABLE, SET_V4,
            "{ type ipv4_addr ; flags timeout ; }"));
        // interval: v6 bans are /64 prefixes, and prefix elements need it.
        run(List.of("add", "set", "inet", TABLE, SET_V6,
            "{ type ipv6_addr ; flags interval, timeout ; }"));
        run(List.of("flush", "chain", "inet", TABLE, CHAIN));
        run(List.of("add", "rule", "inet", TABLE, CHAIN,
            "tcp", "dport", portSet, "ip", "saddr", "@" + SET_V4, "drop"));
        run(List.of("add", "rule", "inet", TABLE, CHAIN,
            "tcp", "dport", portSet, "ip6", "saddr", "@" + SET_V6, "drop"));
    }

    /** Add a banned IP element; a null ttl means permanent (no kernel timeout).
     * @return true when disabled or the kernel accepted the element
     */
    public synchronized boolean addBan(@NonNull String ip, @Nullable Long ttlSeconds) {
        if (!isEnabled()) {
            return true;
        }
        return run(List.of("add", "element", "inet", TABLE, setFor(ip), elementLiteral(ip, ttlSeconds)));
    }

    /** Remove a banned IP element (a missing element only logs). */
    public synchronized void removeBan(@NonNull String ip) {
        if (!isEnabled()) {
            return;
        }
        run(List.of("delete", "element", "inet", TABLE, setFor(ip), "{ " + ip + " }"));
    }

    /** One active ban for {@link #resync}: ip plus remaining ttl (null = permanent). */
    public record ActiveBan(@NonNull String ip, @Nullable Long ttlSeconds) {}

    /**
     * Full resync (used at boot): flush both sets and re-add every active DB
     * ban, so the database stays the source of truth over kernel state.
     */
    public synchronized void resync(@NonNull List<ActiveBan> active) {
        if (!isEnabled()) {
            return;
        }
        run(List.of("flush", "set", "inet", TABLE, SET_V4));
        run(List.of("flush", "set", "inet", TABLE, SET_V6));
        for (ActiveBan ban : active) {
            run(List.of("add", "element", "inet", TABLE, setFor(ban.ip()),
                elementLiteral(ban.ip(), ban.ttlSeconds())));
        }
    }

    static @NonNull String setFor(@NonNull String ip) {
        return ip.indexOf(':') >= 0 ? SET_V6 : SET_V4;
    }

    static @NonNull String elementLiteral(@NonNull String ip, @Nullable Long ttlSeconds) {
        if (ttlSeconds == null) {
            return "{ " + ip + " }";
        }
        return "{ " + ip + " timeout " + Math.max(1, ttlSeconds) + "s }";
    }

    static @NonNull String portSetLiteral(@NonNull List<Integer> ports) {
        List<Integer> effective = ports.isEmpty() ? List.of(80, 443) : ports;
        StringBuilder literal = new StringBuilder("{ ");
        for (int i = 0; i < effective.size(); i++) {
            if (i > 0) {
                literal.append(", ");
            }
            literal.append(effective.get(i));
        }
        return literal.append(" }").toString();
    }

    /** Parse the security.nftables_ports setting; blank or garbage falls back to 80,443. */
    public static @NonNull List<Integer> configuredPorts() {
        String raw = HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.NFTABLES_PORTS);
        List<Integer> ports = new ArrayList<>();
        if (raw != null) {
            for (String part : raw.split("[,\\s]+")) {
                if (part.isBlank()) {
                    continue;
                }
                try {
                    int port = Integer.parseInt(part.trim());
                    if (port > 0 && port <= 65535) {
                        ports.add(port);
                    }
                } catch (NumberFormatException ignored) {
                    // Garbage entries are skipped; the fallback below covers an all-garbage value.
                }
            }
        }
        return ports.isEmpty() ? List.of(80, 443) : List.copyOf(ports);
    }

    private boolean run(@NonNull List<String> nftArgs) {
        try {
            Result result = runner.run(nftArgs);
            if (!result.ok()) {
                Blast.log("NFT: command failed (exit", result.exitCode() + "):",
                    String.join(" ", nftArgs), "-", result.stderr().trim());
            }
            return result.ok();
        } catch (Exception e) {
            Blast.log("NFT: command error:", String.join(" ", nftArgs), "-", e.getMessage());
            return false;
        }
    }

    /**
     * Executes {@code sudo -n -- nft <args>} as root. This is the ONE root-command
     * seam in the app; site processes keep going through SystemUsers (which
     * refuses root by design and must stay that way).
     */
    static final class SudoNftRunner implements CommandRunner {
        @Override
        public Result run(@NonNull List<String> nftArgs) {
            List<String> argv = new ArrayList<>(List.of("/usr/bin/sudo", "-n", "--", "nft"));
            argv.addAll(nftArgs);
            try {
                Process process = new ProcessBuilder(argv)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
                if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return new Result(-1, "timed out after " + COMMAND_TIMEOUT_SECONDS + "s");
                }
                String stderr = new String(process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
                return new Result(process.exitValue(), stderr);
            } catch (IOException e) {
                return new Result(-1, String.valueOf(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(-1, "interrupted");
            }
        }
    }
}
