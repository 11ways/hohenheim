package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.server.dns.DnsNotifier;
import be.elevenways.hohenheim.server.dns.DnsSecondaryFreshness;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A primary zone's secondary links, the admin entry built from its parts: which peers replicate the zone (NOTIFY
 * targets and AXFR-authorized TSIG keys), each with its freshness as probed from this primary and what this primary
 * last did for it. Nav-hidden; listed on the zone's Secondaries tab.
 *
 * AIDEV-NOTE: there is deliberately no inline cell. PEER_ID is the whole link (which peer is AXFR-authorized for this
 * zone), so editing it in place is replacing the link; unlinking and linking are the two acts, and both exist.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class DnsZonePeerParts {

    /** The entry slug, which the zone's Secondaries tab links peer records by. */
    public static final String SLUG = "dns-zone-peers";

    /** The zone's tab listing its secondaries. */
    public static final String TAB = "secondaries";

    /** The computed column naming the linked peer. */
    static final String PEER_NAME = "peer_name";

    /** The computed column naming the peer's transfer host. */
    private static final String TRANSFER_HOST = "transfer_host";

    /** The computed column reading the peer's freshness, its probe failure under it. */
    private static final String FRESHNESS = "freshness";

    /** The Secondaries tab's quick-add entry; the zone rides along as a preset. */
    private static final QuickCreateSpec QUICK_CREATE = QuickCreateSpec
        .of(DnsZonePeerModel.PEER_ID.getName())
        .presets(DnsZonePeerModel.ZONE_ID.getName());

    private DnsZonePeerParts() {
    }

    /** @return the admin zone-peer link resource */
    public static @NonNull PanelResource<Row> admin() {
        // The zone is a RELATION, never the raw zone_id number an operator had to know the primary key for.
        FormSpec form = FormSpec.builder()
            .add(RelationPick.of(DnsZonePeerModel.ZONE_ID, DnsZoneModel.MODEL_ID).build())
            .add(Select.of(DnsZonePeerModel.PEER_ID)
                .options(OptionSource.dynamic(ctx -> peerOptions()))
                .build())
            .build();
        // A link is a PAIR, so the list names both halves: a peer that secondaries four zones is four different rows.
        // Every fact gets its own column: a serial beside the time it was served read as one sentence ("2026083001 3
        // hours ago"), in which the serial looked like part of the wording. A serial is never a duration.
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(DnsZonePeerModel.ZONE_ID)
                .relation(RelationPick.of(DnsZonePeerModel.ZONE_ID, DnsZoneModel.MODEL_ID).build())
                .build())
            .column(ColumnSpec.virtual(PEER_NAME, HohenheimFormCopy.label("peer_name")).build())
            .column(ColumnSpec.virtual(TRANSFER_HOST, secondariesText(TRANSFER_HOST)).build())
            .column(ColumnSpec.virtual(FRESHNESS, secondariesText(FRESHNESS))
                .renderer(HohenheimTemplateIds.CELL_STATE_LINE).build())
            .column(ColumnSpec.fromField(DnsZonePeerModel.SERVED_SERIAL).label(secondariesText("served_serial")).build())
            .column(ColumnSpec.fromField(DnsZonePeerModel.PROBED_AT).label(secondariesText("probed_at")).build())
            .column(ColumnSpec.fromField(DnsZonePeerModel.LAST_AXFR_SERIAL)
                .label(secondariesText("last_axfr_serial")).build())
            .column(ColumnSpec.fromField(DnsZonePeerModel.LAST_AXFR_AT).label(secondariesText("last_axfr")).build())
            .column(ColumnSpec.fromField(DnsZonePeerModel.LAST_NOTIFY_OUTCOME)
                .label(secondariesText("last_notify_result")).build())
            .column(ColumnSpec.fromField(DnsZonePeerModel.LAST_NOTIFY_AT).label(secondariesText("last_notify")).build())
            .build();
        return PanelResource.builder(HohenheimIds.id("dns_zone_peer"), SLUG,
                SubjectType.record(DnsZonePeerModel.MODEL_ID))
            .label(Microcopy.of("plural").withFilter("scope", "dns_zone_peer"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "dns_zone_peer"))
            .icon(Icon.of("handshake"))
            .navGroup(HohenheimPanel.NETWORK_GROUP)
            .navOrder(45)
            .showInNav(false)
            .standsUnder(HohenheimSlugs.DNS_ZONES)
            .parent(ResourceParent.of(HohenheimSlugs.DNS_ZONES, DnsZonePeerModel.ZONE_ID).tab(TAB))
            // A link row carries no name of its own, so it borrows the peer's: the zone is already the breadcrumb it
            // hangs under, which leaves the peer as the only thing telling one link from the next.
            .reads(ResourceReads.rows().title(DnsZonePeerParts::peerName))
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL)
                .computed(Objects.requireNonNull(table.column(PEER_NAME)), (row, request) -> peerName(row))
                .computed(Objects.requireNonNull(table.column(TRANSFER_HOST)), (row, request) -> {
                    Row peer = peerOf(row);
                    return peer != null ? peer.get(DnsPeerModel.TRANSFER_HOST) : null;
                })
                .computed(Objects.requireNonNull(table.column(FRESHNESS)), (row, request) -> freshnessOf(row).cell(row))
                .emptyDescription(secondariesText("empty_hint"))
                .build())
            .form(ResourceForm.<Row>of(form)
                .quickCreate(QUICK_CREATE)
                .quickCreatePresets(DnsZonePeerParts::quickCreatePresets)
                .build())
            .writes(ResourceMutations.rows().create().update().delete()
                .beforeSave(save -> {
                    if (save.isCreate()) {
                        validate(save.values());
                    }
                })
                .afterSave(save -> {
                    if (save.isCreate()) {
                        notifyLinked(Objects.requireNonNull(save.key()));
                    }
                })
                .build())
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /** @return the linked peer's name, or null when the peer is gone */
    static @Nullable String peerName(@NonNull Row link) {
        Row peer = peerOf(link);
        return peer != null ? peer.get(DnsPeerModel.NAME) : null;
    }

    private static @Nullable Row peerOf(@NonNull Row link) {
        Integer peerId = link.get(DnsZonePeerModel.PEER_ID);
        return peerId != null ? Models.get(DnsPeerModel.class).findById(peerId) : null;
    }

    private static @NonNull Microcopy secondariesText(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "dns_secondaries");
    }

    /** A link's freshness as probed from this primary. */
    enum Freshness {
        UNPROBED("unprobed", BadgeVariant.SECONDARY),
        CURRENT("current", BadgeVariant.SUCCESS),
        BEHIND("behind", BadgeVariant.WARNING),
        STALE("stale", BadgeVariant.DESTRUCTIVE);

        private final String token;
        private final BadgeVariant variant;

        Freshness(String token, BadgeVariant variant) {
            this.token = token;
            this.variant = variant;
        }

        /** @return the freshness badge, the last probe's failure verbatim under it */
        @NonNull StateLineCell cell(@NonNull Row link) {
            return new StateLineCell(this.token, this.variant,
                Microcopy.of(this.token).withFilter("scope", "dns_freshness"), null,
                link.get(DnsZonePeerModel.PROBE_ERROR));
        }
    }

    static @NonNull Freshness freshnessOf(@NonNull Row link) {
        if (link.get(DnsZonePeerModel.PROBED_AT) == null) {
            return Freshness.UNPROBED;
        }
        if (link.get(DnsZonePeerModel.BEHIND_SINCE) == null) {
            return Freshness.CURRENT;
        }
        return DnsSecondaryFreshness.isStale(link) ? Freshness.STALE : Freshness.BEHIND;
    }

    /** The zone the bar links into: the {@code ?zone_id=} prefill, else the tab's own record. */
    private static @NonNull Map<String, Object> quickCreatePresets(@NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        if (conduit == null) {
            return Map.of();
        }
        Integer zoneId = CmsSupport.scopedParentId(conduit, DnsZonePeerModel.ZONE_ID.getName(),
            HohenheimSlugs.DNS_ZONES);
        return zoneId != null ? Map.of(DnsZonePeerModel.ZONE_ID.getName(), zoneId) : Map.of();
    }

    /**
     * Prompt the freshly linked secondary to pull immediately, announcing the serial the serving view already
     * publishes (no bump happened here).
     */
    private static void notifyLinked(@NonNull Object key) {
        Row link = Models.get(DnsZonePeerModel.class).findById(Integer.parseInt(String.valueOf(key)));
        Integer zoneId = link != null ? link.get(DnsZonePeerModel.ZONE_ID) : null;
        if (zoneId != null) {
            DnsNotifier.INSTANCE.notifyZonePeers(zoneId, DnsZoneStore.INSTANCE.publishedSerial(zoneId));
        }
    }

    /** @throws Violations for a missing zone or peer, or a peer already linked to the zone */
    private static void validate(@NonNull Map<String, Object> coerced) {
        Object zoneValue = coerced.get(DnsZonePeerModel.ZONE_ID.getName());
        Object peerValue = coerced.get(DnsZonePeerModel.PEER_ID.getName());
        if (!(zoneValue instanceof Integer zoneId) || Models.get(DnsZoneModel.class).findById(zoneId) == null) {
            throw Violations.ofField(DnsZonePeerModel.ZONE_ID.getName(), zoneValue,
                CmsSupport.violationText("dns_zone_missing"));
        }
        if (!(peerValue instanceof Integer peerId) || Models.get(DnsPeerModel.class).findById(peerId) == null) {
            throw Violations.ofField(DnsZonePeerModel.PEER_ID.getName(), peerValue,
                CmsSupport.violationText("dns_peer_missing"));
        }
        Row existing = Models.get(DnsZonePeerModel.class).find()
            .where(DnsZonePeerModel.ZONE_ID.eq(zoneId))
            .and(DnsZonePeerModel.PEER_ID.eq(peerId))
            .first();
        if (existing != null) {
            throw Violations.ofField(DnsZonePeerModel.PEER_ID.getName(), peerId,
                CmsSupport.violationText("dns_secondary_linked"));
        }
    }

    private static @NonNull List<FieldOption<Integer>> peerOptions() {
        List<FieldOption<Integer>> options = new ArrayList<>();
        for (Row peer : Models.get(DnsPeerModel.class).findEnabled()) {
            options.add(FieldOption.of(peer.get(DnsPeerModel.ID), String.valueOf(peer.get(DnsPeerModel.NAME))));
        }
        return options;
    }
}
