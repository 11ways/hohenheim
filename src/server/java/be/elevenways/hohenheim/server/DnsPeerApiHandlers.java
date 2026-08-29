package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.dns.DnsApiErrorResponse;
import be.elevenways.hohenheim.dns.DnsPeerKeyResponse;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.server.dns.DnsFederationKeys;
import be.elevenways.hohenheim.server.dns.DnsTsig;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.auth.model.ApiKeyPrincipal;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.JsonResult;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.TSIG;

import java.util.Map;

/**
 * The receiving half of transfer-key negotiation: a peer that minted a shared TSIG
 * key installs its half here. API-key principals only.
 *
 * AIDEV-NOTE: nothing in this file logs, echoes or toasts the secret. Both sides STORE
 * the key, so there is no human who needs to read it -- which is why there is no
 * disclosure lane here at all, not even a one-time reveal.
 */
final class DnsPeerApiHandlers {

    private DnsPeerApiHandlers() {
    }

    static void init() {
        HohenheimEndpoints.API_DNS_PEER_KEY.setHandler(conduit -> {
            // Same gate as the record API: the endpoint permission decides WHO may call,
            // this decides that it is a key and not an ambient browser session.
            if (!(conduit.getAttribute(ConduitAttributes.PRINCIPAL) instanceof ApiKeyPrincipal)) {
                conduit.forbidden();
                return null;
            }

            Map<String, String> form = HandlerSupport.formMap(conduit);
            String peerName = trimmed(form.get("peer"));
            String keyName = trimmed(form.get("key_name"));
            String algorithm = trimmed(form.get("algorithm"));
            String secret = trimmed(form.get("secret"));

            if (peerName.isEmpty() || keyName.isEmpty() || secret.isEmpty()
                || !DnsTsig.isSupportedAlgorithm(algorithm)) {
                return refusal(conduit, "validation");
            }
            try {
                // Proves the material is a usable key before it is stored: a secret that
                // is not valid base64 would only fail at the first transfer, hours later.
                DnsTsig.canonicalKeyName(keyName);
                new TSIG(DnsTsig.algorithmName(algorithm),
                    DnsTsig.canonicalKeyName(keyName), secret);
            }
            catch (RuntimeException unusable) {
                return refusal(conduit, "validation");
            }

            DnsFederationKeys.Installation installed = DnsFederationKeys.install(peerName,
                keyName, algorithm, secret,
                announcedHost(trimmed(form.get("transfer_host")), conduit),
                port(form.get("transfer_port")));
            Row peer = installed.peer();
            ActivityLog.record(Models.get(DnsPeerModel.class), peer.get(DnsPeerModel.ID),
                "updated", keyName);
            Blast.log("DNS: transfer key", keyName, "installed for peer",
                peer.get(DnsPeerModel.NAME), "transferring from", installed.transferHost());
            return new JsonResult<Object>(new DnsPeerKeyResponse("ok", keyName,
                peer.get(DnsPeerModel.NAME), installed.transferHost(),
                installed.transferPort(), installed.transferKept()));
        });
    }

    /**
     * The address the caller transfers from: what it announced, else the address its own
     * connection arrived from.
     *
     * AIDEV-NOTE: the connection peer is the HONEST fallback, and it is the only workable
     * one for an announcer behind NAT -- its own listen address is then a private address
     * this side can never reach. An announced host with whitespace in it is not a host, so
     * it degrades to the connection peer rather than being stored.
     */
    private static @Nullable String announcedHost(@NonNull String announced, @NonNull Conduit conduit) {
        if (!announced.isEmpty() && announced.indexOf(' ') < 0 && announced.length() <= 253) {
            return announced;
        }
        String remote = trimmed(conduit.getRemoteIp());
        return remote.isEmpty() ? null : remote;
    }

    /** @return the announced transfer port, or null when absent or out of range */
    private static @Nullable Integer port(@Nullable String raw) {
        String value = trimmed(raw);
        if (value.isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 && parsed < 65536 ? parsed : null;
        }
        catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static ActionResult<Object> refusal(Conduit conduit, String error) {
        conduit.setResponseStatus(422);
        return new JsonResult<Object>(new DnsApiErrorResponse(error));
    }

    private static String trimmed(String value) {
        return value != null ? value.trim() : "";
    }
}
