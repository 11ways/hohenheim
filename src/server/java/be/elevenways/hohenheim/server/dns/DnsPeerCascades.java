package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.orm.PendingDeletes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A DNS peer's delete invariants, on every delete lane: a peer that a SECONDARY zone
 * replicates from refuses to go, a stale primary-peer pointer on a PRIMARY zone is
 * cleared, and the zone-peer links (NOTIFY targets, AXFR authorizations) die with it.
 *
 * AIDEV-NOTE: REFUSE for the secondaries, per the repo's split. A secondary zone whose
 * peer is gone is not broken at once -- {@code SecondaryZoneService.transfer} marks
 * {@code error} ("no primary peer configured") every five minutes and keeps serving the
 * persisted replica until the SOA expire window closes, then stops answering for the
 * name. That is a refusal at use, days after the decision, which is the BackupTargetModel
 * lesson; the operator must repoint the zone or delete it first. A primary-role zone never
 * reads the column, so a pointer left there by a role switch is cleared, never refused.
 * The link rows are meaningless without their peer (every reader already skips a link
 * whose peer is gone), so they cascade rather than linger in a table no surface reaches.
 */
public final class DnsPeerCascades {

    private static volatile boolean installed;

    private DnsPeerCascades() {
    }

    /** Install the peer hooks; idempotent, called at the MODULES boot stage. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        DnsPeerModel.SCHEMA.addBeforeRemoveHook(context -> {
            refuseWhileReplicatedFrom(context);
            Models.get(DnsZoneModel.class).find()
                .where(PendingDeletes.dependents(DnsZoneModel.PRIMARY_PEER, context))
                .assign(DnsZoneModel.PRIMARY_PEER_ID, null)
                .updateAll();
            PendingDeletes.deleteDependents(Models.get(DnsZonePeerModel.class),
                DnsZonePeerModel.PEER, context);
        });
    }

    /** @throws Violations {@code dns_peer_in_use} naming the peer and the secondary zones */
    private static void refuseWhileReplicatedFrom(@NonNull RemoveFromDatasource context) {
        QueryBuilder<Row> secondaries = Models.get(DnsZoneModel.class).find()
            .where(DnsZoneModel.ROLE.eq(DnsZoneModel.ROLE_SECONDARY))
            .where(PendingDeletes.dependents(DnsZoneModel.PRIMARY_PEER, context));
        long count = secondaries.count();
        if (count == 0) {
            return;
        }
        Row first = secondaries.first();
        Row peer = first.get(DnsZoneModel.PRIMARY_PEER);
        throw Violations.ofForm(CmsSupport.violationText("dns_peer_in_use")
            .withArg("name", peer != null ? String.valueOf((Object) peer.get(DnsPeerModel.NAME)) : "")
            .withArg("zone", String.valueOf((Object) first.get(DnsZoneModel.ORIGIN)))
            .withArg("count", count));
    }
}
