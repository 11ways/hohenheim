package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.RelativeTime;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FieldGroup;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.attributes.FieldAttributes;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import be.elevenways.zenit.common.routing.RouteScope;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.ui.Timezones;
import be.elevenways.zenit.common.validation.Violations;
import org.bouncycastle.openssl.PEMParser;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TLS certificates: manual PEM uploads plus Let's Encrypt requests (via the
 * request page linked from the header). The internal ACME account row is
 * scoped out of every list/load.
 */
public class CertificateResource extends RowResource {

    /**
     * Display-only form entries: VIRTUAL string fields, never schema columns.
     *
     * AIDEV-NOTE: the stored columns cannot be shown directly here. A readonly entry
     * renders its raw value and NOTHING when that value is null, which is what left the
     * renewal panel with three labels above empty boxes and the DNS publisher with a
     * label, a description and no control at all. These carry an already-resolved
     * sentence instead, so an absent value reads "None" / "Not scheduled" rather than as
     * a rendering bug. They are bound {@code alwaysReadonly} below, so the submit
     * pipeline strips them before any write and the missing columns are never touched.
     */
    private static final StringField COVERED_NAMES_DISPLAY = displayField("covered_names_display",
        "cert_domain_names", "coverage");
    private static final StringField EXPIRY_DISPLAY = displayField("expiry_display",
        "cert_expires_on", "coverage");
    private static final StringField CHALLENGE_DISPLAY = displayField("challenge_type_display",
        "cert_challenge_type", "renewal");
    private static final StringField DNS_PUBLISHER_DISPLAY = displayField("dns_publisher_display",
        "cert_dns_publisher", "renewal");
    private static final StringField RENEWAL_ERROR_DISPLAY = displayField("renewal_error_display",
        "cert_renewal_error", "renewal");
    private static final StringField NEXT_ATTEMPT_DISPLAY = displayField("next_attempt_display",
        "cert_next_attempt_at", "renewal");

    /** Wall-clock shape of {@code Dates.wallText}, which needs a RenderContext this hook has not. */
    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final FormSpec formSpec = FormSpec.builder()
        .add(CertificateModel.NICE_NAME)
        .add(CertificateModel.CERTIFICATE_PEM)
        .add(CertificateModel.PRIVATE_KEY_PEM)
        .add(CertificateModel.AUTO_RENEW)
        .add(COVERED_NAMES_DISPLAY)
        .add(EXPIRY_DISPLAY)
        .add(CHALLENGE_DISPLAY)
        .add(DNS_PUBLISHER_DISPLAY)
        .add(RENEWAL_ERROR_DISPLAY)
        .add(CertificateModel.ERROR_COUNT)
        .add(NEXT_ATTEMPT_DISPLAY)
        .group(FieldGroup.of("coverage", Microcopy.of("coverage").withFilter("scope", "certificate")))
        .group(FieldGroup.of("renewal", Microcopy.of("renewal_status").withFilter("scope", "certificate")))
        .build();

