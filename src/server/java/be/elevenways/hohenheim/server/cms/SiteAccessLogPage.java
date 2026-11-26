package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.server.proxy.AccessLog;
import be.elevenways.hohenheim.server.proxy.AccessLogLine;
import be.elevenways.hohenheim.server.proxy.AccessLogReader;
import be.elevenways.hohenheim.site.AccessLogFilters;
import be.elevenways.hohenheim.site.AccessLogView;
import be.elevenways.hohenheim.site.SiteAccessLogOperations;
import be.elevenways.plumage.component.Pager;
import be.elevenways.protoblast.common.http.UserAgent;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.server.page.RecordTabPart;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.FacetUrlState;
import be.elevenways.zenit.common.data.ListState;
import be.elevenways.zenit.common.data.PageWindow;
import be.elevenways.zenit.common.live.LiveFeed;
import be.elevenways.zenit.common.live.LiveRead;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.BoundEndpoint;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.MalformedQuery;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.time.OfficeZone;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.server.live.LiveReads;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.traffic.common.StatusClass;
import be.elevenways.zenit.traffic.server.HitKeys;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * A site's Access log tab: its proxied requests, newest first, filtered by status, path, time and visitor, followed
 * live while it is open, and downloadable as the lines the filters show; and the read an assistant's
 * {@code access_log_read} makes, through the same reader.
 *
 * AIDEV-NOTE: the tab's content is one live part (its parameter is the record tab part's {@code panel,key,page} and
 * the filters' query, URL-encoded so it holds neither a comma nor a slash), heard on the site's own access-log topic,
 * which AccessLog announces per line, coalesced; following is the reader's switch. Who may read is the site's manage
 * reach (SiteOperationHandlers.reaches) for the tab and the tool alike, so a customer reads the sites they manage and
 * never another's lines. The lines are visitors' data: nothing here puts them in a history entry, a flash or an error.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SiteAccessLogPage implements RecordTab.Rendered<Row> {

    /** Lines per page: the operation's, so the tab and the tool page alike. */
    static final int PAGE_SIZE = SiteAccessLogOperations.PAGE_LINES;

    /** The most lines one download carries, newest first, within the reader's own bound. */
    static final int DOWNLOAD_LINES = 50_000;

    private static final ParameterDefinition<String> STATUS = text(SiteAccessLogOperations.STATUS.getName());
    private static final ParameterDefinition<String> PATH = text(SiteAccessLogOperations.PATH.getName());
    private static final ParameterDefinition<String> VISITOR = text(SiteAccessLogOperations.VISITOR.getName());
    private static final ParameterDefinition<String> FROM = text(SiteAccessLogOperations.FROM.getName());
    private static final ParameterDefinition<String> TO = text(SiteAccessLogOperations.TO.getName());
    private static final ParameterDefinition<String> WHEN = text("when");
    private static final ParameterDefinition<String> DOWNLOAD = text("download");
    private static final List<ParameterDefinition<String>> FILTERS = List.of(STATUS, PATH, WHEN, FROM, TO, VISITOR);

    /** The time choices of the tab; a range reads its from and to. */
    enum When {
        HOUR("hour"), TODAY("today"), YESTERDAY("yesterday"), RANGE("range");

        private final String token;

        When(String token) {
            this.token = token;
        }

        /** @return the choice a token spells, today for none (the tab's opening range) */
        static @NonNull When of(@Nullable String token) {
            for (When each : values()) {
                if (each.token.equals(token)) return each;
            }
            return TODAY;
        }
    }

    /** Where {@link #PART} lives: the Access log tab of a site record. */
    private static final RecordTabPart TAB_PART =
        new RecordTabPart(HohenheimSlugs.SITES, HohenheimSlugs.Tab.ACCESS_LOG);

    /** The tab's lines as one live part, heard on the site's own access-log topic. */
    static final LiveRead<String, AccessLogView> PART =
        LiveRead.of(HohenheimIds.id("site_access_log"), String.class, AccessLogView.class)
            .heard(part -> {
                Integer siteId = siteIdOf(part);
                return siteId == null ? List.of()
                    : List.of(LiveFeed.keyed(AccessContext::isAccount, siteId, AccessLog.TOPIC));
            });

    static {
        OperationHandlers.attach(SiteAccessLogOperations.READ)
            .applies(SiteParts::servesHttp)
            .authorize(SiteOperationHandlers.reachesSite())
            .handle(call -> lines(call.subject(), Objects.requireNonNull(call.input(), "a read has its input")));
    }

    /** Serve {@link #PART} and attach the read's handler; called once by HohenheimHandlers. */
    public static void registerPart() {
        SiteAccessLogPage tab = new SiteAccessLogPage();
        LiveReads.serve(PART, part -> TAB_PART.page(tabParameterOf(part)), tab::partRead);
    }

    @Override public @NonNull Identifier id() { return HohenheimIds.id("site_access_log"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.ACCESS_LOG.of("access_log_tab"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.ACCESS_LOG; }
    @Override public @NonNull Icon icon() { return Icon.of("list-ul"); }

    /** A site answering HTTP itself has one, for whoever manages it: a stream or a passthrough has no request lines. */
    @Override
    public boolean visibleFor(@NonNull Row site, @NonNull AccessContext access) {
        return SiteParts.servesHttp(site) && SiteOperationHandlers.reaches(site, access);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row site) {
        Conduit conduit = request.conduit();
        Endpoint.declareQueryOn(conduit, MalformedQuery.ABSENT, List.of(STATUS, PATH, WHEN, FROM, TO, VISITOR,
            DOWNLOAD));
        Map<String, String> filters = new HashMap<>();
        for (ParameterDefinition<String> filter : FILTERS) {
            String value = conduit.getParameter(filter);
            if (value != null && !value.isBlank()) filters.put(filter.getName(), value.trim());
        }
        int siteId = site.get(SiteModel.ID);
        if (conduit.getParameter(DOWNLOAD) != null) {
            return download(site, filters);
        }
        int page = FacetUrlState.readState(ListState.Shape.PLAIN, conduit::getQueryParams).page();
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, HohenheimMicrocopy.ACCESS_LOG, site.get(SiteModel.NAME)));
        vars.put("head", recordHead(conduit));
        vars.put("filters", filterRow(request.panelSlug(), siteId, filters, page));
        vars.put("part", LiveReads.at(PART, parameter(request.panelSlug(), siteId, page, filters),
            () -> view(request.panelSlug(), site, filters, page)));
        return new RenderTemplateResult(HohenheimTemplateIds.SITE_ACCESS_LOG, vars);
    }

    /** The part's read: the panel's gate admits the reader to the sites, then this tab's gate to the site. */
    private @NonNull AccessLogView partRead(@NonNull AccessContext access, @NonNull String part) {
        RecordTabPart.Opened opened = TAB_PART.open(access, tabParameterOf(part));
        if (!(opened.record() instanceof Row site)) {
            throw new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "No such access log tab");
        }
        return view(opened.request().panelSlug(), site, filtersOf(part), opened.page());
    }

    /** @return the part's parameter: the tab part's, then the filters as their query, URL-encoded */
    static @NonNull String parameter(@NonNull String panel, int siteId, int page,
                                     @NonNull Map<String, String> filters) {
        return TAB_PART.parameter(panel, siteId, page) + ","
            + URLEncoder.encode(query(filters), StandardCharsets.UTF_8);
    }

    /** @return the tab part's own parameter of a part parameter */
    private static @NonNull String tabParameterOf(@NonNull String part) {
        int last = part.lastIndexOf(',');
        return last < 0 ? part : part.substring(0, last);
    }

    /** @return the filters a part parameter carries */
    private static @NonNull Map<String, String> filtersOf(@NonNull String part) {
        Map<String, String> filters = new HashMap<>();
        int last = part.lastIndexOf(',');
        if (last < 0) return filters;
        String query = URLDecoder.decode(part.substring(last + 1), StandardCharsets.UTF_8);
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            String name = URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            for (ParameterDefinition<String> filter : FILTERS) {
                if (filter.getName().equals(name) && !value.isBlank()) filters.put(name, value);
            }
        }
        return filters;
    }

    /** @return the filters as a query string, in their declared order */
    private static @NonNull String query(@NonNull Map<String, String> filters) {
        StringBuilder query = new StringBuilder();
        for (ParameterDefinition<String> filter : FILTERS) {
            String value = filters.get(filter.getName());
            if (value == null) continue;
            if (!query.isEmpty()) query.append('&');
            query.append(URLEncoder.encode(filter.getName(), StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return query.toString();
    }

    /** @return the site a part parameter names, null when it names none */
    private static @Nullable Integer siteIdOf(@NonNull String part) {
        RecordTabPart.At at = TAB_PART.at(tabParameterOf(part));
        try {
            return at == null ? null : Integer.valueOf(at.key());
        } catch (NumberFormatException notSite) {
            return null;
        }
    }

    /** @return the tab on one page, its filters kept */
    private static @NonNull BoundEndpoint<?> target(@NonNull String panel, int siteId,
                                                    @NonNull Map<String, String> filters) {
        BoundEndpoint<?> target = CmsRoutes.subpage(panel, HohenheimSlugs.SITES, siteId, HohenheimSlugs.Tab.ACCESS_LOG);
        for (ParameterDefinition<String> filter : FILTERS) {
            String value = filters.get(filter.getName());
            if (value != null) target = target.with(filter, value);
        }
        return target;
    }

    /** @return the tab's lines whose path holds this one, cut where a counted path stands an id as {@code :id} */
    static @NonNull String pathLines(@NonNull String panel, int siteId, @NonNull String path) {
        int id = path.indexOf(HitKeys.ID);
        return target(panel, siteId, Map.of(PATH.getName(), id < 0 ? path : path.substring(0, id))).toUrl();
    }

    /** @return the filter row as the reader left it */
    private static @NonNull AccessLogFilters filterRow(@NonNull String panel, int siteId,
                                                      @NonNull Map<String, String> filters, int page) {
        List<AccessLogFilters.Choice> statuses = new ArrayList<>();
        for (EnumField.EnumValue each : SiteAccessLogOperations.STATUS.getValues().values()) {
            statuses.add(new AccessLogFilters.Choice(each.getKey(), each.getLabel()));
        }
        List<AccessLogFilters.Choice> times = new ArrayList<>();
        for (When each : When.values()) {
            times.add(new AccessLogFilters.Choice(each.token,
                HohenheimMicrocopy.ACCESS_LOG.of("access_log_when_" + each.token)));
        }
        When when = When.of(filters.get(WHEN.getName()));
        Integer keepDays = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Logging.KEEP_DAYS);
        Microcopy keeping = keepDays == null || keepDays <= 0
            ? HohenheimMicrocopy.ACCESS_LOG.of("access_log_kept_always")
            : HohenheimMicrocopy.ACCESS_LOG.of("access_log_kept").withArg("days", keepDays);
        return new AccessLogFilters(
            CmsRoutes.subpage(panel, HohenheimSlugs.SITES, siteId, HohenheimSlugs.Tab.ACCESS_LOG).toUrl(),
            target(panel, siteId, filters).with(DOWNLOAD, "1").toUrl(),
            filters.getOrDefault(STATUS.getName(), ""), filters.getOrDefault(PATH.getName(), ""), when.token,
            when == When.RANGE ? filters.getOrDefault(FROM.getName(), "") : "",
            when == When.RANGE ? filters.getOrDefault(TO.getName(), "") : "",
            filters.getOrDefault(VISITOR.getName(), ""), page <= 1, statuses, times, keeping);
    }

    /** @return the page of lines the filters ask for, as the tab draws it */
    private static @NonNull AccessLogView view(@NonNull String panel, @NonNull Row site,
                                               @NonNull Map<String, String> filters, int requestedPage) {
        if (rangeUnset(filters)) {
            return new AccessLogView(List.of(), HohenheimMicrocopy.ACCESS_LOG.of("access_log_range_unset"), null, null);
        }
        Instant[] range = range(filters, Now.instant());
        Clamped read = read(filtered(site, filters, PAGE_SIZE), requestedPage);
        AccessLogReader.Page found = read.page();
        ZoneId zone = OfficeZone.office();
        boolean oneDay = range[0] != null && range[1] != null
            && range[0].atZone(zone).toLocalDate().equals(range[1].minusNanos(1).atZone(zone).toLocalDate());
        List<AccessLogView.Line> lines = new ArrayList<>();
        int position = found.offset();
        for (AccessLogLine line : found.lines()) {
            lines.add(line(++position, line, oneDay));
        }
        Microcopy summary;
        if (found.matched() == 0) {
            summary = HohenheimMicrocopy.ACCESS_LOG.of("access_log_none");
        } else {
            // The period the choice names, as the board's summary says it ("of 1,234 today"); a range names none.
            summary = HohenheimMicrocopy.ACCESS_LOG.of(found.complete() ? "access_log_summary"
                    : "access_log_summary_partial")
                .withArg("first", found.offset() + 1).withArg("last", position).withArg("total", found.matched())
                .withArg("period", When.of(filters.get(WHEN.getName())).token);
        }
        Microcopy stopped = found.complete() ? null : found.readBackTo() == null
            ? HohenheimMicrocopy.ACCESS_LOG.of("access_log_stopped_early")
            : HohenheimMicrocopy.ACCESS_LOG.of("access_log_stopped").withArg("at", found.readBackTo());
        int siteId = site.get(SiteModel.ID);
        BoundEndpoint<?> target = target(panel, siteId, filters);
        Pager pager = read.window().pageCount() > 1 ? Pager.of(read.window(), found.matched(), number -> number > 1
            ? target.with(CmsEndpoints.LIST_PAGE_PARAM, number).toUrl() : target.toUrl()) : null;
        return new AccessLogView(lines, summary, stopped, pager);
    }

    /** @return whether the filters pick a range without both its ends, which reads nothing until they are set */
    private static boolean rangeUnset(@NonNull Map<String, String> filters) {
        Instant[] range = range(filters, Now.instant());
        return When.of(filters.get(WHEN.getName())) == When.RANGE && (range[0] == null || range[1] == null);
    }

    /** One line in the tab's nine columns. */
    private static AccessLogView.@NonNull Line line(int position, @NonNull AccessLogLine line, boolean oneDay) {
        int status = line.status();
        BadgeVariant variant = switch (StatusClass.of(status)) {
            case SERVER_ERROR -> BadgeVariant.DESTRUCTIVE;
            case CLIENT_ERROR -> BadgeVariant.WARNING;
            case INFORMATIONAL, SUCCESS, REDIRECTION, OTHER -> null;
        };
        UserAgent agent = UserAgent.of(line.userAgent());
        String product = agent.productWithVersion();
        Microcopy browser;
        if (product != null && agent.platform() != null) {
            browser = HohenheimMicrocopy.ACCESS_LOG.of("access_log_browser").withArg("browser", product)
                .withArg("os", agent.platform());
        } else {
            browser = Microcopy.literal(Objects.requireNonNullElse(product != null ? product
                : agent.platform() != null ? agent.platform() : line.userAgent(), "-"));
        }
        return new AccessLogView.Line(String.valueOf(position),
            HohenheimMicrocopy.ACCESS_LOG.of(oneDay ? "access_log_time" : "access_log_date_time")
                .withArg("at", line.at()),
            line.visitor(), line.method(), line.path(), status, variant,
            HohenheimMicrocopy.ACCESS_LOG.of("access_log_size").withArg("bytes", line.bytes()),
            line.millis() == null ? null : HohenheimMicrocopy.ACCESS_LOG.of("access_log_took")
                .withArg("took", Duration.ofMillis(line.millis())),
            HitKeys.referrerHost(line.referrer()), browser,
            Objects.requireNonNullElse(line.userAgent(), ""));
    }

    /** The filtered lines as a file, oldest first, as the log holds them; none while a range misses an end. */
    private static @NonNull ActionResult<Object> download(@NonNull Row site, @NonNull Map<String, String> filters) {
        Instant end = range(filters, Now.instant())[1];
        AccessLogReader.Page found = rangeUnset(filters) ? null : AccessLogReader.read(AccessLog.livePath(),
            filtered(site, filters, DOWNLOAD_LINES));
        StringBuilder text = new StringBuilder();
        List<AccessLogLine> lines = found == null ? List.of() : found.lines();
        for (int i = lines.size() - 1; i >= 0; i--) {
            text.append(AccessLog.withoutSite(lines.get(i).raw())).append('\n');
        }
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        LocalDate day = (end == null ? Now.instant() : end).atZone(OfficeZone.office()).toLocalDate();
        // A file cut at the bound or at its line count says so in its name: a comment line would break its parsers.
        boolean whole = found != null && found.complete() && found.matched() == lines.size();
        return HandlerSupport.downloadStream("text/plain; charset=utf-8", site.get(SiteModel.SLUG) + "-access-" + day
            + (whole ? "" : "-incomplete") + ".log", new ByteArrayInputStream(body), body.length);
    }

    /** @return the reader's query of a filter row, on its first page */
    private static AccessLogReader.@NonNull Query filtered(@NonNull Row site, @NonNull Map<String, String> filters,
                                                          int limit) {
        Instant[] range = range(filters, Now.instant());
        return query(site, range[0], range[1], filters.get(STATUS.getName()), null, filters.get(PATH.getName()),
            filters.get(VISITOR.getName()), 0, limit);
    }

    /**
     * The tool's read: one page of the site's lines, each as the log holds it without the site it already names.
     *
     * @return the page, its position among every match and whether the whole range was read
     */
    private static SiteAccessLogOperations.@NonNull Lines lines(@NonNull Row site,
                                                               SiteAccessLogOperations.@NonNull ReadInput input) {
        AccessLogReader.Query query = query(site, input.from(), input.to(), input.status(), input.status_code(),
            input.path(), input.visitor(), 0, PAGE_SIZE);
        AccessLogReader.Page found = read(query, input.page() == null ? 1 : input.page()).page();
        List<String> lines = new ArrayList<>();
        for (AccessLogLine line : found.lines()) lines.add(AccessLog.withoutSite(line.raw()));
        return new SiteAccessLogOperations.Lines(lines, found.offset() + 1, found.matched(), found.complete(),
            found.readBackTo());
    }

    /** A read on its page, and the window that page lies in once the matches are counted. */
    private record Clamped(AccessLogReader.@NonNull Page page, @NonNull PageWindow window) {
    }

    /** @return the page asked for, or the last one when it asks past the matches */
    private static @NonNull Clamped read(AccessLogReader.@NonNull Query query, int requestedPage) {
        int asked = Math.max(1, requestedPage);
        AccessLogReader.Page found = AccessLogReader.read(AccessLog.livePath(),
            query.at(PageWindow.offsetFor(asked, query.limit())));
        PageWindow window = PageWindow.of(asked, found.matched(), query.limit(), PageWindow.OutOfRange.CLAMP);
        if (window.offset() != found.offset()) {
            found = AccessLogReader.read(AccessLog.livePath(), query.at(window.offset()));
        }
        return new Clamped(found, window);
    }

    /** @return the reader's query for the site and the filters */
    private static AccessLogReader.@NonNull Query query(@NonNull Row site, @Nullable Instant from, @Nullable Instant to,
                                                       @Nullable String status, @Nullable Integer statusCode,
                                                       @Nullable String path, @Nullable String visitor, int offset,
                                                       int limit) {
        Set<String> hostnames = new LinkedHashSet<>();
        for (Row domain : SiteParts.domainsOf(site)) {
            String hostname = domain.get(SiteDomainModel.HOSTNAME);
            if (hostname != null && SiteDomainModel.MATCH_EXACT.equals(domain.get(SiteDomainModel.MATCH_TYPE))) {
                hostnames.add(hostname.toLowerCase(Locale.ROOT));
            }
        }
        return new AccessLogReader.Query(site.get(SiteModel.ID), hostnames, from, to, statuses(status, statusCode),
            path, visitor, offset, limit);
    }

    /** @return which statuses to read: one code, one class, or all; an unknown class reads none (fail closed) */
    private static @NonNull IntPredicate statuses(@Nullable String status, @Nullable Integer statusCode) {
        IntPredicate byClass = status == null || status.isEmpty() ? code -> true
            : SiteAccessLogOperations.statusesOf(status);
        return statusCode == null ? byClass : byClass.and(code -> code == statusCode);
    }

    /** @return the time range a filter row asks for: [from, to], either end null for open */
    private static @Nullable Instant @NonNull [] range(@NonNull Map<String, String> filters, @NonNull Instant now) {
        ZoneId zone = OfficeZone.office();
        LocalDate today = now.atZone(zone).toLocalDate();
        return switch (When.of(filters.get(WHEN.getName()))) {
            case HOUR -> new Instant[]{now.minus(1, ChronoUnit.HOURS), now};
            case TODAY -> new Instant[]{today.atStartOfDay(zone).toInstant(), now};
            case YESTERDAY -> new Instant[]{today.minusDays(1).atStartOfDay(zone).toInstant(),
                today.atStartOfDay(zone).toInstant().minusNanos(1)};
            case RANGE -> new Instant[]{localTime(filters.get(FROM.getName()), zone),
                localTime(filters.get(TO.getName()), zone)};
        };
    }

    /** @return a date-time control's value in the office zone, null when it holds none that reads */
    private static @Nullable Instant localTime(@Nullable String value, @NonNull ZoneId zone) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value).atZone(zone).toInstant();
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    private static @NonNull ParameterDefinition<String> text(@NonNull String name) {
        return ParameterDefinition.builder(String.class).name(name).stringResolver(value -> value).build();
    }
}
