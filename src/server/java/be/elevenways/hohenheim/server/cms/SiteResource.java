package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;

import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.sitetype.types.DevNamespaceSiteType;
import be.elevenways.hohenheim.server.source.GitProvisioner;
import be.elevenways.zenit.server.security.SecureTokens;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
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
public final class SiteResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(SiteModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SITE_TYPE))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SETTINGS))
        .add(SiteModel.ENABLED)
        .add(SiteModel.DESCRIPTION)
        .add(RelationPick.of(SiteModel.AUTH_PROVIDER_ID, SiteAuthProviderModel.MODEL_ID).build())
        .add(RelationPick.of(SiteModel.ACCESS_LIST_ID, AccessListModel.MODEL_ID).build())
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SOURCE))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SOURCE_SETTINGS))
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(SiteModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(SiteModel.SITE_TYPE).filterable().build())
        .column(ColumnSpec.fromField(SiteModel.ENABLED).filterable().build())
        .column(ColumnSpec.fromField(SiteModel.STATUS).filterable().build())
        .column(ColumnSpec.fromField(SiteModel.CREATED_AT).filterable().build())
        .filter(FilterSpec.forField(SiteModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(SiteModel.NAME)).build())
        .filter(FilterSpec.forField(SiteModel.SITE_TYPE, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(SiteModel.SITE_TYPE)).build())
        .filter(FilterSpec.forField(SiteModel.ENABLED, FilterSpec.Kind.BOOLEAN)
            .label(FieldLabels.labelFor(SiteModel.ENABLED)).build())
        .filter(FilterSpec.forField(SiteModel.STATUS, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(SiteModel.STATUS)).build())
        .filter(FilterSpec.forField(SiteModel.CREATED_AT, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(SiteModel.CREATED_AT)).build())
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
        Map<String, Object> values = CmsSupport.mutable(coerced);
        String name = trimmed(values.get("name"));
        if (name.isEmpty()) {
            throw Violations.ofField("name", name, CmsSupport.violationText("name_required"));
        }
        values.put("slug", slugify(name));
        values.put("status", SiteModel.STATUS_ACTIVE);
        normalizeSource(values);
        normalizeDevNamespace(values);
        return super.persistRow(values, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        String name = trimmed(values.get("name"));
        if (name.isEmpty()) {
            throw Violations.ofField("name", name, CmsSupport.violationText("name_required"));
        }
        normalizeSource(values);
        normalizeDevNamespace(values);
        super.updateRow(existing, values, accessContext);
    }

    /**
     * Normalize the source discriminator: "local" stores as null and clears
     * the git settings; "git" keeps them and mints a webhook secret when none
     * exists yet (keep-on-blank for an existing secret is the framework's
     * FormSecrets contract -- webhook_secret is a .secret() field).
     */
    private static void normalizeSource(@NonNull Map<String, Object> coerced) {
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
            sourceSettings.put("webhook_secret", UUID.randomUUID().toString());
        }
        coerced.put("source_settings", sourceSettings);
    }

    /**
     * Mint a registration token for a dev-namespace site that has none yet (a
     * blank submit on an existing secret was already restored by the secrets
     * contract before this runs, so blank here really means absent).
     */
    private static void normalizeDevNamespace(@NonNull Map<String, Object> coerced) {
        if (!DevNamespaceSiteType.ID.toString().equals(coerced.get("site_type"))) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = coerced.get("settings") instanceof Map<?, ?> map
            ? new HashMap<>((Map<String, Object>) map)
            : new HashMap<>();
        if (isBlank(settings.get(DevNamespaceSiteType.REGISTRATION_TOKEN_KEY))) {
            settings.put(DevNamespaceSiteType.REGISTRATION_TOKEN_KEY,
                "zdev_" + SecureTokens.randomToken(24));
        }
        coerced.put("settings", settings);
    }

    /** Soft delete: stamp deleted_at, remove a git checkout, keep the row. */
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        Integer siteId = existing.get(SiteModel.ID);
        existing.set(SiteModel.DELETED_AT, Instant.now());
        ActivityLog.withAction(ActivityLog.ACTION_DELETE, "soft-delete",
            () -> this.model().save(existing));
        if (SiteModel.SOURCE_GIT.equals(existing.get(SiteModel.SOURCE))) {
            GitProvisioner.deleteSiteDirectory(siteId);
        }
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
                ActivityLog.withAction(current ? "disabled" : "enabled", null,
                    () -> this.model().save(row));
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
        ActivityLog.withAction("cloned", "of site #" + site.get(SiteModel.ID),
            () -> siteModel.save(clone));

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
            domainClone.set(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT,
                domain.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT));
            domainClone.set(SiteDomainModel.LISTEN_ON, domain.get(SiteDomainModel.LISTEN_ON));
            domainClone.set(SiteDomainModel.CUSTOM_HEADERS, domain.get(SiteDomainModel.CUSTOM_HEADERS));
            domainClone.set(SiteDomainModel.RESPONSE_HEADERS, domain.get(SiteDomainModel.RESPONSE_HEADERS));
            domainModel.save(domainClone);
        }

        return CmsActionResult.redirect(new be.elevenways.protoblast.common.http.Uri(
            "/admin/sites/" + newSiteId));
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        List<RecordScopedPage<Row>> pages = new ArrayList<>(
            List.of(new SiteDomainsPage(), new SiteDatabasesPage(), new SiteProcessesPage(),
                new SiteDeploymentsPage(), new SiteDevSessionsPage()));
        pages.addAll(this.frameworkSubpages());
        return pages;
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
