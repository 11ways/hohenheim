package be.elevenways.hohenheim.server.api;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.DomainParts;
import be.elevenways.hohenheim.server.cms.SiteParts;
import be.elevenways.zenit.cms.common.access.AccessRefusedException;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.body.FormSubmissionRawValues;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The PaaS API's site and domain WRITE lane: create and delete a site, add and remove
 * its hostnames, through the very resource pipeline the admin form posts to.
 *
 * AIDEV-NOTE: there is no model write in this class, on purpose. Every mutation goes
 * through zenit-cms {@code ResourceWrites} over the admin panel's own site and domain
 * entries ({@link SiteParts#admin()}, {@link DomainParts#admin()}): the site verbs are
 * domain operations ({@code SiteWrites}), so the input coercion, authorizers and
 * availability are the pipeline's, and the route claim, hostname canonicalization, tenant
 * column freeze and proxy reload are the model write hooks'. Authorization is decided
 * exactly where the panels decide it: sites are created and deleted only by installation
 * administration (the /manage twin does neither), so those two verbs demand
 * {@link HohenheimAccess#isAdmin}; domain rows are a tenant's own affordance on a site they
 * manage, so those ride the same {@code manage} walk the read lane uses and let
 * {@code TenantWrites} refuse the columns a tenant may not set.
 *
 * AIDEV-NOTE: the /api/v1 answers are a frozen wire (stage 4 contract 10): a site verb's
 * pipeline refusal answers exactly what the row lane answered, see {@link #refused}.
 */
public final class SiteApi {

    private SiteApi() {
    }

    static void init() {
        HohenheimEndpoints.API_V1_SITE_CREATE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireAdminKey(conduit);
            if (ctx == null) {
                return null;
            }
            try {
                Panel panel = ApiConduits.adminPanel();
                int siteId = (Integer) ResourceWrites.create(panel, entryIn(panel, HohenheimSlugs.SITES),
                    FormSubmissionRawValues.fromConduit(conduit), ctx);
                Row created = Objects.requireNonNull(
                    Models.get(SiteModel.class).findById(siteId));
                ActivityLog.record(Models.get(SiteModel.class), siteId, ZenitActivityAction.CREATE,
                    created.get(SiteModel.NAME));
                return ApiConduits.json(PaasApi.siteProjection(created, true));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (DomainRefusal refused) {
                return refused(conduit, refused);
            } catch (AccessRefusedException refused) {
                conduit.forbidden();
                return null;
            }
        });

        HohenheimEndpoints.API_V1_SITE_DELETE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireAdminKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row site = PaasApi.visibleSite(conduit, ctx);
            if (site == null) {
                return null;
            }
            try {
                // The delete operation is the soft delete the admin form runs, previews
                // reclaimed and deleted_at stamped by the site's SoftDeleteBehaviour; the
                // offered-but-dead lockout (the site serving this very panel) is its
                // availability and refuses here exactly as it did on the row lane.
                Panel panel = ApiConduits.adminPanel();
                ResourceWrites.delete(panel, entryIn(panel, HohenheimSlugs.SITES), site, ctx);
                return ApiConduits.json(Map.of("id", site.get(SiteModel.ID), "status", "deleted"));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (DomainRefusal refused) {
                return refused(conduit, refused);
            } catch (AccessRefusedException refused) {
                conduit.forbidden();
                return null;
            }
        });

        HohenheimEndpoints.API_V1_SITE_DOMAINS.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row site = PaasApi.visibleSite(conduit, ctx);
            if (site == null) {
                return null;
            }
            int siteId = site.get(SiteModel.ID);
            return ApiConduits.json(Map.of("id", siteId, "domains", domainProjections(siteId)));
        });

        HohenheimEndpoints.API_V1_SITE_DOMAIN_CREATE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row site = PaasApi.visibleSite(conduit, ctx);
            if (site == null) {
                return null;
            }
            int siteId = site.get(SiteModel.ID);
            Map<String, Object> raw = new LinkedHashMap<>(FormSubmissionRawValues.fromConduit(conduit));
            String siteKey = SiteDomainModel.SITE_ID.getName();
            Object submittedSite = raw.get(siteKey);
            if (submittedSite != null && !String.valueOf(siteId).equals(String.valueOf(submittedSite))) {
                // The URL names the site; a body that names another one is a contradiction
                // to refuse, never a value to prefer.
                return ApiConduits.refusal(conduit, Violations.ofField(siteKey, submittedSite,
                    ApiConduits.violationText("domain_site_mismatch")));
            }
            raw.put(siteKey, String.valueOf(siteId));
            Panel panel = ApiConduits.adminPanel();
            try {
                int domainId = (Integer) ResourceWrites.create(panel, entryIn(panel, DomainParts.SLUG), raw, ctx);
                Row added = Objects.requireNonNull(
                    Models.get(SiteDomainModel.class).findById(domainId));
                ActivityLog.record(Models.get(SiteModel.class), siteId, HohenheimActivityAction.DOMAIN_ADDED,
                    added.get(SiteDomainModel.HOSTNAME));
                return ApiConduits.json(domainProjection(added));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (AccessRefusedException refused) {
                conduit.forbidden();
                return null;
            }
        });

        HohenheimEndpoints.API_V1_SITE_DOMAIN_DELETE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row site = PaasApi.visibleSite(conduit, ctx);
            if (site == null) {
                return null;
            }
            int siteId = site.get(SiteModel.ID);
            Integer domainId = conduit.getParameter(HohenheimEndpoints.DOMAIN_ID);
            // A row of ANOTHER site answers exactly like a missing one (PaasApi.childRow's rule).
            Row domain = domainId == null ? null : Models.get(SiteDomainModel.class).find()
                .where(SiteDomainModel.ID.eq(domainId))
                .where(SiteDomainModel.SITE_ID.eq(siteId))
                .first();
            if (domain == null) {
                conduit.notFound();
                return null;
            }
            Panel panel = ApiConduits.adminPanel();
            try {
                ResourceWrites.delete(panel, entryIn(panel, DomainParts.SLUG), domain, ctx);
                ActivityLog.record(Models.get(SiteModel.class), siteId, HohenheimActivityAction.DOMAIN_REMOVED,
                    domain.get(SiteDomainModel.HOSTNAME));
                return ApiConduits.json(Map.of("id", domainId, "site_id", siteId,
                    "status", "deleted"));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (AccessRefusedException refused) {
                conduit.forbidden();
                return null;
            }
        });
    }

    /**
     * The admin panel's own entry, whose registered record source its programmatic writes read.
     *
     * @throws IllegalStateException when that panel declares no such entry (its proxy role is off)
     */
    @SuppressWarnings("unchecked")
    private static @NonNull PanelResource<Row> entryIn(@NonNull Panel panel, @NonNull String slug) {
        if (panel.entryBySlug(slug) instanceof PanelResource<?> entry) {
            return (PanelResource<Row>) entry;
        }
        throw new IllegalStateException("panel '" + panel.slug() + "' declares no entry '" + slug + "'");
    }

    /**
     * A site verb's pipeline refusal on this frozen wire: an offered-but-dead verb (the panel lockout, the operation's
     * availability) answers the 422 envelope the row lane's form-level {@code Violations} of the same words wrote,
     * byte-identical; every other reason through the API's one refusal adapter.
     *
     * @return the answer, or null when the response has already been ended
     * @throws DomainRefusal a refusal this wire has no answer of its own for
     */
    static @Nullable ActionResult<Object> refused(@NonNull Conduit conduit, @NonNull DomainRefusal refusal) {
        if (refusal.is(ZenitRefusalReason.OPERATION_UNAVAILABLE)) {
            return ApiConduits.refusal(conduit, Violations.ofForm(refusal.shown()));
        }
        return ApiConduits.refusal(conduit, refusal);
    }

    /** Every domain row of a site, oldest first, as the enumerated projection. */
    static @NonNull List<Map<String, Object>> domainProjections(int siteId) {
        List<Map<String, Object>> domains = new ArrayList<>();
        for (Row domain : Models.get(SiteDomainModel.class).find()
                .where(SiteDomainModel.SITE_ID.eq(siteId))
                .orderBy(SiteDomainModel.ID, SortOrder.ASC).all()) {
            domains.add(domainProjection(domain));
        }
        return domains;
    }

    /**
     * THE enumerated view of a domain row: every operator-editable column plus two
     * derived facts, whether the row holds its route claim right now and whether a
     * system authored it. The claim key itself stays internal.
     */
    static @NonNull Map<String, Object> domainProjection(@NonNull Row domain) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", domain.get(SiteDomainModel.ID));
        entry.put("site_id", domain.get(SiteDomainModel.SITE_ID));
        entry.put("hostname", domain.get(SiteDomainModel.HOSTNAME));
        entry.put("match_type", String.valueOf((Object) domain.get(SiteDomainModel.MATCH_TYPE)));
        entry.put("listen_on", stringOrEmpty(domain.get(SiteDomainModel.LISTEN_ON)));
        entry.put("path", stringOrEmpty(domain.get(SiteDomainModel.PATH)));
        entry.put("strip_path", Boolean.TRUE.equals(domain.get(SiteDomainModel.STRIP_PATH)));
        entry.put("force_ssl", Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL)));
        entry.put("certificate_id", domain.get(SiteDomainModel.CERTIFICATE_ID));
        entry.put("hsts_enabled", Boolean.TRUE.equals(domain.get(SiteDomainModel.HSTS_ENABLED)));
        entry.put("hsts_subdomains", Boolean.TRUE.equals(domain.get(SiteDomainModel.HSTS_SUBDOMAINS)));
        entry.put("exclude_from_letsencrypt",
            Boolean.TRUE.equals(domain.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT)));
        entry.put("custom_headers", headerMap(domain.get(SiteDomainModel.CUSTOM_HEADERS)));
        entry.put("response_headers", headerMap(domain.get(SiteDomainModel.RESPONSE_HEADERS)));
        entry.put("live", domain.get(SiteDomainModel.LIVE_ROUTE_KEY) != null);
        entry.put("generated", domain.get(SiteDomainModel.GENERATED_BY) != null);
        return entry;
    }

    private static @NonNull Map<String, Object> headerMap(@Nullable Object value) {
        Map<String, Object> headers = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> header : map.entrySet()) {
                headers.put(String.valueOf(header.getKey()), stringOrEmpty(header.getValue()));
            }
        }
        return headers;
    }

    private static @NonNull String stringOrEmpty(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
