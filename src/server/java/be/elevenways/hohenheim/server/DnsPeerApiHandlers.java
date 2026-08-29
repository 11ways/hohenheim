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

            Row peer = DnsFederationKeys.install(peerName, keyName, algorithm, secret);
            ActivityLog.record(Models.get(DnsPeerModel.class), peer.get(DnsPeerModel.ID),
                "updated", keyName);
            Blast.log("DNS: transfer key", keyName, "installed for peer",
                peer.get(DnsPeerModel.NAME));
            return new JsonResult<Object>(new DnsPeerKeyResponse("ok", keyName,
                peer.get(DnsPeerModel.NAME)));
        });
    }

    private static ActionResult<Object> refusal(Conduit conduit, String error) {
        conduit.setResponseStatus(422);
        return new JsonResult<Object>(new DnsApiErrorResponse(error));
    }

    private static String trimmed(String value) {
        return value != null ? value.trim() : "";
    }
}
