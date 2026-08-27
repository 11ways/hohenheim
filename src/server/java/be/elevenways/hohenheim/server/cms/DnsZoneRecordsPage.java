package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.dns.DnsRecordDto;
import be.elevenways.hohenheim.dns.DnsRecordFormView;
import be.elevenways.hohenheim.dns.DnsRecordView;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsPeerApi;
import be.elevenways.hohenheim.server.dns.DnsZoneSnapshot;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.render.table.TableState;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.cms.server.page.InlineEditStates;
import be.elevenways.zenit.cms.server.page.QuickAddState;
import be.elevenways.zenit.cms.server.render.table.TableStateTranslator;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.TextSearchable;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.BoundEndpoint;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Records tab on a DNS zone: the zone's own records rendered through the record
 * resource's declarations (search, quick-add, inline-editable cells, row actions),
 * or -- on a SECONDARY zone -- the owning peer's records read through its API.
 */
public final class DnsZoneRecordsPage implements RecordScopedPage<Row> {

    /** This page's template, shared by the available and unavailable branches. */
    private static final Identifier TEMPLATE = Identifier.of("hohenheim", "cms/dns-zone-records");

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "dns_zone_records"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("records").withFilter("scope", "dns_zone"); }
    @Override public @NonNull String slug() { return "records"; }
    @Override public @NonNull Icon icon() { return Icon.of("list-ul"); }

    /** The DNS record resource lives only on the admin panel. */
    private static final String PANEL = "admin";

    /** The record resource's slug, for the panel lookup this page's links then read off it. */
    private static final String RECORD_SLUG = "dns-records";

    /**
     * The plain search term, named after the framework's own {@code cms-list-search} input:
     * the box on this page is that element, so the page reads and re-binds the parameter it
     * submits rather than inventing one.
     */
    private static final String SEARCH = "search";

    private static final ParameterDefinition<String> SEARCH_PARAM =
        ParameterDefinition.builder(String.class).name(SEARCH).build();

    /**
     * The columns this tab shows, in this order: the zone itself is the page, so the
     * resource's zone column would repeat the heading on every row.
     */
    private static final List<String> COLUMNS = List.of(
        DnsRecordModel.NAME.getName(), DnsRecordModel.TYPE.getName(),
        DnsRecordModel.VALUE.getName(), DnsRecordModel.TTL.getName(),
        DnsRecordModel.ENABLED.getName(), DnsRecordModel.MANAGED_BY.getName());

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row zone) {
        if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
            return renderRemote(conduit, zone);
        }
        return renderLocal(conduit, accessContext, zone);
    }

    /**
     * A PRIMARY zone's own records, rendered through the record resource's declarations:
     * its table spec (typed cells, the per-type badge the TYPE enum declares, the copy
     * chip), its row actions, its search fields, its quick-add bar and its inline-editable
     * cells. The page stays bespoke ONLY for what the generated list cannot know: the zone
     * scope and the zone preset the add bar carries.
     */
    private @NonNull ActionResult<?> renderLocal(@NonNull Conduit conduit,
                                                 @NonNull AccessContext accessContext,
                                                 @NonNull Row zone) {
        DnsRecordResource resource = recordResource();
        if (resource == null) {
            // The DNS role is off, so the record resource is not on the panel at all.
            return new RenderTemplateResult(TEMPLATE, unavailableVars(conduit, zone));
        }
        return renderLocal(conduit, accessContext, zone, resource);
    }

    /**
     * The record resource this tab renders through, the sibling of
     * {@code SiteDomainsPage.domainResource}.
     *
     * @return null when the DNS role is off, so the resource is not on the panel at all
     */
    private static @Nullable DnsRecordResource recordResource() {
        Panel panel = PanelRegistry.getBySlug(PANEL);
        return panel != null && panel.peerBySlug(RECORD_SLUG) instanceof DnsRecordResource peer
            ? peer : null;
    }

    /**
     * The same render against a GIVEN record resource, so the per-record declarations this
     * page forwards (delete confirmation, delete refusal reason, inline-editable cells) can
     * be exercised against a resource that declares them; the shipped one declares none
     * today, which would leave the forwarding itself unproven.
     */
    @NonNull ActionResult<?> renderLocal(@NonNull Conduit conduit,
                                         @NonNull AccessContext accessContext,
                                         @NonNull Row zone,
                                         @NonNull DnsRecordResource resource) {
        Integer zoneId = zone.get(DnsZoneModel.ID);
        String origin = zone.get(DnsZoneModel.ORIGIN);

        String search = Texts.trimmedOrNull(conduit.getQueryParam(SEARCH));
        List<Row> records = zoneRecords(zoneId, search, resource.searchFields());
        TableView.Applied<Row> applied = TableView
            .forPrincipal(accessContext.principal().id(), resource.id())
            .visibleColumns(COLUMNS)
            .build()
            .apply(resource.tableSpec());

        BoundEndpoint<?> listTarget = CmsRoutes.subpage(PANEL, DnsZoneResource.SLUG, zoneId, this.slug());
        String listUrl = listTarget.toUrl();
        // An add returns to the listing AS IT STANDS, so a search made before it survives.
        // Rebuilt from the state this render knows rather than echoed from the request URL:
        // the kept select picks are appended to it per add, and echoing would stack them.
        String refreshUrl = search != null
            ? listTarget.with(SEARCH_PARAM, search).toUrl() : listUrl;
        // Outgoing record links carry a return target only when this listing carries state
        // worth coming back TO (a search): the bare tab URL is already the record form's
        // recomputed fallback, so a stateless page keeps clean links. Same rule the
        // generated list applies.
        String returnTo = search != null ? ReturnTarget.capture(conduit) : null;
        TableState table = new TableStateTranslator().translate(
            applied,
            records,
            resource::rowKey,
            row -> resource.rowCells(applied, row),
            resource.rowActions(),
            (actionId, row) -> new Uri(ReturnTarget.bind(
                CmsRoutes.invokeRow(PANEL, resource.slug(), resource.rowKey(row), actionId),
                returnTo).toUrl()),
            column -> null,
            row -> recordUrl(resource, row, returnTo),
            row -> resource.updatable() && resource.updatableBy(row, accessContext)
                ? recordUrl(resource, row, returnTo) : null,
            row -> resource.deletable() && resource.deletableBy(row, accessContext)
                ? ReturnTarget.bind(CmsRoutes.delete(PANEL, resource.slug(), resource.rowKey(row)),
                    returnTo).toUrl()
                : null,
            row -> resource.deleteConfirmationFor(row),
            // Per ROW: a delete the principal may perform in general yet the write will
            // refuse for THIS record stays on the menu, dead, with its reason (see
            // Resource.deleteUnavailableReason); DELETE_SUBMIT refuses with the same text.
            row -> resource.deleteUnavailableReason(row, accessContext),
            // Promoted seam: the framework's own affordance answer, which the generated
            // list page uses too -- this page used to carry a copy of it.
            row -> InlineEditStates.editableCellsFor(resource, applied, row, accessContext),
            accessContext);

        RouteTarget addRecordTarget = CmsEndpoints.CREATE_FORM
            .with(CmsEndpoints.PANEL_PARAM, PANEL)
            .with(CmsEndpoints.RESOURCE_PARAM, resource.slug())
            .with(HohenheimParams.ZONE_ID_PREFILL, zoneId);

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "dns_zone_records", origin));
        vars.put("zoneId", zoneId);
        vars.put("origin", origin);
        vars.put("available", true);
        vars.put("table", table);
        vars.put("hasRowActions", table.rows().stream().anyMatch(TableState.RowState::hasAnyActions));
        vars.put("panelSlug", PANEL);
        vars.put("resourceSlug", resource.slug());
        vars.put("listUrl", listUrl);
        vars.put("searchValue", search != null ? search : "");
        vars.put("searchEnabled", resource.searchOffered());
        vars.put("searchActive", search != null);
        vars.put("addRecordTarget", addRecordTarget);
        vars.put("recordTabs", recordTabs(conduit));
        // Promoted seam: the framework's own quick-add builder. The zone preset it needs
        // is answered by DnsRecordResource.quickCreatePresetValues, which reads THIS route.
        QuickAddState.putVars(vars, resource, accessContext, refreshUrl, addRecordTarget.toUrl());
        return new RenderTemplateResult(TEMPLATE, vars);
    }

    /** Everything the template declares, for the branch that has no record resource to read. */
    private @NonNull Map<String, Object> unavailableVars(@NonNull Conduit conduit, @NonNull Row zone) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "dns_zone_records",
            zone.get(DnsZoneModel.ORIGIN)));
        vars.put("origin", zone.get(DnsZoneModel.ORIGIN));
        vars.put("zoneId", zone.get(DnsZoneModel.ID));
        vars.put("available", false);
        vars.put("recordTabs", recordTabs(conduit));
        return vars;
    }

    /**
     * The zone's records, narrowed by the plain search term over the fields the resource
     * declared searchable.
     *
     * AIDEV-NOTE: a hand-built query rather than {@code resource.listRows}, because the
     * zone scope is not expressible as a table-view filter and the generated list has no
     * zone to scope by. Only the TERM is interpreted here, and only for text fields --
     * which is every field this resource declares searchable.
     */
    private static @NonNull List<Row> zoneRecords(@NonNull Integer zoneId, @Nullable String search,
                                                  @NonNull List<Field<?, ?>> searchFields) {
        QueryBuilder<Row> query = Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId));
        if (search != null) {
            List<Criteria> candidates = new ArrayList<>(searchFields.size());
            for (Field<?, ?> field : searchFields) {
                if (field instanceof TextSearchable searchable) {
                    candidates.add(searchable.icontains(search));
                }
            }
            // A term no declared field can match narrows to nothing: an unfiltered list
            // would read as "everything matches", which is the opposite of the truth.
            query.where(candidates.isEmpty()
                ? Models.get(DnsRecordModel.class).matchNone()
                : candidates.size() == 1 ? candidates.get(0)
                    : Criteria.or(candidates.toArray(new Criteria[0])));
        }
        return query.orderBy(DnsRecordModel.NAME, SortOrder.ASC).all();
    }

    private static @NonNull String recordUrl(@NonNull DnsRecordResource resource, @NonNull Row row,
                                             @Nullable String returnTo) {
        return ReturnTarget.bind(
            CmsRoutes.detail(PANEL, resource.slug(), resource.rowKey(row)), returnTo).toUrl();
    }

    /**
     * A SECONDARY zone's records live on its owning peer: the tab reads the
     * owner's live records through the peer API and forwards edits to it.
     * When the peer is unconfigured or unreachable, the replica snapshot is
     * shown read-only instead (DNS keeps serving; only editing needs the owner).
     */
    private @NonNull ActionResult<?> renderRemote(@NonNull Conduit conduit, @NonNull Row zone) {
        Integer zoneId = zone.get(DnsZoneModel.ID);
        String origin = zone.get(DnsZoneModel.ORIGIN);

        Integer peerId = zone.get(DnsZoneModel.PRIMARY_PEER_ID);
        Row peer = peerId != null ? Models.get(DnsPeerModel.class).findById(peerId) : null;
        DnsPeerApi api = DnsPeerApi.forPeer(peer);

        List<DnsRecordView> records = new ArrayList<>();
        boolean editable = false;
        String notice = "";
        DnsRecordFormView editRecord = null;
        String requestedRecord = conduit.getQueryParam("record");

        if (api != null) {
            try {
                for (DnsRecordDto remote : api.listRecords(origin)) {
                    String id = text(remote.id());
                    records.add(new DnsRecordView(
                        id,
                        text(remote.name()),
                        text(remote.type()),
                        text(remote.ttl()),
                        remoteDisplayValue(remote),
                        remote.enabled(),
                        remote.managed_by() != null,
                        remoteRecordTarget(zoneId, id)));
                    if (id.equals(requestedRecord)) {
                        editRecord = formView(remote);
                    }
                }
                editable = true;
            }
            catch (RuntimeException e) {
                notice = Microcopy.of("peer_unreachable").withFilter("scope", "dns_remote")
                    .withArg("message", String.valueOf(e.getMessage()))
                    .resolve(conduit.getLocales(), conduit.getMessageResolver());
            }
        }
        else {
            notice = Microcopy.of("peer_not_configured").withFilter("scope", "dns_remote")
                .resolve(conduit.getLocales(), conduit.getMessageResolver());
        }

        if (!editable) {
            replicaRecords(origin, records);
        }
        if ("new".equals(requestedRecord) && editable) {
            editRecord = DnsRecordFormView.empty();
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "dns_zone_records", origin));
        vars.put("zoneId", zoneId);
        vars.put("origin", origin);
        vars.put("peerName", peer != null ? String.valueOf(peer.get(DnsPeerModel.NAME)) : "");
        vars.put("records", records);
        vars.put("editable", editable);
        vars.put("notice", notice);
        vars.put("editRecord", editRecord);
        vars.put("recordTypes", DnsRecordModel.ALL_TYPES);
        vars.put("addRecordTarget", remoteRecordTarget(zoneId, "new"));
        vars.put("recordsTabTarget", CmsRoutes.subpage(PANEL, DnsZoneResource.SLUG, zoneId, this.slug()));
        vars.put("remoteFormTarget", HohenheimEndpoints.DNS_REMOTE_RECORD
            .with(HohenheimEndpoints.ZONE_ID, zoneId));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/dns-zone-remote-records"), vars);
    }

    /**
     * The secondary zone's Records tab, opened on ONE remote record (or {@code new}).
     *
     * AIDEV-NOTE: composed off CmsEndpoints rather than CmsRoutes.subpage because the
     * link is a CMS route PLUS a query parameter, and CmsRoutes returns the RouteTarget
     * interface, which has no with(...).
     */
    private static @NonNull RouteTarget remoteRecordTarget(@NonNull Integer zoneId,
                                                           @NonNull String recordId) {
        return CmsEndpoints.RECORD_SUBPAGE
            .with(CmsEndpoints.PANEL_PARAM, PANEL)
            .with(CmsEndpoints.RESOURCE_PARAM, DnsZoneResource.SLUG)
            .with(CmsEndpoints.RESOURCE_ID_PARAM, String.valueOf(zoneId))
            .with(CmsEndpoints.SUBPAGE_PARAM, "records")
            .with(HohenheimParams.REMOTE_RECORD, recordId);
    }

    /** Read-only listing from the replica snapshot when the owner cannot be reached. */
    private static void replicaRecords(@NonNull String origin, @NonNull List<DnsRecordView> records) {
        DnsZoneSnapshot snapshot = DnsZoneStore.INSTANCE.getZone(origin);
        if (snapshot == null) {
            return;
        }
        for (org.xbill.DNS.Record record : snapshot.allRecordsExceptSoa()) {
            records.add(new DnsRecordView(
                "",
                record.getName().relativize(snapshot.getOrigin()).toString(true),
                org.xbill.DNS.Type.string(record.getType()),
                String.valueOf(record.getTTL()),
                record.rdataToString(),
                true,
                false,
                null));
        }
        records.sort(Comparator.comparing(DnsRecordView::name));
    }

    /** @return remote fields encoded as strings for HTML form controls */
    private static @NonNull DnsRecordFormView formView(@NonNull DnsRecordDto remote) {
        return new DnsRecordFormView(
            text(remote.id()),
            text(remote.name()),
            text(remote.type()),
            text(remote.ttl()),
            text(remote.value()),
            text(remote.priority()),
            text(remote.weight()),
            text(remote.port()),
            remote.enabled() ? "true" : "false");
    }

    private static @NonNull String remoteDisplayValue(@NonNull DnsRecordDto remote) {
        String value = text(remote.value());
        if (DnsRecordModel.TYPE_MX.equals(remote.type()) && remote.priority() != null) {
            return remote.priority() + " " + value;
        }
        if (DnsRecordModel.TYPE_SRV.equals(remote.type())) {
            return zeroIfNull(remote.priority()) + " " + zeroIfNull(remote.weight())
                + " " + zeroIfNull(remote.port()) + " " + value;
        }
        return value;
    }

    private static @NonNull String text(@Nullable String value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static @NonNull String text(@Nullable Integer value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static int zeroIfNull(@Nullable Integer value) {
        return value != null ? value : 0;
    }

}