    /** One virtual read-only entry: a label from the field catalog, no column behind it. */
    private static @NonNull StringField displayField(@NonNull String name, @NonNull String labelKey,
                                                     @NonNull String group) {
        return StringField.builder().name(name)
            .visibleIn(EditView.EDIT)
            .attribute(FieldAttributes.GROUP, group)
            .label(HohenheimFormCopy.label(labelKey))
            .build();
    }

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        // AIDEV-NOTE: eight visible columns down to five. Each pair below answers ONE
        // question in one cell: what does it cover, why is it in this state, and when is
        // the next thing going to happen to it.
        .column(ColumnSpec.fromField(CertificateModel.NICE_NAME).filterable()
            .subtext("domain_names_text").build())
        .column(ColumnSpec.fromField(CertificateModel.DOMAIN_NAMES_TEXT).filterable().hidden().build())
        .column(ColumnSpec.fromField(CertificateModel.PROVIDER).filterable().build())
        .column(ColumnSpec.fromField(CertificateModel.STATUS).filterable().subtext("renewal_error").build())
        .column(ColumnSpec.fromField(CertificateModel.RENEWAL_ERROR).filterable().hidden().build())
        .column(ColumnSpec.fromField(CertificateModel.CHALLENGE_TYPE).filterable().hidden().build())
        .column(ColumnSpec.fromField(CertificateModel.DNS_PUBLISHER).hidden().build())
        .column(ColumnSpec.fromField(CertificateModel.EXPIRES_ON).filterable()
            .subtext("next_attempt_at").build())
        .column(ColumnSpec.fromField(CertificateModel.NEXT_ATTEMPT_AT).hidden().build())
        .column(ColumnSpec.fromField(CertificateModel.ERROR_COUNT).hidden().build())
        .column(ColumnSpec.fromField(CertificateModel.CREATED_AT).filterable().build())
        .filter(FilterSpec.forField(CertificateModel.NICE_NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(CertificateModel.NICE_NAME)).build())
        .filter(FilterSpec.forField(CertificateModel.PROVIDER, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(CertificateModel.PROVIDER)).build())
        .filter(FilterSpec.forField(CertificateModel.DOMAIN_NAMES_TEXT, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(CertificateModel.DOMAIN_NAMES_TEXT)).build())
        .filter(FilterSpec.forField(CertificateModel.STATUS, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(CertificateModel.STATUS)).build())
        .filter(FilterSpec.forField(CertificateModel.EXPIRES_ON, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(CertificateModel.EXPIRES_ON)).build())
        .filter(FilterSpec.forField(CertificateModel.CREATED_AT, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(CertificateModel.CREATED_AT)).build())
        .defaultSort(SortSpec.desc(CertificateModel.CREATED_AT.getName()))
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "certificate"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "certificate"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "certificate"); }
    @Override public @NonNull String slug() { return "certificates"; }
    @Override public @NonNull Model model() { return Models.get(CertificateModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    /** Views deliberately dropped: a certificate list is read by expiry, never by a saved query. */
    @Override public @NonNull ListChrome listChrome() { return CmsSupport.WIDE_LIST; }

    /** The question asked here is always which certificate covers a hostname. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(CertificateModel.NICE_NAME, CertificateModel.DOMAIN_NAMES_TEXT);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.NETWORK_GROUP; }
    @Override public int navOrder() { return 20; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "certificate");
    }
    @Override public @NonNull Icon icon() { return Icon.of("certificate"); }

    /** Deleting a certificate destroys its private key; the type-level dialog says so. */
    @Override
    public @NonNull ConfirmationSpec deleteConfirmation() {
        return deleteConfirmation(Microcopy.of("delete_confirm").withFilter("scope", "certificate"));
    }

    /**
     * The same warning NAMING the domains this certificate secures, so an operator sees
     * which hostnames stop serving HTTPS before the key is gone.
     */
    @Override
    public @NonNull ConfirmationSpec deleteConfirmationFor(@NonNull Row record) {
        String domains = DeleteImpact.join(CertificateCoverage.namesOf(record));
        if (domains.isEmpty()) {
            return deleteConfirmation();
        }
        return deleteConfirmation(Microcopy.of("delete_confirm_domains")
            .withFilter("scope", "certificate")
            .withArg("name", String.valueOf((Object) record.get(CertificateModel.NICE_NAME)))
            .withArg("domains", domains));
    }


    /** provider/status are staged by persistRow but are not form entries; stamp them here. */
    @Override
    public @NonNull Row valuesToRow(@NonNull Map<String, Object> coerced) {
        Row row = super.valuesToRow(coerced);
        if (coerced.get("provider") instanceof String provider) {
            row.set(CertificateModel.PROVIDER, provider);
        }
        if (coerced.get("status") instanceof String status) {
            row.set(CertificateModel.STATUS, status);
        }
        return row;
    }

    /** Coverage and renewal diagnostics are written by the ACME machinery, never by hand. */
    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        return List.of(
            ResourceFieldBinding.of(COVERED_NAMES_DISPLAY.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(EXPIRY_DISPLAY.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(CHALLENGE_DISPLAY.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(DNS_PUBLISHER_DISPLAY.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(RENEWAL_ERROR_DISPLAY.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(CertificateModel.ERROR_COUNT.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(NEXT_ATTEMPT_DISPLAY.getName(), FieldAccess.alwaysReadonly()));
    }

    /**
     * Fill the display-only entries: what this certificate covers, when it expires, and
     * why the last renewal did or did not happen -- each as a sentence that says
     * something when the underlying column is empty.
     */
    @Override
    public @NonNull Map<String, Object> valuesFromRow(@NonNull Row row) {
        Map<String, Object> values = new LinkedHashMap<>(super.valuesFromRow(row));
        List<String> names = CertificateCoverage.namesOf(row);
        values.put(COVERED_NAMES_DISPLAY.getName(),
            names.isEmpty() ? copy("coverage_none") : String.join(", ", names));
        values.put(EXPIRY_DISPLAY.getName(), instantText(row.get(CertificateModel.EXPIRES_ON),
            copy("expiry_none")));
        values.put(CHALLENGE_DISPLAY.getName(), orNone(
            CmsSupport.enumLabel(CertificateModel.CHALLENGE_TYPE, row.get(CertificateModel.CHALLENGE_TYPE))));
        values.put(DNS_PUBLISHER_DISPLAY.getName(), orNone(
            CmsSupport.enumLabel(CertificateModel.DNS_PUBLISHER, row.get(CertificateModel.DNS_PUBLISHER))));
        values.put(RENEWAL_ERROR_DISPLAY.getName(), orNone(row.get(CertificateModel.RENEWAL_ERROR)));
        Integer errors = row.get(CertificateModel.ERROR_COUNT);
        values.put(CertificateModel.ERROR_COUNT.getName(), errors != null ? errors : 0);
        values.put(NEXT_ATTEMPT_DISPLAY.getName(), instantText(row.get(CertificateModel.NEXT_ATTEMPT_AT),
            copy("next_attempt_none")));
        return values;
    }

    /** An absolute wall-clock stamp plus the relative wording, or the absence sentence. */
    private static @NonNull String instantText(@Nullable Instant instant, @NonNull String absent) {
        if (instant == null) {
            return absent;
        }
        return WALL_CLOCK.format(instant.atZone(viewerZone()))
            + " (" + RelativeTime.ago(instant, wording()) + ")";
    }

    /** The viewer's own zone, falling back to UTC when no request or cookie says otherwise. */
    private static @NonNull ZoneId viewerZone() {
        try {
            return ZoneId.of(Timezones.current(RouteScope.currentConduit()));
        } catch (RuntimeException unknownZone) {
            return ZoneOffset.UTC;
        }
    }

    /** Request-locale relative-time wording; null falls back to the English defaults. */
    private static @Nullable RelativeTimeWording wording() {
        Conduit conduit = RouteScope.currentConduit();
        return conduit == null ? null
            : RelativeTimeWording.resolve(conduit.getLocales(), conduit.getMessageResolver());
    }

    private static @NonNull String orNone(@Nullable Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? copy("value_none") : text;
    }

    private static @NonNull String copy(@NonNull String key) {
        return CmsSupport.resolvedTextOrDefault(Microcopy.of(key).withFilter("scope", "certificate"));
    }

    /** Scope out the internal ACME account row everywhere. */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> AccessDecision.allow(QueryPredicate.of(new CompositeCriteria(CompositeOperator.OR,
            CertificateModel.PROVIDER.isNull(),
            CertificateModel.PROVIDER.ne(CertificateModel.PROVIDER_ACME_ACCOUNT))));
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        validatePems(values, null);
        values.put("provider", "custom");
        values.put("status", CertificateModel.STATUS_ACTIVE);
        values.put(CertificateModel.AUTO_RENEW.getName(), false);
        return super.persistRow(values, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        validatePems(values, existing);
        if (CertificateModel.DNS_PUBLISHER_MANUAL.equals(existing.get(CertificateModel.DNS_PUBLISHER))) {
            values.put(CertificateModel.AUTO_RENEW.getName(), false);
        }
        super.updateRow(existing, values, accessContext);
    }

    /**
     * All three parts must be present and parseable, submitted or stored.
     *
     * AIDEV-NOTE: each read takes the STORED value when the write does not carry the key.
     * The inline cell lane hands updateRow a map holding EXACTLY ONE entry, so demanding
     * all three off that map refused every partial write with "cert_fields_required" --
     * about a certificate body that was sitting in the row all along. Re-parsing the
     * stored PEMs on an unrelated edit is deliberate: they are the record's whole point,
     * and a row that cannot parse must not be saved further.
     *
     * @param existing the stored certificate, or null on a create
     */
    private static void validatePems(@NonNull Map<String, Object> coerced,
                                     @Nullable Row existing) {
        String certPem = CmsSupport.textOf(coerced, existing, CertificateModel.CERTIFICATE_PEM);
        String keyPem = CmsSupport.textOf(coerced, existing, CertificateModel.PRIVATE_KEY_PEM);
        String name = CmsSupport.textOf(coerced, existing, CertificateModel.NICE_NAME);
        if (name.isEmpty() || certPem.isEmpty() || keyPem.isEmpty()) {
            throw Violations.ofForm(CmsSupport.violationText("cert_fields_required"));
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            cf.generateCertificates(new ByteArrayInputStream(certPem.getBytes()));
        } catch (Exception e) {
            throw Violations.ofField("certificate_pem", null,
                CmsSupport.violationText("cert_pem_invalid").withArg("detail", e.getMessage()));
        }
        try {
            new PEMParser(new StringReader(keyPem)).readObject();
        } catch (Exception e) {
            throw Violations.ofField("private_key_pem", null,
                CmsSupport.violationText("key_pem_invalid").withArg("detail", e.getMessage()));
        }
    }


    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(RowAction.Url.<Row>builder(Identifier.of("hohenheim", "download_certificate"))
            .label(Microcopy.of("download").withFilter("scope", "certificate"))
            .icon(Icon.of("download"))
            .url(row -> new Uri(HohenheimEndpoints.CERTIFICATES_DOWNLOAD
                .with(HohenheimEndpoints.CERT_ID, row.get(CertificateModel.ID)).toUrl()))
            // Exporting the PEM is rare next to edit/delete: overflow, not inline.
            .inlineInRow(false)
            .build());
        // Re-ordering a certificate is how a domain is added or HTTP-01/DNS-01 is switched:
        // the row's own domain list and challenge are readonly on the form because they
        // describe what the CA actually issued, and only a new order may change them.
        actions.add(RowAction.Url.<Row>builder(Identifier.of("hohenheim", "reissue_certificate"))
            .label(Microcopy.of("reissue").withFilter("scope", "certificate"))
            .icon(Icon.of("rotate"))
            .url(row -> new Uri(CmsRoutes.list("admin", "certificates-request")
                .with(HohenheimParams.CERTIFICATE_REISSUE, row.get(CertificateModel.ID)).toUrl()))
            // A manual upload has no order to repeat, and the ACME account row is not a
            // certificate at all. The page and the handler refuse them again -- this only
            // stops offering an action that could never succeed.
            .visibleFor((row, ctx) -> CertificateModel.PROVIDER_LETSENCRYPT
                .equals(row.get(CertificateModel.PROVIDER)))
            .inlineInRow(false)
            .build());
        return actions;
    }

    /** The order page is a sibling PEER, so it is declared as one rather than linked. */
    @Override
    public @NonNull List<RelatedPage> relatedPages() {
        return List.of(RelatedPage.toPeer("certificates-request"));
    }
}
