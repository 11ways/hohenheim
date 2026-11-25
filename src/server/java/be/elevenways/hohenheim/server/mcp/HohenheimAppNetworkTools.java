package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.AppNetworkReads;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import be.elevenways.zenit.common.data.DataSeries;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.traffic.server.TrafficQuery;
import be.elevenways.zenit.traffic.server.TrafficTotals;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hohenheim's hand-declared tool over an app's network use; {@link HohenheimTools} offers it beside its own.
 *
 * AIDEV-NOTE: a declaration only, as in HohenheimTools: who may read and what is read are the operation's
 * (AppNetworkReads), the Network tab reading through the same read.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimAppNetworkTools {

    private HohenheimAppNetworkTools() {
    }

    /** @return every tool of this class, in the order an assistant is offered them */
    static @NonNull List<McpHostTool> declared() {
        McpHostTool network = McpCodeTool.builder("app_network_read",
                HohenheimTools.words("mcp_tool_app_network_read"), McpHostTool.Annotations.ofReadOnly())
            .code(HohenheimTools.words("mcp_code_app_network_read"))
            .route(AppNetworkReads.READ, HohenheimAppNetworkTools::network)
            .build();
        return List.of(network);
    }

    /**
     * An app's network use: the span in office time, its bytes against the span before, one line per step with what
     * it received and sent, then each of its sites with the requests it answered and the bytes it sent visitors.
     */
    static @NonNull McpResponse network(@NonNull McpToolContext context, AppNetworkReads.@Nullable Read read) {
        McpResponse response = McpResponse.create();
        if (read == null) {
            return response.line("network: nothing read");
        }
        TrafficQuery query = read.query();
        TrafficTotals totals = read.totals();
        TrafficTotals before = read.before();
        response.line("span: " + HohenheimTrafficTools.local(query.from()) + " to "
            + HohenheimTrafficTools.local(query.to()) + " office time, per " + query.granularity().storageKey());
        response.line("received: " + totals.bytesReceived() + " bytes; sent: " + totals.bytesSent()
            + " bytes; the span before, as long: received " + before.bytesReceived() + ", sent "
            + before.bytesSent());
        if (!read.counted()) {
            response.line("nothing counted in this span: the app's counters are read every 5 minutes while it runs,"
                + " every second while someone watches its Metrics tab");
        }
        List<DataSeries> series = read.steps().series();
        response.line("steps, each its start, then bytes received and sent:");
        int steps = series.isEmpty() ? 0 : series.getFirst().buckets().size();
        for (int step = 0; step < steps; step++) {
            List<String> amounts = new ArrayList<>();
            for (DataSeries each : series) {
                Double value = each.buckets().get(step).value();
                amounts.add(String.valueOf(value == null ? 0 : Math.round(value)));
            }
            response.line(series.getFirst().buckets().get(step).key() + ": " + String.join(" ", amounts));
        }
        List<String> keys = new ArrayList<>();
        for (AppNetworkReads.SiteTraffic site : read.sites()) {
            keys.add(String.valueOf(site.siteId()));
        }
        Map<String, String> codes = keys.isEmpty() ? Map.of()
            : RecordCodes.codes(SiteModel.MODEL_ID, keys, context.access());
        if (read.sites().isEmpty()) {
            response.line("sites: none points at this app");
        } else {
            response.line("sites, each its code, requests answered and bytes sent to visitors in the span:");
            for (AppNetworkReads.SiteTraffic site : read.sites()) {
                String code = codes.get(String.valueOf(site.siteId()));
                response.line((code != null ? code : "a site you do not manage") + " " + site.requests() + " "
                    + site.bytesSent());
            }
        }
        response.line("to visitors: " + read.toVisitors() + " bytes through the proxy");
        return response;
    }
}
