package be.elevenways.hohenheim.server.cms;


import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Site domain entries: hostname matching, TLS/HSTS toggles, per-domain
 * headers and certificate pinning. Hidden from the sidebar -- reached
 * through a site's Domains tab.
 */
public final class SiteDomainResource extends RowResource {

    private static final List<FieldOption<String>> MATCH_OPTIONS = List.of(
        FieldOption.of(SiteDomainModel.MATCH_EXACT, "Exact"),
        FieldOption.of("wildcard", "Wildcard (* and ?)"),
        FieldOption.of("regex", "Regular expression"));

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(SiteDomainModel.SITE_ID, SiteModel.MODEL_ID).build())
        .add(SiteDomainModel.HOSTNAME)
        .add(Select.of(SiteDomainModel.MATCH_TYPE).options(OptionSource.of(MATCH_OPTIONS)).build())
        .add(SiteDomainModel.LISTEN_ON)
        .add(SiteDomainModel.PORT)
        .add(SiteDomainModel.PATH)
        .add(SiteDomainModel.STRIP_PATH)
        .add(SiteDomainModel.FORCE_SSL)
        .add(RelationPick.of(SiteDomainModel.CERTIFICATE_ID, CertificateModel.MODEL_ID).build())
        .add(SiteDomainModel.HSTS_ENABLED)
        .add(SiteDomainModel.HSTS_SUBDOMAINS)
        .add(SiteDomainModel.HTTP2_SUPPORT)
        .add(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteDomainModel.CUSTOM_HEADERS))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteDomainModel.RESPONSE_HEADERS))
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(SiteDomainModel.HOSTNAME).build())
        .column(ColumnSpec.fromField(SiteDomainModel.MATCH_TYPE).build())
        .column(ColumnSpec.fromField(SiteDomainModel.FORCE_SSL).build())
        .column(ColumnSpec.fromField(SiteDomainModel.SITE_ID).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "site_domain"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("hohenheim.site_domain.plural"); }
    @Override public @NonNull String slug() { return "domains"; }
    @Override public @NonNull Model model() { return Models.get(SiteDomainModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.PROXY_GROUP; }
    @Override public int navOrder() { return 20; }
    @Override public @NonNull Icon icon() { return Icon.of("at"); }
    @Override public boolean showInNav() { return false; }


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
            throw new IllegalStateException("Hostname is required");
        }
        Object siteIdValue = coerced.containsKey("site_id") ? coerced.get("site_id")
            : existing != null ? existing.get(SiteDomainModel.SITE_ID) : null;
        if (!(siteIdValue instanceof Integer siteId)) {
            throw new IllegalStateException("A site is required");
        }
        Row duplicate = this.model().find()
            .where(SiteDomainModel.SITE_ID.eq(siteId))
            .where(SiteDomainModel.HOSTNAME.eq(hostname))
            .first();
        if (duplicate != null
            && (existing == null || !duplicate.get(SiteDomainModel.ID).equals(existing.get(SiteDomainModel.ID)))) {
            throw new IllegalStateException("That hostname is already configured for this site");
        }
    }
}
