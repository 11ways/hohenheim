package be.elevenways.hohenheim.server.security;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * THE kernel-truth half of every isolation policy: read one base chain back out of
 * nftables, decide whether it carries what we applied, and delete it when the workload
 * goes away.
 *
 * AIDEV-NOTE: extracted 2026-08-06 so the network-keyed applier
 * ({@link WorkloadNetworkPolicy}) and the uid-keyed one ({@link ProcessNetworkPolicy})
 * cannot drift on the three distinctions that make this verification worth anything:
 * ABSENT is not UNREADABLE ("No such file or directory" is an observation, every other
 * nft failure is a refusal to answer and throws), EXISTS is not HOOKED (a chain without
 * its hook filters nothing), and "nft exited 0" is not "the rule is in the kernel". Both
 * owners keep their own refusal WORDING -- the subject differs ("deploy 'net'" vs
 * "start site 'x'") and operators grep for it -- so the prefix travels in, the semantics
 * do not.
 *
 * AIDEV-NOTE: listing takes NO numeric flag, and that is a decision, not an omission.
 * Any {@code -n} level makes nft render symbolic values numerically -- measured: {@code ct
 * state established,related} comes back as {@code ct state 0x2,0x4} -- so a flag added to
 * force numeric uids would break the comparison for every chain that has a conntrack rule,
 * which is all of them. What the flag would have protected against is documented where it
 * matters, on {@link ProcessNetworkPolicy}.
 */
final class NftChains {

    private final @NonNull NftRunner runner;
    private final @NonNull Supplier<String> table;

    /**
     * AIDEV-NOTE: the table is a SUPPLIER, never a captured string. Table names carry the
     * controller identity token, which resolves through the CURRENT datasource -- so
     * capturing it at construction let a policy object built under one datasource read
     * back from a different controller's table than the one it had just written to, and
     * report "the chain does not exist in the kernel" for a chain that did. It also made
     * the appliers' static PRODUCTION instances resolve an identity at class-load, before
     * any database existed. Resolve at every use, never once.
     */
    NftChains(@NonNull NftRunner runner, @NonNull Supplier<String> table) {
        this.runner = runner;
        this.table = table;
    }

    /**
     * The kernel's own rendering of one chain as normalized lines, or null when the chain
     * (or the whole table) does not exist.
     *
     * @throws IOException when nft fails for any reason other than absence
     */
    @Nullable List<String> read(@NonNull String chain) throws IOException {
        NftRunner.Result listed = this.runner.run(
            List.of("list", "chain", "inet", this.table.get(), chain), null);
        if (!listed.ok()) {
            if (listed.failureText().contains("No such file or directory")) {
                return null;   // observed absent (the whole point of a kernel-truth check)
            }
            throw new IOException("Could not read back the network policy chain '" + chain
                + "' (exit " + listed.exitCode() + "): " + listed.failureText());
        }
        List<String> present = new ArrayList<>();
        for (String line : listed.stdout().split("\n")) {
            present.add(line.trim().replaceAll("\\s+", " "));
        }
        return present;
    }

    boolean exists(@NonNull String chain) throws IOException {
        return read(chain) != null;
    }

    /** Whether the chain exists, hooks the given hook, and carries every expected rule. */
    boolean satisfies(@NonNull String chain, @NonNull String hook,
                      @NonNull List<String> expected) throws IOException {
        List<String> present = read(chain);
        return present != null && hooked(present, hook) && present.containsAll(expected);
    }

    /**
     * Re-list one chain and require every rule we applied to be present in the kernel's own
     * rendering of it.
     *
     * @param refusal the caller's refusal prefix, e.g. {@code REFUSED to deploy 'x'}
     * @throws IOException when the chain is absent, unhooked, unreadable, or incomplete
     */
    void verify(@NonNull String refusal, @NonNull String chain, @NonNull String hook,
                @NonNull List<String> expected) throws IOException {
        List<String> present;
        try {
            present = read(chain);
        } catch (IOException unreadable) {
            throw new IOException(refusal + ": nft accepted the policy but the " + hook
                + " chain '" + chain + "' cannot be read back: " + unreadable.getMessage());
        }
        if (present == null) {
            throw new IOException(refusal + ": nft accepted the policy but the " + hook
                + " chain '" + chain + "' does not exist in the kernel. Something on this"
                + " host is flushing our table.");
        }
        if (!hooked(present, hook)) {
            throw new IOException(refusal + ": chain '" + chain + "' exists but is not hooked"
                + " into " + hook + "; it would filter nothing. Kernel state: "
                + String.join(" | ", present));
        }
        for (String rule : expected) {
            if (!present.contains(rule)) {
                throw new IOException(refusal + ": nft reported success but the kernel does not"
                    + " carry the rule '" + rule + "' in chain '" + chain + "'. Something on"
                    + " this host is flushing our table. Kernel state: "
                    + String.join(" | ", present));
            }
        }
    }

    /**
     * Flush and delete one chain, VERIFIED absent afterwards; a chain that was never
     * applied is an observed no-op.
     *
     * @throws IOException when nft refuses, or the chain survives the delete
     */
    void remove(@NonNull String chain) throws IOException {
        if (!exists(chain)) {
            return;
        }
        NftRunner.Result removed = this.runner.run(List.of("-f", "-"),
            "flush chain inet " + this.table.get() + ' ' + chain + '\n'
                + "delete chain inet " + this.table.get() + ' ' + chain + '\n');
        if (!removed.ok()) {
            throw new IOException("Could not remove the network policy chain '" + chain
                + "' (exit " + removed.exitCode() + "): " + removed.failureText());
        }
        if (exists(chain)) {
            throw new IOException("The network policy chain '" + chain + "' still exists"
                + " after nft reported a successful delete; the kernel state of this host"
                + " is not what nft says it is.");
        }
    }

    private static boolean hooked(@NonNull List<String> present, @NonNull String hook) {
        return present.stream().anyMatch(line -> line.startsWith("type filter hook " + hook + " ")
            && line.contains("policy accept"));
    }
}
