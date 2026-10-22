package be.elevenways.hohenheim.server.ports;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.app.PutOnlineReach;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.server.instance.PortPublications;
import be.elevenways.hohenheim.server.util.PortProbe;
import be.elevenways.hohenheim.upstream.UpstreamKindInfo;
import be.elevenways.hohenheim.upstream.UpstreamKinds;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.SaveToDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The public ports a site listens on itself (a TCP/UDP stream's), claimed in the port ledger on the proxy's host in
 * the save that sets them, so a port another owner holds is refused before the stream exists.
 *
 * AIDEV-NOTE: the claims follow the site's LIVE state, never its record alone: a site in the trash holds none (its
 * port is closed by the reload its trashing triggers, so a delete frees the port at once, as npmplus's does), and
 * putting it back claims them again, refused when another owner took the port meanwhile. A switched-off stream keeps
 * its claims, like a stopped container keeps its number. The claim is pre-allocated (it is the operator's chosen
 * number) and the whole-host bind, on the controller's own host row: the proxy runs in this process. A port this
 * site does not already hold is also probed at the kernel, which sees listeners no ledger row describes (sshd, the
 * proxy's own HTTP and HTTPS ports).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class SitePortClaims {

    /** The field a stream's public port is given in, where a refusal of it is shown. */
    private static final String PORT_FIELD = "settings.listen_port";

    private static boolean installed;

    private SitePortClaims() {
    }

    /** Registers the claim and the credential refusal on every site write; idempotent. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        SiteModel.SCHEMA.addBeforeValidateHook(SitePortClaims::refuseCredentials);
        SiteModel.SCHEMA.addAfterSaveHook(SitePortClaims::claim);
        // A hard delete (a purge from the trash) parks whatever a site still held for the reconciler.
        PortLedger.parkClaimsOnRemove(SiteModel.SCHEMA);
    }

    /**
     * A stream is relayed unread, so nothing can ask its clients for a password or a login: an auth provider, or an
     * access list with a basic-auth or provider rule, is refused on its field. Address rules apply.
     *
     * @throws Violations {@code stream_no_http_auth} or {@code stream_access_list_credentials}
     */
    private static void refuseCredentials(@NonNull SaveToDatasource context) {
        Row row = context.getRow();
        // Judged only when the write moves a column the rule relates (Row.isWritten, never Row.has: a narrow save
        // hands its hooks the whole stored record), so a later access-rule change never locks unrelated site edits.
        if (row == null || !(row.isWritten(SiteModel.UPSTREAM_KIND) || row.isWritten(SiteModel.AUTH_PROVIDER_ID)
                || row.isWritten(SiteModel.ACCESS_LIST_ID))) {
            return;
        }
        Row stored = StoredRows.of(Models.get(SiteModel.class), row);
        UpstreamKindInfo kind = kindOf(row.afterWrite(SiteModel.UPSTREAM_KIND, stored));
        if (kind == null) {
            return;
        }
        Violations refused = switch (kind.putOnlineReach()) {
            case WEBSITE, NONE -> null;
            case PORTS -> credentialRefusal(row.afterWrite(SiteModel.AUTH_PROVIDER_ID, stored),
                row.afterWrite(SiteModel.ACCESS_LIST_ID, stored));
        };
        if (refused != null) {
            throw refused;
        }
    }

    /**
     * THE rule a site reached on its own port meets: no login gate, and an access list of address rules only. The
     * put-online request asks it too, before anything exists.
     *
     * @return the refusal on the gate's field, null when it may carry these gates
     */
    public static @Nullable Violations credentialRefusal(@Nullable Object authProvider, @Nullable Object accessList) {
        if (authProvider != null) {
            return Violations.ofField(SiteModel.AUTH_PROVIDER_ID.getName(), authProvider,
                HohenheimMicrocopy.VIOLATIONS.of("stream_no_http_auth"));
        }
        Integer listId = RawValues.parsedInt(accessList);
        if (listId == null) {
            return null;
        }
        for (Row rule : Models.get(AccessRuleModel.class).find()
                .where(AccessRuleModel.ACCESS_LIST_ID.eq(listId)).all()) {
            String type = rule.get(AccessRuleModel.TYPE);
            boolean credential = AccessRuleModel.TYPE_BASIC_AUTH.equals(type)
                || AccessRuleModel.TYPE_AUTH_PROVIDER.equals(type);
            if (credential && !Boolean.FALSE.equals(rule.get(AccessRuleModel.ENABLED))) {
                return Violations.ofField(SiteModel.ACCESS_LIST_ID.getName(), listId,
                    HohenheimMicrocopy.VIOLATIONS.of("stream_access_list_credentials"));
            }
        }
        return null;
    }

    /**
     * Holds exactly the claims the saved site's live state asks for: each public port its kind names while it is out
     * of the trash, none otherwise.
     *
     * @throws Violations naming the port another owner holds, or one a program on the host is bound to
     */
    private static void claim(@NonNull SaveToDatasource context) {
        Row row = context.getRow();
        Integer siteId = row == null ? null : row.get(SiteModel.ID);
        if (siteId == null) {
            return;
        }
        Row site = StoredRows.byId(Models.get(SiteModel.class), siteId);
        List<Row> held = PortLedger.claimsOf(SiteModel.MODEL_ID, siteId);
        UpstreamKindInfo kind = site == null ? null : kindOf(site.get(SiteModel.UPSTREAM_KIND));
        if (held.isEmpty() && (kind == null || kind.putOnlineReach() != PutOnlineReach.PORTS)) {
            return;
        }
        boolean live = site != null && site.get(SiteModel.DELETED_AT) == null;
        Map<String, Object> settings = site == null ? null : RawValues.mapOrNull(site.get(SiteModel.SETTINGS));
        List<PublishedPort> wanted = live && kind != null && settings != null ? kind.proxyPorts(settings) : List.of();
        int serverId = ServerModel.localServerId();
        Set<String> keep = new HashSet<>();
        for (PublishedPort port : wanted) {
            int number = port.hostPort() == null ? 0 : port.hostPort();
            String key = PortLedger.claimKeyOf(serverId, "", number, port.protocol());
            keep.add(key);
            Row rival = PortLedger.rivalHolder(serverId, "", number, port.protocol(), SiteModel.MODEL_ID, siteId);
            if (rival != null) {
                throw PortPublications.conflictRefusal(PORT_FIELD, number, rival, PortLedger.describeHolder(rival));
            }
            Row own = PortLedger.holderOf(key);
            if (own == null && !PortProbe.isFree("", number, port.protocol())) {
                throw Violations.ofField(PORT_FIELD, number,
                    HohenheimMicrocopy.VIOLATIONS.of("port_bound_on_host").withArg("port", number));
            }
            try {
                PortLedger.claimPreallocated(serverId, "", number, port.protocol(), SiteModel.MODEL_ID, siteId,
                    null);
            } catch (PortLedger.PortConflict conflict) {
                throw PortPublications.conflictRefusal(PORT_FIELD, number, conflict.getHolderClaim(),
                    conflict.getHolder());
            }
        }
        // A port the site no longer asks for (changed, or the site went to the trash) is free from this save on.
        for (Row claim : held) {
            String key = claim.get(PortAllocationModel.CLAIM_KEY);
            if (key != null && !keep.contains(key)) {
                PortLedger.releaseKey(key);
            }
        }
    }

    private static @Nullable UpstreamKindInfo kindOf(@Nullable Object kind) {
        Identifier id = kind == null ? null : Identifier.tryParse(String.valueOf(kind));
        return id == null ? null : UpstreamKinds.registry().get(id);
    }
}
