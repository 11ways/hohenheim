package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.server.tls.HostnameReach;
import be.elevenways.hohenheim.server.tls.RenewalFailure;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.RowWriteCall;
import be.elevenways.zenit.cms.common.resource.RowWriter;
import be.elevenways.zenit.common.edit.EditView;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A site's address created or changed by its settings as an assistant names them, through the address panel
 * resource's own create and save, so the route claims, the quarantine, the passthrough refusals and the tenant freeze
 * decide as they do for the panel's form.
 *
 * AIDEV-NOTE: the entries are the address form's own fields (DomainParts.adminFormSpec), the site and the pinned
 * certificate taken by their codes. Someone who is no administrator writes through the /manage twin, whose form takes
 * fewer fields: one it does not take is refused there, and TenantWrites freezes the rest on every writer anyway.
 *
 * AIDEV-NOTE: {@link #HTTPS}, the "Put online" wizard's certificate choice with its default, is an entry of the
 * operator's create form alone, so the tool and the panel take it through the same create ({@link #created}): the
 * certificate is ordered through the wizard's own orderer (PutOnlineHandlers.certificate) once the address is
 * committed, because the order reaches Let's Encrypt; a refused order is no refusal of the create. Certificates are
 * installation administration (CertificateOperationHandlers.adminRefusal), so the /manage twin orders none, as the
 * wizard gives a person who is no administrator no address at all. What happened is read back from the address and
 * its certificates ({@link #httpsSaid}), never carried from the create.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class AddressWrites {

    /** The argument naming the site an address belongs to. */
    public static final String SITE = "site";

    /** The argument pinning the certificate an address serves. */
    public static final String CERTIFICATE = "certificate";

    /**
     * A new address's certificate: ordered now, or not yet; labelled as the order it places ("Get a certificate"), so it
     * never reads as the certificate picker beside it, with the wizard's words for its two choices and its default.
     */
    public static final EnumField HTTPS = EnumField.builder(PutOnline.HTTPS.getName())
        .label(CertificateOperations.REQUEST_FOR_DOMAIN.label())
        .help(HohenheimMicrocopy.HELP.of("address_https_order"))
        .value(HttpsChoice.AUTOMATIC.token(), HttpsChoice.AUTOMATIC.token(), HttpsChoice.AUTOMATIC.label())
        .value(HttpsChoice.LATER.token(), HttpsChoice.LATER.token(), HttpsChoice.LATER.label())
        .defaultValue(String.valueOf(PutOnline.HTTPS.getDefaultValue()))
        .keepsAValue()
        .visibleIn(EditView.CREATE)
        .build();

    /** Who reads what HTTPS gives a new address: how its certificate is named and who orders one. */
    public enum HttpsReader {

        /** An administrator's assistant: the certificate by its code, one ordered with certificate_order. */
        ASSISTANT,

        /** An administrator on the panel: the certificate by its name, one ordered from the address's row. */
        OPERATOR,

        /** Anyone else, on the panel or through an assistant: no certificate named, one ordered by an administrator. */
        DELEGATED
    }

    /** What HTTPS gives an address now. */
    private enum Https { PASSTHROUGH, PATTERN, WORKS, WAITING, UNSERVED, EXCLUDED, NONE }

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
        .createOnly(List.of(HTTPS))
        // The order reaches Let's Encrypt once the address is committed, outside any transaction.
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

    /**
     * The operator's create of an address: the framework's own create without {@link #HTTPS}, no column of the
     * address, then the certificate ordered as chosen, else as the wizard's default, once the create is committed.
     */
    static @NonNull Object created(@NonNull RowWriteCall call, @NonNull RowWriter framework) {
        Map<String, Object> staged = new LinkedHashMap<>(call.values());
        Object chosen = staged.remove(HTTPS.getName());
        HttpsChoice choice = HttpsChoice.ofToken(String.valueOf(chosen != null ? chosen : HTTPS.getDefaultValue()));
        Object key = Objects.requireNonNull(framework.write(new RowWriteCall(call.verb(), null, staged,
            call.access())), "the create answers its key");
        int addressId = Integer.parseInt(String.valueOf(key));
        Models.get(SiteDomainModel.class).getResolvedDatasource()
            .afterCommit(() -> order(addressId, choice, call.access()));
        return key;
    }

    /**
     * Orders the address's certificate as chosen, through the wizard's own orderer; an address no certificate is
     * ordered for (a pattern, one excluded from Let's Encrypt, one on a TLS passthrough site) orders none, and neither
     * does a caller who is no administrator.
     */
    static void order(int addressId, @Nullable HttpsChoice choice, @NonNull AccessContext caller) {
        if (CertificateOperationHandlers.adminRefusal(caller) != null) return;
        Row address = Models.get(SiteDomainModel.class).findById(addressId);
        if (address == null || !CertificateOperationHandlers.certifiable(address)) return;
        PutOnlineHandlers.certificate(String.valueOf((Object) address.get(SiteDomainModel.HOSTNAME)),
            new PutOnline.HttpsAnswer(choice, null, null, null), caller);
    }

    /**
     * What HTTPS gives a new address, as an assistant's answer says it: to an administrator by the certificate's code
     * and the call that orders one, to anyone else without naming one.
     *
     * @param code the address's code, which the call ordering a certificate names
     */
    public static @NonNull Microcopy httpsSaid(@NonNull Row address, @NonNull String code,
                                               @NonNull AccessContext access) {
        return httpsSaid(address, HohenheimAccess.isAdmin(access) ? HttpsReader.ASSISTANT : HttpsReader.DELEGATED,
            code, access);
    }

    /** @return the toast of an address the panel's create form added: its name, then what HTTPS gives it */
    static @NonNull Microcopy added(@NonNull Row address, @NonNull HttpsReader reader,
                                    @NonNull AccessContext access) {
        return HohenheimMicrocopy.HOHENHEIM.of("address_added")
            .withArg("address", String.valueOf((Object) address.get(SiteDomainModel.HOSTNAME)))
            .withArg("https", httpsSaid(address, reader, null, access));
    }

    /**
     * What HTTPS gives the address now, in plain words: working, waiting to be issued, not served, or none yet and
     * why, with the way to order one; read from the address and its certificates as they stand.
     *
     * @param code   the address's code, for an assistant's call
     * @param access the reader: whose codes an assistant is shown, and whose orders of the hour may be spent
     */
    private static @NonNull Microcopy httpsSaid(@NonNull Row address, @NonNull HttpsReader reader,
                                                @Nullable String code, @NonNull AccessContext access) {
        String hostname = String.valueOf((Object) address.get(SiteDomainModel.HOSTNAME));
        boolean passthrough = SiteParts.tlsPassthrough(Models.get(SiteModel.class)
            .findById(address.get(SiteDomainModel.SITE_ID)));
        CertCoverage coverage = AppHealth.httpsOf(address, passthrough, AppHealth.workingNames());
        Row cert = coverage.hasCertificate() ? CertificateCoverage.coveringCertificate(hostname) : null;
        Https state = switch (coverage) {
            case NOT_USED -> Https.PASSTHROUGH;
            case PATTERN -> Https.PATTERN;
            case ACTIVE -> Https.WORKS;
            case PENDING -> Https.WAITING;
            // An error is a certificate the proxy does not serve, or none that works.
            case NONE, ERROR -> Boolean.TRUE.equals(address.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT))
                ? Https.EXCLUDED
                : cert != null && CertificateModel.STATUS_ACTIVE.equals(cert.get(CertificateModel.STATUS))
                ? Https.UNSERVED : Https.NONE;
        };
        Microcopy why = state == Https.NONE && reader != HttpsReader.DELEGATED
            ? whyNone(hostname, cert, access) : null;
        Microcopy said = said(key(reader, state, why != null));
        if (why != null) said = said.withArg("reason", why);
        return switch (reader) {
            case ASSISTANT -> said.withArg("address", Objects.requireNonNullElse(code, hostname))
                .withArg("certificate", cert == null ? "" : Objects.requireNonNullElse(RecordCodes.code(
                    CertificateModel.MODEL_ID, String.valueOf((Object) cert.get(CertificateModel.ID)), access),
                    name(cert)));
            case OPERATOR -> said.withArg("certificate", cert == null ? "" : name(cert))
                .withArg("action", CertificateOperations.REQUEST_FOR_DOMAIN.label());
            case DELEGATED -> said;
        };
    }

    /** @return the key of the sentence saying the state to the reader; a neutral sentence is shared */
    private static @NonNull String key(@NonNull HttpsReader reader, @NonNull Https state, boolean because) {
        return switch (state) {
            case PASSTHROUGH -> "mcp_address_https_passthrough";
            case WORKS -> switch (reader) {
                case ASSISTANT, OPERATOR -> "mcp_address_https_works";
                case DELEGATED -> "address_https_delegated_works";
            };
            case WAITING -> switch (reader) {
                case ASSISTANT, OPERATOR -> "mcp_address_https_waiting";
                case DELEGATED -> "address_https_delegated_waiting";
            };
            case UNSERVED -> switch (reader) {
                case ASSISTANT, OPERATOR -> "address_https_unserved";
                case DELEGATED -> "address_https_delegated_unserved";
            };
            case PATTERN -> switch (reader) {
                case ASSISTANT -> "mcp_address_https_pattern";
                case OPERATOR -> "address_https_pattern";
                case DELEGATED -> "address_https_delegated_pattern";
            };
            case EXCLUDED -> switch (reader) {
                case ASSISTANT -> "mcp_address_https_excluded";
                case OPERATOR -> "address_https_excluded";
                case DELEGATED -> "address_https_delegated_excluded";
            };
            case NONE -> switch (reader) {
                case ASSISTANT -> because ? "mcp_address_https_none_because" : "mcp_address_https_none";
                case OPERATOR -> because ? "address_https_none_because" : "address_https_none";
                case DELEGATED -> "address_https_delegated_none";
            };
        };
    }

    /** @return the certificate's name, as the panel shows it */
    private static @NonNull String name(@NonNull Row cert) {
        return String.valueOf((Object) cert.get(CertificateModel.NICE_NAME));
    }

    /**
     * @return why an address without a working certificate has none: Let's Encrypt off, its order refused (the
     *     certificate's stored failure), the name not pointing here by the order's own pre-check, or the reader's
     *     orders of the hour spent; null for none of these (later was chosen)
     */
    private static @Nullable Microcopy whyNone(@NonNull String hostname, @Nullable Row cert,
                                               @NonNull AccessContext access) {
        Microcopy off = CertificateOperationHandlers.letsEncryptUnavailable();
        if (off != null) return off;
        if (cert != null && CertificateModel.STATUS_ERROR.equals(cert.get(CertificateModel.STATUS))) {
            return RenewalFailure.sentenceFor(cert.get(CertificateModel.RENEWAL_ERROR));
        }
        Microcopy unreachable = CertificateOperationHandlers.unreachable(hostname, HostnameReach.recent(hostname));
        return unreachable != null ? unreachable : CertificateOperationHandlers.orderBudgetSpent(access);
    }

    private static @NonNull Microcopy said(@NonNull String key) {
        return HohenheimMicrocopy.HOHENHEIM.of(key);
    }
}
