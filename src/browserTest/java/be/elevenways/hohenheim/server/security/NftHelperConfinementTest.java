package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.server.host.PrivilegedHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nft lane reaches root only through the privileged helper's nft verbs, and the helper the
 * installer writes refuses every command outside Hohenheim's own inet tables.
 *
 * AIDEV-NOTE: the helper script is run for real (bash, its own body cut out of install-host.sh)
 * but never as root: a command it accepts goes on to nft, which then fails for want of
 * privilege. The proof is therefore "refused" (exit 77, before nft) versus "handed to nft".
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class NftHelperConfinementTest {

    private static final int REFUSED = 77;

    @TempDir
    Path dir;

    @Test
    void everyNftInvocationBecomesAHelperVerb() {

        // 1. A ruleset for nft -f - is the apply verb's stdin, unchanged.
        NftRunner.HelperCall apply = NftRunner.HelperCall.of(List.of("-f", "-"), "add table inet t\n");
        assertThat(apply.verb()).as("step 1: -f - is nft-apply").isEqualTo(PrivilegedHelper.Verb.NFT_APPLY);
        assertThat(apply.args()).as("step 1: no file path ever travels").isEmpty();
        assertThat(apply.stdin()).as("step 1: the ruleset is stdin").isEqualTo("add table inet t\n");

        // 2. An argv command is one stdin line, joined as nft joins its own argv.
        NftRunner.HelperCall element = NftRunner.HelperCall.of(
            List.of("add", "element", "inet", "hohenheim_x", "banned_v4", "{ 1.2.3.4 timeout 5s }"), null);
        assertThat(element.verb()).as("step 2: an argv command is nft-apply").isEqualTo(PrivilegedHelper.Verb.NFT_APPLY);
        assertThat(element.stdin()).as("step 2: as one line")
            .isEqualTo("add element inet hohenheim_x banned_v4 { 1.2.3.4 timeout 5s }\n");

        // 3. A listing is the list verb with the object as its arguments.
        NftRunner.HelperCall list = NftRunner.HelperCall.of(List.of("list", "chain", "inet", "hohenheim_x", "c"), null);
        assertThat(list.verb()).as("step 3: list is nft-list").isEqualTo(PrivilegedHelper.Verb.NFT_LIST);
        assertThat(list.args()).as("step 3: the object").containsExactly("chain", "inet", "hohenheim_x", "c");
        assertThat(list.stdin()).as("step 3: no stdin").isNull();
    }

    @Test
    void theHelperAppliesOnlyHohenheimsOwnTables() throws IOException {
        Path helper = helperScript();
        String own = WorkloadNetworkPolicy.TABLE_BASE + "_abc123";
        String bans = NftService.TABLE_BASE + "_abc123";

        // 1. The workload policy's own ruleset shape and a ban element are handed to nft.
        String policy = "add table inet " + own + "\n"
            + "add chain inet " + own + " fwd_x { type filter hook forward priority -10 ; policy accept ; }\n"
            + "flush chain inet " + own + " fwd_x\n"
            + "add rule inet " + own + " fwd_x ct state established,related accept\n"
            + "add rule inet " + own + " fwd_x ip6 saddr fd00::/64 ip6 daddr 2000::/3 tcp dport 443 accept\n"
            + "add set inet " + bans + " banned_v6 { type ipv6_addr ; flags interval, timeout ; }\n"
            + "add element inet " + bans + " banned_v4 { 1.2.3.4 timeout 60s }\n"
            + "delete chain inet " + own + " fwd_x\n"
            + "delete table inet " + own + "\n";
        assertHanded(run(helper, policy, "nft-apply"), "step 1: Hohenheim's own ruleset");

        // 2. Anything outside the namespace, or smuggled past it, is refused before nft runs.
        assertRefused(run(helper, "add table inet filter\n", "nft-apply"), "step 2: a foreign table");
        assertRefused(run(helper, "add table ip " + own + "\n", "nft-apply"), "step 2: another family");
        assertRefused(run(helper, "add rule inet " + own + " c drop ; delete table inet filter\n", "nft-apply"),
            "step 2: a second command after ';'");
        assertRefused(run(helper, "add chain inet " + own + " c { type filter hook input priority 0 ; } ; flush ruleset\n",
            "nft-apply"), "step 2: a second command after a closed brace");
        assertRefused(run(helper, "include \"/etc/shadow\"\n", "nft-apply"), "step 2: an include");
        assertRefused(run(helper, "flush ruleset\n", "nft-apply"), "step 2: the whole ruleset");
        assertRefused(run(helper, "add table inet " + own + "\nadd table inet filter\n", "nft-apply"),
            "step 2: one foreign line refuses the whole ruleset");
        assertRefused(run(helper, "", "nft-apply", "/etc/shadow"), "step 2: a file argument");

        // 3. Listing: our tables and chains, and Incus's bridge table, nothing else.
        assertHanded(run(helper, null, "nft-list", "table", "inet", own), "step 3: our table");
        assertHanded(run(helper, null, "nft-list", "chain", "inet", own, "inp_x"), "step 3: our chain");
        assertHanded(run(helper, null, "nft-list", "table", "bridge", "incus"), "step 3: Incus's bridge table");
        assertRefused(run(helper, null, "nft-list", "table", "inet", "filter"), "step 3: a foreign table");
        assertRefused(run(helper, null, "nft-list", "table", "bridge", "other"), "step 3: another bridge table");
        assertRefused(run(helper, null, "nft-list", "chain", "inet", own, "-o"), "step 3: an option as chain");
    }

    private static void assertRefused(NftRunner.Result result, String step) {
        assertThat(result.exitCode()).as(step + " is refused (" + result.stderr().trim() + ")").isEqualTo(REFUSED);
        assertThat(result.stderr()).as(step + " says so").contains("hohenheim-helper: refused:");
    }

    private static void assertHanded(NftRunner.Result result, String step) {
        assertThat(result.stderr()).as(step + " reaches nft").doesNotContain("hohenheim-helper: refused");
        assertThat(result.exitCode()).as(step + " is not refused").isNotIn(REFUSED, 64);
    }

    private static NftRunner.Result run(Path helper, String stdin, String... args) {
        List<String> argv = new ArrayList<>(List.of("bash", helper.toString()));
        argv.addAll(List.of(args));
        return NftRunner.Result.of(argv, stdin, 20);
    }

    /** The helper body exactly as install-host.sh writes it, placeholders left in. */
    private Path helperScript() throws IOException {
        String script = Files.readString(Path.of("tools/install-host.sh"));
        int start = script.indexOf("cat <<'HELPER'\n");
        assertThat(start).as("the installer embeds the helper").isGreaterThanOrEqualTo(0);
        int bodyStart = start + "cat <<'HELPER'\n".length();
        int end = script.indexOf("\nHELPER\n", bodyStart);
        Path helper = this.dir.resolve("hohenheim-helper");
        Files.writeString(helper, script.substring(bodyStart, end + 1));
        return helper;
    }
}
