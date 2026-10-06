package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.hohenheim.server.upstream.kinds.TlsPassthroughUpstreamKind;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.ResourceAuthority;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.server.panel.PartsReads;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.RowScope;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The site domain entries' shared parts, and the admin domain resource and its /manage twin built from them.
 *
 * AIDEV-NOTE: a domain is a hostname row of a site, hidden from the sidebar and reached through a site's Domains tab.
 * The route claim, hostname canonicalization and tenant column freeze are the model's write hooks
 * ({@link SiteDomainRouteInvariant}, {@code TenantWrites}), so both twins write plain rows; the /manage form is a UX
 * narrowing only, never the gate.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class DomainParts {

    /** Domains of live sites; the /manage scope narrows this same base per principal. */
    public static final RowScope ROWS = RowScope.within(DomainParts::liveSiteScope);

    /** The entry slug both twins share, which a site's Domains tab and the parent links name. */
    public static final String SLUG = "domains";

    /**
     * The Domains tab's quick-add entries; the site rides along as a host-supplied preset.
     *
     * AIDEV-NOTE: MATCH_TYPE is deliberately NOT here even though the admin form carries it: the /manage form has
     * no match_type entry, and a bar naming an entry a twin's form does not declare refuses that twin at boot. The
     * default (exact) is what a hostname typed into a one-line bar means anyway.
     */
    private static final QuickCreateSpec QUICK_CREATE = QuickCreateSpec
        .of(SiteDomainModel.HOSTNAME.getName(), SiteDomainModel.FORCE_SSL.getName())
        .presets(SiteDomainModel.SITE_ID.getName());

    private static final SubjectType<Row> SUBJECT = SubjectType.record(SiteDomainModel.MODEL_ID);

    private DomainParts() {
    }

    /** @return the admin domain resource: every domain of a live site */
    public static @NonNull PanelResource<Row> admin() {
        return entry("site_domain")
            .scope(ROWS)
            .form(form(adminFormSpec()))
            // Requesting a certificate stays installation administration (an issued certificate is authority over a
            // name), so only the admin twin offers it.
            .actions(List.of(CertificateOperations.requestForDomainAction()))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /** @return the /manage twin: the domains of the sites the caller manages, through the delegated form */
    public static @NonNull PanelResource<Row> manage() {
        return entry("manage_site_domain")
            // Reached through a site's Addresses tab on this panel, which has no Domains cluster.
            .showInNav(false)
            .scope(TenantScopes.DOMAINS)
            .form(form(manageFormSpec()))
            // NAV-ONLY (zero granted sites hide the empty list); the route itself stays scoped.
            .hasInScopeRecords(ManagePanel::hasManageScope)
            .build();
    }

    /** The entry, list, reads, writes, authority and parent both twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull String id) {
        return PanelResource.builder(HohenheimIds.id(id), SLUG, SUBJECT)
            .label(Microcopy.of("plural").withFilter("scope", "site_domain"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "site_domain"))
            .description(Microcopy.of("nav_hint").withFilter("scope", "site_domain"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(20)
            .icon(Icon.of("at"))
            .parent(ResourceParent.of(HohenheimSlugs.SITES, SiteDomainModel.SITE_ID).tab(SLUG))
            .list(list())
            .reads(ResourceReads.rows())
            .writes(ResourceMutations.rows().create().update().delete().build())
            .authority(authority());
    }

    /**
     * Writing a domain row demands {@code manage} on the site it binds to.
     *
     * AIDEV-NOTE: reachesRecord, never canManageSite: this runs once per RENDERED ROW, and the per-record walk would
     * be a grant-store round trip per row on a page whose own scope criteria already asked the same question
     * set-wise. The write pipeline's {@code TenantWrites} freeze stays the gate; this decides the affordance, so a
     * view-only delegate is never shown a control that can only refuse.
     */
    private static @NonNull ResourceAuthority<Row> authority() {
        return ResourceAuthority.<Row>builder()
            .write(null, (record, access) -> HohenheimAccess.reachesRecord(access, SiteModel.MODEL_ID,
                record.get(SiteDomainModel.SITE_ID), HohenheimAccess.MANAGE))
            // A create under a site (its Domains tab's add, the create form, the submit) asks the same of that site.
            .createUnder((site, access) -> site instanceof Integer id
                && HohenheimAccess.reachesRecord(access, SiteModel.MODEL_ID, id, HohenheimAccess.MANAGE))
            .build();
    }

    /** A route is looked up by the host it answers on and the path it claims. */
    private static @NonNull ResourceList<Row> list() {
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(SiteDomainModel.HOSTNAME).filterable().copyable().build())
            // The path is half of what this route matches; a match type without it is a rule
            // with its subject missing.
            .column(ColumnSpec.fromField(SiteDomainModel.MATCH_TYPE).filterable().subtext("path").build())
            .column(ColumnSpec.fromField(SiteDomainModel.PATH).hidden().build())
            .column(ColumnSpec.fromField(SiteDomainModel.FORCE_SSL).filterable().build())
            // The cell resolves the site's NAME, so the header says "Site": the relation spelling drops "_id".
            .column(ColumnSpec.fromField(SiteDomainModel.SITE_ID)
                .label(FieldLabels.labelForRelation(SiteDomainModel.SITE_ID))
                .relation(RelationPick.of(SiteDomainModel.SITE_ID, SiteModel.MODEL_ID).build()).build())
            .filter(FilterSpec.leaf(SiteDomainModel.HOSTNAME, CoreTypes.CONTAINS)
                .label(FieldLabels.labelFor(SiteDomainModel.HOSTNAME)).build())
            .filter(FilterSpec.leaf(SiteDomainModel.MATCH_TYPE, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(SiteDomainModel.MATCH_TYPE)).build())
            .filter(FilterSpec.leaf(SiteDomainModel.FORCE_SSL, CoreTypes.IS_TRUE, CoreTypes.IS_FALSE)
                .label(FieldLabels.labelFor(SiteDomainModel.FORCE_SSL)).build())
            .build();
        return ResourceList.rows(table)
            .chrome(ListChrome.MINIMAL)
            .search(SiteDomainModel.HOSTNAME, SiteDomainModel.PATH)
            // What an empty list (a new site's Domains tab above all: it routes nothing yet) tells the reader to do.
            .emptyDescription(Microcopy.of("empty_description").withFilter("scope", "site_domains"))
            .build();
    }

    /**
     * A twin's form part around its own spec: the quick-add bar with its site preset, the parent prefill and the
     * TLS switches as inline cells.
     *
     * AIDEV-NOTE: every inline write is correct on a ONE-ENTRY map by construction, because the route invariant reads
     * its inputs through {@link SiteDomainModel#effective} rather than off the coerced map. HOSTNAME, PATH, LISTEN_ON
     * and MATCH_TYPE are excluded on purpose: those four ARE the live route claim, and a one-click cell edit would
     * quarantine a hostname the operator still believes they own; that belongs on a form, next to the refusals that
     * explain it.
     */
    private static @NonNull ResourceForm<Row> form(@NonNull FormSpec spec) {
        return ResourceForm.<Row>of(spec)
            .createDefaults(request -> createDefaults(spec, request))
            .quickCreate(QUICK_CREATE)
            .quickCreatePresets(DomainParts::quickCreatePresets)
            .inlineEditable(SiteDomainModel.FORCE_SSL, SiteDomainModel.HSTS_ENABLED,
                SiteDomainModel.HSTS_SUBDOMAINS, SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT)
            .build();
    }

    /**
     * A create under a TLS passthrough site (its Domains tab's add link names the site as the create's parent) opens
     * with HTTPS forcing and ACME off: that site terminates no TLS here. The parent field itself is the framework's
     * child create preset.
     *
     * AIDEV-NOTE: the site is read through the panel's own site entry, the caller's scope: a site the caller cannot
     * reach opens the create exactly as no site does, so the form is no probe of another tenant's configuration
     * (GPT review 25 D03).
     */
    private static @NonNull Map<String, Object> createDefaults(@NonNull FormSpec spec, @NonNull PanelRequest request) {
        Map<String, Object> values = new LinkedHashMap<>(spec.defaultValues());
        Integer siteId = CmsSupport.scopedParentId(request.conduit(), CmsEndpoints.PARENT_PARAM.getName(),
            HohenheimSlugs.SITES);
        if (siteId != null && request.panel().entryBySlug(HohenheimSlugs.SITES) instanceof PanelResource<?> sites) {
            Object site = PartsReads.loadRow(request, sites, siteId, request.access());
            if (site instanceof Row row && TlsPassthroughUpstreamKind.ID.toString()
                    .equals(row.get(SiteModel.UPSTREAM_KIND))) {
                values.put(SiteDomainModel.FORCE_SSL.getName(), false);
                values.put(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName(), true);
            }
        }
        return Map.copyOf(values);
    }

    /** The site the bar adds into: the {@code ?site_id=} prefill, else the tab's own record. */
    private static @NonNull Map<String, Object> quickCreatePresets(@NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        if (conduit == null) {
            return Map.of();
        }
        Integer siteId = CmsSupport.scopedParentId(conduit, SiteDomainModel.SITE_ID.getName(),
            HohenheimSlugs.SITES);
        return siteId != null ? Map.of(SiteDomainModel.SITE_ID.getName(), siteId) : Map.of();
    }

    /** Discovered local addresses (refreshed hourly by UpdateSystemIpAddresses); blank = all interfaces. */
    private static @NonNull List<FieldOption<String>> listenOnOptions() {
        List<FieldOption<String>> options = new ArrayList<>();
        for (String address : UpdateSystemIpAddresses.getLocalAddresses()) {
            // An address is data, never a translation key.
            options.add(FieldOption.of(address, Microcopy.literal(address)));
        }
        return options;
    }

    private static @NonNull FormSpec adminFormSpec() {
        return FormSpec.builder()
            .add(RelationPick.of(SiteDomainModel.SITE_ID, SiteModel.MODEL_ID).build())
            .add(SiteDomainModel.HOSTNAME)
            // The select (options, labels, icons) derives from the MATCH_TYPE EnumField, the vocabulary's one home.
            .add(SiteDomainModel.MATCH_TYPE)
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
            // A domain is a hostname, how it matches and what serves TLS for it. HSTS, the
            // ACME opt-out, path stripping and the two header maps are tuning.
            .section(FormSection.advanced(
                SiteDomainModel.STRIP_PATH.getName(),
                SiteDomainModel.HSTS_ENABLED.getName(),
                SiteDomainModel.HSTS_SUBDOMAINS.getName(),
                SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName(),
                SiteDomainModel.CUSTOM_HEADERS.getName(),
                SiteDomainModel.RESPONSE_HEADERS.getName()))
            .build();
    }

    /**
     * The delegated form. A UX affordance ONLY: the fields it omits are frozen by {@code TenantWrites} on the
     * SiteDomainModel write pipeline, which every writer passes and this form does not.
     */
    private static @NonNull FormSpec manageFormSpec() {
        return FormSpec.builder()
            .add(RelationPick.of(SiteDomainModel.SITE_ID, SiteModel.MODEL_ID).build())
            .add(SiteDomainModel.HOSTNAME)
            .add(SiteDomainModel.FORCE_SSL)
            .add(SiteDomainModel.HSTS_ENABLED)
            .add(SiteDomainModel.HSTS_SUBDOMAINS)
            .add(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT)
            .build();
    }

    /**
     * A soft-deleted site is invisible everywhere, and so are its hostnames.
     *
     * AIDEV-NOTE: the rows are deliberately NOT deleted with the site: they are what a site restore brings its
     * hostnames back from, and {@code DnsClaimReleases} already quarantined the names. This is a VISIBILITY answer,
     * so the same predicate 404s the detail route of an orphaned row. Spelled through the site's SoftDeleteBehaviour
     * because a relation hop runs no find hook.
     */
    private static @NonNull Criteria liveSiteScope() {
        return Criteria.related(SiteDomainModel.SITE, SiteModel.SOFT_DELETE.isNotTrashed());
    }
}
