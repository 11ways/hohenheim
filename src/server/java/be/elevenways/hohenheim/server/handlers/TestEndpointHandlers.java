package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.zenit.common.result.JsonResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Test endpoints for verifying error handling behavior.
 */
public final class TestEndpointHandlers {

    private TestEndpointHandlers() {
    }

    public static void init() {
        HohenheimEndpoints.TEST_ERROR.setHandler(conduit -> {
            throw new RuntimeException("Deliberate test error for error handling verification");
        });

        // Health check endpoint (no auth required -- handled in middleware)
        HohenheimEndpoints.HEALTH.setHandler(conduit -> {
            var proxy = ServerMain.getProxyServer();
            Map<String, Object> status = new HashMap<>();
            status.put("status", "ok");
            status.put("httpState", proxy != null ? proxy.getHttpState().name() : "UNKNOWN");
            status.put("httpsState", proxy != null ? proxy.getHttpsState().name() : "UNKNOWN");
            return new JsonResult<>(status);
        });
    }
}
