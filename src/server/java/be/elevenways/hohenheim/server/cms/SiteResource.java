package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;

import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.process.SiteApiKeys;
import be.elevenways.hohenheim.server.proxy.RouteClaims;
import be.elevenways.hohenheim.server.proxy.RouteClaims.ClaimConflict;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import be.elevenways.hohenheim.server.sitetype.types.TlsPassthroughSiteType;

/**
 * The proxied sites: type-discriminated settings, git provisioning, relation
 * picks to auth providers and access lists, clone/toggle row actions, and a
 * soft delete that also removes a git-provisioned checkout.
 */
public class SiteResource extends RowResource {

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
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "site"); }
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
        validateTlsPassthrough(values);
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
        validateTlsPassthrough(values);
        // The enable invariant is NOT re-checked here: it runs in the SiteModel
        // write pipeline (installEnableInvariant), which super.updateRow's save
        // funnels through -- one enforcement point for every writer.
        super.updateRow(existing, values, accessContext);
    }

    private static volatile boolean enableInvariantInstalled;

    /**
     * Install THE enable invariant on the SiteModel write pipeline so every writer
     * of a live transition passes through exactly one check: the admin form, the
     * toggle action, the delegated /manage save, the generic revision-restore
     * endpoint, seeds, and any future writer.
     *
     * AIDEV-NOTE: this MUST live in the write pipeline, never in the resource layer.
     * The framework's RESTORE_REVISION endpoint is registered for EVERY revisionable
     * RowResource -- callable even when the resource hides its revision subpage --
     * and it restores a snapshot via RevisionableBehaviour.restore -> model.save
     * DIRECTLY, running no resource-layer hook. A delegated tenant could restore a
     * formerly-enabled revision after another site took the hostname and silently
     * seize the route (SiteDispatcher resolves first-wins). A before-write hook is
     * the one seam every save funnels through. Do NOT move this back into updateRow
     * / toggleAction as a per-path check -- that is the very bypass this closes.
     */
    public static synchronized void installEnableInvariant() {
        if (enableInvariantInstalled) {
            return;
        }
        enableInvariantInstalled = true;
        // The scan: produces the specific, localized refusal an operator can act on. It
        // runs inside the one write transaction SiteModel.save declares, so on the
        // serialized SQLite engine it cannot go stale and is the authoritative refusal
        // for overlapping listener sets (see RouteClaims); the claim stamp below feeds
        // the unique index that backstops identical keys.
        //
        // AIDEV-NOTE: beforeVALIDATE, not beforeWrite. Both tiers run inside the same
        // Schema.beforeWrite pass on EVERY save path (there is no way to reach the
        // datasource past one but not the other), so the bypass argument above is
        // unchanged; the split exists so the diagnosis runs before the row is judged and
        // the authoritative claim runs last, immediately before the datasource write.
        SiteModel.SCHEMA.addBeforeValidateHook(context -> {
            Row stored = storedSiteOf(context.getRow());
            if (stored != null && willBeLive(context.getRow(), stored) && !RouteClaims.isLive(stored)) {
                refuseConflictingEnable(stored, true);
            }
        });
        // The AUTHORITATIVE claim: rewrites this site's live_route_key column, whose UNIQUE
        // index is the only thing that can refuse a route to the loser of a simultaneous
        // enable. Runs on every site write, not just a transition, so a route edited while
        // the site is live re-claims under its new key.
        SiteModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            Row stored = storedSiteOf(row);
            if (stored == null) {
                return;
            }
            try {
                RouteClaims.restamp(stored.get(SiteModel.ID), willBeLive(row, stored));
            } catch (ClaimConflict conflict) {
                throw refusalFor(conflict);
            }
        });
    }

    /**
     * The stored site a write targets, or null for a create -- a site with no id has no
     * domain rows yet and therefore claims nothing.
     */
    private static @Nullable Row storedSiteOf(@Nullable Row row) {
        if (row == null || !row.has(SiteModel.ID.getName()) || row.get(SiteModel.ID) == null) {
            return null;
        }
        return Models.get(SiteModel.class).findById(row.get(SiteModel.ID));
    }

    /** Whether the site will route traffic AFTER this write, reading through partial rows. */
    private static boolean willBeLive(@NonNull Row row, @NonNull Row stored) {
        Object enabled = row.has(SiteModel.ENABLED.getName())
            ? row.get(SiteModel.ENABLED) : stored.get(SiteModel.ENABLED);
        Object deletedAt = row.has(SiteModel.DELETED_AT.getName())
            ? row.get(SiteModel.DELETED_AT) : stored.get(SiteModel.DELETED_AT);
        return Boolean.TRUE.equals(enabled) && deletedAt == null;
    }

    /**
     * Translate the unique-index refusal into the SAME violation the advisory scan
     * produces, so a tenant who lost the race is told what happened instead of seeing a
     * driver error -- the whole point of the constraint is that the loser is TOLD.
     */
    private static @NonNull Violations refusalFor(@NonNull ClaimConflict conflict) {
        String holder = RouteClaims.holderNameOf(conflict.getKey());
        return Violations.ofField("enabled", true,
            CmsSupport.violationText("enable_route_conflict")
                .withArg("hostname", RouteClaims.hostnameOf(conflict.getKey()))
                .withArg("site", holder != null ? holder : "?"));
    }

    /**
     * THE enable invariant, invoked only from the write-pipeline hook above.
     *
     * AIDEV-NOTE: enabling puts a site's domain rows into the global route table, and
     * sites that do not route are EXEMPT from the cross-site route-identity check -- so a
     * site staged on someone else's hostname seizes it the moment it goes live. Skips a
     * site that ALREADY routes so an already-live re-save never self-conflicts; only the
     * routeless->live transition is validated. Routeless is RouteClaims.isLive, not a
     * bare enabled check: a soft-deleted site keeps enabled=true, so restoring one is a
     * transition into the route table exactly like an enable, and an enabled-only guard
     * would wave it through unchecked.
     *
     * @throws Violations when going live would collide with a live site's route
     */
    protected static void refuseConflictingEnable(@NonNull Row existing, boolean willBeEnabled) {
        if (!willBeEnabled || RouteClaims.isLive(existing)) {
            return;
        }
        SiteDomainResource.refuseEnableRouteConflicts(existing.get(SiteModel.ID));
    }

    private static void validateTlsPassthrough(Map<String, Object> values) {
        if (!TlsPassthroughSiteType.ID.toString().equals(values.get("site_type"))) return;
        if (values.get("auth_provider_id") != null) {
            throw Violations.ofField("auth_provider_id", values.get("auth_provider_id"),
                CmsSupport.violationText("tls_passthrough_no_http_auth"));
        }
        if (values.get("access_list_id") != null) {
            throw Violations.ofField("access_list_id", values.get("access_list_id"),
                CmsSupport.violationText("tls_passthrough_no_access_list"));
        }
        if (values.get("source") != null) {
            throw Violations.ofField("source", values.get("source"),
                CmsSupport.violationText("tls_passthrough_local_only"));
        }
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
        actions.add(this.toggleAction());
        actions.add(this.cloneAction());
        actions.add(this.generateApiKeyAction());
        return actions;
    }

    /**
     * Mint a control-API key for a managed-process site. Only the digest is
     * stored, so the toast is the ONE disclosure of the plaintext.
     */
    private @NonNull RowAction<Row> generateApiKeyAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "generate_api_key"))
            .label(Microcopy.of("generate_api_key").withFilter("scope", "site"))
            .icon(Icon.of("key"))
            .description(Microcopy.of("generate_api_key_hint").withFilter("scope", "site"))
            .visibleFor((row, ctx) -> supportsApiKeys(row))
            .handler((row, ctx) -> generateApiKey(row))
            .build();
    }

    /** @return true when this site's type declares an {@code api_keys} setting */
    private static boolean supportsApiKeys(@NonNull Row site) {
        SiteTypeHandler handler = SiteTypes.getHandler((String) site.get(SiteModel.SITE_TYPE));
        return handler != null && handler.getSchema().getField(SiteApiKeys.SETTING_NAME) != null;
    }

    private @NonNull CmsActionResult generateApiKey(@NonNull Row site) {
        if (!supportsApiKeys(site)) {
            return CmsActionResult.errorToast(
                Microcopy.of("api_keys_unsupported").withFilter("scope", "site"));
        }

        String plaintext = SiteApiKeys.mint();
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = site.get(SiteModel.SETTINGS) != null
            ? new LinkedHashMap<>((Map<String, Object>) site.get(SiteModel.SETTINGS))
            : new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(SiteApiKeys.normalize(settings.get(SiteApiKeys.SETTING_NAME)));
        keys.add(SiteApiKeys.digest(plaintext));
        settings.put(SiteApiKeys.SETTING_NAME, keys);
        site.set(SiteModel.SETTINGS, settings);
        ActivityLog.withAction("api_key_minted", null, () -> this.model().save(site));

        // AIDEV-NOTE: withSecretArg, never withArg — the toast is the ONLY
        // disclosure and flash toasts are session data; the flash carries a
        // single-use SecretDisclosures handle so the plaintext never rests in
        // auth_sessions.data. Re-mint stays the documented recovery.
        return CmsActionResult.refreshWithToast(
                Microcopy.of("api_key_minted").withFilter("scope", "site"))
            .withSecretArg("key", plaintext);
    }

    /** The enable/disable operate action, shared with the delegated manage panel. */
    protected final @NonNull RowAction<Row> toggleAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "toggle_site"))
            .label(Microcopy.of("toggle").withFilter("scope", "site"))
            .dynamicLabel(row -> Microcopy.of(Boolean.TRUE.equals(row.get(SiteModel.ENABLED))
                ? "disable" : "enable").withFilter("scope", "site"))
            .icon(Icon.of("power-off"))
            .handler((row, ctx) -> {
                boolean current = Boolean.TRUE.equals(row.get(SiteModel.ENABLED));
                // No pre-check: the write-pipeline enable invariant (installEnableInvariant)
                // runs inside model.save below and throws the enable_route_conflict Violations,
                // which the row-action handler surfaces as a refusal toast.
                row.set(SiteModel.ENABLED, !current);
                ActivityLog.withAction(current ? "disabled" : "enabled", null,
                    () -> this.model().save(row));
                return CmsActionResult.refreshWithToast(Microcopy.of(
                    current ? "disabled_toast" : "enabled_toast").withFilter("scope", "site"));
            })
            .build();
    }

    /** The record-creating clone action; deliberately admin-panel-only. */
    protected final @NonNull RowAction<Row> cloneAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "clone_site"))
            .label(Microcopy.of("clone").withFilter("scope", "site"))
            .icon(Icon.of("copy"))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("clone").withFilter("scope", "site"))
                .body(Microcopy.of("clone_confirm").withFilter("scope", "site"))
                .build())
            .handler((row, ctx) -> cloneSite(row, ctx.access()))
            .build();
    }

    /**
     * Clone a site + its domains; the copy starts disabled and carries NO bearer
     * credentials of its own: a fresh webhook secret and no api keys at all.
     */
    private @NonNull CmsActionResult cloneSite(@NonNull Row site, @NonNull AccessContext access) {
        SiteModel siteModel = (SiteModel) this.model();
        SiteDomainModel domainModel = Models.get(SiteDomainModel.class);

        String name = site.get(SiteModel.NAME) + " (copy)";
        Row clone = siteModel.createEmptyRow();
        clone.set(SiteModel.NAME, name);
        clone.set(SiteModel.SLUG, slugify(name));
        clone.set(SiteModel.SITE_TYPE, site.get(SiteModel.SITE_TYPE));
        @SuppressWarnings("unchecked")
        Map<String, Object> clonedSettings = site.get(SiteModel.SETTINGS) != null
            ? new LinkedHashMap<>((Map<String, Object>) site.get(SiteModel.SETTINGS))
            : null;
        if (clonedSettings != null) {
            // AIDEV-NOTE: the source's keys authenticate the source's processes;
            // handing them to a second site silently widens what each key unlocks.
            clonedSettings.remove(SiteApiKeys.SETTING_NAME);
        }
        clone.set(SiteModel.SETTINGS, clonedSettings);
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
                new SiteDeploymentsPage(), new SiteDevSessionsPage(), new SiteAccessPage()));
        pages.addAll(this.frameworkSubpages());
        return pages;
    }

    private static @NonNull String slugify(@NonNull String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static @NonNull String trimmed(@Nullable Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static boolean isBlank(@Nullable Object value) {
        return value == null || String.valueOf(value).isBlank();
    }
}
