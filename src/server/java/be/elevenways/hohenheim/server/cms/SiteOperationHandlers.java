package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.zenit.cms.server.page.ResourcePageEndpoints;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.DuplicateKeyException;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.text.Slugs;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.Authorizer;
import be.elevenways.zenit.server.operation.OperationCall;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The handlers of the site operations, attached once per JVM.
 *
 * AIDEV-NOTE: attached in a static initializer like InstanceOperationHandlers; {@link #init()} only forces the class
 * to load before boot verifies every operation has its handler. Who may act is an authorizer per operation: reach of
 * the site (manage) for the switches, which is /manage's own row scope and every site for an operator, and
 * installation administration for clone and rollback, which only the admin panel ever offered. A refusal conceals
 * the site as missing, the answer a caller gets for a site it cannot see.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class SiteOperationHandlers {

    static {
        OperationHandlers.attach(SiteOperations.ENABLE)
            .applies(site -> live(site) && !enabled(site))
            .authorize(reachesSite())
            .handle(call -> switchTo(call, true));
        // The panel's own site cannot be switched off from inside the panel: dead, with the reason on screen.
        OperationHandlers.attach(SiteOperations.DISABLE)
            .applies(site -> live(site) && enabled(site))
            .availability((site, access) -> SiteResource.panelLockoutReason("toggle_self_lockout", site, access))
            .authorize(reachesSite())
            .handle(call -> switchTo(call, false));
        OperationHandlers.attach(SiteOperations.CLONE)
            .applies(SiteOperationHandlers::live)
            .authorize(administers())
            .handle(call -> cloneSite(call.subject(), Objects.requireNonNull(call.input(), "a clone has its input")));
        // Only a site exposing an application through the instance upstream has a release to roll back.
        OperationHandlers.attach(SiteOperations.ROLLBACK_RELEASE)
            .applies(site -> live(site)
                && InstanceUpstreamKind.ID.toString().equals(site.get(SiteModel.UPSTREAM_KIND)))
            .authorize(administers())
            .handle(SiteOperationHandlers::rollback);
    }

    private SiteOperationHandlers() {
    }

    /** Loads the class, attaching the handlers; idempotent. */
    public static void init() {
        // The static initializer did the work.
    }

    /**
     * A trashed site takes none of these: it routes nothing until a restore, which runs its own route checks, so an
     * enable there would bypass them and a clone or rollback of it would act on a record the panels hide.
     */
    private static boolean live(@NonNull Row site) {
        return !SiteModel.SOFT_DELETE.isTrashed(site);
    }

    private static boolean enabled(@NonNull Row site) {
        return Boolean.TRUE.equals(site.get(SiteModel.ENABLED));
    }

    private static <I> @NonNull Authorizer<Row, I> reachesSite() {
        return (site, input, access) -> HohenheimAccess.reachesRecord(access, SiteModel.MODEL_ID,
            site.get(SiteModel.ID), HohenheimAccess.MANAGE) ? null : concealed(site);
    }

    private static <I> @NonNull Authorizer<Row, I> administers() {
        return (site, input, access) -> HohenheimAccess.isAdmin(access) ? null : concealed(site);
    }

    private static @NonNull DomainRefusal concealed(@NonNull Row site) {
        return new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "site " + site.get(SiteModel.ID) + " is not reachable");
    }

    /**
     * Sets the site's switch; the write pipeline's enable invariant (SiteEnableInvariant) runs inside the save and
     * refuses a site whose hostnames another enabled site already routes, by name.
     */
    private static @Nullable Void switchTo(@NonNull OperationCall<Row, Void> call, boolean enable) {
        Row site = call.subject();
        site.set(SiteModel.ENABLED, enable);
        ActivityLog.withAction(enable ? HohenheimActivityAction.ENABLED : HohenheimActivityAction.DISABLED, null,
            () -> Models.get(SiteModel.class).save(site));
        return null;
    }

    private static @Nullable Void rollback(@NonNull OperationCall<Row, Void> call) {
        Integer instanceId = call.subject().get(SiteModel.INSTANCE_ID);
        if (instanceId == null) {
            throw Violations.ofForm(CmsSupport.violationText("site_exposes_no_instance"));
        }
        ReleaseEngine.rollback(instanceId);
        return null;
    }

    /**
     * Clone a site and its domains under the name the operator gave; the copy starts disabled and carries NO bearer
     * credentials of its own.
     *
     * AIDEV-NOTE: the copy deliberately does NOT carry instance_id: two sites pointing at one instance is a second
     * front door to the same workload, not a copy of it, so the operator picks or creates the instance for the clone.
     * The slug's UNIQUE constraint is the whole name check, and it covers every row, soft-deleted ones included: a
     * read-then-insert both missed a trashed site's slug and lost the race to a concurrent clone.
     *
     * @return the copy's id
     * @throws Violations on the name when its slug is already a site's: the form shows it inline
     */
    private static @NonNull Integer cloneSite(@NonNull Row site, SiteOperations.@NonNull CloneInput input) {
        String name = input.name();
        if (name == null) {
            throw Violations.ofField(SiteOperations.CLONE_NAME.getName(), "",
                CmsSupport.violationText("name_required"));
        }
        SiteModel siteModel = Models.get(SiteModel.class);
        SiteDomainModel domainModel = Models.get(SiteDomainModel.class);

        Row clone = siteModel.createEmptyRow();
        clone.set(SiteModel.NAME, name);
        clone.set(SiteModel.SLUG, Slugs.slugify(name));
        clone.set(SiteModel.UPSTREAM_KIND, site.get(SiteModel.UPSTREAM_KIND));
        @SuppressWarnings("unchecked")
        Map<String, Object> clonedSettings = site.get(SiteModel.SETTINGS) != null
            ? new LinkedHashMap<>((Map<String, Object>) site.get(SiteModel.SETTINGS))
            : null;
        clone.set(SiteModel.SETTINGS, clonedSettings);
        clone.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        clone.set(SiteModel.ENABLED, false);
        clone.set(SiteModel.AUTH_PROVIDER_ID, site.get(SiteModel.AUTH_PROVIDER_ID));
        clone.set(SiteModel.ACCESS_LIST_ID, site.get(SiteModel.ACCESS_LIST_ID));
        try {
            ActivityLog.withAction(HohenheimActivityAction.CLONED, "of site #" + site.get(SiteModel.ID),
                () -> siteModel.save(clone));
        } catch (DuplicateKeyException conflict) {
            if (!SiteModel.SLUG.getName().equals(conflict.getColumnName())) {
                throw conflict;
            }
            throw Violations.ofField(SiteOperations.CLONE_NAME.getName(), name, ResourcePageEndpoints.DUPLICATE_VALUE);
        }

        int newSiteId = clone.get(SiteModel.ID);
        for (Row domain : domainModel.findBySiteId(site.get(SiteModel.ID))) {
            Row domainClone = domainModel.createEmptyRow();
            domainClone.set(SiteDomainModel.SITE_ID, newSiteId);
            domainClone.set(SiteDomainModel.HOSTNAME, domain.get(SiteDomainModel.HOSTNAME) + ".clone");
            domainClone.set(SiteDomainModel.MATCH_TYPE, domain.get(SiteDomainModel.MATCH_TYPE));
            domainClone.set(SiteDomainModel.FORCE_SSL, domain.get(SiteDomainModel.FORCE_SSL));
            domainClone.set(SiteDomainModel.HSTS_ENABLED, domain.get(SiteDomainModel.HSTS_ENABLED));
            domainClone.set(SiteDomainModel.HSTS_SUBDOMAINS, domain.get(SiteDomainModel.HSTS_SUBDOMAINS));
            domainClone.set(SiteDomainModel.PATH, domain.get(SiteDomainModel.PATH));
            domainClone.set(SiteDomainModel.STRIP_PATH, domain.get(SiteDomainModel.STRIP_PATH));
            domainClone.set(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT,
                domain.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT));
            domainClone.set(SiteDomainModel.LISTEN_ON, domain.get(SiteDomainModel.LISTEN_ON));
            domainClone.set(SiteDomainModel.CUSTOM_HEADERS, domain.get(SiteDomainModel.CUSTOM_HEADERS));
            domainClone.set(SiteDomainModel.RESPONSE_HEADERS, domain.get(SiteDomainModel.RESPONSE_HEADERS));
            domainModel.save(domainClone);
        }
        return newSiteId;
    }
}
