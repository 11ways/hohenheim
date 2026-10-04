package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.dns.DnsRecordDto;
import be.elevenways.hohenheim.dns.DnsRecordFormView;
import be.elevenways.hohenheim.dns.DnsRecordView;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.server.dns.DnsPeerApi;
import be.elevenways.hohenheim.server.dns.DnsZoneSnapshot;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.protoblast.common.typed.rule.Condition;
import be.elevenways.protoblast.common.typed.rule.Operand;
import be.elevenways.plumage.component.Pager;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.render.table.TableState;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.render.action.PageFormState;
import be.elevenways.zenit.cms.server.page.PageActions;
import be.elevenways.zenit.cms.server.panel.PartsLists;
import be.elevenways.zenit.cms.server.panel.PartsReads;
import be.elevenways.zenit.cms.server.panel.PanelActionOffers;
import be.elevenways.zenit.cms.server.panel.ResourceVerbs;
import be.elevenways.zenit.cms.common.schema.FilterState;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.cms.server.page.InlineEditStates;
import be.elevenways.zenit.cms.server.page.QuickAddState;
import be.elevenways.zenit.cms.server.panel.PartsWrites;
import be.elevenways.zenit.cms.server.render.table.TableStateTranslator;
import be.elevenways.zenit.common.data.FacetUrlState;
import be.elevenways.zenit.common.data.ListState;
import be.elevenways.zenit.common.coerce.PrimitiveCoercion;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.RecordPage;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.rules.RuleCompiler;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.BoundEndpoint;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.routing.ReturnPath;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Records tab on a DNS zone: the zone's own records rendered through the record
 * resource's declarations (search, quick-add, inline-editable cells, row actions),
 * or -- on a SECONDARY zone -- the owning peer's records read through its API, whose
 * edits this tab's own submit forwards to the owner.
 *
 * AIDEV-NOTE: the remote edits ride the framework's subpage submit lane, never an endpoint
 * of their own: zenit-cms refuses a submit over a read-only zone (trashed, or under a
 * trashed record) before {@link #submit} runs, and the render offers no edit there.
 */
public final class DnsZoneRecordsPage implements RecordTab.Rendered<Row> {

    private static final PanelAction<Row> REMOTE_EDIT = PanelAction.<Row, CmsActionResult>places(
        DnsOperations.REMOTE_EDIT, ActionPlacement.PAGE, (request, result) -> result.value())
        .transport("action", "record_id")
        .confirmation(ConfirmationSpec.builder()
            .title(Microcopy.of("save_remote").withFilter("scope", "dns_remote"))
            .body(Microcopy.of("edit_saved").withFilter("scope", "dns_remote")).build())
        .selectedByRoute(zone -> String.valueOf((Object) zone.get(DnsZoneModel.ID))).build();

    @Override public @NonNull List<PanelAction<Row>> actions() { return List.of(REMOTE_EDIT); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row zone) {
        if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
            return renderRemote(request, zone);
        }
        return renderLocal(request.conduit(), request.access(), zone);
    }

    /** This page's template, shared by the available and unavailable branches. */
    private static final Identifier TEMPLATE = HohenheimTemplateIds.DNS_ZONE_RECORDS;

    @Override public @NonNull Identifier id() { return HohenheimIds.id("dns_zone_records"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("records").withFilter("scope", "dns_zone"); }
    /** This tab's slug under the zone record. */
    public static final String SLUG = "records";

    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("list-ul"); }

    /** The DNS record resource lives only on the admin panel. */
    private static final String PANEL = HohenheimSlugs.ADMIN;

    /**
     * The columns this tab shows, in this order: the zone itself is the page, so the
     * resource's zone column would repeat the heading on every row.
     */
    private static final List<String> COLUMNS = List.of(
        DnsRecordModel.NAME.getName(), DnsRecordModel.TYPE.getName(),
        DnsRecordModel.VALUE.getName(), DnsRecordModel.TTL.getName(),
        DnsRecordModel.ENABLED.getName(), DnsRecordModel.MANAGED_BY.getName());

    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row zone) {
        if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
            Panel panel = Objects.requireNonNull(PanelRegistry.getBySlug(PANEL));
            return renderRemote(new PanelRequest(panel, conduit, accessContext, null), zone);
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
        PanelResource<Row> resource = recordResource(conduit, accessContext);
        if (resource == null) {
            // The DNS role is off, so the record resource is not on the panel at all.
            return new RenderTemplateResult(TEMPLATE, unavailableVars(conduit, zone));
        }
        return renderLocal(conduit, accessContext, zone, resource);
    }

    /**
     * The record resource this tab renders through, the sibling of
     * {@code SiteParts.DOMAINS}.
     *
     * @return null when the DNS role is off, so the resource is not on the panel at all
     */
    private static @Nullable PanelResource<Row> recordResource(Conduit conduit, AccessContext access) {
        Panel panel = PanelRegistry.getBySlug(PANEL);
        if (panel != null && panel.entryBySlug(DnsRecordParts.SLUG) instanceof PanelResource<?> entry) {
            @SuppressWarnings("unchecked") PanelResource<Row> records = (PanelResource<Row>) entry;
            return records;
        }
        return null;
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
                                          @NonNull PanelResource<Row> resource) {
        Integer zoneId = zone.get(DnsZoneModel.ID);
        String origin = zone.get(DnsZoneModel.ORIGIN);
        // The panel this tab renders under: the one whose peers every write predicate below
        // resolves the record's parent against (a record under a trashed zone is read-only).
        Panel panel = Objects.requireNonNull(PanelRegistry.getBySlug(PANEL), "the admin panel is registered");
        PanelRequest request = new PanelRequest(panel, conduit, accessContext, null);

        // The tab's search and page are core's one list state, read by its one codec.
        ListState state = FacetUrlState.readState(ListState.Shape.PLAIN, conduit::getQueryParams);
        String search = state.text().isEmpty() ? null : state.text();
        TableView.Applied<Row> applied = TableView
            .forPrincipal(accessContext.principalId(), resource.id())
            .visibleColumns(COLUMNS)
            .sort(SortSpec.asc(DnsRecordModel.NAME.getName()))
            .filter(zoneScope(resource, zoneId, accessContext))
            .build()
            .apply(PartsLists.<Row>tableSpec(resource))
            .withSearch(search)
            .withPage(state.page());
        // The resource's OWN list read: its access predicate, its search semantics, its
        // page window and its total -- this tab only adds the zone scope.
        RecordPage<Row> page = PartsReads.listPage(request, resource, null, applied, accessContext);
        List<Row> records = page.rows();
        // The per-row write verdicts below walk each record's parent (this zone) once for the whole page.
        PartsWrites.prefetchLineage(resource, panel, records, accessContext);

        BoundEndpoint<?> listTarget = CmsRoutes.subpage(PANEL, DnsZoneParts.SLUG, zoneId, this.slug());
        String listUrl = listTarget.toUrl();
        // An add returns to the listing AS IT STANDS, so a search made before it survives.
        // Rebuilt from the state this render knows rather than echoed from the request URL:
        // the kept select picks are appended to it per add, and echoing would stack them.
        String refreshUrl = search != null
            ? listTarget.with(CmsEndpoints.LIST_SEARCH_PARAM, search).toUrl() : listUrl;
        // Every outgoing link (record form, row invoke, delete) carries THIS tab as its
        // return target, search included. The generated list binds one only when it has
        // query state, because its bare URL is already the framework's recomputed
        // fallback -- but the fallback for a dns-record write is the GLOBAL record list,
        // not this zone's tab, so here the bare tab URL is state worth coming back to:
        // without it a delete confirmed from this tab landed on /admin/dns-records.
        String returnTo = ReturnTarget.capture(conduit);
        TableState table = new TableStateTranslator().translate(
            applied,
            records,
            row -> PartsReads.rowKey(resource, row),
            row -> PartsReads.rowCells(request, resource, null, applied, row),
            column -> null,
            row -> recordUrl(resource, row, returnTo),
            row -> ResourceVerbs.editableBy(request, resource, row, accessContext)
                ? recordUrl(resource, row, returnTo) : null,
            row -> null,
            row -> DeleteConfirmation.<Row>defaults().fallback(),
            // Per ROW: a delete the principal may perform in general yet the write will
            // refuse for THIS record stays on the menu, dead, with its reason (see
            // ResourceVerbs.unavailable); DELETE_SUBMIT refuses with the same text.
            row -> null,
            // Promoted seam: the framework's own affordance answer, which the generated
            // list page uses too -- this page used to carry a copy of it.
            row -> InlineEditStates.editableCellsFor(panel, resource, applied, row, accessContext),
            PanelActionOffers.rowsForRender(request, resource, null, records, accessContext,
                ReturnPath.of(returnTo)),
            accessContext);

        // A read-only zone (trashed, or under a trashed record) offers no add: its records' writes are refused by
        // zenit-cms through the record resource's parent, and this is the same answer on screen.
        RouteTarget addRecordTarget = this.hostReadOnly(conduit) ? null : CmsEndpoints.CREATE_FORM
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
        vars.put("searchParam", FacetUrlState.TEXT_PARAM);
        vars.put("searchEnabled", PartsLists.searchOffered(resource));
        vars.put("searchActive", search != null);
        vars.put("pager", Pager.of(page.window(), page.total(), number -> pageUrl(listTarget, search, number)));
        vars.put("addRecordTarget", addRecordTarget);
        vars.put("recordTabs", recordTabs(conduit));
        // Promoted seam: the framework's own quick-add builder. The zone preset it needs
        // is answered by the record entry's quick-create presets, which read THIS route.
        QuickAddState.putVars(vars, panel, resource, accessContext, refreshUrl,
            addRecordTarget == null ? null : addRecordTarget.toUrl());
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
     * The zone this tab lists, as the record resource's own ADVANCED filter tier.
     *
     * AIDEV-NOTE: this replaced a hand-built, UNPAGED query with its own copy of the search
     * (capability map: never two UIs over the same records). The scope rides the resource's
     * filter compile, so the list, its count and its search are the generated list's own.
     * The framework treats a tree that fails vocabulary validation as INERT -- which here
     * would silently widen the tab to every zone's records -- so the tree is validated
     * against the resource's vocabulary first and a failure refuses loudly (fail closed).
     */
    static @NonNull FilterState zoneScope(@NonNull PanelResource<Row> resource, @NonNull Integer zoneId,
                                           @NonNull AccessContext access) {
        Condition scope = Condition.all(Condition.test(DnsRecordModel.ZONE_ID.getName(), CoreTypes.EQUALS,
            Operand.of(zoneId)));
        if (!RuleCompiler.validate(scope, PartsLists.filterVocabulary(resource, access)).isEmpty()) {
            throw new IllegalStateException("The DNS record vocabulary cannot scope by "
                + DnsRecordModel.ZONE_ID.getName());
        }
        return FilterState.empty().withAdvanced(scope);
    }

    /** One pager rung: the tab itself, keeping the search, on the given page. */
    private static @NonNull String pageUrl(@NonNull BoundEndpoint<?> listTarget, @Nullable String search,
                                           int number) {
        BoundEndpoint<?> target = search != null ? listTarget.with(CmsEndpoints.LIST_SEARCH_PARAM, search)
            : listTarget;
        return number > 1 ? target.with(CmsEndpoints.LIST_PAGE_PARAM, number).toUrl() : target.toUrl();
    }

    private static @NonNull String recordUrl(@NonNull PanelResource<Row> resource, @NonNull Row row,
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
    private @NonNull ActionResult<?> renderRemote(@NonNull PanelRequest request, @NonNull Row zone) {
        Conduit conduit = request.conduit();
        Integer zoneId = zone.get(DnsZoneModel.ID);
        String origin = zone.get(DnsZoneModel.ORIGIN);

        Integer peerId = zone.get(DnsZoneModel.PRIMARY_PEER_ID);
        Row peer = peerId != null ? Models.get(DnsPeerModel.class).findById(peerId) : null;
        DnsPeerApi api = DnsPeerApi.forPeer(peer);

        List<DnsRecordView> records = new ArrayList<>();
        boolean reachable = false;
        String notice = "";
        DnsRecordFormView editRecord = null;
        String requestedRecord = conduit.getQueryParam(HohenheimParams.REMOTE_RECORD.getName());

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
                reachable = true;
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

        if (!reachable) {
            replicaRecords(origin, records);
        }
        // A read-only zone still reads its owner's records, but offers none of their edits: the submit is refused.
        boolean editable = reachable && !this.hostReadOnly(conduit);
        if (!editable) {
            editRecord = null;
        }
        else if ("new".equals(requestedRecord)) {
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
        // The numeric fields ride pl-number-input, whose value is a typed Double (null =
        // empty) -- never the native numeric input, whose decimal separator follows the
        // browser's UI locale. Scale 0 makes the submitted canonical text a whole number.
        vars.put("editTtl", editRecord != null ? wholeNumber(editRecord.ttl()) : null);
        vars.put("editPriority", editRecord != null ? wholeNumber(editRecord.priority()) : null);
        vars.put("editWeight", editRecord != null ? wholeNumber(editRecord.weight()) : null);
        vars.put("editPort", editRecord != null ? wholeNumber(editRecord.port()) : null);
        vars.put("recordTypes", DnsRecordModel.ALL_TYPES);
        vars.put("addRecordTarget", remoteRecordTarget(zoneId, "new"));
        vars.put("recordsTabTarget", CmsRoutes.subpage(PANEL, DnsZoneParts.SLUG, zoneId, this.slug()));
        vars.put("remoteForm", editable && editRecord != null
            ? remoteForm(request, zone, Map.of("record_id", editRecord.id())) : null);
        Map<String, PageFormState> deletes = new LinkedHashMap<>();
        if (editable) {
            for (DnsRecordView record : records) {
                PageFormState form = remoteForm(request, zone, Map.of("action", "delete", "record_id", record.id()));
                if (form != null) deletes.put(record.id(), form);
            }
        }
        vars.put("deleteForms", deletes);
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(HohenheimTemplateIds.DNS_ZONE_REMOTE_RECORDS, vars);
    }

    /**
     * Forward a secondary zone's remote-record save or delete to its owning peer.
     *
     * @return a toast of the owner's answer, back on this tab; a primary zone's tab has nothing to forward
     */
    static @NonNull CmsActionResult forward(@NonNull Row zone, DnsOperations.@NonNull RemoteInput input) {
        if (!DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
            return CmsActionResult.refresh();
        }
        Integer peerId = zone.get(DnsZoneModel.PRIMARY_PEER_ID);
        DnsPeerApi api = DnsPeerApi.forPeer(peerId != null ? Models.get(DnsPeerModel.class).findById(peerId) : null);
        if (api == null) {
            return CmsActionResult.errorToast(Microcopy.of("peer_not_configured").withFilter("scope", "dns_remote"));
        }

        String origin = zone.get(DnsZoneModel.ORIGIN);
        String action = input.action() == null ? "" : input.action();
        if (!action.isEmpty() && !"delete".equals(action)) {
            throw HohenheimViolations.ofField("action", action, "dns_remote_action_invalid");
        }
        String recordText = input.record_id() == null ? "" : input.record_id();
        Integer recordId = HandlerSupport.submittedInteger(Map.of("record_id", recordText), "record_id");
        if (!recordText.isEmpty() && recordId == null) {
            return CmsActionResult.refresh();
        }
        Map<String, String> fields = input.fields();

        try {
            if ("delete".equals(action) && recordId != null) {
                api.deleteRecord(origin, recordId);
            }
            else if (recordId != null) {
                api.updateRecord(origin, recordId, fields);
            }
            else {
                api.createRecord(origin, fields);
            }
        }
        catch (DnsPeerApi.PeerApiException e) {
            // A validation refusal round-trips by microcopy key (same catalogs on both instances); a transport
            // failure shows the raw message.
            return CmsActionResult.errorToast(e.getViolationKey() != null
                ? HohenheimViolations.text(e.getViolationKey())
                : Microcopy.of("peer_call_failed").withFilter("scope", "dns_remote")
                    .withArg("reason", String.valueOf(e.getMessage())));
        }
        return CmsActionResult.refreshWithToast(Microcopy.of("edit_saved").withFilter("scope", "dns_remote"));
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
            .with(CmsEndpoints.RESOURCE_PARAM, DnsZoneParts.SLUG)
            .with(CmsEndpoints.RESOURCE_ID_PARAM, String.valueOf(zoneId))
            .with(CmsEndpoints.SUBPAGE_PARAM, SLUG)
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
        return DnsRecordModel.presentationValue(remote.type(), text(remote.value()),
            remote.priority(), remote.weight(), remote.port());
    }

    /** @return the form view's number text as the Double a pl-number-input takes, null when blank or not one */
    private static @Nullable Double wholeNumber(@NonNull String text) {
        PrimitiveCoercion.Result<Double> parsed =
            PrimitiveCoercion.toDouble(text, true, PrimitiveCoercion.TextRule.TRIMMED_BLANK_IS_NULL);
        return parsed.ok() ? parsed.value() : null;
    }

    private @Nullable PageFormState remoteForm(PanelRequest request, Row zone, Map<String, Object> values) {
        PageActions.Opened opened = PageActions.open(request, this, zone, REMOTE_EDIT.id(), values);
        return opened instanceof PageActions.Form form ? form.state() : null;
    }

    private static @NonNull String text(@Nullable String value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static @NonNull String text(@Nullable Integer value) {
        return value != null ? String.valueOf(value) : "";
    }

}
