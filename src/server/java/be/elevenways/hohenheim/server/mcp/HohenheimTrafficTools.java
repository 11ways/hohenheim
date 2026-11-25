package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.server.cms.TrafficReads;
import be.elevenways.hohenheim.server.proxy.AccessLogReader;
import be.elevenways.hohenheim.site.SiteAccessLogOperations;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import be.elevenways.zenit.common.data.DataSeries;
import be.elevenways.zenit.common.data.DataTable;
import be.elevenways.zenit.common.data.RecordFacet;
import be.elevenways.zenit.common.time.OfficeZone;
import be.elevenways.zenit.traffic.common.StatusClass;
import be.elevenways.zenit.traffic.server.TrafficQuery;
import be.elevenways.zenit.traffic.server.TrafficTotals;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Hohenheim's hand-declared tools over a site's traffic and a zone's questions: the access log read and the counted
 * totals; {@link HohenheimTools} offers them beside its own.
 *
 * AIDEV-NOTE: declarations only, as in HohenheimTools: who may read and what is read are the operations'
 * (SiteAccessLogOperations, TrafficReads), the panel reading through the same operations.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimTrafficTools {

    private HohenheimTrafficTools() {
    }

    /** @return every tool of this class, in the order an assistant is offered them */
    static @NonNull List<McpHostTool> declared() {
        McpHostTool accessLog = McpCodeTool.builder("access_log_read", HohenheimTools.words("mcp_tool_access_log_read")
                    .withArg("lines", SiteAccessLogOperations.PAGE_LINES).withArg("bound", AccessLogReader.SCAN_BYTES),
                McpHostTool.Annotations.ofReadOnly())
            .subject("site", HohenheimTools.words("mcp_code_access_log_site"))
            .route(SiteAccessLogOperations.READ, HohenheimTrafficTools::lines)
            .build();
        // A call naming no site reads every site at once, an administrator's read.
        McpHostTool traffic = McpCodeTool.builder("traffic_read", HohenheimTools.words("mcp_tool_traffic_read")
                    .withArg("rows", TrafficReads.TOP_ROWS), McpHostTool.Annotations.ofReadOnly())
            .subject("site", HohenheimTools.words("mcp_code_traffic_site"))
            .route(TrafficReads.READ_SITE, HohenheimTrafficTools::siteTraffic)
            .routeWithoutRecord(TrafficReads.READ_SERVER, HohenheimTrafficTools::siteTraffic)
            .build();
        McpHostTool zoneQueries = McpCodeTool.builder("dns_zone_queries_read",
                HohenheimTools.words("mcp_tool_dns_zone_queries_read").withArg("rows", TrafficReads.TOP_ROWS),
                McpHostTool.Annotations.ofReadOnly())
            .subject("dns_zone", HohenheimTools.words("mcp_code_dns_zone_queries"))
            .route(TrafficReads.READ_ZONE, HohenheimTrafficTools::zoneQuestions)
            .build();
        return List.of(accessLog, traffic, zoneQueries);
    }

    /** One page of a site's lines: which of how many first, then the lines, which visitors wrote, as untrusted text. */
    static @NonNull McpResponse lines(@NonNull McpToolContext context,
                                      SiteAccessLogOperations.@Nullable Lines lines) {
        McpResponse response = McpResponse.create();
        if (lines == null || lines.lines().isEmpty()) {
            response.line(lines != null && lines.matched() > 0 ? "lines: past the last page of " + lines.matched()
                : "lines: none match");
        } else {
            response.line("lines " + lines.first() + " to " + (lines.first() + lines.lines().size() - 1) + " of "
                + (lines.complete() ? "" : "at least ") + lines.matched() + ", newest first");
            response.untrusted("lines", String.join("\n", lines.lines()));
        }
        if (lines != null && !lines.complete()) {
            response.line("stopped: the read reached its bound at " + lines.readBackTo()
                + "; give from and to for older lines");
        }
        return response;
    }

    /** A site's counted traffic, or every site's at once, in a site's words. */
    static @NonNull McpResponse siteTraffic(@NonNull McpToolContext context, TrafficReads.@Nullable Read read) {
        return counted(read, "requests", "visitors");
    }

    /** A zone's counted questions, in a zone's words. */
    static @NonNull McpResponse zoneQuestions(@NonNull McpToolContext context, TrafficReads.@Nullable Read read) {
        return counted(read, "questions", "resolvers");
    }

    /**
     * A read of counted traffic: the span in office time, its totals against the span before, one line per step with
     * what was asked per status class, then the top table asked for, whose keys visitors wrote, as untrusted text.
     *
     * @param asked   the word for what was counted
     * @param askers  the word for who asked
     */
    private static @NonNull McpResponse counted(TrafficReads.@Nullable Read read, @NonNull String asked,
                                                @NonNull String askers) {
        McpResponse response = McpResponse.create();
        if (read == null) {
            return response.line("traffic: nothing read");
        }
        TrafficQuery query = read.query();
        TrafficTotals totals = read.totals();
        response.line("span: " + local(query.from()) + " to " + local(query.to()) + " office time, per "
            + query.granularity().storageKey());
        response.line(asked + ": " + totals.requests() + "; the span before, as long: " + read.requestsBefore());
        response.line("sent: " + totals.bytesSent() + " bytes; received: " + totals.bytesReceived() + " bytes");
        response.line(askers + ": " + totals.visitors());
        List<String> classes = new ArrayList<>();
        for (StatusClass statusClass : StatusClass.values()) {
            long count = totals.statusClasses().getOrDefault(statusClass, 0L);
            if (count > 0) classes.add(statusClass.storageKey() + " " + count);
        }
        response.line("answered: " + (classes.isEmpty() ? "none" : String.join(", ", classes)));
        response.line(totals.medianMillis() == null ? "response time: none"
            : "response time: median " + millis(totals.medianMillis()) + " ms, slowest 5 percent from "
                + millis(totals.slowestMillis()) + " ms");
        List<DataSeries> series = read.steps().series();
        response.line("steps, each its start and requests per status class:");
        int steps = series.isEmpty() ? 0 : series.getFirst().buckets().size();
        for (int step = 0; step < steps; step++) {
            List<String> counts = new ArrayList<>();
            for (DataSeries each : series) {
                Double value = each.buckets().get(step).value();
                if (value != null && value > 0) counts.add(each.key() + " " + Math.round(value));
            }
            response.line(series.getFirst().buckets().get(step).key() + ": "
                + (counts.isEmpty() ? "0" : String.join(", ", counts)));
        }
        TrafficReads.Top top = read.top();
        DataTable table = read.table();
        if (top != null && table != null) {
            boolean timed = top.table().timed();
            response.line("top " + top.token() + ", each its requests, bytes sent" + (timed ? ", median and slowest 5"
                + " percent in ms" : "") + ", then its key; the last line holds every one not listed:");
            List<String> rows = new ArrayList<>();
            for (DataTable.Line line : table.lines()) {
                StringBuilder row = new StringBuilder().append(line.records()).append(' ')
                    .append(Math.round(orZero(line.value("bytes_sent").number())));
                if (timed && !RecordFacet.MORE_SLUG.equals(line.groups().getLast().key())) {
                    row.append(' ').append(millis(line.value("median_ms").number())).append(' ')
                        .append(millis(line.value("slowest_ms").number()));
                }
                rows.add(row.append(' ').append(line.groups().getLast().label()).toString());
            }
            response.untrusted(top.token(), rows.isEmpty() ? "none" : String.join("\n", rows));
        }
        return response;
    }

    /** @return the moment as office time to the minute, as the steps are keyed */
    private static @NonNull String local(@NonNull Instant at) {
        return at.atZone(OfficeZone.office()).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).toString();
    }

    /** @return milliseconds as whole ones, "-" for none */
    private static @NonNull String millis(@Nullable Double value) {
        return value == null ? "-" : String.valueOf(Math.round(value));
    }

    private static double orZero(@Nullable Double value) {
        return value == null ? 0 : value;
    }
}
