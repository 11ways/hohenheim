package be.elevenways.hohenheim.server.api;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.server.cms.SignedLinkSecrets;
import be.elevenways.hohenheim.site.SignedLinks;
import be.elevenways.zenit.common.operation.ZenitPlacementSurface;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.body.FormSubmissionRawValues;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The PaaS API's protected-path lane: one signed link to a file under a path protected with one, for a customer's own
 * script holding an API key.
 *
 * AIDEV-NOTE: the call is the operation the path's Signed link tab and site_link_make run (SignedLinks.MAKE_LINK):
 * its authorizer decides who may and its refusals name {@code path} and {@code minutes}. A link is no stored secret,
 * so the answer carries it; the path's secrets are never read here.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class ProtectedPathApi {

    private ProtectedPathApi() {
    }

    static void init() {
        HohenheimEndpoints.API_V1_PROTECTED_PATH_LINK.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Integer pathId = conduit.getParameter(HohenheimEndpoints.PROTECTED_PATH_ID);
            Row path = pathId == null ? null : Models.get(ProtectedPathModel.class).findById(pathId);
            if (path == null) {
                conduit.notFound();
                return null;
            }
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put(SignedLinks.PATH.getName(), form.get(SignedLinks.PATH.getName()));
            if (form.get(SignedLinks.MINUTES.getName()) != null) {
                input.put(SignedLinks.MINUTES.getName(), form.get(SignedLinks.MINUTES.getName()));
            }
            String link;
            try {
                link = OperationPipeline.invoke(OperationRequest.of(SignedLinks.MAKE_LINK,
                        ZenitPlacementSurface.HTTP_API)
                    .caller(ctx)
                    .subjects(List.of(path))
                    .form(input)).value();
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (DomainRefusal refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            String made = Objects.requireNonNull(link, "a made link");
            Instant until = Objects.requireNonNull(SignedLinkSecrets.expiresOf(made), "a made link carries its expiry");
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("link", made);
            answer.put("expires", until.getEpochSecond());
            answer.put("expires_at", until.toString());
            return ApiConduits.json(answer);
        });
    }
}
