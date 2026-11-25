package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.ProxyAttention;
import be.elevenways.hohenheim.site.SiteAccessLogOperations;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.protoblast.diagnostic.AtomicFiles;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import be.elevenways.protoblast.common.time.Now;
import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.common.time.OfficeZone;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import com.sun.net.httpserver.HttpServer;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The access log writes the Node original's line (host prefix, Referer, user agent) byte for byte, then the time taken
 * and the answering site; escapes every control character a field could carry, logs the path the client sent (not
 * the stripped one), follows a file someone else rotated, rotates by office day itself, at a day's change and at a
 * restart, compresses old days and deletes those past the keeping period, splits an old file of several days into its
 * days once, and sees another program rotating the same file; its one reader reads one site's lines over
 * the live file, a rotated and a compressed day, filtered and paged, within its bound. The fail2ban domain-miss log
 * writes the original's DOMAIN_MISS line again.
 */
class AccessLogTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static ProxyServer proxy;
    private static HttpServer upstream;

    @BeforeAll
    static void boot() throws Exception {
        HohenheimEndpoints.init();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            byte[] body = "logged".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
    }

    @AfterAll
    static void stop() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Logging.ACCESS_TO_FILE, false);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Logging.DOMAIN_MISSES_TO_FILE, false);
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        if (upstream != null) {
            upstream.stop(0);
            upstream = null;
        }
    }

    @Test
    void everyStatusChoiceIsWordedInBothLanguages() {
        ShippedCatalogs catalogs = new ShippedCatalogs();
        for (EnumField.EnumValue each : SiteAccessLogOperations.STATUS.getValues().values()) {
            for (String tag : List.of("en", "nl")) {
                String words = each.getLabel().resolve(LocaleChain.ofTags(tag), catalogs);
                assertThat(words).as(each.getKey() + " in " + tag).doesNotContain("status_").isNotBlank();
            }
        }
    }

    @Test
    void everyControlCharacterIsEscaped() {
        // Step 1: a newline, a quote and a backslash can neither end a field nor start a line.
        assertThat(LogFile.escape("a\nb\"c\\d\re\u0000f\u007f"))
            .as("step 1: control characters become \\xNN, quote and backslash are escaped")
            .isEqualTo("a\\x0ab\\\"c\\\\d\\x0de\\x00f\\x7f");

        // Step 2: a clean value is returned as-is.
        assertThat(LogFile.escape("/plain/path")).as("step 2: nothing to escape").isEqualTo("/plain/path");
    }

    @Test
    @Timeout(60)
    void theLogFollowsARotatedFileAndRecordsTheClientPath() throws Exception {
        Path directory = Files.createTempDirectory("hh-access-log");
        Path logFile = directory.resolve("access.log");
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Logging.ACCESS_PATH, logFile.toString());
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Logging.ACCESS_TO_FILE, true);

        Row site = ProxyTestSupport.setupSite("hohenheim:address", "Access Log Site", "access-log-site",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstream.getAddress().getPort()));
        ProxyTestSupport.addDomain(site, "log.access.test", "exact", "/app", true);
        proxy = ProxyTestSupport.startProxy();
        int port = ProxyTestSupport.httpPort(proxy);

        // Step 1: two requests land as two lines, each naming the path the CLIENT sent.
        ProxyTestSupport.rawRequest(port, "log.access.test", "/app/one", "Referer: https://from.example/page",
            "User-Agent: probe/1.0");
        ProxyTestSupport.rawRequest(port, "log.access.test", "/app/two%20x");
        List<String> first = awaitLines(logFile, 2);
        assertThat(first).as("step 1: the unstripped client path is logged")
            .anyMatch(line -> line.contains("\"GET /app/one "));
        assertThat(first).as("step 1: the path is logged in its raw encoding")
            .anyMatch(line -> line.contains("\"GET /app/two%20x "));
        // Step 1b: up to the user agent the line is the original's, byte for byte, so an old parser reads it; the time
        //          taken and the answering site follow it, and the reader reads both back.
        String one = first.stream().filter(line -> line.contains("\"GET /app/one ")).findFirst().orElseThrow();
        int agentEnd = one.indexOf("\"probe/1.0\"") + "\"probe/1.0\"".length();
        assertThat(one.substring(0, agentEnd)).as("step 1b: the original's line: host prefix, date, request, referer, "
                + "agent")
            .matches("log\\.access\\.test: \\S+ - - \\[\\d{2}/\\w{3}/\\d{4}:\\d{2}:\\d{2}:\\d{2} "
                + "[+-]\\d{4}\\] \"GET /app/one HTTP/1\\.1\" 200 \\d+ \"https://from\\.example/page\" "
                + "\"probe/1\\.0\"");
        int siteId = site.get(SiteModel.ID);
        assertThat(one.substring(agentEnd)).as("step 1b: then the time taken and the site")
            .matches(" time_ms=\\d+ site=" + siteId);
        Matcher old = Pattern.compile("^(\\S+): (\\S+) - - \\[([^\\]]+)\\] \"([^\"]*)\" (\\d{3}) (\\d+) \"([^\"]*)\" "
            + "\"([^\"]*)\"").matcher(one);
        assertThat(old.find()).as("step 1b: a parser written for the Node line still reads it").isTrue();
        assertThat(old.group(8)).as("step 1b: up to its agent").isEqualTo("probe/1.0");
        AccessLogLine read = AccessLogLine.parse(one);
        assertThat(read).as("step 1b: the reader reads it").isNotNull();
        assertThat(read.siteId()).as("step 1b: its site").isEqualTo(siteId);
        assertThat(read.millis()).as("step 1b: and its time taken").isNotNull().isNotNegative();
        assertThat(AccessLog.withoutSite(one)).as("step 1b: a reader naming the site reads it without")
            .isEqualTo(one.substring(0, one.lastIndexOf(" site=")));

        // Step 2: logrotate moves the file away; the next line starts a fresh file at the path
        // instead of vanishing into the moved one.
        Path rotated = directory.resolve("access.log.1");
        Files.move(logFile, rotated);
        ProxyTestSupport.rawRequest(port, "log.access.test", "/app/three");
        List<String> fresh = awaitLines(logFile, 1);
        assertThat(fresh).as("step 2: the rotated path holds only the new line").hasSize(1);
        assertThat(fresh.get(0)).contains("/app/three");
        assertThat(Files.readAllLines(rotated))
            .as("step 2: the rotated file kept exactly its two lines").hasSize(2);
    }

    @Test
    @Timeout(60)
    void aDomainScannerLandsInTheFail2banLog() throws Exception {
        Path directory = Files.createTempDirectory("hh-domain-miss-log");
        Path logFile = directory.resolve("domain-misses.log");
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Logging.DOMAIN_MISSES_PATH, logFile.toString());
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Logging.DOMAIN_MISSES_TO_FILE, true);
        Integer threshold = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD, 1);
        ProxyServer own = ProxyTestSupport.startProxy();
        try {
            int port = ProxyTestSupport.httpPort(own);

            // Step 1: a request for a name nobody serves crosses the (lowered) threshold and lands one line in the
            // original's fail2ban format.
            ProxyTestSupport.rawRequest(port, "nobody-serves.scan.test", "/wp-login.php", "User-Agent: scanner/2");
            List<String> lines = awaitLines(logFile, 1);
            assertThat(lines.get(0)).as("step 1: the jail's failregex matches the line")
                .matches("^.*DOMAIN_MISS ip=\\S+ domain=nobody-serves\\.scan\\.test path=/wp-login\\.php "
                    + "user_agent=\"scanner/2\"$");
        } finally {
            own.stop();
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD, threshold);
        }
    }

    @Test
    @Timeout(60)
    void aNewOfficeDayStartsANewFileAtItsChangeAndAtARestart() throws Exception {
        Path directory = Files.createTempDirectory("hh-access-rotation");
        Path live = directory.resolve("access.log");
        Duration offset = Now.offset();
        try {
            // Step 1: a line on one office day, then a line on the next: the first day's file moves aside under its
            //         day and is compressed off the request thread; the live file holds the new day alone.
            LogFile file = new LogFile(AccessLogLine::timeOf);
            file.append(live, "first");
            LocalDate firstDay = RotatedLogs.today();
            Now.setOffset(offset.plus(Duration.ofDays(1)));
            file.append(live, "second");
            assertThat(Files.readAllLines(live)).as("step 1: the new day's file").containsExactly("second");
            Path compressed = directory.resolve("access.log." + firstDay + ".gz");
            Poll.value("the first day is compressed", Duration.ofSeconds(10),
                () -> Files.exists(compressed) && !Files.exists(directory.resolve("access.log." + firstDay))
                    ? compressed : null);
            assertThat(gunzipped(compressed)).as("step 1: the first day kept its line").containsExactly("first");

            // Step 2: a restart finds the file last written two days back: before its first line it moves that file
            //         aside under the day it was written on.
            Instant written = Now.instant().minus(Duration.ofDays(2));
            Files.setLastModifiedTime(live, FileTime.from(written));
            new LogFile(AccessLogLine::timeOf).append(live, "third");
            assertThat(Files.readAllLines(live)).as("step 2: the restart's file").containsExactly("third");
            LocalDate writtenDay = RotatedLogs.dayOf(written);
            assertThat(RotatedLogs.days(live)).as("step 2: the old file stands under its own day")
                .anyMatch(day -> day.day().equals(writtenDay));
        } finally {
            Now.setOffset(offset);
        }
    }

    @Test
    void daysPastTheKeepingPeriodAreDeletedAndTheRestCompressed() throws Exception {
        Path directory = Files.createTempDirectory("hh-access-keeping");
        Path live = directory.resolve("access.log");
        LocalDate today = LocalDate.of(2026, 11, 25);
        Path tooOld = Files.writeString(directory.resolve("access.log." + today.minusDays(120)), "old\n");
        Path tooOldCompressed = directory.resolve("access.log." + today.minusDays(91) + ".gz");
        gzip(tooOldCompressed, List.of("older"));
        Path lastKept = Files.writeString(directory.resolve("access.log." + today.minusDays(90)), "kept\n");
        Path yesterday = Files.writeString(directory.resolve("access.log." + today.minusDays(1)), "recent\n");
        // What a tidy a crash ended had staged for yesterday's compressed day.
        AtomicFiles.Staged leftover = AtomicFiles.stage(RotatedLogs.compressedOf(yesterday));
        Path unfinished = Files.writeString(leftover.path(), "x");
        Path other = Files.writeString(directory.resolve("other.log." + today.minusDays(200)), "not ours\n");

        // Step 1: with 90 days kept, the days before them go, plain or compressed; the kept ones are compressed, and
        //         what an ended tidy left half-written is removed; another log's files stay.
        RotatedLogs.tidy(live, today, 90, AccessLogLine::timeOf);
        assertThat(List.of(Files.exists(tooOld), Files.exists(tooOldCompressed)))
            .as("step 1: past the keeping period, deleted").containsExactly(false, false);
        assertThat(gunzipped(RotatedLogs.compressedOf(lastKept))).as("step 1: the 90th day kept, compressed")
            .containsExactly("kept");
        assertThat(gunzipped(RotatedLogs.compressedOf(yesterday))).as("step 1: yesterday compressed")
            .containsExactly("recent");
        assertThat(List.of(Files.exists(lastKept), Files.exists(yesterday), Files.exists(unfinished)))
            .as("step 1: no plain twin nor a half-written file left").containsExactly(false, false, false);
        assertThat(other).as("step 1: another log's file stays").exists();
        leftover.close();

        // Step 2: keeping 0 days keeps every day.
        RotatedLogs.tidy(live, today.plusYears(1), 0, AccessLogLine::timeOf);
        assertThat(RotatedLogs.days(live)).as("step 2: nothing deleted").hasSize(2);
    }

    @Test
    void aFileOfSeveralDaysIsSplitIntoItsDaysAtItsRotationOnce() throws Exception {
        Path directory = Files.createTempDirectory("hh-access-split");
        Path live = Files.createFile(directory.resolve("access.log"));
        LocalDate today = LocalDate.of(2026, 11, 25);
        LocalDate august = today.minusDays(88);
        LocalDate before = today.minusDays(3);
        LocalDate named = today.minusDays(2);
        // The one old file of every line since August, named at its first rotation for the day of its last line: a
        // torn line before any dated one, an old line from before the site field, a line no clock reads, bytes outside
        // ASCII, and the day it is named for.
        String accented = written(at(before, 9, 0), "seven.test", "198.51.100.3", "/café", 200, 7);
        Path old = Files.write(directory.resolve("access.log." + named), List.of(
            "torn line before any date",
            AccessLog.line("seven.test", "198.51.100.1", at(august, 10, 0), "GET", "/legacy", "HTTP/1.1", 200, 12,
                null, "curl/8.0"),
            written(at(before, 8, 0), "seven.test", "198.51.100.2", "/before", 200, 7),
            "a line no clock reads",
            accented,
            written(at(named, 7, 0), "seven.test", "198.51.100.4", "/named", 200, 7)), StandardCharsets.UTF_8);
        // A rotated file of one day is no split's business.
        Path single = Files.write(directory.resolve("access.log." + today.minusDays(1)), List.of(
            written(at(today.minusDays(1), 12, 0), "seven.test", "198.51.100.5", "/single", 200, 7)));

        // Step 1: each line lands in the day it was written on, compressed: the torn line with the first dated
        //         line, the unreadable one with the line before it, every byte as it was; the old file is gone.
        RotatedLogs.tidy(live, today, 0, AccessLogLine::timeOf);
        assertThat(old).as("step 1: the old file is gone").doesNotExist();
        assertThat(gunzipped(directory.resolve("access.log." + august + ".gz"))).as("step 1: August")
            .containsExactly("torn line before any date", AccessLog.line("seven.test", "198.51.100.1",
                at(august, 10, 0), "GET", "/legacy", "HTTP/1.1", 200, 12, null, "curl/8.0"));
        assertThat(gunzipped(directory.resolve("access.log." + before + ".gz"))).as("step 1: the day before")
            .containsExactly(written(at(before, 8, 0), "seven.test", "198.51.100.2", "/before", 200, 7),
                "a line no clock reads", accented);
        assertThat(gunzipped(RotatedLogs.compressedOf(old))).as("step 1: the day it was named for")
            .containsExactly(written(at(named, 7, 0), "seven.test", "198.51.100.4", "/named", 200, 7));
        assertThat(gunzipped(RotatedLogs.compressedOf(single))).as("step 1: the one-day file compressed whole")
            .hasSize(1);

        // Step 2: every old day is readable now, the line from before the site field by its hostname.
        AccessLogReader.Page read = AccessLogReader.read(live, query(7, Set.of("seven.test"), null, null,
            code -> true, null, null, 0, 10));
        assertThat(paths(read)).as("step 2: newest first across the split days")
            .containsExactly("/single", "/named", "/café", "/before", "/legacy");

        // Step 3: once: a second tidy leaves every day as it is.
        List<RotatedLogs.Day> days = RotatedLogs.days(live);
        RotatedLogs.tidy(live, today, 0, AccessLogLine::timeOf);
        assertThat(RotatedLogs.days(live)).as("step 3: nothing split again").isEqualTo(days);

        // Step 4: with 90 days kept, a split keeps no day before them.
        Path older = Files.write(directory.resolve("access.log." + today.minusDays(1) + ".1"), List.of(
            written(at(today.minusDays(120), 9, 0), "seven.test", "198.51.100.6", "/ancient", 200, 7),
            written(at(today.minusDays(1), 13, 0), "seven.test", "198.51.100.7", "/kept", 200, 7)));
        RotatedLogs.tidy(live, today, 90, AccessLogLine::timeOf);
        assertThat(directory.resolve("access.log." + today.minusDays(120) + ".gz"))
            .as("step 4: past the keeping period, never written").doesNotExist();
        assertThat(gunzipped(RotatedLogs.compressedOf(older))).as("step 4: the kept day")
            .containsExactly(written(at(today.minusDays(1), 13, 0), "seven.test", "198.51.100.7", "/kept", 200, 7));
    }

    @Test
    void anOutsideRotationOfTheSameLogIsSeenAndItsRuleNamed() throws Exception {
        Path directory = Files.createTempDirectory("hh-access-outside");
        Path live = directory.resolve("access.log");
        Path rules = Files.createTempDirectory("hh-logrotate");
        Files.writeString(rules.resolve("nginx"), "/var/log/nginx/*.log {\n    daily\n}\n");
        Path rule = Files.writeString(rules.resolve("hohenheim"), directory + "/*.log {\n    daily\n"
            + "    copytruncate\n    rotate 14\n}\n");
        Files.writeString(directory.resolve("access.log." + LocalDate.of(2026, 11, 24)), "own day\n");

        // Step 1: Hohenheim's own days alone: nothing is said.
        OutsideRotation.look(live, rules);
        assertThat(findings(live)).as("step 1: no outside rotation").isEmpty();

        // Step 2: numbered copies appear beside the log: the copy and the rule naming its folder are found.
        Files.writeString(directory.resolve("access.log.1"), "copied\n");
        gzip(directory.resolve("access.log.2.gz"), List.of("copied before"));
        OutsideRotation.look(live, rules);
        assertThat(findings(live)).as("step 2: one finding")
            .containsExactly(new OutsideRotation.Finding(live.toAbsolutePath(), "access.log.1", rule));

        // Step 3: the dashboard says so in both languages, naming the file to remove.
        List<AttentionItem> items = new ArrayList<>();
        ProxyAttention.outsideLogRotations(items);
        ShippedCatalogs catalogs = new ShippedCatalogs();
        List<AttentionItem> ours = items.stream().filter(item -> item.title().resolve(LocaleChain.ofTags("en"),
            catalogs).contains(live.toAbsolutePath().toString())).toList();
        assertThat(ours).as("step 3: one item").hasSize(1);
        for (String tag : List.of("en", "nl")) {
            assertThat(ours.get(0).detail().resolve(LocaleChain.ofTags(tag), catalogs)).as("step 3: in " + tag)
                .contains(rule.toString()).contains("access.log.1").doesNotContain("outside_log_rotation");
        }

        // Step 4: the copies removed, the next look forgets it.
        Files.delete(directory.resolve("access.log.1"));
        Files.delete(directory.resolve("access.log.2.gz"));
        OutsideRotation.look(live, rules);
        assertThat(findings(live)).as("step 4: solved").isEmpty();
    }

    private static List<OutsideRotation.Finding> findings(Path live) {
        return OutsideRotation.found().stream().filter(found -> found.log().equals(live.toAbsolutePath())).toList();
    }

    @Test
    @Timeout(60)
    void theReaderReadsOneSitesLinesNewestFirstAcrossARotatedAndACompressedDay() throws Exception {
        Path directory = Files.createTempDirectory("hh-access-reader");
        Path live = directory.resolve("access.log");
        LocalDate today = RotatedLogs.today();
        Set<String> sevenHosts = Set.of("seven.test");
        // Two days back, compressed: a line of site 7, a line from before the site field (its hostname is site 7's
        // exact address) and a line of site 8.
        gzip(directory.resolve("access.log." + today.minusDays(2) + ".gz"), List.of(
            written(at(today.minusDays(2), 10, 0), "seven.test", "198.51.100.4", "/old", 200, 7),
            AccessLog.line("seven.test", "198.51.100.4", at(today.minusDays(2), 10, 1), "GET", "/legacy",
                "HTTP/1.1", 404, 12, null, "curl/8.0"),
            written(at(today.minusDays(2), 10, 2), "eight.test", "198.51.100.8", "/eight", 200, 8)));
        // Yesterday, rotated and not compressed yet.
        Files.write(directory.resolve("access.log." + today.minusDays(1)), List.of(
            written(at(today.minusDays(1), 12, 0), "seven.test", "203.0.113.5", "/a", 200, 7),
            written(at(today.minusDays(1), 12, 1), "seven.test", "203.0.113.57", "/b", 500, 7)));
        // Today, live.
        Files.write(live, List.of(
            written(at(today, 0, 10), "www.seven.test", "203.0.113.5", "/c", 301, 7),
            written(at(today, 0, 11), "seven.test", "2001:db8:4f2a::17", "/d?x=1", 200, 7),
            written(at(today, 0, 12), "eight.test", "203.0.113.5", "/e", 200, 8)));

        // Step 1: every line of site 7, newest first, through the live file, the rotated day and the compressed day;
        //         an old line by its exact address, a new one by its site whatever address it came on.
        AccessLogReader.Page all = AccessLogReader.read(live, query(7, sevenHosts, null, null, code -> true, null,
            null, 0, 10));
        assertThat(paths(all)).as("step 1").containsExactly("/d?x=1", "/c", "/b", "/a", "/legacy", "/old");
        assertThat(List.of(all.matched(), all.complete())).as("step 1: counted, whole").containsExactly(6L, true);

        // Step 2: pages across the rotated day into the compressed one, and inside the compressed one.
        assertThat(paths(AccessLogReader.read(live, query(7, sevenHosts, null, null, code -> true, null, null, 3, 2))))
            .as("step 2: across days").containsExactly("/a", "/legacy");
        assertThat(paths(AccessLogReader.read(live, query(7, sevenHosts, null, null, code -> true, null, null, 4, 2))))
            .as("step 2: inside a compressed day").containsExactly("/legacy", "/old");
        assertThat(paths(AccessLogReader.read(live, query(7, sevenHosts, null, null, code -> true, null, null, 5, 1))))
            .as("step 2: a page starting deep inside a compressed day").containsExactly("/old");

        // Step 3: status class, path text, a visitor exactly and by its network.
        assertThat(paths(AccessLogReader.read(live, query(7, sevenHosts, null, null,
            SiteAccessLogOperations.statusesOf(SiteAccessLogOperations.FAILED),
            null, null, 0, 10)))).as("step 3: 4xx and 5xx").containsExactly("/b", "/legacy");
        assertThat(paths(AccessLogReader.read(live, query(7, sevenHosts, null, null, code -> code == 301, null, null,
            0, 10)))).as("step 3: one code").containsExactly("/c");
        assertThat(paths(AccessLogReader.read(live, query(7, sevenHosts, null, null, code -> true, "x=1", null, 0,
            10)))).as("step 3: path with its query").containsExactly("/d?x=1");
        assertThat(paths(AccessLogReader.read(live, query(7, sevenHosts, null, null, code -> true, null,
            "203.0.113.5", 0, 10)))).as("step 3: one visitor").containsExactly("/c", "/a");
        assertThat(paths(AccessLogReader.read(live, query(7, sevenHosts, null, null, code -> true, null,
            "203.0.113.", 0, 10)))).as("step 3: a network").containsExactly("/c", "/b", "/a");

        // Step 4: a time range: yesterday alone, and up to two days back, which passes over the newer files.
        Instant yesterdayStart = at(today.minusDays(1), 0, 0);
        Instant todayStart = at(today, 0, 0);
        assertThat(paths(AccessLogReader.read(live, query(7, sevenHosts, yesterdayStart, todayStart.minusNanos(1),
            code -> true, null, null, 0, 10)))).as("step 4: yesterday").containsExactly("/b", "/a");
        assertThat(paths(AccessLogReader.read(live, query(7, sevenHosts, null, yesterdayStart.minusNanos(1),
            code -> true, null, null, 0, 10)))).as("step 4: before yesterday").containsExactly("/legacy", "/old");

        // Step 5: another site reads its own lines and never site 7's, the old line included.
        assertThat(paths(AccessLogReader.read(live, query(8, Set.of("eight.test"), null, null, code -> true, null,
            null, 0, 10)))).as("step 5").containsExactly("/e", "/eight");

        // Step 6: a compressed day whose text no longer fits the bound is not started: the read says it stopped, how
        //         far back it came and what matched up to there.
        try (Writer big = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(
                directory.resolve("access.log." + today.minusDays(3) + ".gz"))), StandardCharsets.UTF_8)) {
            String line = written(at(today.minusDays(3), 9, 0), "seven.test", "198.51.100.9", "/bulk", 200, 7) + "\n";
            for (long size = 0; size <= AccessLogReader.SCAN_BYTES; size += line.length()) big.write(line);
        }
        AccessLogReader.Page bounded = AccessLogReader.read(live, query(7, sevenHosts, null, null, code -> true, null,
            null, 0, 10));
        assertThat(List.of(bounded.complete(), bounded.matched())).as("step 6: stopped at the bound")
            .containsExactly(false, 6L);
        assertThat(bounded.readBackTo()).as("step 6: back to the oldest line read")
            .isEqualTo(at(today.minusDays(2), 10, 0));
    }

    /** A line as the proxy writes it today: the original's, then the time taken and the site. */
    private static String written(Instant at, String host, String visitor, String target, int status, int site) {
        return AccessLog.line(host, visitor, at, "GET", target, "HTTP/1.1", status, 100, null, "probe/1.0")
            + AccessLog.fields(3, site);
    }

    private static Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(OfficeZone.office()).toInstant();
    }

    private static AccessLogReader.Query query(int site, Set<String> hosts, @Nullable Instant from,
                                               @Nullable Instant to, IntPredicate status, @Nullable String path,
                                               @Nullable String visitor, int offset, int limit) {
        return new AccessLogReader.Query(site, hosts, from, to, status, path, visitor, offset, limit);
    }

    private static List<String> paths(AccessLogReader.Page page) {
        List<String> paths = new ArrayList<>();
        for (AccessLogLine line : page.lines()) paths.add(line.path());
        return paths;
    }

    private static void gzip(Path target, List<String> lines) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
            for (String line : lines) out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static List<String> gunzipped(Path file) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            return in.lines().toList();
        }
    }

    /** The log's lines once it holds at least {@code count}; the line lands after the response. */
    private static List<String> awaitLines(Path file, int count) {
        return Poll.value("the access log holds " + count + " line(s)", Duration.ofSeconds(5), () -> {
            if (!Files.exists(file)) {
                return null;
            }
            try {
                List<String> lines = Files.readAllLines(file);
                return lines.size() >= count ? lines : null;
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        });
    }
}
