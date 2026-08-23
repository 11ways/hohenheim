package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimPickRules;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.proxy.RouteClaims.ClaimConflict;
import be.elevenways.hohenheim.server.proxy.RouteClaims;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.hohenheim.server.upstream.kinds.DevNamespaceUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.hohenheim.site.SiteHostnamesCell;
import be.elevenways.hohenheim.site.SiteTlsCell;
import be.elevenways.hohenheim.site.SiteUpstreamCell;
import be.elevenways.hohenheim.upstream.UpstreamKinds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldFormEntryDefaults;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The proxied sites: type-discriminated settings, git provisioning, relation
 * picks to auth providers and access lists, clone/toggle row actions, and a
 * soft delete that also removes a git-provisioned checkout.
 */
public class SiteResource extends RowResource {

    /** Virtual column names (renderer cells). */
    static final String HOSTNAMES_COLUMN = "hostnames";
    static final String UPSTREAM_COLUMN = "upstream";
    static final String TLS_COLUMN = "tls";

    /**
     * The site form: choice cards decide the upstream kind (each card names what it
     * does in one sentence), the per-kind settings switch under it without a round
     * trip, and the instance pick only wakes up for the {@code instance} kind --
     * narrowed to instances the routing tier can actually serve.
     */
    private final FormSpec formSpec = FormSpec.builder()
        .add(SiteModel.NAME)
        .add(Select.of(SiteModel.UPSTREAM_KIND)
            .options(FieldFormEntryDefaults.enumOptionSource(SiteModel.UPSTREAM_KIND))
            .presentation(Select.Presentation.CARDS)
            .clearable(false)
            .build())
        // The pick resolves ONLY while the chosen upstream kind is the instance one
        // (disabled otherwise), and offers only kinds whose serving container publishes
        // a port. An instance is never created on a whim from inside a site form, so
        // there is no "create new" here. The submit is re-narrowed server-side.
        .add(RelationPick.of(SiteModel.INSTANCE_ID, InstanceModel.MODEL_ID)
            .creatable(false).clearable(true)
            .rulesFromSiblings(new HohenheimPickRules.UpstreamInstanceRules(
                SiteModel.UPSTREAM_KIND.getName(),
                InstanceUpstreamKind.ID.toString(),
                InstanceKinds.kindsWhere(InstanceKindHandler::supportsSiteUpstream)),
                "upstream_kind")
            .build())
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SETTINGS))
        .add(SiteModel.ENABLED)
        .add(SiteModel.DESCRIPTION)
        // Both are SECURITY declarations shared across sites: minting one from inside a
        // site form is how a half-configured provider or an empty allow-list goes live.
        .add(RelationPick.of(SiteModel.AUTH_PROVIDER_ID, SiteAuthProviderModel.MODEL_ID)
            .creatable(false).build())
        .add(RelationPick.of(SiteModel.ACCESS_LIST_ID, AccessListModel.MODEL_ID)
            .creatable(false).build())
        // Creating a site is deciding a name, what it serves and whether it is on. The
        // prose and the two shared security declarations are edits a site receives later,
        // so they fold -- still in the DOM, still posted, and forced open by a refusal.
        .section(FormSection.advanced(
            SiteModel.DESCRIPTION.getName(),
            SiteModel.AUTH_PROVIDER_ID.getName(),
            SiteModel.ACCESS_LIST_ID.getName()))
        .build();

    /**
     * Prefill from the instance page's Expose action
     * ({@code ?upstream_kind=hohenheim:instance&instance_id=N}); render-time only,
     * the submit still runs full coercion and the upstream-instance narrowing.
     */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(formSpec().defaultValues());
        String kind = conduit.getQueryParam("upstream_kind");
        if (kind != null && !kind.isEmpty()
                && UpstreamKinds.REGISTRY.get(Identifier.tryParse(kind)) != null) {
            values.put(SiteModel.UPSTREAM_KIND.getName(), kind);
        }
        String instanceId = conduit.getQueryParam("instance_id");
        if (instanceId != null && !instanceId.isEmpty()) {
            try {
                values.put(SiteModel.INSTANCE_ID.getName(), Integer.parseInt(instanceId));
            } catch (NumberFormatException ignored) {
                // Malformed prefill: render the bare form.
            }
        }
        return Map.copyOf(values);
    }

    /**
     * A brand-new site lands on its Domains tab, because it has no hostname yet and
     * therefore serves nothing.
     *
     * AIDEV-NOTE: this closes the Expose journey. The instance page's Expose action
     * prefills this form, the operator saves, and the site then answers 503 to nobody --
     * hostnames are {@code site_domains} child rows and the create form carries no
     * hostname field, so the one screen that finishes the job was an undirected
     * discovery. The Domains tab's own empty state already says what to do there
     * ("Add a hostname so traffic routes to this site"); all that was missing was
     * arriving at it. CREATE only: after an ordinary Save the operator stays on the
     * form, which is the framework's own rule.
     *
     * AIDEV-NOTE: the panel slug is spelled because {@code createdRecordUrl} is declared
     * as a String and never sees the Panel -- the framework's own escape-hatch note. It
     * is safe HERE and nowhere by luck: the only other mount of this resource is
     * {@code ManageSiteResource}, whose {@code creatable()} is false, so the delegated
     * panel never reaches this method. A third, creatable mount would have to revisit it.
     */
    @Override
    public @Nullable String createdRecordUrl(@NonNull Row record) {
        Object id = record.get(SiteModel.ID);
        if (id == null) {
            return null;
        }
        return CmsRoutes.subpage("admin", slug(), id, new SiteDomainsPage().slug()).toUrl();
    }

    // AIDEV-NOTE: STATUS is deliberately absent. SiteModel.STATUS declares exactly ONE
    // member ("active") and every write sets it, so the column rendered the same pill on
    // every row forever -- a filter over it could only ever return the whole list.
    // AIDEV-NOTE: the hostname/upstream/TLS cells each cost one small query per rendered
    // row (page-capped); the upstream kind filter stays DECLARED without a visible enum
    // column, so it renders labeled in the filter strip instead of duplicating the badge
    // the upstream cell already draws.
    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        // The slug names this site in every generated path, container name and log line,
        // so it reads under the name instead of costing a column of its own.
        .column(ColumnSpec.fromField(SiteModel.NAME).filterable().subtext("slug").build())
        .column(ColumnSpec.fromField(SiteModel.SLUG).hidden().build())
        .column(ColumnSpec.virtual(HOSTNAMES_COLUMN,
                Microcopy.of("hostnames").withFilter("scope", "site"))
            .renderer("hohenheim:cms/cell/site-hostnames").build())
        .column(ColumnSpec.virtual(UPSTREAM_COLUMN,
                Microcopy.of("upstream").withFilter("scope", "site"))
            .renderer("hohenheim:cms/cell/site-upstream").build())
        .column(ColumnSpec.virtual(TLS_COLUMN, Microcopy.of("tls").withFilter("scope", "site"))
            .renderer("hohenheim:cms/cell/site-tls").build())
        // Enabled reads as ROW STATE (a disabled site renders muted, the strip keeps
        // the tri-state filter) instead of costing a column; the picker still offers it.
        .column(ColumnSpec.fromField(SiteModel.ENABLED).filterable().hidden().build())
        // Off the default view: most sites gate nothing, so a column of blanks would
        // push the actions column into horizontal scroll at laptop widths. The column
        // picker (and the advanced filter) still offer it.
        .column(ColumnSpec.fromField(SiteModel.ACCESS_LIST_ID)
            .relation(RelationPick.of(SiteModel.ACCESS_LIST_ID, AccessListModel.MODEL_ID).build())
            .hidden().build())
        .column(ColumnSpec.fromField(SiteModel.UPSTREAM_KIND).filterable().hidden().build())
        .column(ColumnSpec.fromField(SiteModel.CREATED_AT).filterable().hidden().build())
        .filter(FilterSpec.forField(SiteModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(SiteModel.NAME)).build())
        .filter(FilterSpec.forField(SiteModel.UPSTREAM_KIND, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(SiteModel.UPSTREAM_KIND)).build())
        .filter(FilterSpec.forField(SiteModel.ENABLED, FilterSpec.Kind.BOOLEAN)
            .label(FieldLabels.labelFor(SiteModel.ENABLED)).build())
        .filter(FilterSpec.forField(SiteModel.CREATED_AT, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(SiteModel.CREATED_AT)).build())
        .defaultSort(SortSpec.desc(SiteModel.CREATED_AT.getName()))
        .rowClasses(row -> Boolean.TRUE.equals(row.get(SiteModel.ENABLED))
            ? "" : "hh-site-disabled")
        .build();

    /** The three derived cells; everything else is plain fields. */
    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        return switch (column.name()) {
            case HOSTNAMES_COLUMN -> hostnamesCellOf(row);
            case UPSTREAM_COLUMN -> upstreamCellOf(row);
            case TLS_COLUMN -> tlsCellOf(row);
            default -> super.cellValue(row, column);
        };
    }

    private static @NonNull List<Row> domainsOf(@NonNull Row site) {
        return Models.get(SiteDomainModel.class).findBySiteId(site.get(SiteModel.ID));
    }

    static @NonNull SiteHostnamesCell hostnamesCellOf(@NonNull Row site) {
        List<Row> domains = domainsOf(site);
        if (domains.isEmpty()) {
            return new SiteHostnamesCell(null, 0);
        }
        return new SiteHostnamesCell(
            String.valueOf((Object) domains.get(0).get(SiteDomainModel.HOSTNAME)),
            domains.size() - 1);
    }

    static @NonNull SiteTlsCell tlsCellOf(@NonNull Row site) {
        List<Row> domains = domainsOf(site);
        if (domains.isEmpty()) {
            return new SiteTlsCell(SiteTlsCell.NONE);
        }
        long forced = domains.stream()
            .filter(domain -> Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL)))
            .count();
        if (forced == domains.size()) {
            return new SiteTlsCell(SiteTlsCell.FORCED);
        }
        return new SiteTlsCell(forced > 0 ? SiteTlsCell.PARTIAL : SiteTlsCell.OFF);
    }

    static @NonNull SiteUpstreamCell upstreamCellOf(@NonNull Row site) {
        String kindKey = String.valueOf((Object) site.get(SiteModel.UPSTREAM_KIND));
        UpstreamKindHandler handler = UpstreamKindHandlers.getHandler(kindKey);
        Microcopy label = handler != null ? handler.getLabel()
            : Microcopy.of("upstream").withFilter("scope", "site");
        String icon = handler != null && handler.getIcon() != null
            ? handler.getIcon().name() : null;
        String color = handler != null ? handler.getColor() : null;

        String instanceName = null;
        String instanceUrl = null;
        Integer instanceId = site.get(SiteModel.INSTANCE_ID);
        if (instanceId != null) {
            Row instance = Models.get(InstanceModel.class).findById(instanceId);
            if (instance != null) {
                instanceName = Models.get(InstanceModel.class).getDisplayTitle(instance);
                instanceUrl = CmsRoutes.detail("admin", "instances", instanceId).toUrl();
            }
        }

        String summary = null;
        if (handler != null && instanceName == null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> settings = site.get(SiteModel.SETTINGS) instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
            summary = handler.upstreamSummary(settings);
        }

        return new SiteUpstreamCell(kindKey, label, icon, color, summary,
            instanceName, instanceUrl);
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "site"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "site"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "site"); }
    @Override public @NonNull String slug() { return "sites"; }
    @Override public @NonNull Model model() { return Models.get(SiteModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    /**
     * The one list an operator lives in: hundreds of sites, a real rule vocabulary, and
     * saved views ("my staging sites", "everything failing TLS") are how they are reached.
     */
    @Override public @NonNull ListChrome listChrome() { return ListChrome.DEFAULT; }

    /** An operator looks a site up by what they call it, by what the paths call it, or by the note they left on it. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(SiteModel.NAME, SiteModel.SLUG, SiteModel.DESCRIPTION);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 20; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "site");
    }
    @Override public @NonNull Icon icon() { return Icon.of("globe"); }


    /**
     * The two columns this resource's OWN write path stages (persistRow derives the
     * slug from the name, and stamps the status) without offering a form entry for
     * them. Revision restore is default-deny over everything the surface does not
     * expose, so the admin panel has to declare what it owns or it would lose the
     * ability to restore a revision that predates a rename or a status change.
     * ManageSiteResource overrides this back to empty -- the delegated surface owns
     * neither.
     */
    @Override
    public @NonNull Set<String> restorableFieldsOutsideForm() {
        return Set.of(SiteModel.SLUG.getName(), SiteModel.STATUS.getName());
    }

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
        normalizeDevNamespace(values, null);
        return super.persistRow(values, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        // AIDEV-NOTE: every read below goes through CmsSupport's stored-value fallback
        // because the inline cell lane hands this a map with EXACTLY ONE entry: reading
        // "name" off the map alone refused a save that never touched it.
        String name = CmsSupport.textOf(values, existing, SiteModel.NAME);
        if (name.isEmpty()) {
            throw Violations.ofField("name", name, CmsSupport.violationText("name_required"));
        }
        normalizeDevNamespace(values, existing);
        // The enable invariant is NOT re-checked here: it runs in the SiteModel
        // write pipeline (installEnableInvariant), which super.updateRow's save
        // funnels through -- one enforcement point for every writer.
        // AIDEV-NOTE: the tls-passthrough refusals are enforced there too, by the
        // SiteModel beforeValidate hook, which reads through partial rows (effective())
        // and throws the very same three violations. The copy that used to stand here
        // read auth_provider_id/access_list_id/source straight off the coerced map, so on
        // a one-entry map it PASSED a combination the row already held -- a duplicate
        // vocabulary that was also the weaker of the two. Do not reintroduce it.
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

    /**
     * Mint a registration token for a dev-namespace site that has none yet (a
     * blank submit on an existing secret was already restored by the secrets
     * contract before this runs, so blank here really means absent).
     *
     * AIDEV-NOTE: both reads take the stored value when the write does not carry the key
     * (the one-entry immutable cell map). Seeding the settings from an absent key would
     * have written a map holding nothing but a freshly minted token over the site's whole
     * configuration.
     *
     * @param existing the stored row, or null on a create
     */
    private static void normalizeDevNamespace(@NonNull Map<String, Object> coerced,
                                              @Nullable Row existing) {
        if (!DevNamespaceUpstreamKind.ID.toString().equals(
                CmsSupport.valueOf(coerced, existing, SiteModel.UPSTREAM_KIND))) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> settings =
            CmsSupport.valueOf(coerced, existing, SiteModel.SETTINGS) instanceof Map<?, ?> map
                ? new HashMap<>((Map<String, Object>) map)
                : new HashMap<>();
        if (isBlank(settings.get(DevNamespaceUpstreamKind.REGISTRATION_TOKEN_KEY))) {
            settings.put(DevNamespaceUpstreamKind.REGISTRATION_TOKEN_KEY,
                "zdev_" + SecureTokens.randomToken(24));
        }
        coerced.put("settings", settings);
    }

    /**
     * Soft delete: reclaim the previews this site routed, then stamp deleted_at.
     *
     * AIDEV-NOTE: a site delete drops a HOSTNAME and nothing else. Since phase-0 brief 7
     * the site owns no runtime at all -- the application it exposed keeps running, keeps
     * its releases and keeps its volumes, and can be pointed at by another site tomorrow.
     * Previews are the one cascade left, and only the ones whose generated hostname lived
     * on THIS site (see PreviewDeployments.destroyForSite): the soft delete fires no remove
     * hook that could ever reclaim them.
     */
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        Integer siteId = existing.get(SiteModel.ID);
        // Previews die WITH the site (explicit for the same reason destroyFor is:
        // the soft delete fires no remove hook that could ever do this).
        be.elevenways.hohenheim.server.preview.PreviewDeployments.destroyForSite(siteId);
        existing.set(SiteModel.DELETED_AT, Instant.now());
        ActivityLog.withAction(ActivityLog.ACTION_DELETE, "soft-delete",
            () -> this.model().save(existing));
    }

    /**
     * The application a site exposes.
     *
     * @throws Violations when it exposes none -- the row action is hidden in that case, so
     *         reaching this is a stale form, not a normal path
     */
    private static int requireApplicationOf(@NonNull Row site) {
        Integer instanceId = site.get(SiteModel.INSTANCE_ID);
        if (instanceId == null) {
            throw Violations.ofForm(CmsSupport.violationText("site_exposes_no_instance"));
        }
        return instanceId;
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(this.toggleAction());
        actions.add(this.cloneAction());
        actions.add(this.rollbackAction());
        return actions;
    }

    /**
     * Roll a Docker site back to its retained release: one durable operation over the
     * digest-pinned prior spec, through the same health gate as a forward release. The
     * engine refuses with a toast when no rollback target exists.
     */
    private @NonNull RowAction<Row> rollbackAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "rollback_release"))
            .label(Microcopy.of("rollback").withFilter("scope", "site"))
            .icon(Icon.of("clock-rotate-left"))
            .inlineInRow(false)
            .description(Microcopy.of("rollback_hint").withFilter("scope", "site"))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("rollback").withFilter("scope", "site"))
                .body(Microcopy.of("rollback_confirm").withFilter("scope", "site"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .visibleFor((row, ctx) -> InstanceUpstreamKind.ID.toString()
                .equals(row.get(SiteModel.UPSTREAM_KIND)))
            .handler((row, ctx) -> {
                ReleaseEngine.rollback(requireApplicationOf(row));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("rollback_done").withFilter("scope", "site"));
            })
            .build();
    }

    /** The enable/disable operate action, shared with the delegated manage panel. */
    /**
     * Enable/disable rides the OVERFLOW: rows keep exactly Edit and Delete inline, the
     * calm strip the admin-UI wave promises, and the Enabled column already answers
     * the question the inline button used to.
     */
    protected final @NonNull RowAction<Row> toggleAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "toggle_site"))
            .label(Microcopy.of("toggle").withFilter("scope", "site"))
            .dynamicLabel(row -> Microcopy.of(Boolean.TRUE.equals(row.get(SiteModel.ENABLED))
                ? "disable" : "enable").withFilter("scope", "site"))
            .icon(Icon.of("power-off"))
            .inlineInRow(false)
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
            .inlineOnRecord(false)
            .inlineInRow(false)
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
        clone.set(SiteModel.UPSTREAM_KIND, site.get(SiteModel.UPSTREAM_KIND));
        @SuppressWarnings("unchecked")
        Map<String, Object> clonedSettings = site.get(SiteModel.SETTINGS) != null
            ? new LinkedHashMap<>((Map<String, Object>) site.get(SiteModel.SETTINGS))
            : null;
        clone.set(SiteModel.SETTINGS, clonedSettings);
        // AIDEV-NOTE: the clone deliberately does NOT copy instance_id. The source and
        // the workload now live on the instance (phase-0 design section 3), and two sites
        // pointing at one instance is a second front door to the same workload, not a
        // copy of it -- the operator picks or creates the instance for the clone.
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

        // CmsActionResult.redirect is Uri-typed, so the typed target renders here.
        return CmsActionResult.redirect(new be.elevenways.protoblast.common.http.Uri(
            CmsRoutes.detail("admin", "sites", newSiteId).toUrl()));
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        // The "access" tab is the GENERIC record-access page, contributed by
        // zenit-auth through RecordSubpageRegistry (part of frameworkSubpages).
        // AIDEV-NOTE: no Deployments tab anymore -- the release history moved to the
        // APPLICATION instance (InstanceDeploymentsPage) when the engine was re-keyed;
        // a second copy here would be two UIs over one operation history.
        List<RecordScopedPage<Row>> pages = new ArrayList<>(
            List.of(new SiteDomainsPage(), new SiteDevSessionsPage()));
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

    /**
     * The proxy tier's sibling catalogs, demoted out of the sidebar.
     *
     * Builds and releases moved to the INSTANCES list when the release engine was re-keyed
     * to the application; a site owns hostnames, not artifacts.
     */
    @Override
    public @NonNull List<RelatedPage> relatedPages() {
        return List.of(
            RelatedPage.toPeer("auth-providers"),
            RelatedPage.toPeer("previews"));
    }

}
