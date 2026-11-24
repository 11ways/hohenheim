package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.tls.RenewalFailure;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimVerbs;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
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
import be.elevenways.zenit.common.operation.OperationVerb;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
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
import java.util.Set;

import static be.elevenways.hohenheim.RawValues.trimmed;

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
    public static final SubjectType<Row> DOMAIN = SubjectType.record(SiteDomainModel.REF);

    /** A stored certificate: re-ordered, or its manual DNS-01 order continued. */
    public static final SubjectType<Row> CERTIFICATE = SubjectType.record(CertificateModel.REF);

    private static final StringField DOMAIN_NAME = StringField.builder("domain").placeholder("example.com").build();

    public static final ListField<String> DOMAINS = ListField.<String>builder(DOMAIN_NAME)
        .name("domains")
        .inputName("hostnames")
        .label(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("domains"))
        .help(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("domains_help"))
        .build();

    static final StringField NICE_NAME = StringField.builder("nice_name").inputName("name")
        .label(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("name"))
        .help(HohenheimMicrocopy.HELP.of("certificate_name"))
        .placeholder("example.com")
        .build();

    static final StringField EMAIL = StringField.builder("letsencrypt_email").inputName("email")
        .label(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("account_email"))
        .help(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("account_email_help"))
        .build();

    public static final EnumField CHALLENGE = CertificateModel.challengeTypeField()
        .inputName("challenge")
        .defaultValue(CertificateModel.CHALLENGE_HTTP)
        .label(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("validation"))
        .help(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("validation_help"))
        .build();

    public static final EnumField DNS_PUBLISHER = CertificateModel.dnsPublisherField()
        .defaultValue(CertificateModel.DNS_PUBLISHER_MANUAL)
        .label(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("dns_mode"))
        .help(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("dns_mode_help"))
        .build();

    // The dialog's, the HTTP API's and the assistant's one input: each entry goes by its field's input name.
    private static final FormSpec INPUT = FormSpec.builder()
        .add(Array.of(DOMAINS, DOMAIN_NAME).minCount(1).build().named(DOMAINS.getInputName()))
        .add(FieldFormEntryRegistry.INSTANCE.deriveInputEntry(NICE_NAME))
        .add(FieldFormEntryRegistry.INSTANCE.deriveInputEntry(EMAIL))
        .add(FieldFormEntryRegistry.INSTANCE.deriveInputEntry(CHALLENGE))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DNS_PUBLISHER))
        .showWhen(DNS_PUBLISHER.getName(), CHALLENGE.getInputName(), CertificateModel.CHALLENGE_DNS)
        .build();

    private static final OperationInput<Order> ORDER = OperationInput.of(INPUT, Order.class, values -> new Order(
        PrimitiveCoercion.toTrimmedTextList(values.get(DOMAINS)), values.get(NICE_NAME), values.get(EMAIL),
        values.get(CHALLENGE), values.get(DNS_PUBLISHER)));

    /** A new certificate for the names the operator lists; the certificate list's header action. */
    public static final Operation<Void, Order, Integer> REQUEST =
            Operation.declare(HohenheimIds.id("request_certificate"))
        .happened(OperationSentences.of("request_certificate"))
        .label(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("get_certificate"))
        .description(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("uses_production"))
        .icon(Icon.of("lock"))
        .noSubject()
        .verb(OperationVerb.CREATE)
        .noun(CertificateModel.REF)
        .gate(OperationGate.open())
        .input(ORDER)
        .result(Integer.class)
        .createdRecord(CertificateModel.MODEL_ID, id -> id)
        .facts(OperationFact.REACHES_OUTSIDE)
        .rateLimit(HohenheimEndpoints.LE_REQUEST_LIMIT)
        .command(CmsCommands.EXTERNAL)
        .register();

    /** A new certificate for one domain row's hostname, prefilled; the domain rows' action. */
    public static final Operation<Row, Order, Integer> REQUEST_FOR_DOMAIN =
        Operation.declare(HohenheimIds.id("request_domain_certificate"))
            .happened(OperationSentences.of("request_domain_certificate"))
            .label(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("get_certificate"))
            .description(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("uses_production"))
            .icon(Icon.of("lock"))
            .one(DOMAIN)
            .verb(HohenheimVerbs.ORDER_CERTIFICATE)
            .gate(OperationGate.open())
            .input(ORDER)
            // An assistant's order may leave out what the row's dialog prefills: its hostname and its site's name.
            .patchable()
            .result(Integer.class)
            .createdRecord(CertificateModel.MODEL_ID, id -> id)
            .facts(OperationFact.REACHES_OUTSIDE)
            .rateLimit(HohenheimEndpoints.LE_REQUEST_LIMIT)
            .command(CmsCommands.EXTERNAL)
            .register();

    /** A new order written back into an existing Let's Encrypt certificate: how names or the challenge change. */
    public static final Operation<Row, Order, Integer> REISSUE =
            Operation.declare(HohenheimIds.id("reissue_certificate"))
        .happened(OperationSentences.of("reissue_certificate"))
        .label(HohenheimMicrocopy.CERTIFICATE.of("reissue"))
        .description(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("uses_production"))
        .icon(Icon.of("rotate"))
        .one(CERTIFICATE)
        .verb(HohenheimVerbs.REISSUE)
        .gate(OperationGate.open())
        .input(ORDER)
        // An assistant's re-order may leave out what the dialog prefills: what the certificate was last issued for.
        .patchable()
        .result(Integer.class)
        .createdRecord(CertificateModel.MODEL_ID, id -> id)
        .facts(OperationFact.REACHES_OUTSIDE)
        .rateLimit(HohenheimEndpoints.LE_REQUEST_LIMIT)
        .command(CmsCommands.EXTERNAL)
        .register();

    /** Finishes a manual DNS-01 order after its TXT records are published. */
    public static final Operation<Row, Void, Integer> CONTINUE_DNS =
            Operation.declare(HohenheimIds.id("continue_dns_order"))
        .happened(OperationSentences.of("continue_dns_order"))
        .label(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("verify_dns"))
        .description(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("verify_dns_hint"))
        .icon(Icon.of("circle-check"))
        .one(CERTIFICATE)
        .verb(HohenheimVerbs.CONTINUE_ORDER)
        .gate(OperationGate.open())
        .result(Integer.class)
        .facts(OperationFact.REACHES_OUTSIDE)
        .rateLimit(HohenheimEndpoints.LE_REQUEST_LIMIT)
        .command(CmsCommands.EXTERNAL)
        .register();

    /** Renews a Let's Encrypt certificate now, as the renewal sweep would: the fix a failing renewal offers. */
    public static final Operation<Row, Void, Integer> RENEW =
            Operation.declare(HohenheimIds.id("renew_certificate"))
        .happened(OperationSentences.of("renew_certificate"))
        .label(HohenheimMicrocopy.CERTIFICATE.of("renew_now"))
        .description(HohenheimMicrocopy.CERTIFICATE.of("renew_now_description"))
        .icon(Icon.of("rotate-right"))
        .one(CERTIFICATE)
        .verb(HohenheimVerbs.RENEW)
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

    /** @return the certificate list's header action, "Get a certificate" beside the upload */
    static @NonNull PanelAction<Row> requestAction() {
        init();
        return PanelAction.<Row, Integer>places(REQUEST, ActionPlacement.HEADER, CertificateOperations::opened)
            .confirmation(requestConfirmation(null))
            .inSheet()
            .inlineInHeader(true)
            .build();
    }

    /** @return a domain row's "Get a certificate", prefilled with its hostname */
    static @NonNull PanelAction<Row> requestForDomainAction() {
        init();
        return PanelAction.<Row, Integer>places(REQUEST_FOR_DOMAIN, ActionPlacement.ROW, CertificateOperations::opened)
            .confirmation(requestConfirmation(null))
            .dynamicConfirmation(domain -> requestConfirmation(domain.get(SiteDomainModel.HOSTNAME)))
            .inSheet()
            .inputValues((domain, request) -> prefill(domain))
            // A name a working certificate already covers needs no new one: changing that certificate is its own
            // reissue, on the certificate's row. Working is what the proxy loaded: a stored row it cannot load
            // covers nothing, so its name is offered a certificate. The working names are read once per page.
            .hiddenWhenAmong((domains, access) -> {
                Set<String> working = CertificateCoverage.workingNames();
                return domain -> CertificateCoverage.covers(working, domain.get(SiteDomainModel.HOSTNAME));
            })
            .build();
    }

    /** @return a Let's Encrypt certificate's re-order, prefilled with what it was last issued for */
    static @NonNull PanelAction<Row> reissueAction() {
        init();
        return PanelAction.<Row, Integer>places(REISSUE, ActionPlacement.ROW, CertificateOperations::opened)
            .confirmation(reissueConfirmation(null))
            .dynamicConfirmation(CertificateOperations::reissueConfirmation)
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
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("verify_dns"),
                HohenheimMicrocopy.CERTIFICATE_REQUEST.of("publish_description"), ActionStyle.PRIMARY))
            .build();
    }

    /**
     * @return a failing Let's Encrypt certificate's "Renew now"; kept in the record's menu so its health band, which
     *         names it as the failure's fix, is where the record page offers it
     */
    static @NonNull PanelAction<Row> renewAction() {
        init();
        return PanelAction.<Row, Integer>places(RENEW, ActionPlacement.ROW, CertificateOperations::opened)
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.CERTIFICATE.of("renew_now"),
                HohenheimMicrocopy.CERTIFICATE.of("renew_now_description"), ActionStyle.PRIMARY))
            .hiddenWhen(cert -> !RenewalFailure.failing(cert))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .build();
    }

    /**
     * The row's dialog asks about the address it orders for; the fallback serves an offer drawn without its row.
     *
     * @param hostname the domain row's name, null for none
     */
    private static @NonNull ConfirmationSpec requestConfirmation(@Nullable String hostname) {
        HohenheimMicrocopy copy = HohenheimMicrocopy.CERTIFICATE_REQUEST;
        if (hostname == null || hostname.isBlank()) {
            return ConfirmationSpec.of(copy.of("get_certificate_title"), copy.of("get_certificate"),
                copy.of("uses_production"), ActionStyle.PRIMARY);
        }
        return ConfirmationSpec.of(copy.of("get_certificate_for").withArg("hostname", hostname),
            copy.of("get_certificate"), copy.of("get_certificate_for_body").withArg("hostname", hostname),
            ActionStyle.PRIMARY);
    }

    /**
     * The re-order's dialog names the domains the certificate secures; the fallback serves an offer drawn without its
     * row.
     *
     * @param cert the certificate row, null for none
     */
    private static @NonNull ConfirmationSpec reissueConfirmation(@Nullable Row cert) {
        HohenheimMicrocopy copy = HohenheimMicrocopy.CERTIFICATE_REQUEST;
        String domains = cert == null ? "" : DeleteImpact.join(CertificateCoverage.namesOf(cert));
        return ConfirmationSpec.of(domains.isEmpty() ? copy.of("reissue_title")
                : copy.of("reissue_title_for").withArg("domains", domains),
            copy.of("reissue_submit"), copy.of("reissue_notice"), ActionStyle.PRIMARY);
    }

    /**
     * Every successful order opens the certificate it wrote at its front door: the overview, where a waiting DNS-01
     * order shows its TXT records, never the edit form an operator lands on through the plain record URL.
     */
    private static @NonNull CmsActionResult opened(@NonNull ActionRequest<Row> request,
                                                   @Nullable Integer certificateId) {
        return CmsActionResult.redirect(new Uri(CmsRoutes.open(request.request().panelSlug(),
            HohenheimSlugs.CERTIFICATES, certificateId).toUrl()));
    }

    /**
     * @return an HTTP API order as the order's input takes it: the API names its fields as they are stored
     *     (domains, nice_name, letsencrypt_email, challenge_type), the input by their input names
     */
    public static @NonNull Map<String, Object> apiOrder(@NonNull Map<String, Object> posted, @NonNull String hostname) {
        Map<String, Object> order = INPUT.byEntryNames(posted);
        order.putIfAbsent(DOMAINS.getInputName(), hostname);
        return order;
    }

    /** @return the order a domain row's dialog opens with, the base an assistant's order for it lands on */
    static @NonNull Order prefilledOrder(@NonNull Row domain) {
        return orderOf(prefill(domain));
    }

    /** @return the re-order a certificate's dialog opens with, the base an assistant's re-order lands on */
    static @NonNull Order reorderOf(@NonNull Row cert) {
        return orderOf(reorder(cert));
    }

    /** @return the order the dialog's prefilled values say, read as the input form names its entries */
    private static @NonNull Order orderOf(@NonNull Map<String, Object> values) {
        Object domains = values.get(DOMAINS.getInputName());
        return new Order(PrimitiveCoercion.toTrimmedTextList(domains == null ? List.of() : domains),
            (String) values.get(NICE_NAME.getInputName()), (String) values.get(EMAIL.getInputName()),
            (String) values.get(CHALLENGE.getInputName()), (String) values.get(DNS_PUBLISHER.getName()));
    }

    private static @NonNull Map<String, Object> prefill(@Nullable Row domain) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (domain == null) {
            return values;
        }
        String hostname = domain.get(SiteDomainModel.HOSTNAME);
        values.put(DOMAINS.getInputName(), hostname == null ? List.of() : List.of(hostname));
        Row site = Models.get(SiteModel.class).findById(domain.get(SiteDomainModel.SITE_ID));
        values.put(NICE_NAME.getInputName(), site != null ? String.valueOf((Object) site.get(SiteModel.NAME))
            : hostname);
        return values;
    }

    private static @NonNull Map<String, Object> reorder(@Nullable Row cert) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (cert == null) {
            return values;
        }
        String stored = cert.get(CertificateModel.DOMAIN_NAMES_TEXT);
        values.put(DOMAINS.getInputName(), stored == null || stored.isBlank() ? List.of()
            : List.of(stored.split(",")));
        values.put(NICE_NAME.getInputName(), trimmed(cert.get(CertificateModel.NICE_NAME)));
        values.put(EMAIL.getInputName(), trimmed(cert.get(CertificateModel.LETSENCRYPT_EMAIL)));
        String challenge = trimmed(cert.get(CertificateModel.CHALLENGE_TYPE));
        values.put(CHALLENGE.getInputName(), challenge.isEmpty() ? CertificateModel.CHALLENGE_HTTP : challenge);
        String publisher = trimmed(cert.get(CertificateModel.DNS_PUBLISHER));
        values.put(DNS_PUBLISHER.getName(), publisher.isEmpty() ? CertificateModel.DNS_PUBLISHER_MANUAL : publisher);
        return values;
    }

    /**
     * One coerced order; the components are named as the input form names its entries.
     *
     * @param hostnames the names to certify, at least one
     * @param name      the certificate's display name; the first name when blank
     * @param email     the ACME account email, null for the installation's own
     * @param challenge {@link CertificateModel#CHALLENGE_HTTP} or {@link CertificateModel#CHALLENGE_DNS}
     * @param dns_publisher the DNS-01 publisher, read only for a DNS challenge
     */
    public record Order(@NonNull List<String> hostnames, @Nullable String name, @Nullable String email,
                        @Nullable String challenge, @Nullable String dns_publisher) {

        public Order {
            hostnames = List.copyOf(hostnames);
        }

        /** @return the display name, or null */
        public @Nullable String niceName() {
            return this.name;
        }

        /** @return the DNS-01 publisher, or null */
        public @Nullable String publisher() {
            return this.dns_publisher;
        }
    }
}
