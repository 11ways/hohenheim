package be.elevenways.hohenheim.server.proxy;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.auth.server.PasswordHasher;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Enforces a route's access list: IP allow/deny rules and HTTP basic auth, combined by the
 * list's satisfy mode.
 */
final class AccessListGate {

    private AccessListGate() {}

    /**
     * Check the access list. Returns true if the request is allowed, false if blocked.
     * When blocked, the response (401 or 403) is already sent.
     */
    static boolean allows(HttpServerExchange exchange, RouteEntry entry, String clientIp) {
        boolean ipAllowed = checkIpAccess(entry, clientIp);
        boolean authPassed = checkBasicAuth(exchange, entry);

        boolean hasIpRules = entry.allowedIps != null || entry.deniedIps != null;
        boolean hasAuth = entry.basicAuthUser != null;

        if ("all".equals(entry.accessListSatisfy)) {
            // Both must pass (if configured)
            if (hasIpRules && !ipAllowed) {
                exchange.setStatusCode(403);
                exchange.getResponseSender().send("Forbidden");
                return false;
            }
            if (hasAuth && !authPassed) {
                sendAuthChallenge(exchange, entry);
                return false;
            }
        } else {
            // "any": pass if either passes (or if only one is configured)
            if (hasIpRules && hasAuth) {
                if (!ipAllowed && !authPassed) {
                    sendAuthChallenge(exchange, entry);
                    return false;
                }
            } else if (hasIpRules && !ipAllowed) {
                exchange.setStatusCode(403);
                exchange.getResponseSender().send("Forbidden");
                return false;
            } else if (hasAuth && !authPassed) {
                sendAuthChallenge(exchange, entry);
                return false;
            }
        }

        return true;
    }

    private static boolean checkIpAccess(RouteEntry entry, String clientIp) {
        // Deny takes priority
        if (entry.deniedIps != null) {
            for (String denied : entry.deniedIps) {
                if (matchesIp(clientIp, denied)) return false;
            }
        }
        // If allow list exists, IP must be in it
        if (entry.allowedIps != null) {
            for (String allowed : entry.allowedIps) {
                if (matchesIp(clientIp, allowed)) return true;
            }
            return false; // Not in allow list
        }
        return true; // No allow list = allow all
    }

    private static boolean matchesIp(String clientIp, String rule) {
        if (rule == null || rule.isEmpty()) return false;

        if (rule.contains("/")) {
            // CIDR notation
            try {
                String[] parts = rule.split("/");
                byte[] ruleAddr = InetAddress.getByName(parts[0]).getAddress();
                byte[] clientAddr = InetAddress.getByName(clientIp).getAddress();
                int prefixLen = Integer.parseInt(parts[1]);

                if (ruleAddr.length != clientAddr.length) return false;

                int fullBytes = prefixLen / 8;
                int remainBits = prefixLen % 8;

                for (int i = 0; i < fullBytes; i++) {
                    if (ruleAddr[i] != clientAddr[i]) return false;
                }
                if (remainBits > 0) {
                    int mask = 0xFF << (8 - remainBits);
                    if ((ruleAddr[fullBytes] & mask) != (clientAddr[fullBytes] & mask)) return false;
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        return clientIp.equals(rule);
    }

    private static boolean checkBasicAuth(HttpServerExchange exchange, RouteEntry entry) {
        if (entry.basicAuthUser == null) return true;

        String authHeader = exchange.getRequestHeaders().getFirst(Headers.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Basic ")) return false;

        try {
            String decoded = new String(Base64.getDecoder().decode(authHeader.substring(6)));
            int colon = decoded.indexOf(':');
            if (colon < 0) return false;
            String user = decoded.substring(0, colon);
            String pass = decoded.substring(colon + 1);
            boolean passwordMatches = verifyBasicAuthPassword(pass, entry.basicAuthPass, entry.siteName);
            return MessageDigest.isEqual(
                    entry.basicAuthUser.getBytes(StandardCharsets.UTF_8),
                    user.getBytes(StandardCharsets.UTF_8))
                && passwordMatches;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @return true only when the stored value is an argon2 hash that verifies the presented
     *         password; any other non-null stored value is refused LOUDLY, never compared
     */
    // AIDEV-NOTE: this used to fall back to a constant-time PLAINTEXT compare for stored
    // values without the $argon2 prefix -- security theater that silently accepted an
    // unhashed column. AccessListResource hashes on every save and nothing was ever deployed,
    // so a non-argon2 stored value is operator/data corruption and must fail closed and loud.
    static boolean verifyBasicAuthPassword(String presented, String stored, String siteName) {
        if (stored == null) {
            return false;
        }
        if (!stored.startsWith("$argon2")) {
            Blast.log("SiteDispatcher: stored basic-auth password for site", siteName,
                "is not an argon2 hash; refusing authentication. Re-save the access list to hash it.");
            return false;
        }
        return PasswordHasher.verify(presented, stored);
    }

    private static void sendAuthChallenge(HttpServerExchange exchange, RouteEntry entry) {
        String realm = entry.siteName != null && !entry.siteName.isBlank()
            ? entry.siteName.replace("\"", "")
            : "Restricted";
        exchange.setStatusCode(401);
        exchange.getResponseHeaders().put(new HttpString("WWW-Authenticate"), "Basic realm=\"" + realm + "\"");
        exchange.getResponseSender().send("Unauthorized");
    }
}
