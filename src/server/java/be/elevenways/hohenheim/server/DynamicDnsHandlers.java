package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.zenit.common.conduit.Conduit;

/**
 * Dynamic DNS: the public dyndns2 update endpoint (/nic/update). The update token
 * travels in HTTP Basic auth ONLY (password, or username as a fallback); the service
 * refuses anything the token does not unlock.
 *
 * AIDEV-NOTE: a ?token= query fallback was DROPPED (2026-08-10). A DNS-write
 * credential in the query string lands in access logs, proxy logs and the
 * Referer of anything the response links to -- and dyndns2 clients (ddclient,
 * routers) present the token as the Basic password anyway, which is exactly what
 * the record's help text documents. Do not reintroduce the query fallback.
 */
final class DynamicDnsHandlers {

    private DynamicDnsHandlers() {
    }

    static void init() {
        DynamicDnsService service = new DynamicDnsService(DnsZoneStore.INSTANCE);
        HohenheimEndpoints.DYNDNS_UPDATE.setHandler(conduit -> {
            String token = dyndnsToken(conduit);
            String hostname = conduit.getQueryParam("hostname");
            String myip = conduit.getQueryParam("myip");
            String callerIp = conduit.getRemoteIp();
            DynamicDnsService.UpdateResult result = service.update(token, hostname, myip, callerIp);
            conduit.endWithContentType("text/plain", result.wire());
            return null;
        });
    }

    /** The update token from HTTP Basic auth (password preferred, username fallback); no query fallback. */
    private static @org.checkerframework.checker.nullness.qual.Nullable String dyndnsToken(Conduit conduit) {
        String authorization = conduit.getRequestHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded = new String(java.util.Base64.getDecoder()
                    .decode(authorization.substring(6).trim()), java.nio.charset.StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                String user = colon >= 0 ? decoded.substring(0, colon) : decoded;
                String pass = colon >= 0 ? decoded.substring(colon + 1) : "";
                // dyndns2 puts the credential in the password; tolerate clients that
                // send it as the username with an empty password.
                if (pass.startsWith(DynamicDnsService.TOKEN_MARKER)) {
                    return pass;
                }
                if (user.startsWith(DynamicDnsService.TOKEN_MARKER)) {
                    return user;
                }
            }
            catch (IllegalArgumentException ignored) {
                // Malformed base64: no credential to use.
            }
        }
        // Deliberately NO ?token= fallback: a DNS-write credential must never ride the query
        // string (access/proxy logs, Referer). See the class note above.
        return null;
    }
}
