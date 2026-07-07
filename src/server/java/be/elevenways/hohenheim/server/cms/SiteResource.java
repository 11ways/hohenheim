package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.source.GitProvisioner;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.FieldOption;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The proxied sites: type-discriminated settings, git provisioning, relation
 * picks to auth providers and access lists, clone/toggle row actions, and a
 * soft delete that also removes a git-provisioned checkout.
 */
public final class SiteResource extends HohenheimRowResource {

    private static final List<FieldOption<String>> SOURCE_OPTIONS = List.of(
        FieldOption.of("local", "Local files"),
        FieldOption.of(SiteModel.SOURCE_GIT, "Git repository"));

    private final FormSpec formSpec = FormSpec.builder()
        .add(SiteModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SITE_TYPE))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SETTINGS))
        .add(SiteModel.ENABLED)
        .add(SiteModel.DESCRIPTION)
        .add(RelationPick.of(SiteModel.AUTH_PROVIDER_ID, SiteAuthProviderModel.MODEL_ID).build())
        .add(RelationPick.of(SiteModel.ACCESS_LIST_ID, AccessListModel.MODEL_ID).build())
        .add(Select.of(SiteModel.SOURCE).options(OptionSource.of(SOURCE_OPTIONS)).build())
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SOURCE_SETTINGS))
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(SiteModel.NAME).build())
        .column(ColumnSpec.fromField(SiteModel.SITE_TYPE).build())
        .column(ColumnSpec.fromField(SiteModel.ENABLED).build())
        .column(ColumnSpec.fromField(SiteModel.STATUS).build())
        .column(ColumnSpec.fromField(SiteModel.CREATED_AT).build())
        .defaultSort(SortSpec.desc(SiteModel.CREATED_AT.getName()))
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "site"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("hohenheim.site.plural"); }
    @Override public @NonNull String slug() { return "sites"; }
    @Override public @NonNull Model model() { return Models.get(SiteModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.PROXY_GROUP; }
    @Override public int navOrder() { return 10; }
    @Override public @NonNull Icon icon() { return Icon.of("globe"); }

    @Override protected @NonNull String auditResourceType() { return AuditLogModel.RESOURCE_SITE; }

    /** slug/status are staged by persistRow but are not form entries; stamp them here. */
    @Override
    public @NonNull Row valuesToRow(@NonNull Map<String, Object> coerced) {
        Row row = super.valuesToRow(coerced);
        if (coerced.get("slug") instanceof String slug) {
            row.set(SiteModel.SLUG, slug);
        }
        if (coerced.get("status") instanceof String status) {
            row.set(SiteModel.STATUS, status);
        }
        return row;
    }

    /** Soft-deleted sites are invisible everywhere. */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> AccessDecision.allow(QueryPredicate.of(SiteModel.DELETED_AT.isNull()));
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = mutable(coerced);
        String name = trimmed(values.get("name"));
        if (name.isEmpty()) {
            throw new IllegalStateException("Name is required");
        }
        values.put("slug", slugify(name));
        values.put("status", SiteModel.STATUS_ACTIVE);
        normalizeSource(values, null);
        return super.persistRow(values, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = mutable(coerced);
        String name = trimmed(values.get("name"));
        if (name.isEmpty()) {
            throw new IllegalStateException("Name is required");
        }
        normalizeSource(values, existing);
        super.updateRow(existing, values, accessContext);
    }

    /**
     * Normalize the source discriminator: "local" stores as null and clears
     * the git settings; "git" keeps them and guarantees a webhook secret
     * (preserving an existing one when the form leaves it blank).
     */
    private static void normalizeSource(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        String source = trimmed(coerced.get("source"));
        if (!SiteModel.SOURCE_GIT.equals(source)) {
            coerced.put("source", null);
            coerced.put("source_settings", null);
            return;
        }
        coerced.put("source", SiteModel.SOURCE_GIT);
        @SuppressWarnings("unchecked")
        Map<String, Object> sourceSettings = coerced.get("source_settings") instanceof Map<?, ?> map
            ? new HashMap<>((Map<String, Object>) map)
            : new HashMap<>();
        if (isBlank(sourceSettings.get("webhook_secret"))) {
            String preserved = null;
            if (existing != null
                && existing.get(SiteModel.SOURCE_SETTINGS) instanceof Map<?, ?> existingSettings
                && !isBlank(existingSettings.get("webhook_secret"))) {
                preserved = String.valueOf(existingSettings.get("webhook_secret"));
            }
            sourceSettings.put("webhook_secret", preserved != null ? preserved : UUID.randomUUID().toString());
        }
        coerced.put("source_settings", sourceSettings);
    }

    /** Soft delete: stamp deleted_at, remove a git checkout, keep the row. */
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        Integer siteId = existing.get(SiteModel.ID);
        String name = existing.get(SiteModel.NAME);
        existing.set(SiteModel.DELETED_AT, Instant.now());
        this.model().save(existing);
        if (SiteModel.SOURCE_GIT.equals(existing.get(SiteModel.SOURCE))) {
            GitProvisioner.deleteSiteDirectory(siteId);
        }
        CmsSupport.audit(accessContext, AuditLogModel.ACTION_DELETED,
            AuditLogModel.RESOURCE_SITE, siteId, name);
        CmsSupport.reloadProxy();
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());

        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "toggle_site"))
            .label("hohenheim.site.toggle")
            .icon(Icon.of("power-off"))
            .handler((row, ctx) -> {
                boolean current = Boolean.TRUE.equals(row.get(SiteModel.ENABLED));
                row.set(SiteModel.ENABLED, !current);
                this.model().save(row);
                CmsSupport.audit(ctx.access(),
                    current ? AuditLogModel.ACTION_DISABLED : AuditLogModel.ACTION_ENABLED,
                    AuditLogModel.RESOURCE_SITE, row.get(SiteModel.ID), row.get(SiteModel.NAME));
                CmsSupport.reloadProxy();
                return CmsActionResult.refreshWithToast(Microcopy.of(
                    current ? "hohenheim.site.disabled" : "hohenheim.site.enabled"));
            })
            .build());

        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "clone_site"))
            .label("hohenheim.site.clone")
            .icon(Icon.of("copy"))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("hohenheim.site.clone"))
                .body(Microcopy.of("hohenheim.site.clone.confirm"))
                .build())
            .handler((row, ctx) -> cloneSite(row, ctx.access()))
            .build());

        return actions;
    }

    /** Clone a site + its domains; the copy starts disabled with a fresh webhook secret. */
    private @NonNull CmsActionResult cloneSite(@NonNull Row site, @NonNull AccessContext access) {
        SiteModel siteModel = (SiteModel) this.model();
        SiteDomainModel domainModel = Models.get(SiteDomainModel.class);

        String name = site.get(SiteModel.NAME) + " (copy)";
        Row clone = siteModel.createEmptyRow();
        clone.set(SiteModel.NAME, name);
        clone.set(SiteModel.SLUG, slugify(name));
        clone.set(SiteModel.SITE_TYPE, site.get(SiteModel.SITE_TYPE));
        clone.set(SiteModel.SETTINGS, site.get(SiteModel.SETTINGS));
        clone.set(SiteModel.SOURCE, site.get(SiteModel.SOURCE));
        @SuppressWarnings("unchecked")
        Map<String, Object> clonedSourceSettings = site.get(SiteModel.SOURCE_SETTINGS) != null
            ? new HashMap<>((Map<String, Object>) site.get(SiteModel.SOURCE_SETTINGS))
            : null;
        if (clonedSourceSettings != null) {
            clonedSourceSettings.put("webhook_secret", UUID.randomUUID().toString());
        }
        clone.set(SiteModel.SOURCE_SETTINGS, clonedSourceSettings);
        clone.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        clone.set(SiteModel.ENABLED, false);
        clone.set(SiteModel.AUTH_PROVIDER_ID, site.get(SiteModel.AUTH_PROVIDER_ID));
        clone.set(SiteModel.ACCESS_LIST_ID, site.get(SiteModel.ACCESS_LIST_ID));
        siteModel.save(clone);

        int newSiteId = clone.get(SiteModel.ID);
        for (Row domain : domainModel.findBySiteId(site.get(SiteModel.ID))) {
            Row domainClone = domainModel.createEmptyRow();
            domainClone.set(SiteDomainModel.SITE_ID, newSiteId);
            domainClone.set(SiteDomainModel.HOSTNAME, domain.get(SiteDomainModel.HOSTNAME) + ".clone");
            domainClone.set(SiteDomainModel.MATCH_TYPE, domain.get(SiteDomainModel.MATCH_TYPE));
            domainClone.set(SiteDomainModel.FORCE_SSL, domain.get(SiteDomainModel.FORCE_SSL));
            domainClone.set(SiteDomainModel.HSTS_ENABLED, domain.get(SiteDomainModel.HSTS_ENABLED));
            domainClone.set(SiteDomainModel.HSTS_SUBDOMAINS, domain.get(SiteDomainModel.HSTS_SUBDOMAINS));
            domainClone.set(SiteDomainModel.PATH, domain.get(SiteDomainModel.PATH));
            domainClone.set(SiteDomainModel.STRIP_PATH, domain.get(SiteDomainModel.STRIP_PATH));
            domainClone.set(SiteDomainModel.HTTP2_SUPPORT, domain.get(SiteDomainModel.HTTP2_SUPPORT));
            domainClone.set(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT,
                domain.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT));
            domainClone.set(SiteDomainModel.LISTEN_ON, domain.get(SiteDomainModel.LISTEN_ON));
            domainClone.set(SiteDomainModel.PORT, domain.get(SiteDomainModel.PORT));
            domainClone.set(SiteDomainModel.CUSTOM_HEADERS, domain.get(SiteDomainModel.CUSTOM_HEADERS));
            domainClone.set(SiteDomainModel.RESPONSE_HEADERS, domain.get(SiteDomainModel.RESPONSE_HEADERS));
            domainModel.save(domainClone);
        }

        CmsSupport.audit(access, AuditLogModel.ACTION_CLONED,
            AuditLogModel.RESOURCE_SITE, newSiteId, name);
        return CmsActionResult.redirect(new be.elevenways.protoblast.common.http.Uri(
            "/admin/sites/" + newSiteId));
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        return List.of(new SiteDomainsPage(), new SiteProcessesPage());
    }

    private static @NonNull String slugify(@NonNull String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static @NonNull String trimmed(@Nullable Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static boolean isBlank(@Nullable Object value) {
        return value == null || String.valueOf(value).isBlank();
    }
}
