package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.server.operation.Authorizer;
import be.elevenways.zenit.server.operation.OperationHandlers;

/** Operation handlers retain DNS model funnels and gate their side effects before execution. */
final class DnsOperationHandlers {
    static {
        OperationHandlers.attach(DnsOperations.DELETE_ZONE).authorize(admin())
            .handle(call -> { new DnsZoneParts().deleteRow(call.subject(), call.subjectAccess()); return 1; });
        OperationHandlers.attach(DnsOperations.DELETE_RECORD)
            .authorize((row, input, access) -> TenantWrites.mayAuthorRecord(access, row) ? null : concealed())
            .handle(call -> { new DnsRecordParts().deleteRow(call.subject(), call.subjectAccess()); return 1; });
        OperationHandlers.attach(DnsOperations.DELETE_PEER).authorize(admin())
            .availability((row, access) -> new DnsPeerParts().deleteUnavailableReason(row, access))
            .handle(call -> { Models.get(DnsPeerModel.class).delete(call.subject()); return 1; });
        OperationHandlers.attach(DnsOperations.CHECK_HEALTH).authorize(admin())
            .applies(row -> !DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(row)))
            .handle(call -> DnsZoneParts.checkHealth(call.subject()));
        OperationHandlers.attach(DnsOperations.MINT_DYNAMIC_TOKEN).authorize(dynamic())
            .applies(DnsOperationHandlers::address)
            .handle(call -> DnsRecordParts.mintDynamicToken(call.subject()));
        OperationHandlers.attach(DnsOperations.REVOKE_DYNAMIC_TOKEN).authorize(dynamic())
            .applies(row -> address(row) && DynamicDnsService.credentialFor(row.get(DnsRecordModel.ID)) != null)
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
    private static <I> Authorizer<Row, I> admin() {
        return (row, input, access) -> HohenheimAccess.isAdmin(access) ? null : concealed();
    }
    private static <I> Authorizer<Row, I> dynamic() {
        return (row, input, access) -> HohenheimAccess.reachesRecord(access, DnsRecordModel.MODEL_ID,
            row.get(DnsRecordModel.ID), HohenheimAccess.DYNDNS) ? null : concealed();
    }
    private static DomainRefusal concealed() {
        return new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "the DNS subject is not reachable");
    }
}
