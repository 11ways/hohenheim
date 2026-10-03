package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.SurfaceBaselines;
import be.elevenways.zenit.cms.test.support.SurfaceCase;
import be.elevenways.zenit.cms.test.support.SurfaceFact;
import be.elevenways.zenit.cms.test.support.PlacedOperationMoves;
import be.elevenways.zenit.cms.test.support.FilterLeafMoves;
import be.elevenways.zenit.cms.common.render.table.SynthesizedRowActions;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.hohenheim.server.cms.DnsOperations;
import be.elevenways.hohenheim.server.cms.DnsRecordParts;
import be.elevenways.hohenheim.server.cms.DnsPeerParts;
import be.elevenways.hohenheim.server.cms.ManageDnsRecordParts;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.common.orm.query.rules.SchemaVocabulary;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import org.junit.jupiter.api.Test;

import java.util.Map;

/** The record and peer surfaces captured before the DNS parts conversion. */
class DnsPartsSurfacesBrowserTest extends HohenheimTestBase {
    @Test
    void recordsAndPeersKeepTheirBeforeSurfaces() {
        int peer = DnsFixtures.transferPeer("dns-parts-peer", "192.0.2.53", 53);
        int primary = DnsFixtures.createZone("dns-parts-primary.test", DnsZoneModel.ROLE_PRIMARY, null);
        DnsFixtures.createZone("dns-parts-replica.test", DnsZoneModel.ROLE_SECONDARY, peer);
        int address = DnsFixtures.record(primary, "address", DnsRecordModel.TYPE_A, "192.0.2.40");
        int text = DnsFixtures.record(primary, "text", DnsRecordModel.TYPE_TXT, "value");
        int remote = DnsFixtures.apiPeer("dns-parts-remote", "https://peer.invalid");
        DynamicDnsService.mintFor(address);
        DnsZoneStore.INSTANCE.reload();
        var admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        AccessContext operator = AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(admin.get(UserModel.ID), "Test Admin")));
        SurfaceBaselines stored = SurfaceBaselines.load(DnsPartsSurfacesBrowserTest.class,
            "/panel-surfaces/dns-parts.txt")
            .placedOperations(PlacedOperationMoves.of(DnsOperations.MINT_DYNAMIC_TOKEN.id(),
                DnsOperations.REVOKE_DYNAMIC_TOKEN.id(), DnsOperations.NEGOTIATE_KEY.id())
                .synthesized("dns-records", SynthesizedRowActions.DELETE, DnsOperations.DELETE_RECORD.id())
                .synthesized("dns-peers", SynthesizedRowActions.DELETE, DnsOperations.DELETE_PEER.id()));
        for (String record : new String[] {"address", "text"}) {
            stored.addedTab("admin.dns-records.operator." + record,
                SurfaceFact.of(SurfaceFact.Kind.TAB, "access", 1, false, false));
            stored.addedTab("manage.dns-records.operator." + record,
                SurfaceFact.of(SurfaceFact.Kind.TAB, "access", 0, false, false));
        }
        for (Map.Entry<String, Integer> record : Map.of("address", address, "text", text).entrySet()) {
            for (String panel : new String[] {HohenheimSlugs.ADMIN, HohenheimSlugs.MANAGE}) {
                stored.check(project(PanelSurfaces.capture(SurfaceCase.of(panel, "dns-records", "operator", operator)
                    .onRecord(record.getValue().toString(), record.getKey())),
                    panel.equals(HohenheimSlugs.ADMIN) ? DnsRecordParts.admin() : ManageDnsRecordParts.manage()));
            }
        }
        for (Map.Entry<String, Integer> record : Map.of("in-use", peer, "remote", remote).entrySet()) {
            stored.check(project(PanelSurfaces.capture(SurfaceCase.of(HohenheimSlugs.ADMIN, "dns-peers", "operator", operator)
                .onRecord(record.getValue().toString(), record.getKey())), DnsPeerParts.admin()));
        }
        stored.finish();
    }

    private static PanelSurfaces project(PanelSurfaces capture, PanelResource<Row> parts) {
        var vocabulary = SchemaVocabulary.of(parts.model());
        Map<String, FilterSpec.Kind> legacy = Map.of("name", FilterSpec.Kind.TEXT,
            "type", FilterSpec.Kind.SELECT, "enabled", FilterSpec.Kind.BOOLEAN);
        FilterLeafMoves moves = null;
        for (FilterSpec filter : parts.list().table().filters()) {
            var move = FilterLeafMoves.of(legacy.get(filter.name()), filter, vocabulary, null);
            moves = moves == null ? move : moves.and(move);
        }
        return moves == null ? capture : moves.legacyProjection(capture);
    }
}
