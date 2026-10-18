package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsDyndnsCredentialModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.server.operation.Applicability;
import be.elevenways.zenit.server.operation.Authorizer;
import be.elevenways.zenit.server.operation.Availability;
import be.elevenways.zenit.server.operation.OperationHandlers;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Operation handlers retain DNS model funnels and gate their side effects before execution. */
final class DnsOperationHandlers {
    static {
        OperationHandlers.attach(DnsOperations.DELETE_ZONE).authorize(admin())
            .handle(call -> { new DnsZoneParts().deleteRow(call.subject(), call.subjectAccess()); return 1; });
        OperationHandlers.attach(DnsOperations.DELETE_RECORD)
            .authorize((row, input, access) -> TenantWrites.mayAuthorRecord(access, row) ? null : concealed())
            .handle(call -> { new DnsRecordParts().deleteRow(call.subject(), call.subjectAccess()); return 1; });
        OperationHandlers.attach(DnsOperations.DELETE_PEER).authorize(admin())
            .availability(Availability.batch((peers, access) -> DeleteImpact.dnsPeersInUse(peers)))
            .handle(call -> { Models.get(DnsPeerModel.class).delete(call.subject()); return 1; });
        OperationHandlers.attach(DnsOperations.CHECK_HEALTH).authorize(admin())
            .applies(row -> !DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(row)))
            .handle(call -> DnsZoneParts.checkHealth(call.subject()));
        OperationHandlers.attach(DnsOperations.MINT_DYNAMIC_TOKEN).authorize(dynamic())
            .applies(DnsOperationHandlers::address)
            .handle(call -> DnsRecordParts.mintDynamicToken(call.subject()));
        OperationHandlers.attach(DnsOperations.REVOKE_DYNAMIC_TOKEN).authorize(dynamic())
            .applies(Applicability.batch((records, access, source) -> revocable(records)))
            .handle(call -> DnsRecordParts.revokeDynamicToken(call.subject()));
        OperationHandlers.attach(DnsOperations.NEGOTIATE_KEY).authorize(admin())
            .applies(DnsPeerModel::isHohenheim).handle(call -> DnsPeerParts.negotiate(call.subject()));
        OperationHandlers.attach(DnsOperations.REMOTE_EDIT).authorize(admin())
            .source(RecordSource.of(DnsZoneModel.class)
                .project(DnsZoneModel.ID, DnsZoneModel.ORIGIN, DnsZoneModel.ROLE, DnsZoneModel.PRIMARY_PEER_ID)
                .permission(HohenheimPanel.ACCESS).build())
            .applies(row -> DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(row)))
            .handle(call -> DnsZoneRecordsPage.forward(call.subject(), call.input()));
    }

    private DnsOperationHandlers() {}
    static void init() {}
    private static boolean address(Row row) { return DnsRecordModel.isAddressType(row.get(DnsRecordModel.TYPE)); }

    /** An address record holding a dyndns credential, the credentials of a page of records counted in one read. */
    private static List<Boolean> revocable(List<? extends Row> records) {
        Set<Integer> dynamic = Models.get(DnsDyndnsCredentialModel.class).find()
            .countBy(DnsDyndnsCredentialModel.RECORD_ID, records.stream().filter(DnsOperationHandlers::address)
                .map(row -> row.get(DnsRecordModel.ID)).filter(Objects::nonNull).toList()).keySet();
        return records.stream().map(row -> address(row) && dynamic.contains(row.get(DnsRecordModel.ID))).toList();
    }
    private static <I> Authorizer<Row, I> admin() {
        return (row, input, access) -> HohenheimAccess.isAdmin(access) ? null : concealed();
    }
    private static <I> Authorizer<Row, I> dynamic() {
        return (row, input, access) -> HohenheimAccess.reachesRecord(access, DnsRecordModel.MODEL_ID,
            row.get(DnsRecordModel.ID), HohenheimCapabilities.DYNDNS) ? null : concealed();
    }
    private static DomainRefusal concealed() {
        return new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "the DNS subject is not reachable");
    }
}
