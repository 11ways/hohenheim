package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.panel.CmsSurfaceAddress;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two landing dashboards answer every audience over HTTP exactly as they did as legacy dashboard peers.
 *
 * AIDEV-NOTE: {@code /panel-surfaces/landing-dashboards.txt} was captured once with AdminDashboard and ManageDashboard
 * extending the legacy DashboardPanelPeer (41691aa9^), the commit's only server change reverted locally; a failing
 * comparison is a changed landing surface, never a file to refresh. A missing resource writes the live set to
 * {@code build/panel-surfaces/} and fails, so recording is a deliberate copy. Per case it holds the status, the
 * redirect, the title, the dashboard nav links and the widget surface, with markup ids, digits, UUIDs and CSRF values
 * masked and every tag's attributes in name order (HTML attribute order means nothing): the data under the widgets
 * is not this conversion's subject. The one declared difference is the surface
 * address: a legacy peer answered to its slug address, a PanelDashboard only to its id token (P07, no slug alias).
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
    private static final Pattern START_TAG = Pattern.compile("<([a-z][a-z0-9-]*)((?:\\s+[^\\s=>\"]+(?:=\"[^\"]*\")?)+)\\s*>");
    private static final Pattern ATTRIBUTE = Pattern.compile("[^\\s=>\"]+(?:=\"[^\"]*\")?");
    private static final Pattern RECORD_TEXT = Pattern.compile(
        "(<span class=\"widget-record-(?:title|subtitle)\">).*?(</span>)", Pattern.DOTALL);

    private static TestSession tenant;
    private static TestSession outsider;

    @BeforeAll
    static void seed() throws Exception {
        freshSeededDatabase();
        int tenantId = ApiSupport.user("landing-tenant@hohenheim.local", "Landing Tenant");
        int outsiderId = ApiSupport.user("landing-outsider@hohenheim.local", "Landing Outsider");
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
            HohenheimAccess.MANAGE, true);
        tenant = sessionFor(tenantId);
        outsider = sessionFor(outsiderId);
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

        // 2. The live set equals the stored one exactly; without a stored set it is written for review and fails.
        String stored = stored();
        if (stored == null) {
            Path written = Path.of("build", "panel-surfaces", RESOURCE);
            Files.createDirectories(written.getParent());
            Files.writeString(written, current, StandardCharsets.UTF_8);
            throw new AssertionError("step 2: no stored landing dashboards; the live set is at "
                + written.toAbsolutePath());
        }
        // 3. The stored slug addresses read as the entries' id tokens, every other fact compared exactly.
        for (String panelSlug : List.of(HohenheimSlugs.ADMIN, HohenheimSlugs.MANAGE)) {
            stored = stored.replace("surface=\"cms:" + panelSlug + "/dashboard\"",
                "surface=\"" + normalize(dashboardToken(panelSlug)) + "\"");
        }
        assertThat(cases(current)).as("step 3: every audience's landing dashboards are the stored ones")
            .containsExactlyElementsOf(cases(normalizeRecordText(sortAttributes(stored))));
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
        return normalizeRecordText(sortAttributes(SPACE.matcher(text).replaceAll(" ").trim()));
    }

    /** Keep the record links and row structure while excluding the widget data this conversion never compared. */
    private static @NonNull String normalizeRecordText(@NonNull String html) {
        return RECORD_TEXT.matcher(html).replaceAll("$1<record-text>$2");
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
