package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.HeaderAction;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FieldGroup;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.bouncycastle.openssl.PEMParser;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TLS certificates: manual PEM uploads plus Let's Encrypt requests (via the
 * request page linked from the header). The internal ACME account row is
 * scoped out of every list/load.
 */
public class CertificateResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(CertificateModel.NICE_NAME)
        .add(CertificateModel.CERTIFICATE_PEM)
        .add(CertificateModel.PRIVATE_KEY_PEM)
        .add(CertificateModel.AUTO_RENEW)
        .add(CertificateModel.CHALLENGE_TYPE)
        .add(CertificateModel.DNS_PUBLISHER)
        .add(CertificateModel.RENEWAL_ERROR)
        .add(CertificateModel.ERROR_COUNT)
        .add(CertificateModel.NEXT_ATTEMPT_AT)
        .group(FieldGroup.of("renewal", Microcopy.of("renewal_status").withFilter("scope", "certificate")))
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(CertificateModel.NICE_NAME).filterable().build())
        .column(ColumnSpec.fromField(CertificateModel.PROVIDER).filterable().build())
        .column(ColumnSpec.fromField(CertificateModel.DOMAIN_NAMES_TEXT).filterable().build())
        .column(ColumnSpec.fromField(CertificateModel.STATUS).filterable().build())
        .column(ColumnSpec.fromField(CertificateModel.CHALLENGE_TYPE).filterable().build())
        .column(ColumnSpec.fromField(CertificateModel.DNS_PUBLISHER).hidden().build())
        .column(ColumnSpec.fromField(CertificateModel.RENEWAL_ERROR).filterable().build())
        .column(ColumnSpec.fromField(CertificateModel.NEXT_ATTEMPT_AT).hidden().build())
        .column(ColumnSpec.fromField(CertificateModel.ERROR_COUNT).hidden().build())
        .column(ColumnSpec.fromField(CertificateModel.EXPIRES_ON).filterable().build())
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
    @Override public @NonNull String slug() { return "certificates"; }
    @Override public @NonNull Model model() { return Models.get(CertificateModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.NETWORK_GROUP; }
    @Override public int navOrder() { return 20; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "certificate");
    }
    @Override public @NonNull Icon icon() { return Icon.of("certificate"); }


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

    /** Renewal diagnostics are written by the ACME machinery, never by hand. */
    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        return List.of(
            ResourceFieldBinding.of(CertificateModel.RENEWAL_ERROR.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(CertificateModel.ERROR_COUNT.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(CertificateModel.NEXT_ATTEMPT_AT.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(CertificateModel.CHALLENGE_TYPE.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(CertificateModel.DNS_PUBLISHER.getName(), FieldAccess.alwaysReadonly()));
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
        validatePems(values);
        values.put("provider", "custom");
        values.put("status", CertificateModel.STATUS_ACTIVE);
        values.put(CertificateModel.AUTO_RENEW.getName(), false);
        return super.persistRow(values, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        validatePems(values);
        if (CertificateModel.DNS_PUBLISHER_MANUAL.equals(existing.get(CertificateModel.DNS_PUBLISHER))) {
            values.put(CertificateModel.AUTO_RENEW.getName(), false);
        }
        super.updateRow(existing, values, accessContext);
    }

    private static void validatePems(@NonNull Map<String, Object> coerced) {
        String certPem = trimmed(coerced.get("certificate_pem"));
        String keyPem = trimmed(coerced.get("private_key_pem"));
        String name = trimmed(coerced.get("nice_name"));
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

    private static @NonNull String trimmed(@Nullable Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(RowAction.Url.<Row>builder(Identifier.of("hohenheim", "download_certificate"))
            .label(Microcopy.of("download").withFilter("scope", "certificate"))
            .icon(Icon.of("download"))
            .url(row -> new Uri(HohenheimEndpoints.CERTIFICATES_DOWNLOAD
                .with(HohenheimEndpoints.CERT_ID, row.get(CertificateModel.ID)).toUrl()))
            .build());
        return actions;
    }

    @Override
    public @NonNull List<HeaderAction> headerActions() {
        List<HeaderAction> actions = new ArrayList<>(super.headerActions());
        actions.add(HeaderAction.Url.builder(Identifier.of("hohenheim", "request_letsencrypt"))
            .label(Microcopy.of("request_le").withFilter("scope", "certificate"))
            .icon(Icon.of("lock"))
            .url(new Uri(CmsRoutes.list("admin", "certificates-request").toUrl()))
            .build());
        return actions;
    }
}
