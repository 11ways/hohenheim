package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.server.dns.DnsNotifier;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A primary zone's secondary links: which peers replicate the zone (NOTIFY
 * targets and AXFR-authorized TSIG keys). Nav-hidden; reached from the zone's
 * Secondaries tab with {@code ?zone_id=} preselected.
 */
public final class DnsZonePeerResource extends RowResource {

    /** The Secondaries tab's quick-add entry; the zone rides along as a preset. */
    private static final QuickCreateSpec QUICK_CREATE = QuickCreateSpec
        .of(DnsZonePeerModel.PEER_ID.getName())
        .presets(DnsZonePeerModel.ZONE_ID.getName());

    private final FormSpec formSpec = FormSpec.builder()
        .add(DnsZonePeerModel.ZONE_ID)
        .add(Select.of(DnsZonePeerModel.PEER_ID)
            .options(OptionSource.dynamic(ctx -> peerOptions()))
            .build())
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.virtual("peer_name", Microcopy.of("peer_name").withFilter("scope", "field")).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "dns_zone_peer"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "dns_zone_peer"); }
    @Override public @NonNull String slug() { return "dns-zone-peers"; }
    @Override public @NonNull Model model() { return Models.get(DnsZonePeerModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.NETWORK_GROUP; }
    @Override public int navOrder() { return 45; }
    @Override public @NonNull Icon icon() { return Icon.of("handshake"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @org.checkerframework.checker.nullness.qual.Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("dns-zones", row -> row.get(DnsZonePeerModel.ZONE_ID)).tab("secondaries");
    }

    /** The zone's Secondaries tab links here with ?zone_id= so the link is scoped. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        String zoneId = conduit.getQueryParam("zone_id");
        if (zoneId != null && !zoneId.isEmpty()) {
            try {
                return Map.of("zone_id", Integer.parseInt(zoneId));
            } catch (NumberFormatException ignored) {
                // Malformed prefill: render the bare form.
            }
        }
        return Map.of();
    }

    /**
     * The Secondaries tab's quick-add bar: linking a peer is one pick.
     *
     * AIDEV-NOTE: there is deliberately no inline counterpart. PEER_ID is the whole row --
     * it defines which peer is AXFR-authorized for this zone -- so editing it in place is
     * not editing a property of a link, it is replacing the link. Unlinking and linking
     * are the two acts, and both already exist.
     */
    @Override
    public @Nullable QuickCreateSpec quickCreate() {
        return QUICK_CREATE;
    }

    /** The zone the bar links into: the {@code ?zone_id=} prefill, else the tab's own record. */
    @Override
    public @NonNull Map<String, Object> quickCreatePresetValues(@NonNull AccessContext accessContext) {
        Conduit conduit = accessContext.conduit();
        if (conduit == null) {
            return Map.of();
        }
        Integer zoneId = CmsSupport.scopedParentId(conduit, DnsZonePeerModel.ZONE_ID.getName(),
            DnsZoneResource.SLUG);
        return zoneId != null ? Map.of(DnsZonePeerModel.ZONE_ID.getName(), zoneId) : Map.of();
    }

    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        if ("peer_name".equals(column.name())) {
            Integer peerId = row.get(DnsZonePeerModel.PEER_ID);
            Row peer = peerId != null ? Models.get(DnsPeerModel.class).findById(peerId) : null;
            return peer != null ? peer.get(DnsPeerModel.NAME) : null;
        }
        return super.cellValue(row, column);
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        validate(coerced);
        Object id = super.persistRow(coerced, accessContext);
        // Prompt the freshly linked secondary to pull immediately.
        if (coerced.get("zone_id") instanceof Integer zoneId) {
            DnsNotifier.INSTANCE.notifyZonePeers(zoneId);
        }
        return id;
    }

    private static void validate(@NonNull Map<String, Object> coerced) {
        Object zoneValue = coerced.get("zone_id");
        Object peerValue = coerced.get("peer_id");
        if (!(zoneValue instanceof Integer zoneId) || Models.get(DnsZoneModel.class).findById(zoneId) == null) {
            throw Violations.ofField("zone_id", zoneValue, CmsSupport.violationText("dns_zone_missing"));
        }
        if (!(peerValue instanceof Integer peerId) || Models.get(DnsPeerModel.class).findById(peerId) == null) {
            throw Violations.ofField("peer_id", peerValue, CmsSupport.violationText("dns_peer_missing"));
        }
        Row existing = Models.get(DnsZonePeerModel.class).find()
            .where(DnsZonePeerModel.ZONE_ID.eq(zoneId))
            .and(DnsZonePeerModel.PEER_ID.eq(peerId))
            .first();
        if (existing != null) {
            throw Violations.ofField("peer_id", peerId, CmsSupport.violationText("dns_secondary_linked"));
        }
    }

    private static @NonNull List<FieldOption<Integer>> peerOptions() {
        List<FieldOption<Integer>> options = new ArrayList<>();
        for (Row peer : Models.get(DnsPeerModel.class).findEnabled()) {
            options.add(FieldOption.of(peer.get(DnsPeerModel.ID),
                String.valueOf(peer.get(DnsPeerModel.NAME))));
        }
        return options;
    }
}
