package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.DnsDyndnsCredentialModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.dns.DnsZoneFiles;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.flash.FlashLevel;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.Applicability;
import be.elevenways.zenit.server.operation.Authorizer;
import be.elevenways.zenit.server.operation.Availability;
import be.elevenways.zenit.server.operation.OperationHandlers;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.NonNull;

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
            .source(RecordSource.of(DnsZoneModel.REF)
                .project(DnsZoneModel.ID, DnsZoneModel.ORIGIN, DnsZoneModel.ROLE, DnsZoneModel.PRIMARY_PEER_ID)
                .permission(HohenheimPanel.ACCESS).build())
            .applies(row -> DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(row)))
            .handle(call -> DnsZoneRecordsPage.forward(call.subject(), call.input()));
        OperationHandlers.attach(DnsOperations.IMPORT_ZONE).authorize(admin())
            .handle(call -> importZone(call.subject(), Objects.requireNonNull(call.input()),
                Objects.requireNonNull(call.access(), "an import has its caller")));
    }

    private DnsOperationHandlers() {}
    static void init() {}

    /**
     * Replaces the zone's records with the zone-file text's, as the zone-file tab's import does, and records it in
     * the zone's history.
     *
     * @return the toast saying how many records came in, a warning naming the lines skipped when there are any
     * @throws Violations for blank text, a secondary zone or text that does not import
     */
    private static @NonNull CmsActionResult importZone(@NonNull Row zone, OperationInput.@NonNull Values input,
                                                       @NonNull AccessContext access) {
        int zoneId = zone.get(DnsZoneModel.ID);
        DnsRecordParts.requireImportable(ApiConduits.adminPanel(), zoneId, access);
        String text = (String) input.get(DnsOperations.ZONE_TEXT);
        if (text == null || text.isBlank()) {
            throw Violations.ofField(DnsOperations.ZONE_TEXT.getName(), text,
                HohenheimMicrocopy.DNS_ZONE.of("import_empty"));
        }
        DnsZoneFiles.ImportResult result;
        try {
            result = DnsZoneFiles.importText(zone, text, Boolean.TRUE.equals(input.get(DnsOperations.KEEP_NS))
                ? DnsZoneFiles.ApexNsPolicy.KEEP_FILE : DnsZoneFiles.ApexNsPolicy.REPLACE_WITH_DECLARED);
        } catch (Violations refused) {
            throw refused;
        } catch (IOException | RuntimeException failed) {
            throw Violations.ofField(DnsOperations.ZONE_TEXT.getName(), null, HohenheimMicrocopy.DNS_ZONE
                .of("import_failed").withArg("reason", String.valueOf(failed.getMessage())));
        }
        History.event(Models.get(DnsZoneModel.class), zoneId, Change.fact(HohenheimActivityAction.IMPORTED));
        String notes = String.join("; ", result.notes());
        if (!result.skipped().isEmpty()) {
            return new CmsActionResult.Toast(HohenheimMicrocopy.DNS_ZONE.of("import_partial")
                .withArg("count", result.imported()).withArg("skipped", String.join("; ", result.skipped()))
                .withArg("notes", notes), FlashLevel.WARNING);
        }
        return CmsActionResult.successToast(notes.isEmpty()
            ? HohenheimMicrocopy.DNS_ZONE.of("import_done").withArg("count", result.imported())
            : HohenheimMicrocopy.DNS_ZONE.of("import_done_notes").withArg("count", result.imported())
                .withArg("notes", notes));
    }
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
        return (row, input, access) -> keysDynamic(access, row.get(DnsRecordModel.ID)) ? null : concealed();
    }

    /** @return whether the caller may make, re-key or drop the record's dynamic DNS token, on every lane */
    static boolean keysDynamic(AccessContext access, Integer recordId) {
        return HohenheimAccess.reachesRecord(access, DnsRecordModel.MODEL_ID, recordId, HohenheimCapabilities.DYNDNS);
    }
    private static DomainRefusal concealed() {
        return new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "the DNS subject is not reachable");
    }
}
