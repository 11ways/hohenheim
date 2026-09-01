package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.security.BanScope;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.SecurityEventTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SSH ban tier end to end without ever spawning journalctl: the line grammar, the
 * address extraction, the watcher's scoring and health reporting, and the journey from an
 * sshd line to a scope-ssh ban row programmed into the port-22 nftables set.
 */
class SshAuthWatchTest {

    private static boolean initialized = false;

    @BeforeAll
    static void initDb() throws Exception {
        if (initialized) return;
        initialized = true;
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @BeforeEach
    void enableBans() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.BANS_ENABLED, true);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN, List.of());
    }

    @AfterEach
    void resetSettings() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.BANS_ENABLED, true);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN, List.of());
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.SSH_WATCH_ENABLED, false);
    }

    // -----------------------------------------------------------------------
    // The line grammar
    // -----------------------------------------------------------------------

    /**
     * Every sshd family this build claims to recognize, with the real message shapes, plus
     * the ordering that decides which family a line belongs to.
     */
    @Test
    void everyRecognisedSshdFamilyBecomesItsOwnEvent() {
        // 1. A username sweep: sshd emits its own "Invalid user" line.
        assertSignal("Invalid user admin from 203.0.113.5 port 40222",
            SecurityEventTypes.SSH_INVALID_USER, "203.0.113.5");

        // 2. A refused password, for a real account and for an invented one. The second
        //    stays a PASSWORD failure: sshd already emitted line 1 for the same attempt,
        //    so counting it as "invalid user" again would double one signal.
        assertSignal("Failed password for root from 203.0.113.5 port 40222 ssh2",
            SecurityEventTypes.SSH_PASSWORD_FAILED, "203.0.113.5");
        assertSignal("Failed password for invalid user admin from 203.0.113.6 port 40222 ssh2",
            SecurityEventTypes.SSH_PASSWORD_FAILED, "203.0.113.6");

        // 3. A refused key: the cheap signal, because agents offer stale keys all day.
        assertSignal("Failed publickey for root from 203.0.113.7 port 40222 ssh2:"
                + " RSA SHA256:abcdef",
            SecurityEventTypes.SSH_PUBLICKEY_FAILED, "203.0.113.7");

        // 4. The two preauth drops. The invalid-user one is classified as the sweep it is,
        //    which is why the ladder checks it before the authenticating-user family.
        assertSignal("Connection closed by authenticating user root 203.0.113.8 port 40222"
                + " [preauth]",
            SecurityEventTypes.SSH_PREAUTH_ABORT, "203.0.113.8");
        assertSignal("Connection closed by invalid user oracle 203.0.113.9 port 40222"
                + " [preauth]",
            SecurityEventTypes.SSH_INVALID_USER, "203.0.113.9");

        // 5. sshd counted the failures itself, and a handshake that is not SSH at all.
        assertSignal("error: maximum authentication attempts exceeded for root from"
                + " 203.0.113.10 port 40222 ssh2 [preauth]",
            SecurityEventTypes.SSH_MAX_ATTEMPTS, "203.0.113.10");
        assertSignal("banner exchange: Connection from 203.0.113.11 port 40222: invalid format",
            SecurityEventTypes.SSH_PROTOCOL_ABUSE, "203.0.113.11");

        // 6. Everything else is silence, including a successful login and a line whose
        //    address is not a literal (a reverse-resolved hostname is never bannable).
        assertThat(SshAuthLine.parse("Accepted publickey for jelle from 203.0.113.12 port 1 ssh2"))
            .as("step 6: a successful login is not a failure")
            .isNull();
        assertThat(SshAuthLine.parse("Failed password for root from scanner.example port 22 ssh2"))
            .as("step 6: a non-literal source cannot be scored")
            .isNull();
        assertThat(SshAuthLine.parse("")).as("step 6: blank input").isNull();
        assertThat(SshAuthLine.parse(null)).as("step 6: null input").isNull();
    }

    /** IPv6 sources, in both the "from" and the bare "IP port" spellings. */
    @Test
    void ipv6SourcesAreExtractedInEverySpelling() {
        assertSignal("Failed password for root from 2001:db8::1 port 40222 ssh2",
            SecurityEventTypes.SSH_PASSWORD_FAILED, "2001:db8::1");
        assertSignal("Connection closed by invalid user admin 2001:db8:aa:bb::17 port 22 [preauth]",
            SecurityEventTypes.SSH_INVALID_USER, "2001:db8:aa:bb::17");

        // The port number is never mistaken for an address, and the "from" anchor wins over
        // any earlier literal on the line.
        assertThat(SshAuthLine.extractIp("Failed password for 10.0.0.1 from 203.0.113.20 port 40222"))
            .isEqualTo("203.0.113.20");
    }

    // -----------------------------------------------------------------------
    // The watcher
    // -----------------------------------------------------------------------

    /** Recognized lines score, unrecognized ones do not, and health follows. */
    @Test
    void theWatcherScoresRecognisedLinesAndReportsItsOwnHealth() {
        List<String> scored = new ArrayList<>();
        SshAuthWatcher watcher = new SshAuthWatcher(
            () -> { throw new java.io.IOException("no journal in a test"); },
            (type, ip) -> scored.add(type + " " + ip));

        // 1. Only the recognized lines reach the scorer.
        watcher.consume("Invalid user admin from 203.0.113.30 port 40222");
        watcher.consume("Server listening on 0.0.0.0 port 22.");
        watcher.consume("Failed password for root from 203.0.113.30 port 40222 ssh2");
        assertThat(scored)
            .as("step 1: two failures scored, the listening line ignored")
            .containsExactly(
                SecurityEventTypes.SSH_INVALID_USER + " 203.0.113.30",
                SecurityEventTypes.SSH_PASSWORD_FAILED + " 203.0.113.30");
        assertThat(watcher.signalCount()).as("step 1: the counter agrees").isEqualTo(2);

        // 2. An install that never asked for SSH watching raises nothing on the dashboard.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.SSH_WATCH_ENABLED, false);
        assertThat(AttentionCollector.sshWatchIssue(watcher.snapshot()))
            .as("step 2: not configured is a choice, never a warning")
            .isNull();

        // 3. Asked for but not running IS a warning: the silent-success shape this guards.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.SSH_WATCH_ENABLED, true);
        assertThat(AttentionCollector.sshWatchIssue(watcher.snapshot()))
            .as("step 3: enabled but dead must be visible")
            .isNotNull();
    }

    /** Starting with an unusable journal degrades: it reports, and it never crash-loops. */
    @Test
    void anUnreadableJournalDegradesInsteadOfCrashing() throws Exception {
        AtomicLong attempts = new AtomicLong();
        SshAuthWatcher watcher = new SshAuthWatcher(
            () -> {
                attempts.incrementAndGet();
                throw new java.io.IOException("Permission denied");
            },
            (type, ip) -> { });

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.SSH_WATCH_ENABLED, true);
        watcher.start();
        // The first attempt is immediate; the backoff makes the second one a second later.
        Thread.sleep(300);
        watcher.stop();

        assertThat(attempts.get())
            .as("step 1: it tried, and the 1s backoff kept it from spinning")
            .isBetween(1L, 2L);
        assertThat(watcher.snapshot().lastError())
            .as("step 2: the reason is retained for the dashboard")
            .contains("Permission denied");
        assertThat(AttentionCollector.sshWatchIssue(watcher.snapshot()))
            .as("step 3: and it is visible where the firewall role reports health")
            .isNotNull();
    }

    // -----------------------------------------------------------------------
    // The journey
    // -----------------------------------------------------------------------

    /**
     * An sshd line ends as a scope-ssh ban row in the port-22 set, and NOT in the web set
     * or the proxy's ban cache.
     */
    @Test
    void sshBruteForceEndsAsAnSshScopedBan() {
        List<String> nftCommands = new ArrayList<>();
        NftService nft = new NftService((args, stdin) -> {
            nftCommands.add(String.join(" ", args));
            return new NftRunner.Result(0, "", "");
        }, () -> true);
        BanService service = new BanService(nft);

        // 1. The scorer decides the scope from the event type that crossed the threshold.
        assertThat(BanScope.forEventType(SecurityEventTypes.SSH_INVALID_USER))
            .as("step 1: the sshd family is ssh-scoped").isEqualTo(BanScope.SSH);
        assertThat(BanScope.forEventType(SecurityEventTypes.DOMAIN_MISS))
            .as("step 1: everything else stays web").isEqualTo(BanScope.WEB);
        assertThat(BanScope.forEventType(null))
            .as("step 1: an unattributed ban is a web ban").isEqualTo(BanScope.WEB);

        // 2. The auto-ban funnel therefore stores the scope on the row -- which also proves
        //    the appended migration ran, because the column would not otherwise exist.
        service.autoBan("198.51.100.210", SecurityEventTypes.SSH_INVALID_USER,
            "score 30 over threshold");
        Row ban = activeBan(service, "198.51.100.210");
        assertThat(ban).as("step 2: the ban exists").isNotNull();
        assertThat(ban.get(BanModel.SCOPE))
            .as("step 2: and it carries the ssh scope")
            .isEqualTo(BanScope.SSH.token());

        // 3. It is programmed into the SSH set only.
        assertThat(nftCommands)
            .as("step 3: the ssh set is the one that got the element")
            .anyMatch(command -> command.contains("banned_ssh_v4")
                && command.contains("198.51.100.210"))
            .noneMatch(command -> command.contains(" banned_v4 ")
                && command.contains("198.51.100.210"));

        // 4. And the proxy's app-level refusal is untouched: an SSH brute-forcer was never
        //    declared unwelcome on a customer's website.
        assertThat(service.isBanned("198.51.100.210"))
            .as("step 4: an ssh ban does not refuse HTTP")
            .isFalse();

        // 5. A web-scoped ban still does everything it always did.
        nftCommands.clear();
        service.createBan("198.51.100.211", "web", BanModel.SOURCE_MANUAL, null,
            Duration.ofHours(1));
        assertThat(service.isBanned("198.51.100.211"))
            .as("step 5: a web ban still refuses HTTP").isTrue();
        assertThat(nftCommands)
            .as("step 5: and lands in the 80/443 set")
            .anyMatch(command -> command.contains(" banned_v4 ")
                && command.contains("198.51.100.211"));

        // 6. Lifting the ssh ban deletes from the ssh set, not the web one.
        nftCommands.clear();
        service.lift(ban, "test");
        assertThat(nftCommands)
            .as("step 6: the lift addresses the set the ban was filed in")
            .anyMatch(command -> command.startsWith("delete element")
                && command.contains("banned_ssh_v4"));
    }

    /** A stored scope token this build does not know is enforced NOWHERE, and says so. */
    @Test
    void anUnknownStoredScopeFailsClosed() {
        assertThat(BanScope.fromToken("web")).isEqualTo(BanScope.WEB);
        assertThat(BanScope.fromToken("ssh")).isEqualTo(BanScope.SSH);
        assertThat(BanScope.fromToken(null))
            .as("a row stored before the scope column existed is a web ban")
            .isEqualTo(BanScope.WEB);
        assertThat(BanScope.fromToken(""))
            .as("and so is a blank one").isEqualTo(BanScope.WEB);
        assertThat(BanScope.fromToken("quic"))
            .as("but an unknown token has no safe superset to guess")
            .isNull();
    }

    private static void assertSignal(String line, String expectedType, String expectedIp) {
        SshAuthLine.Signal signal = SshAuthLine.parse(line);
        assertThat(signal).as("recognised: " + line).isNotNull();
        assertThat(signal.eventType()).as("type of: " + line).isEqualTo(expectedType);
        assertThat(signal.ip()).as("address of: " + line).isEqualTo(expectedIp);
    }

    private static Row activeBan(BanService service, String ip) {
        for (Row row : service.listActive()) {
            if (ip.equals(row.get(BanModel.IP))) {
                return row;
            }
        }
        return null;
    }
}
