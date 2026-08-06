package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Command CONSTRUCTION tests: the argv shapes handed to the executor seam for
 * setup/ban/unban/resync, timeout formatting, port scoping and the disabled
 * gate. No real nft/sudo anywhere.
 */
class NftServiceTest {

    /** Names and labels are controller-namespaced, so this needs a real identity. */
    @BeforeAll
    static void controllerIdentity() {
        HohenheimTestRuntime.ensureDatasource();
    }


    /** Records every nft argv and answers success. */
    private static final class RecordingRunner implements NftRunner {
        final List<String> commands = new ArrayList<>();

        @Override
        public NftRunner.Result run(List<String> nftArgs, String stdin) {
            commands.add(String.join(" ", nftArgs));
            return new NftRunner.Result(0, "", "");
        }
    }

    private RecordingRunner runner;

    private NftService enabledService() {
        runner = new RecordingRunner();
        return new NftService(runner, () -> true);
    }

    @Test
    void setupCreatesTableChainSetsAndPortScopedRules() {
        NftService nft = enabledService();
        nft.setup(List.of(80, 443));

        assertThat(runner.commands).containsExactly(
            "add table inet " + NftService.table(),
            "add chain inet " + NftService.table() + " banned { type filter hook input priority -10 ; policy accept ; }",
            "add set inet " + NftService.table() + " banned_v4 { type ipv4_addr ; flags timeout ; }",
            "add set inet " + NftService.table() + " banned_v6 { type ipv6_addr ; flags interval, timeout ; }",
            "flush chain inet " + NftService.table() + " banned",
            "add rule inet " + NftService.table() + " banned tcp dport { 80, 443 } ip saddr @banned_v4 drop",
            "add rule inet " + NftService.table() + " banned tcp dport { 80, 443 } ip6 saddr @banned_v6 drop");
    }

    @Test
    void banWithTtlUsesAKernelTimeoutElement() {
        NftService nft = enabledService();
        nft.addBan("203.0.113.9", 86400L);
        assertThat(runner.commands).containsExactly(
            "add element inet " + NftService.table() + " banned_v4 { 203.0.113.9 timeout 86400s }");
    }

    @Test
    void permanentBanHasNoTimeout() {
        NftService nft = enabledService();
        nft.addBan("203.0.113.9", null);
        assertThat(runner.commands).containsExactly(
            "add element inet " + NftService.table() + " banned_v4 { 203.0.113.9 }");
    }

    @Test
    void ipv6BansTargetTheV6Set() {
        // BanService hands /64 CIDR keys for v6; the set holds prefix elements.
        NftService nft = enabledService();
        nft.addBan("2001:db8::/64", 60L);
        nft.removeBan("2001:db8::/64");
        assertThat(runner.commands).containsExactly(
            "add element inet " + NftService.table() + " banned_v6 { 2001:db8::/64 timeout 60s }",
            "delete element inet " + NftService.table() + " banned_v6 { 2001:db8::/64 }");
    }

    @Test
    void unbanDeletesTheElement() {
        NftService nft = enabledService();
        nft.removeBan("203.0.113.9");
        assertThat(runner.commands).containsExactly(
            "delete element inet " + NftService.table() + " banned_v4 { 203.0.113.9 }");
    }

    @Test
    void resyncFlushesBothSetsAndReaddsActiveBans() {
        NftService nft = enabledService();
        nft.resync(List.of(
            new NftService.ActiveBan("203.0.113.1", 3600L),
            new NftService.ActiveBan("203.0.113.2", null),
            new NftService.ActiveBan("2001:db8::/64", 60L)));

        assertThat(runner.commands).containsExactly(
            "flush set inet " + NftService.table() + " banned_v4",
            "flush set inet " + NftService.table() + " banned_v6",
            "add element inet " + NftService.table() + " banned_v4 { 203.0.113.1 timeout 3600s }",
            "add element inet " + NftService.table() + " banned_v4 { 203.0.113.2 }",
            "add element inet " + NftService.table() + " banned_v6 { 2001:db8::/64 timeout 60s }");
    }

    @Test
    void disabledServiceRunsNothing() {
        RecordingRunner recorder = new RecordingRunner();
        NftService nft = new NftService(recorder, () -> false);
        nft.setup(List.of(80, 443));
        nft.addBan("203.0.113.9", 60L);
        nft.removeBan("203.0.113.9");
        nft.resync(List.of(new NftService.ActiveBan("203.0.113.9", null)));
        assertThat(recorder.commands).isEmpty();
    }

    @Test
    void failingCommandsNeverThrow() {
        NftService nft = new NftService((args, stdin) -> new NftRunner.Result(1, "", "boom"), () -> true);
        assertThat(nft.addBan("203.0.113.9", 60L)).isFalse();
    }

    @Test
    void portSetLiteralAndEmptyPortsFallBack() {
        assertThat(NftService.portSetLiteral(List.of(80))).isEqualTo("{ 80 }");
        assertThat(NftService.portSetLiteral(List.of(80, 443, 8443))).isEqualTo("{ 80, 443, 8443 }");
        assertThat(NftService.portSetLiteral(List.of())).isEqualTo("{ 80, 443 }");
    }

    @Test
    void zeroTtlIsClampedToOneSecond() {
        assertThat(NftService.elementLiteral("203.0.113.9", 0L))
            .isEqualTo("{ 203.0.113.9 timeout 1s }");
    }
}
