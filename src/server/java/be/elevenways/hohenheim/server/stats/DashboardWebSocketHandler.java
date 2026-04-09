package be.elevenways.hohenheim.server.stats;

import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.zenit.common.websocket.WebSocketHandler;
import be.elevenways.zenit.common.websocket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * WebSocket handler for the live dashboard.
 * Sends an initial data dump, then streams real-time stats and activity events.
 * Equivalent to the original Node.js 'dashboardlive' linkup.
 */
public class DashboardWebSocketHandler implements WebSocketHandler, StatsCollector.StatsListener {

    private final WebSocketSession session;
    private final StatsCollector collector;

    public DashboardWebSocketHandler(WebSocketSession session) {
        this.session = session;
        this.collector = ServerMain.getStatsCollector();
    }

    @Override
    public void onOpen() {
        if (collector == null) return;

        // Send initial history and activity log
        sendInitData();

        // Subscribe to live updates
        collector.addListener(this);
    }

    @Override
    public void onClose(int code, String reason) {
        if (collector != null) {
            collector.removeListener(this);
        }
    }

    @Override
    public void onStats(StatsCollector.StatsSnapshot snapshot) {
        if (!session.isOpen()) return;
        session.sendText(toJson(Map.of(
            "type", "stats",
            "timestamp", snapshot.timestamp(),
            "hitsPerSecond", snapshot.hitsPerSecond(),
            "connectionsPerSecond", snapshot.connectionsPerSecond(),
            "totalHits", snapshot.totalHits(),
            "totalConnections", snapshot.totalConnections(),
            "incomingBytes", snapshot.incomingBytesDelta(),
            "outgoingBytes", snapshot.outgoingBytesDelta()
        )));
    }

    @Override
    public void onActivity(StatsCollector.ActivityEvent event) {
        if (!session.isOpen()) return;
        session.sendText(toJson(Map.of(
            "type", "activity",
            "timestamp", event.timestamp(),
            "eventType", event.type(),
            "message", event.message()
        )));
    }

    private void sendInitData() {
        List<StatsCollector.StatsSnapshot> history = collector.getHistory();
        List<StatsCollector.ActivityEvent> activity = collector.getActivityLog();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"init\",\"history\":[");
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) sb.append(",");
            var s = history.get(i);
            sb.append("{\"t\":").append(s.timestamp())
              .append(",\"h\":").append(String.format("%.1f", s.hitsPerSecond()))
              .append(",\"c\":").append(String.format("%.1f", s.connectionsPerSecond()))
              .append(",\"i\":").append(s.incomingBytesDelta())
              .append(",\"o\":").append(s.outgoingBytesDelta())
              .append("}");
        }
        sb.append("],\"activity\":[");
        for (int i = 0; i < activity.size(); i++) {
            if (i > 0) sb.append(",");
            var a = activity.get(i);
            sb.append("{\"t\":").append(a.timestamp())
              .append(",\"type\":\"").append(escapeJson(a.type()))
              .append("\",\"msg\":\"").append(escapeJson(a.message()))
              .append("\"}");
        }
        sb.append("]}");
        session.sendText(sb.toString());
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            Object val = entry.getValue();
            if (val instanceof Number) {
                if (val instanceof Double d) {
                    sb.append(String.format("%.2f", d));
                } else {
                    sb.append(val);
                }
            } else if (val instanceof String s) {
                sb.append("\"").append(escapeJson(s)).append("\"");
            } else {
                sb.append("\"").append(val).append("\"");
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
