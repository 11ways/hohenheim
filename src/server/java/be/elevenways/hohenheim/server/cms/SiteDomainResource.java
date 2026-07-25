package be.elevenways.hohenheim.server.cms;


import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.hohenheim.server.sitetype.types.TlsPassthroughSiteType;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Site domain entries: hostname matching, TLS/HSTS toggles, per-domain
 * headers and certificate pinning. Hidden from the sidebar -- reached
 * through a site's Domains tab.
 */
public class SiteDomainResource extends RowResource {

    static final List<FieldOption<String>> MATCH_OPTIONS = List.of(
        FieldOption.of(SiteDomainModel.MATCH_EXACT,
            Microcopy.of("exact").withFilter("scope", "domain_match")),
        FieldOption.of("wildcard",
            Microcopy.of("wildcard").withFilter("scope", "domain_match")),
        FieldOption.of("regex",
            Microcopy.of("regex").withFilter("scope", "domain_match")));

    /** Discovered local addresses (refreshed hourly by UpdateSystemIpAddresses); blank = all interfaces. */
    static List<FieldOption<String>> listenOnOptions() {
        List<FieldOption<String>> options = new ArrayList<>();
        for (String address : UpdateSystemIpAddresses.getLocalAddresses()) {
            options.add(FieldOption.of(address, address));
        }
        return options;
    }

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(SiteDomainModel.SITE_ID, SiteModel.MODEL_ID).build())
        .add(SiteDomainModel.HOSTNAME)
        .add(Select.of(SiteDomainModel.MATCH_TYPE).options(OptionSource.of(MATCH_OPTIONS)).build())
        .add(Select.of(SiteDomainModel.LISTEN_ON)
            .options(OptionSource.dynamic(ctx -> listenOnOptions()))
            .build())
        .add(SiteDomainModel.PATH)
        .add(SiteDomainModel.STRIP_PATH)
        .add(SiteDomainModel.FORCE_SSL)
        .add(RelationPick.of(SiteDomainModel.CERTIFICATE_ID, CertificateModel.MODEL_ID).build())
        .add(SiteDomainModel.HSTS_ENABLED)
        .add(SiteDomainModel.HSTS_SUBDOMAINS)
        .add(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteDomainModel.CUSTOM_HEADERS))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteDomainModel.RESPONSE_HEADERS))
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(SiteDomainModel.HOSTNAME).filterable().build())
        .column(ColumnSpec.fromField(SiteDomainModel.MATCH_TYPE).filterable().build())
        .column(ColumnSpec.fromField(SiteDomainModel.FORCE_SSL).filterable().build())
        .column(ColumnSpec.fromField(SiteDomainModel.SITE_ID)
            .relation(RelationPick.of(SiteDomainModel.SITE_ID, SiteModel.MODEL_ID).build()).build())
        .filter(FilterSpec.forField(SiteDomainModel.HOSTNAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(SiteDomainModel.HOSTNAME)).build())
        .filter(FilterSpec.forField(SiteDomainModel.MATCH_TYPE, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(SiteDomainModel.MATCH_TYPE)).build())
        .filter(FilterSpec.forField(SiteDomainModel.FORCE_SSL, FilterSpec.Kind.BOOLEAN)
            .label(FieldLabels.labelFor(SiteDomainModel.FORCE_SSL)).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "site_domain"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "site_domain"); }
    @Override public @NonNull String slug() { return "domains"; }
    @Override public @NonNull Model model() { return Models.get(SiteDomainModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.PROXY_GROUP; }
    @Override public int navOrder() { return 20; }
    @Override public @NonNull Icon icon() { return Icon.of("at"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @org.checkerframework.checker.nullness.qual.Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("sites", row -> row.get(SiteDomainModel.SITE_ID)).tab("domains");
    }


    /** The site's Domains tab links here with ?site_id= so the pick is preselected. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(formSpec().defaultValues());
        String siteId = conduit.getQueryParam("site_id");
        if (siteId != null && !siteId.isEmpty()) {
            try {
                int parsedSiteId = Integer.parseInt(siteId);
                values.put("site_id", parsedSiteId);
                Row site = Models.get(SiteModel.class).findById(parsedSiteId);
                if (site != null && TlsPassthroughSiteType.ID.toString()
                        .equals(site.get(SiteModel.SITE_TYPE))) {
                    values.put("force_ssl", false);
                    values.put("exclude_from_letsencrypt", true);
                }
            } catch (NumberFormatException ignored) {
                // Malformed prefill: render the bare form.
            }
        }
        return Map.copyOf(values);
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        validate(coerced, null);
        return super.persistRow(coerced, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        validate(coerced, existing);
        super.updateRow(existing, coerced, accessContext);
    }

    /** Hostname required and unique per site. */
    private void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        Object hostnameValue = coerced.get("hostname");
        String hostname = hostnameValue != null ? String.valueOf(hostnameValue).trim() : "";
        if (hostname.isEmpty()) {
            throw Violations.ofField("hostname", hostname, CmsSupport.violationText("hostname_required"));
        }
        Object siteIdValue = coerced.containsKey("site_id") ? coerced.get("site_id")
            : existing != null ? existing.get(SiteDomainModel.SITE_ID) : null;
        if (!(siteIdValue instanceof Integer siteId)) {
            throw Violations.ofField("site_id", siteIdValue, CmsSupport.violationText("site_required"));
        }
        Row site = Models.get(SiteModel.class).findById(siteId);
        if (site != null && TlsPassthroughSiteType.ID.toString()
                .equals(site.get(SiteModel.SITE_TYPE))) {
            validateTlsPassthroughDomain(coerced);
        }
        Row duplicate = this.model().find()
            .where(SiteDomainModel.SITE_ID.eq(siteId))
            .where(SiteDomainModel.HOSTNAME.eq(hostname))
            .first();
        if (duplicate != null
            && (existing == null || !duplicate.get(SiteDomainModel.ID).equals(existing.get(SiteDomainModel.ID)))) {
            throw Violations.ofField("hostname", hostname, CmsSupport.violationText("hostname_taken"));
        }
    }

    private static void validateTlsPassthroughDomain(Map<String, Object> values) {
        String path = values.get("path") != null ? String.valueOf(values.get("path")).trim() : "";
        if (!path.isEmpty() && !"/".equals(path)) {
            throw Violations.ofField("path", path,
                CmsSupport.violationText("tls_passthrough_no_path"));
        }
        if (Boolean.TRUE.equals(values.get("strip_path"))) {
            throw Violations.ofField("strip_path", true,
                CmsSupport.violationText("tls_passthrough_no_http_options"));
        }
        if (values.get("certificate_id") != null) {
            throw Violations.ofField("certificate_id", values.get("certificate_id"),
                CmsSupport.violationText("tls_passthrough_backend_certificate"));
        }
        if (Boolean.TRUE.equals(values.get("hsts_enabled"))
                || Boolean.TRUE.equals(values.get("hsts_subdomains"))) {
            throw Violations.ofField("hsts_enabled", values.get("hsts_enabled"),
                CmsSupport.violationText("tls_passthrough_no_http_options"));
        }
        if (hasValues(values.get("custom_headers")) || hasValues(values.get("response_headers"))) {
            throw Violations.ofField("custom_headers", values.get("custom_headers"),
                CmsSupport.violationText("tls_passthrough_no_http_options"));
        }
    }

    private static boolean hasValues(Object value) {
        return value instanceof Map<?, ?> map && !map.isEmpty();
    }
}
