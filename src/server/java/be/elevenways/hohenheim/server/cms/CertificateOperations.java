package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionRequest;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.coerce.PrimitiveCoercion;
import be.elevenways.zenit.common.edit.Array;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.OperationResult;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.ListField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Let's Encrypt from the admin: request a certificate (from the certificate list, or for one domain row), re-order an
 * existing one, and finish a manual DNS-01 order once its TXT records are published.
 *
 * AIDEV-NOTE: these replace the hand-rolled request page, its form helper and its host endpoint. The input is one
 * FormSpec rendered and coerced by the operation pipeline, so a refused submit keeps what the operator typed; the
 * challenge and publisher choices are CertificateModel's own vocabularies. Whether Let's Encrypt may be used at all is
 * the {@code ssl.letsencrypt_enabled} setting, asked as availability (offered dead with the reason) and refused again
 * by the handler (CertificateOperationHandlers).
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
public final class CertificateOperations {

    /** A domain row: "Get a certificate" for its hostname. */
    public static final SubjectType<Row> DOMAIN = SubjectType.record(SiteDomainModel.MODEL_ID);

    /** A stored certificate: re-ordered, or its manual DNS-01 order continued. */
    public static final SubjectType<Row> CERTIFICATE = SubjectType.record(CertificateModel.MODEL_ID);

    private static final StringField DOMAIN_NAME = StringField.builder("domain").placeholder("example.com").build();

    static final ListField<String> DOMAINS = ListField.<String>builder(DOMAIN_NAME)
        .name("domains")
        .label(copy("domains"))
        .help(copy("domains_help"))
        .build();

    static final StringField NICE_NAME = StringField.builder("nice_name")
        .label(copy("name"))
        .placeholder("example.com")
        .build();

    static final StringField EMAIL = StringField.builder("letsencrypt_email")
        .label(copy("account_email"))
        .help(copy("account_email_help"))
        .build();

    public static final EnumField CHALLENGE = CertificateModel.challengeTypeField()
        .defaultValue(CertificateModel.CHALLENGE_HTTP)
        .label(copy("validation"))
        .help(copy("validation_help"))
        .build();

    public static final EnumField DNS_PUBLISHER = CertificateModel.dnsPublisherField()
        .defaultValue(CertificateModel.DNS_PUBLISHER_MANUAL)
        .label(copy("dns_mode"))
        .help(copy("dns_mode_help"))
        .build();

