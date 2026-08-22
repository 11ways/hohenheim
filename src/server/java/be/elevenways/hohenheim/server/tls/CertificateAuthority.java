package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.HostnameAuthority;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE answer to "may this caller obtain a certificate for these names".
 *
 * AIDEV-NOTE: {@code requiresPermission(hohenheim.admin.access)} on the request endpoint
 * checked nothing about the REQUEST -- it is a control that looks like authorization while
 * the hostname list stayed free-form, so an admin could mint a certificate for a hostname
 * this installation does not serve at all, and any future delegation would have made that a
 * cross-tenant hole on day one. Two independent halves answer here, and both are decided
 * from stored state, never from the submitted form:
 *
 * SERVING -- every requested name must be COVERED by a domain row of a site that still
 * exists (a soft-deleted site owns nothing, exactly as its grants do not survive its
 * delete). This half binds EVERYONE, admins included; there is no legitimate reason to hold
 * a certificate for a name this installation cannot serve.
 *
 * AUTHORITY -- the caller must hold {@link HohenheimAccess#MANAGE} on the site of EVERY
 * covering row, with the installation-wide admin permission as the bypass. All of them, not
 * one: a name two sites both claim is a name two owners both answer for.
 *
 * Coverage is {@link HostnamePatterns#covers}, never string equality and never
 * {@code intersect}: a tenant serving {@code a.example.com} asking for
 * {@code *.example.com} holds an intersecting claim and no covering one, and issuing on the
 * intersection would hand it every sibling host under the parent.
 *
 * @author Jelle De Loecker
 */
public final class CertificateAuthority {

    private CertificateAuthority() {
    }

    /** Why a request was refused; the identity a caller reports, never a parsed message. */
    public enum Refusal {

        /** No live domain row covers the name -- this installation does not serve it. */
        NOT_SERVED,

        /** A covering row belongs to a site the caller may not manage. */
        NOT_MANAGED,

        /** A covering row opted out of Let's Encrypt. */
        EXCLUDED
    }

    /**
     * A refused certificate request.
     *
     * AIDEV-NOTE: the three refusal identities are distinguishable, which IS an existence
     * oracle for hostnames configured on this installation -- acceptable while the endpoint
     * is admin-gated. When delegation relaxes that gate, NOT_SERVED and NOT_MANAGED must
     * collapse into one answer, or a tenant can enumerate every hostname the box serves.
     */
    public static final class Refused extends RuntimeException {

        private final Refusal refusal;
        private final String hostname;

        Refused(@NonNull Refusal refusal, @NonNull String hostname) {
            super("Certificate request refused (" + refusal + ") for " + hostname);
            this.refusal = refusal;
            this.hostname = hostname;
        }

        public @NonNull Refusal refusal() {
            return this.refusal;
        }

        /** @return the requested name that was refused, canonicalized */
        public @NonNull String hostname() {
            return this.hostname;
        }
    }

    /**
     * The identity a certificate order acts as, in the three shapes that exist: a live
     * request (conduit-backed context), a renewal re-check (the stored requester, revived
     * as a principal) and unattended system work.
     */
    public static final class Requester {

        /**
         * Unattended work. It passes the AUTHORITY half by definition and is still bound by
         * the SERVING half, so a system order for a name nobody serves is refused too.
         */
        public static final Requester SYSTEM = new Requester(null, null, true);

        private final @Nullable AccessContext context;
        private final @Nullable Principal principal;
        private final boolean system;

        private Requester(@Nullable AccessContext context, @Nullable Principal principal,
                          boolean system) {
            this.context = context;
            this.principal = principal;
            this.system = system;
        }

        public static @NonNull Requester of(@NonNull AccessContext context) {
            return new Requester(context, null, false);
        }

        /**
         * The renewal lane: authority is re-derived from the stored subject, never from a
         * snapshot taken at issuance time.
         *
         * AIDEV-NOTE: an API-key request stores its OWNING user (ApiKeyPrincipal.id() is
         * the human), so a renewal re-checks the owner's authority and not the key's scope
         * narrowing. That is deliberate -- the key may be long gone while the certificate
         * is not -- but it does mean a renewal can succeed on authority a scope-narrowed
         * key could no longer request fresh.
         */
        public static @NonNull Requester ofSubject(int userId) {
            return new Requester(null, new UserPrincipal(userId, ""), false);
        }

        boolean isAdmin() {
            if (this.system) {
                return true;
            }
            if (this.context != null) {
                return HohenheimAccess.isAdmin(this.context);
            }
            return this.principal != null && Zenit.getWebSocketAuthenticator()
                .hasPermission(this.principal, HohenheimPanel.ACCESS);
        }

        boolean canManageSite(int siteId) {
            if (this.system) {
                return true;
            }
            if (this.context != null) {
                return HohenheimAccess.canManageSite(this.context, siteId);
            }
            return this.principal != null && HohenheimAccess.canManageSite(this.principal, siteId);
        }

        /**
         * @return the subject id to stamp on the certificate row, or null for system and
         *         anonymous work (nothing to re-authorize against later)
         */
        public @Nullable Integer subjectId() {
            Long id = this.context != null ? this.context.principalId()
                : this.principal != null && !this.principal.isAnonymous() ? this.principal.id() : null;
            return id != null ? id.intValue() : null;
        }
    }

    /**
     * Decide the whole hostname list, or refuse the first name that fails.
     *
     * @return every requested name (canonicalized) mapped to the domain row that DECLARES
     *         it, which is the record a generated challenge is attributed to
     * @throws Refused on the first name the caller may not obtain a certificate for
     */
    public static @NonNull Map<String, Integer> authorize(@NonNull Requester requester,
                                                          @NonNull List<String> hostnames) {
        // AIDEV-NOTE: the covering-rows walk lives in HostnameAuthority.Snapshot -- a tenant
        // DNS write asks the SAME question and two copies of it would be two answers. Only
        // the certificate-specific halves (LE exclusion, TLS passthrough) stay here.
        HostnameAuthority.Snapshot snapshot = HostnameAuthority.Snapshot.load();

        boolean admin = requester.isAdmin();
        Map<String, Integer> declaring = new LinkedHashMap<>();

        for (String raw : hostnames) {
            String hostname = BlastString.lower(raw != null ? raw.trim() : "");
            List<Row> covering = snapshot.covering(hostname);

            if (covering.isEmpty()) {
                throw new Refused(Refusal.NOT_SERVED, hostname);
            }
            if (!admin) {
                for (Row domain : covering) {
                    Integer siteId = domain.get(SiteDomainModel.SITE_ID);
                    if (siteId == null || !requester.canManageSite(siteId)) {
                        throw new Refused(Refusal.NOT_MANAGED, hostname);
                    }
                }
            }
            // ANY covering row opting out is enough: the flag is a declaration that this
            // hostname must not appear on a Let's Encrypt certificate, and a wildcard row
            // covering it cannot overrule the exact row that said so. A TLS-passthrough
            // site is checked beside the flag rather than through it: the model hook forces
            // the flag on WRITE, so only rows written since then carry it.
            for (Row domain : covering) {
                Row site = snapshot.siteOf(domain);
                if (Boolean.TRUE.equals(domain.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT))
                        || site != null && SiteModel.UPSTREAM_TLS_PASSTHROUGH
                            .equals(site.get(SiteModel.UPSTREAM_KIND))) {
                    throw new Refused(Refusal.EXCLUDED, hostname);
                }
            }
            declaring.put(hostname, covering.get(0).get(SiteDomainModel.ID));
        }

        return declaring;
    }
}
