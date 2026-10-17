package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.dns.DnsServer;
import be.elevenways.hohenheim.server.docker.DockerHealth;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.panel.CmsSurfaceAddress;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.history.ActionModel;
import be.elevenways.zenit.common.orm.history.ChangeFactModel;
import be.elevenways.zenit.common.orm.history.ChangeModel;
import be.elevenways.zenit.common.orm.history.ChangeOp;
import be.elevenways.zenit.test.support.HistoryFixtureRows;
import be.elevenways.zenit.common.security.AccountabilityOrigin;
import be.elevenways.zenit.common.security.SystemPrincipal;
import be.elevenways.plumage.icon.IconRegistry;
import be.elevenways.protoblast.common.time.Now;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two landing dashboards answer every audience over HTTP exactly as they did as legacy dashboard peers.
 *
 * AIDEV-NOTE: {@code /panel-surfaces/landing-dashboards.txt} was captured once with AdminDashboard and ManageDashboard
 * extending the legacy DashboardPanelPeer; a failing
 * comparison is a changed landing surface, never a file to refresh. A missing resource writes the live set to
 * {@code build/panel-surfaces/} and fails, so recording is a deliberate copy. Per case it holds the status, the
 * redirect, the title, the dashboard nav links and the widget surface, with markup ids, digits, UUIDs and CSRF values
 * masked and every tag's attributes in name order (HTML attribute order means nothing): the data under the widgets
 * is not this conversion's subject. The declared differences are the surface address (a legacy peer answered to its
 * slug address, a PanelDashboard only to its id token, no slug alias), the /manage landing's surface, which
 * was redrawn on purpose ({@link #redrawn}), and plumage's mark on a directional glyph ({@link #markDirectional}).
 *
 * AIDEV-NOTE: the fixture decides the operator's checklist and tiles. Its only site has no address and so serves
 * nobody: "Put your first app online" stays TODO with its Open link and the Apps tile reads "1 with a problem". Its
 * never-checked host makes the Hosts tile "1 waiting". The open admission and backups steps present the attention
 * items stating their stage, so the attention band, holding nothing else, is not drawn under the open checklist.
 *
 * AIDEV-NOTE: the attention widget reads JVM-global inputs other classes of a lane change (so: pin
 * every input the test does not own, never compare host state). {@link #pinAttentionInputs} sets the settings and
 * servers and restores them after (the DNS zones follow this class's datasource by themselves); Docker health and
 * the Spamservice manager change only in a real ServerMain boot, which runs solo, so they are asserted untouched.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class LandingDashboardSurfacesTest extends HohenheimTestBase {

    private static final String RESOURCE = "landing-dashboards.txt";
    private static final String CASE_PREFIX = "case ";
    private static final List<String> PATHS = List.of("/admin", "/admin/dashboard", "/manage", "/manage/dashboard");

    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern DASHBOARD_LINK = Pattern.compile("<a\\b[^>]*href=\"[^\"]*/dashboard\"[^>]*>");
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern MARKUP_ID = Pattern.compile("\\b(he|pl-[a-z-]+)-\\d+\\b");
    private static final Pattern UUID = Pattern.compile(
        "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern CSRF = Pattern.compile("(name=\"_csrf\"[^>]*value=\")[^\"]*\"");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern START_TAG =
            Pattern.compile("<([a-z][a-z0-9-]*)((?:\\s+[^\\s=>\"]+(?:=\"[^\"]*\")?)+)\\s*>");
    private static final Pattern ATTRIBUTE = Pattern.compile("[^\\s=>\"]+(?:=\"[^\"]*\")?");
    private static final Pattern MANAGE_LANDING = Pattern.compile("case [a-z]+ /manage/dashboard\\b");
    private static final Pattern SURFACE_LINE = Pattern.compile("(?m)^  surface .*$");
    private static final Pattern ICON_TAG = Pattern.compile("<pl-icon ([^>]*\\bname=\"([^\"]+)\"[^>]*)>");

    private static TestSession tenant;
    private static TestSession outsider;

    private static @Nullable String backupTarget;
    private static @Nullable Boolean dnsEnabled;
    private static @Nullable Boolean sshWatch;
    private static @Nullable ProxyServer proxyServer;
    private static @Nullable DnsServer dnsServer;

    @BeforeAll
    static void seed() throws Exception {
        freshSeededDatabase();
        UUID tenantId = ApiSupport.user("landing-tenant@hohenheim.local", "Landing Tenant");
        UUID outsiderId = ApiSupport.user("landing-outsider@hohenheim.local", "Landing Outsider");
        Model sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, "landing-site");
        site.set(SiteModel.SLUG, "landing-site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, site.get(SiteModel.ID),
            HohenheimCapabilities.MANAGE, true);
        tenant = sessionFor(tenantId);
        outsider = sessionFor(outsiderId);
        seedRecentActivity(site.get(SiteModel.ID));
        pinAttentionInputs();
    }

    /** Pin the dashboard attention's JVM-global inputs to the stored capture's: no proxy, no DNS, no SSH watch. */
    private static void pinAttentionInputs() {
        backupTarget = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET);
        dnsEnabled = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Dns.ENABLED);
        sshWatch = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Security.SSH_WATCH_ENABLED);
        proxyServer = ServerMain.getProxyServer();
        dnsServer = ServerMain.getDnsServer();
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET, null);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Dns.ENABLED, false);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Security.SSH_WATCH_ENABLED, false);
        ServerMain.adoptProxyServer(null);
        ServerMain.adoptDnsServer(null);
        assertThat(DockerHealth.instance().status()).as("setup: no boot probed the shared Docker health")
            .isEqualTo(DockerHealth.Status.UNPROBED);
        assertThat(SpamserviceManager.get().snapshot().needsAttention())
            .as("setup: no boot started the shared Spamservice manager").isFalse();
    }

    @AfterAll
    static void restoreAttentionInputs() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET, backupTarget);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Dns.ENABLED, dnsEnabled);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Security.SSH_WATCH_ENABLED, sshWatch);
        ServerMain.adoptProxyServer(proxyServer);
        ServerMain.adoptDnsServer(dnsServer);
    }

    /**
     * Seed a fixed mixed-provenance history instead of capturing boot seeders and the host's OS accounts.
     *
     * AIDEV-NOTE: each action names its model by the model's own id and its origin by the origin's own token, never a
     * literal: the literals spelled the pre-sweep 'zenit-auth:' namespace and the v1 origins ("web", not "zenit:web"),
     * so the role grant escaped the activity scope's internal models and the person's action left the people-only
     * feed. The one web action is the only person's, so the band shows it and nothing else; it creates the landing
     * site, because a history line names its record by the live title. The rows are written straight into the
     * history tables (HistoryFixtureRows) because their origins and actors must be chosen, which the writer derives
     * from whatever lane calls it.
     */
    private static void seedRecentActivity(@NonNull Object siteId) {
        Datasource datasource = Models.get(SiteModel.class).getResolvedDatasource();
        new ChangeFactModel(datasource).find().delete();
        new ChangeModel(datasource).find().delete();
        new ActionModel(datasource).find().delete();
        var now = Now.instant();
        Object[][] records = {
            {SiteModel.MODEL_ID, siteId, AccountabilityOrigin.WEB},
            {RecordGrantModel.MODEL_ID, null, AccountabilityOrigin.SYSTEM},
            {SiteModel.MODEL_ID, null, AccountabilityOrigin.SYSTEM},
            {InstanceQuotaModel.MODEL_ID, null, AccountabilityOrigin.SYSTEM},
            {UserModel.MODEL_ID, null, AccountabilityOrigin.SYSTEM},
            {UserModel.MODEL_ID, null, AccountabilityOrigin.SYSTEM},
            {GrantModel.MODEL_ID, null, AccountabilityOrigin.UNATTRIBUTED},
            {UserModel.MODEL_ID, null, AccountabilityOrigin.UNATTRIBUTED},
            {SystemUserModel.MODEL_ID, null, AccountabilityOrigin.SYSTEM},
            {SystemUserModel.MODEL_ID, null, AccountabilityOrigin.SYSTEM},
            {SystemUserModel.MODEL_ID, null, AccountabilityOrigin.SYSTEM}
        };
        for (int index = 0; index < records.length; index++) {
            Object[] fixture = records[index];
            AccountabilityOrigin origin = (AccountabilityOrigin) fixture[2];
            HistoryFixtureRows.Action action = HistoryFixtureRows.action(datasource).at(now.minusSeconds(index))
                .origin(origin.token());
            if (origin == AccountabilityOrigin.SYSTEM) {
                action.by(SystemPrincipal.INSTANCE.reference());
            }
            action.change((Identifier) fixture[0], fixture[1] == null ? "landing-fixture-" + index : fixture[1],
                ChangeOp.CREATE).done().write();
        }
    }

    @Test
    void everyAudienceGetsTheLandingDashboardsItGotAsLegacyPeers() throws Exception {
        // 1. Each audience asks both panels' index and dashboard route: the operator, a tenant managing one site and a
        //    signed-in principal holding nothing.
        List<String> live = new ArrayList<>();
        for (Map.Entry<String, String> audience : List.of(Map.entry("operator", sessionToken),
                Map.entry("tenant", tenant.token()), Map.entry("outsider", outsider.token()))) {
            for (String path : PATHS) {
                live.add(capture(audience.getKey(), path, httpGet(path, audience.getValue())));
            }
        }
        String current = String.join("\n", live) + "\n";

        // 2. The live set equals the stored one exactly; without a stored set it is written for review and fails. The
        //    live set is always written beside the build, so a changed surface can be compared against it.
        Path written = Path.of("build", "panel-surfaces", RESOURCE);
        Files.createDirectories(written.getParent());
        Files.writeString(written, current, StandardCharsets.UTF_8);
        String stored = stored();
        if (stored == null) {
            throw new AssertionError("step 2: no stored landing dashboards; the live set is at "
                + written.toAbsolutePath());
        }
        // 3. The stored slug addresses read as the entries' id tokens, every other fact compared exactly.
        for (String panelSlug : List.of(HohenheimSlugs.ADMIN, HohenheimSlugs.MANAGE)) {
            stored = stored.replace("surface=\"cms:" + panelSlug + "/dashboard\"",
                "surface=\"" + normalize(dashboardToken(panelSlug)) + "\"");
        }
        assertThat(redrawn(cases(current))).as("step 3: every audience's landing dashboards are the stored ones")
            .containsExactlyElementsOf(redrawn(cases(sortAttributes(markDirectional(stored)))));
    }

    /**
     * The declared difference plumage made after the capture: a glyph drawn for a left-to-right reading
     * ({@link IconRegistry#isDirectional}) carries {@code data-directional}, the mark its mirror rule reads.
     */
    private static @NonNull String markDirectional(@NonNull String stored) {
        Matcher icon = ICON_TAG.matcher(stored);
        StringBuilder marked = new StringBuilder();
        while (icon.find()) {
            String tag = IconRegistry.isDirectional(icon.group(2)) && !icon.group(1).contains("data-directional")
                ? "<pl-icon data-directional=\"\" " + icon.group(1) + ">" : icon.group();
            icon.appendReplacement(marked, Matcher.quoteReplacement(tag));
        }
        icon.appendTail(marked);
        return marked.toString();
    }

    /**
     * The declared difference: the /manage landing's widget surface was redrawn on purpose
     * (the tenant's apps band, their verdicts in the attention band, the usage card), so its surface line is not
     * compared; its status, title and nav still are. ManagePanelJourneyTest proves what the new surface shows.
     */
    private static @NonNull List<String> redrawn(@NonNull List<String> cases) {
        List<String> declared = new ArrayList<>(cases.size());
        for (String text : cases) {
            boolean manageLanding = MANAGE_LANDING.matcher(text).lookingAt();
            declared.add(manageLanding ? SURFACE_LINE.matcher(text).replaceAll("  surface redrawn on purpose") : text);
        }
        return declared;
    }

    /** @return the surface token the panel's dashboard entry answers to */
    private static @NonNull String dashboardToken(@NonNull String panelSlug) {
        Panel panel = Objects.requireNonNull(PanelRegistry.getBySlug(panelSlug), panelSlug);
        PanelEntry dashboard = Objects.requireNonNull(panel.entryBySlug("dashboard"), panelSlug + " dashboard");
        return new CmsSurfaceAddress(panel.id(), dashboard.id(), null).token();
    }

    /** One case: what {@code audience} got from {@code path}, each fact on its own line. */
    private static @NonNull String capture(@NonNull String audience, @NonNull String path,
                                           @NonNull HttpResponse<String> response) {
        StringBuilder text = new StringBuilder(CASE_PREFIX).append(audience).append(' ').append(path).append('\n');
        text.append("  status ").append(response.statusCode()).append('\n');
        response.headers().firstValue("Location")
            .ifPresent(location -> text.append("  location ").append(location).append('\n'));
        if (response.statusCode() != 200) {
            return text.toString();
        }
        String body = response.body();
        Matcher title = TITLE.matcher(body);
        if (title.find()) {
            text.append("  title ").append(normalize(title.group(1))).append('\n');
        }
        Matcher link = DASHBOARD_LINK.matcher(body);
        while (link.find()) {
            text.append("  nav ").append(normalize(link.group())).append('\n');
        }
        int start = body.indexOf("<zn-widget-surface");
        int end = body.lastIndexOf("</zn-widget-surface>");
        if (start >= 0 && end > start) {
            text.append("  surface ").append(normalize(body.substring(start, end))).append('\n');
        }
        return text.toString();
    }

    private static @NonNull String normalize(@NonNull String html) {
        String text = COMMENT.matcher(html).replaceAll("");
        text = CSRF.matcher(text).replaceAll("$1#\"");
        text = UUID.matcher(text).replaceAll("<uuid>");
        text = MARKUP_ID.matcher(text).replaceAll("$1-#");
        text = DIGITS.matcher(text).replaceAll("#");
        return sortAttributes(SPACE.matcher(text).replaceAll(" ").trim());
    }

    /** @return the text with every start tag's attributes in name order */
    private static @NonNull String sortAttributes(@NonNull String html) {
        Matcher tag = START_TAG.matcher(html);
        StringBuilder sorted = new StringBuilder();
        while (tag.find()) {
            List<String> attributes = new ArrayList<>();
            Matcher attribute = ATTRIBUTE.matcher(tag.group(2));
            while (attribute.find()) {
                attributes.add(attribute.group());
            }
            attributes.sort(null);
            tag.appendReplacement(sorted, Matcher.quoteReplacement(
                "<" + tag.group(1) + " " + String.join(" ", attributes) + ">"));
        }
        tag.appendTail(sorted);
        return sorted.toString();
    }

    /** @return the cases of a set, one string each */
    private static @NonNull List<String> cases(@NonNull String set) {
        List<String> cases = new ArrayList<>();
        StringBuilder current = null;
        for (String line : set.split("\n")) {
            if (line.startsWith(CASE_PREFIX)) {
                if (current != null) {
                    cases.add(current.toString());
                }
                current = new StringBuilder(line);
            } else if (current != null && !line.isBlank()) {
                current.append('\n').append(line);
            }
        }
        if (current != null) {
            cases.add(current.toString());
        }
        return cases;
    }

    private static @Nullable String stored() {
        try (InputStream in = LandingDashboardSurfacesTest.class.getResourceAsStream("/panel-surfaces/" + RESOURCE)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
