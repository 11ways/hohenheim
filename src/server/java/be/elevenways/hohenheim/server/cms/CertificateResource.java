package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.HeaderAction;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.protoblast.common.http.Uri;
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
public final class CertificateResource extends HohenheimRowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(CertificateModel.NICE_NAME)
        .add(CertificateModel.CERTIFICATE_PEM)
        .add(CertificateModel.PRIVATE_KEY_PEM)
        .add(CertificateModel.AUTO_RENEW)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(CertificateModel.NICE_NAME).build())
        .column(ColumnSpec.fromField(CertificateModel.PROVIDER).build())
        .column(ColumnSpec.fromField(CertificateModel.DOMAIN_NAMES_TEXT).build())
        .column(ColumnSpec.fromField(CertificateModel.STATUS).build())
        .column(ColumnSpec.fromField(CertificateModel.EXPIRES_ON).build())
        .column(ColumnSpec.fromField(CertificateModel.CREATED_AT).build())
        .defaultSort(SortSpec.desc(CertificateModel.CREATED_AT.getName()))
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "certificate"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("hohenheim.certificate.plural"); }
    @Override public @NonNull String slug() { return "certificates"; }
    @Override public @NonNull Model model() { return Models.get(CertificateModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.PROXY_GROUP; }
    @Override public int navOrder() { return 30; }
    @Override public @NonNull Icon icon() { return Icon.of("certificate"); }

    @Override protected @NonNull String auditResourceType() { return AuditLogModel.RESOURCE_CERTIFICATE; }

    @Override
    protected @Nullable String auditName(@NonNull Row row) {
        return row.get(CertificateModel.NICE_NAME);
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
        Map<String, Object> values = mutable(coerced);
        validatePems(values);
        values.put("provider", "custom");
        values.put("status", CertificateModel.STATUS_ACTIVE);
        return super.persistRow(values, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        validatePems(coerced);
        super.updateRow(existing, coerced, accessContext);
    }

    private static void validatePems(@NonNull Map<String, Object> coerced) {
        String certPem = trimmed(coerced.get("certificate_pem"));
        String keyPem = trimmed(coerced.get("private_key_pem"));
        String name = trimmed(coerced.get("nice_name"));
        if (name.isEmpty() || certPem.isEmpty() || keyPem.isEmpty()) {
            throw new IllegalStateException("Name, certificate, and private key are required");
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            cf.generateCertificates(new ByteArrayInputStream(certPem.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid certificate PEM: " + e.getMessage());
        }
        try {
            new PEMParser(new StringReader(keyPem)).readObject();
        } catch (Exception e) {
            throw new IllegalStateException("Invalid private key PEM: " + e.getMessage());
        }
    }

    private static @NonNull String trimmed(@Nullable Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(RowAction.Url.<Row>builder(Identifier.of("hohenheim", "download_certificate"))
            .label("hohenheim.certificate.download")
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
            .label("hohenheim.certificate.request_le")
            .icon(Icon.of("lock"))
            .url(new Uri("/admin/certificates-request"))
            .build());
        return actions;
    }
}
