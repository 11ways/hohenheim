package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.server.tls.HostnameReach;
import be.elevenways.hohenheim.server.tls.RenewalFailure;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.Plain;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A site's address created or changed by its settings as an assistant names them, through the address panel
 * resource's own create and save, so the route claims, the quarantine, the passthrough refusals and the tenant freeze
 * decide as they do for the panel's form.
 *
 * AIDEV-NOTE: the entries are the address form's own fields (DomainParts.adminFormSpec), the site and the pinned
 * certificate taken by their codes. Someone who is no administrator writes through the /manage twin, whose form takes
 * fewer fields: one it does not take is refused there, and TenantWrites freezes the rest on every writer anyway.
 *
 * AIDEV-NOTE: a create takes {@link #HTTPS}, the "Put online" wizard's choice with its default, and orders through the
 * wizard's own orderer (PutOnlineHandlers.certificate) once the address exists, outside the create's transaction
 * because the order reaches Let's Encrypt; a refused order is no refusal of the create. What happened is read back
 * from the address and its certificates ({@link #httpsSaid}), never carried from the create.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class AddressWrites {

    /** The argument naming the site an address belongs to. */
    public static final String SITE = "site";

    /** The argument pinning the certificate an address serves. */
    public static final String CERTIFICATE = "certificate";

    /** A new address's certificate: ordered now, or not yet; the wizard's HTTPS step's default. */
    public static final EnumField HTTPS = EnumField.builder(PutOnline.HTTPS.getName())
        .help(HohenheimMicrocopy.HELP.of("address_https_order"))
        .value(HttpsChoice.AUTOMATIC.token(), HttpsChoice.AUTOMATIC.token(), HttpsChoice.AUTOMATIC.label())
        .value(HttpsChoice.LATER.token(), HttpsChoice.LATER.token(), HttpsChoice.LATER.label())
        .defaultValue(String.valueOf(PutOnline.HTTPS.getDefaultValue()))
        .build();

    /** The address's own settings, as its form names them. */
    private static final List<Field<?, ?>> SETTINGS = List.of(SiteDomainModel.HOSTNAME, SiteDomainModel.MATCH_TYPE,
        SiteDomainModel.PATH, SiteDomainModel.STRIP_PATH, SiteDomainModel.LISTEN_ON, SiteDomainModel.FORCE_SSL,
        SiteDomainModel.HSTS_ENABLED, SiteDomainModel.HSTS_SUBDOMAINS, SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT,
        SiteDomainModel.CUSTOM_HEADERS, SiteDomainModel.RESPONSE_HEADERS);

    private static final PanelRecordWrites WRITES = PanelRecordWrites.of("address", SiteDomainModel.MODEL_ID,
            CertificateOperations.DOMAIN, SiteDomainModel.ID, HohenheimSlugs.DOMAINS)
        .link(SITE, HohenheimMicrocopy.HELP.of("address_site"), SiteDomainModel.SITE_ID, SiteModel.MODEL_ID, true)
        .settings(SETTINGS)
        .link(CERTIFICATE, HohenheimMicrocopy.HELP.of("address_certificate"), SiteDomainModel.CERTIFICATE_ID,
            CertificateModel.MODEL_ID, false)
        .alsoOnCreateOnly(List.of(Plain.of(HTTPS)), AddressWrites::httpsChosen)
        // The order reaches Let's Encrypt once the address is stored, outside any transaction.
        .createsOutsideTransaction()
        .build();

    /** Adds an address to a site. */
    public static final Operation<Void, OperationInput.Values, Integer> CREATE = WRITES.create();

    /** Changes what the call sends of an address; every other setting stays as it is. */
    public static final Operation<Row, OperationInput.Values, String> CHANGE = WRITES.change();

    /** Removes an address from its site through the panel's own delete. */
    public static final Operation<Row, Void, Integer> DELETE = PanelRecordWrites.deletion("address",
        CertificateOperations.DOMAIN, HohenheimSlugs.DOMAINS);

    private AddressWrites() {
    }

    /** Installs the handlers; idempotent through the class initializer. */
    public static void init() {
    }

    /** @return the order of the new address's certificate as the call chose it, else as the wizard's default */
    private static @NonNull Consumer<Integer> httpsChosen(OperationInput.@NonNull Values input,
                                                          @NonNull Set<String> sent, @NonNull AccessContext caller) {
        Object chosen = input.get(HTTPS.getName());
        HttpsChoice choice = HttpsChoice.ofToken(String.valueOf(chosen != null ? chosen : HTTPS.getDefaultValue()));
        return addressId -> order(addressId, choice, caller);
    }

    /**
     * Orders the address's certificate as chosen, through the wizard's own orderer; an address no certificate is
     * ordered for (a pattern, one excluded from Let's Encrypt, one on a TLS passthrough site) orders none.
     */
    static void order(int addressId, @Nullable HttpsChoice choice, @NonNull AccessContext caller) {
        Row address = Models.get(SiteDomainModel.class).findById(addressId);
        if (address == null || !CertificateOperationHandlers.certifiable(address)) return;
        PutOnlineHandlers.certificate(String.valueOf((Object) address.get(SiteDomainModel.HOSTNAME)),
            new PutOnline.HttpsAnswer(choice, null, null, null), caller);
    }

    /**
     * What HTTPS gives the address now, in plain words: working, waiting to be issued, or none yet and why, with the
     * call that orders one; read from the address and its certificates as they stand.
     *
     * @param code the address's code, which the call ordering a certificate names
     */
    public static @NonNull Microcopy httpsSaid(@NonNull Row address, @NonNull String code,
                                               @NonNull AccessContext access) {
        String hostname = String.valueOf((Object) address.get(SiteDomainModel.HOSTNAME));
        boolean passthrough = SiteParts.tlsPassthrough(Models.get(SiteModel.class)
            .findById(address.get(SiteDomainModel.SITE_ID)));
        CertCoverage coverage = AppHealth.httpsOf(address, passthrough, AppHealth.workingNames());
        Row cert = coverage.hasCertificate() ? CertificateCoverage.coveringCertificate(hostname) : null;
        String certificate = cert == null ? "" : Objects.requireNonNullElse(RecordCodes.code(
            CertificateModel.MODEL_ID, String.valueOf((Object) cert.get(CertificateModel.ID)), access),
            String.valueOf((Object) cert.get(CertificateModel.NICE_NAME)));
        return switch (coverage) {
            case NOT_USED -> said("passthrough");
            case PATTERN -> said("pattern");
            case ACTIVE -> said("works").withArg("certificate", certificate);
            case PENDING -> said("waiting").withArg("certificate", certificate);
            case NONE, ERROR -> {
                if (Boolean.TRUE.equals(address.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT))) {
                    yield said("excluded");
                }
                Microcopy why = whyNone(hostname, cert);
                yield (why == null ? said("none") : said("none_because").withArg("reason", why))
                    .withArg("address", code);
            }
        };
    }

    /**
     * @return why an address without a working certificate has none: Let's Encrypt off, its order refused (the
     *     certificate's stored failure), or the name not pointing here by the order's own pre-check; null for none of
     *     these (later was chosen)
     */
    private static @Nullable Microcopy whyNone(@NonNull String hostname, @Nullable Row cert) {
        Microcopy off = CertificateOperationHandlers.letsEncryptUnavailable();
        if (off != null) return off;
        if (cert != null && CertificateModel.STATUS_ERROR.equals(cert.get(CertificateModel.STATUS))) {
            return RenewalFailure.sentenceFor(cert.get(CertificateModel.RENEWAL_ERROR));
        }
        return CertificateOperationHandlers.unreachable(hostname, HostnameReach.recent(hostname));
    }

    private static @NonNull Microcopy said(@NonNull String key) {
        return HohenheimMicrocopy.HOHENHEIM.of("mcp_address_https_" + key);
    }
}