    private static final FormSpec INPUT = FormSpec.builder()
        .add(Array.of(DOMAINS, DOMAIN_NAME).minCount(1).build())
        .add(NICE_NAME)
        .add(EMAIL)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(CHALLENGE))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DNS_PUBLISHER))
        .showWhen(DNS_PUBLISHER.getName(), CHALLENGE.getName(), CertificateModel.CHALLENGE_DNS)
        .build();

    private static final OperationInput<Order> ORDER = OperationInput.of(INPUT, Order.class, values -> new Order(
        PrimitiveCoercion.toTrimmedTextList(values.get(DOMAINS)), values.get(NICE_NAME), values.get(EMAIL),
        values.get(CHALLENGE), values.get(DNS_PUBLISHER)));

    /** A new certificate for the names the operator lists; the certificate list's header action. */
    public static final Operation<Void, Order, Integer> REQUEST = Operation.declare(HohenheimIds.id("request_certificate"))
        .happened(OperationSentences.of("request_certificate"))
        .label(copy("get_certificate"))
        .description(copy("uses_production"))
        .icon(Icon.of("lock"))
        .noSubject()
        .gate(OperationGate.open())
        .input(ORDER)
        .result(Integer.class)
        .facts(OperationFact.REACHES_OUTSIDE)
        .rateLimit(HohenheimEndpoints.LE_REQUEST_LIMIT)
        .command(CmsCommands.EXTERNAL)
        .register();

    /** A new certificate for one domain row's hostname, prefilled; the domain rows' action. */
    public static final Operation<Row, Order, Integer> REQUEST_FOR_DOMAIN =
        Operation.declare(HohenheimIds.id("request_domain_certificate"))
            .happened(OperationSentences.of("request_domain_certificate"))
            .label(copy("get_certificate"))
            .description(copy("uses_production"))
            .icon(Icon.of("lock"))
            .one(DOMAIN)
            .gate(OperationGate.open())
            .input(ORDER)
            .result(Integer.class)
            .facts(OperationFact.REACHES_OUTSIDE)
            .rateLimit(HohenheimEndpoints.LE_REQUEST_LIMIT)
            .command(CmsCommands.EXTERNAL)
            .register();

    /** A new order written back into an existing Let's Encrypt certificate: how names or the challenge change. */
    public static final Operation<Row, Order, Integer> REISSUE = Operation.declare(HohenheimIds.id("reissue_certificate"))
        .happened(OperationSentences.of("reissue_certificate"))
        .label(Microcopy.of("reissue").withFilter("scope", "certificate"))
        .description(copy("uses_production"))
        .icon(Icon.of("rotate"))
        .one(CERTIFICATE)
        .gate(OperationGate.open())
        .input(ORDER)
        .result(Integer.class)
        .facts(OperationFact.REACHES_OUTSIDE)
        .rateLimit(HohenheimEndpoints.LE_REQUEST_LIMIT)
        .command(CmsCommands.EXTERNAL)
        .register();

    /** Finishes a manual DNS-01 order after its TXT records are published. */
    public static final Operation<Row, Void, Integer> CONTINUE_DNS = Operation.declare(HohenheimIds.id("continue_dns_order"))
        .happened(OperationSentences.of("continue_dns_order"))
        .label(copy("verify_dns"))
        .description(copy("verify_dns_hint"))
        .icon(Icon.of("circle-check"))
        .one(CERTIFICATE)
        .gate(OperationGate.open())
        .result(Integer.class)
        .facts(OperationFact.REACHES_OUTSIDE)
        .rateLimit(HohenheimEndpoints.LE_REQUEST_LIMIT)
        .command(CmsCommands.EXTERNAL)
        .register();

    private CertificateOperations() {
    }

    /** Installs the handlers; idempotent through the handler class's own initializer. */
    static void init() {
        CertificateOperationHandlers.init();
    }

    /** @return the certificate list's header action, "Get a certificate" beside the upload (board Certificates) */
    static @NonNull PanelAction<Row> requestAction() {
        init();
        return PanelAction.<Row, Integer>places(REQUEST, ActionPlacement.HEADER, CertificateOperations::opened)
            .confirmation(ConfirmationSpec.generic(copy("uses_production"), false))
            .inSheet()
            .inlineInHeader(true)
            .build();
    }

    /** @return a domain row's "Get a certificate", prefilled with its hostname */
    static @NonNull PanelAction<Row> requestForDomainAction() {
        init();
        return PanelAction.<Row, Integer>places(REQUEST_FOR_DOMAIN, ActionPlacement.ROW, CertificateOperations::opened)
            .confirmation(ConfirmationSpec.generic(copy("uses_production"), false))
            .inSheet()
            .inputValues((domain, request) -> prefill(domain))
            // A name a working certificate already covers needs no new one: changing that certificate is its own
            // reissue, on the certificate's row. Working is what the proxy loaded: a stored row it cannot load
            // covers nothing, so its name is offered a certificate.
            .hiddenWhen(domain -> CertificateCoverage.covers(CertificateCoverage.workingNames(),
                domain.get(SiteDomainModel.HOSTNAME)))
            .build();
    }

    /** @return a Let's Encrypt certificate's re-order, prefilled with what it was last issued for */
    static @NonNull PanelAction<Row> reissueAction() {
        init();
        return PanelAction.<Row, Integer>places(REISSUE, ActionPlacement.ROW, CertificateOperations::opened)
            .confirmation(ConfirmationSpec.generic(copy("reissue_notice"), false))
            .inSheet()
            .inputValues((cert, request) -> reorder(cert))
            // A manual upload has no order to repeat, and the ACME account row is not a certificate at all: the
            // handler refuses them, this only stops offering an action that could never succeed.
            .hiddenWhen(cert -> !CertificateModel.PROVIDER_LETSENCRYPT.equals(cert.get(CertificateModel.PROVIDER)))
            // A rare chore: it lives in the row's overflow menu, not in the row itself.
            .inlineInRow(false)
            .build();
    }

    /** @return a waiting manual DNS-01 order's "Verify DNS and finish" */
    static @NonNull PanelAction<Row> continueDnsAction() {
        init();
        return PanelAction.<Row, Integer>places(CONTINUE_DNS, ActionPlacement.ROW, CertificateOperations::opened)
            .confirmation(ConfirmationSpec.generic(copy("publish_description"), false))
            .build();
    }

    /** Every successful order opens the certificate it wrote, where a waiting DNS-01 order shows its TXT records. */
    private static @NonNull CmsActionResult opened(@NonNull ActionRequest<Row> request,
                                                   @NonNull OperationResult<Integer> result) {
        return CmsActionResult.redirect(new Uri(CmsRoutes.detail(request.request().panelSlug(),
            HohenheimSlugs.CERTIFICATES, result.value()).toUrl()));
    }

    private static @NonNull Map<String, Object> prefill(@Nullable Row domain) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (domain == null) {
            return values;
        }
        String hostname = domain.get(SiteDomainModel.HOSTNAME);
        values.put(DOMAINS.getName(), hostname == null ? List.of() : List.of(hostname));
        Row site = Models.get(SiteModel.class).findById(domain.get(SiteDomainModel.SITE_ID));
        values.put(NICE_NAME.getName(), site != null ? String.valueOf((Object) site.get(SiteModel.NAME)) : hostname);
        return values;
    }

    private static @NonNull Map<String, Object> reorder(@Nullable Row cert) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (cert == null) {
            return values;
        }
        String stored = cert.get(CertificateModel.DOMAIN_NAMES_TEXT);
        values.put(DOMAINS.getName(), stored == null || stored.isBlank() ? List.of() : List.of(stored.split(",")));
        values.put(NICE_NAME.getName(), text(cert.get(CertificateModel.NICE_NAME)));
        values.put(EMAIL.getName(), text(cert.get(CertificateModel.LETSENCRYPT_EMAIL)));
        String challenge = text(cert.get(CertificateModel.CHALLENGE_TYPE));
        values.put(CHALLENGE.getName(), challenge.isEmpty() ? CertificateModel.CHALLENGE_HTTP : challenge);
        String publisher = text(cert.get(CertificateModel.DNS_PUBLISHER));
        values.put(DNS_PUBLISHER.getName(), publisher.isEmpty() ? CertificateModel.DNS_PUBLISHER_MANUAL : publisher);
        return values;
    }

    private static @NonNull String text(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    static @NonNull Microcopy copy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "certificate_request");
    }

    /**
     * One coerced order; the components are named as the input form names its entries.
     *
     * @param domains           the names to certify, at least one
     * @param nice_name         the certificate's display name; the first name when blank
     * @param letsencrypt_email the ACME account email, null for the installation's own
     * @param challenge_type    {@link CertificateModel#CHALLENGE_HTTP} or {@link CertificateModel#CHALLENGE_DNS}
     * @param dns_publisher     the DNS-01 publisher, read only for a DNS challenge
     */
    public record Order(@NonNull List<String> domains, @Nullable String nice_name, @Nullable String letsencrypt_email,
                        @Nullable String challenge_type, @Nullable String dns_publisher) {

        public Order {
            domains = List.copyOf(domains);
        }

        /** @return the names to certify */
        public @NonNull List<String> hostnames() {
            return this.domains;
        }

        /** @return the display name, or null */
        public @Nullable String niceName() {
            return this.nice_name;
        }

        /** @return the account email, or null */
        public @Nullable String email() {
            return this.letsencrypt_email;
        }

        /** @return the challenge type, or null */
        public @Nullable String challenge() {
            return this.challenge_type;
        }

        /** @return the DNS-01 publisher, or null */
        public @Nullable String publisher() {
            return this.dns_publisher;
        }
    }
}
