package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Handles git webhook requests on the proxy port.
 * Intercepted by SiteDispatcher before hostname-based routing.
 */
public class GitWebhookHandler {

    private static final String PREFIX = "/api/webhooks/git/";
    private static final HttpString X_HUB_SIGNATURE_256 = new HttpString("X-Hub-Signature-256");
    private static final HttpString X_GITLAB_TOKEN = new HttpString("X-Gitlab-Token");
    private static final HttpString X_GITEA_SIGNATURE = new HttpString("X-Gitea-Signature");

    /**
     * Check if the request is a POST to the webhook prefix.
     */
    public static boolean matches(HttpServerExchange exchange) {
        return "POST".equals(exchange.getRequestMethod().toString())
            && exchange.getRequestPath().startsWith(PREFIX);
    }

    /**
     * Handle a webhook request. Reads the raw body, validates the signature,
     * and enqueues a deploy if the branch matches.
     */
    public static void handle(HttpServerExchange exchange) {
        // Extract slug from path
        String path = exchange.getRequestPath();
        String slug = path.substring(PREFIX.length());
        if (slug.isEmpty() || slug.contains("/")) {
            sendJson(exchange, 404, "{\"error\":\"not found\"}");
            return;
        }

        // Read full body, then dispatch to a worker thread for the DB query
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            ex.dispatch(() -> {
                try {
                    processWebhook(ex, slug, body);
                } catch (Exception e) {
                    Blast.log("GIT WEBHOOK: error processing webhook for slug", slug, "-", e.getMessage());
                    sendJson(ex, 500, "{\"error\":\"internal error\"}");
                }
            });
        }, StandardCharsets.UTF_8);
    }

    private static void processWebhook(HttpServerExchange exchange, String slug, String body) {
        // Look up site by slug
        var siteModel = new SiteModel(HohenheimDatabase.datasource());
        Row site = siteModel.find()
            .where(SiteModel.SLUG.eq(slug))
            .where(SiteModel.DELETED_AT.isNull())
            .first();

        if (site == null) {
            sendJson(exchange, 404, "{\"error\":\"site not found\"}");
            return;
        }

        // Check that this is a git-provisioned site
        String source = site.get(SiteModel.SOURCE);
        if (!"git".equals(source)) {
            sendJson(exchange, 400, "{\"error\":\"site is not git-provisioned\"}");
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> sourceSettings = (Map<String, Object>) site.get(SiteModel.SOURCE_SETTINGS);
        if (sourceSettings == null) {
            sendJson(exchange, 400, "{\"error\":\"no source settings\"}");
            return;
        }

        String webhookSecret = (String) sourceSettings.get("webhook_secret");
        if (webhookSecret == null || webhookSecret.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"no webhook secret configured\"}");
            return;
        }

        // Validate signature
        if (!validateSignature(exchange, body, webhookSecret)) {
            sendJson(exchange, 401, "{\"error\":\"invalid signature\"}");
            return;
        }

        // Check auto_deploy setting
        if (!Boolean.TRUE.equals(sourceSettings.get("auto_deploy"))) {
            sendJson(exchange, 200, "{\"status\":\"ignored\",\"reason\":\"auto_deploy disabled\"}");
            return;
        }

        // Find the handler and enqueue a deploy
        int siteId = site.get(SiteModel.ID);
        var proxy = ServerMain.getProxyServer();
        if (proxy == null) {
            sendJson(exchange, 503, "{\"error\":\"proxy not running\"}");
            return;
        }

        SiteRequestHandler handler = proxy.getDispatcher().findHandlerBySiteId(siteId);
        if (handler instanceof GitSiteRequestHandler gitHandler) {
            gitHandler.enqueueDeploy("webhook");
            Blast.log("GIT WEBHOOK: deploy queued for site", slug, "(id:", siteId + ")");
            sendJson(exchange, 200, "{\"status\":\"queued\"}");
        } else {
            sendJson(exchange, 404, "{\"error\":\"handler not found\"}");
        }
    }

    private static boolean validateSignature(HttpServerExchange exchange, String body, String secret) {
        // GitHub: X-Hub-Signature-256
        String hubSig = exchange.getRequestHeaders().getFirst(X_HUB_SIGNATURE_256);
        if (hubSig != null) {
            String expected = "sha256=" + hmacSha256(secret, body);
            return secureEquals(expected, hubSig);
        }

        // Gitea: X-Gitea-Signature
        String giteaSig = exchange.getRequestHeaders().getFirst(X_GITEA_SIGNATURE);
        if (giteaSig != null) {
            String expected = hmacSha256(secret, body);
            return secureEquals(expected, giteaSig);
        }

        // GitLab: X-Gitlab-Token (direct comparison, not HMAC)
        String gitlabToken = exchange.getRequestHeaders().getFirst(X_GITLAB_TOKEN);
        if (gitlabToken != null) {
            return secureEquals(secret, gitlabToken);
        }

        // No recognized signature header -- reject
        return false;
    }

    private static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private static boolean secureEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void sendJson(HttpServerExchange exchange, int status, String json) {
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(json);
    }
}
