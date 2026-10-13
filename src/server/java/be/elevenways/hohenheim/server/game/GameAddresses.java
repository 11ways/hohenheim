package be.elevenways.hohenheim.server.game;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * THE write funnel of a game server's own game address ({@code instances.game_address}): the name players type,
 * published as an A/AAAA pair at the host's declared addresses plus an SRV record on the game port, in the managed
 * zone that covers it. The records are generated through {@link GameDomains#reconcileRecords}, attributed to the
 * instance, so they live and die with the address and the instance.
 *
 * AIDEV-NOTE: an operator act, asked by the caller (the put-online run admits it to an operator only; the
 * Configuration offers the entry on the operator twin alone): a name in a managed zone is never a tenant's to aim. The
 * SRV service label is the template's fact ({@code srv_service} in the kind settings); the SRV rides the first port
 * whose purpose carries one ({@code PortPurpose.carriesSrv}). A restored copy (restore-to-new) does NOT carry the
 * address: the same name on a second host would take the players away from the original; the operator re-points it.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class GameAddresses {

    private GameAddresses() {
    }

    /**
     * Give the instance this game address, or none, and reconcile its records.
     *
     * @throws Violations on {@code game_address} when no enabled zone this installation is primary for covers the name
     */
    public static void set(int instanceId, @Nullable String address) {
        InstanceModel instances = Models.get(InstanceModel.class);
        Row instance = Objects.requireNonNull(instances.findById(instanceId), "the instance");
        instance.set(InstanceModel.GAME_ADDRESS, address);
        prepare(instance);
        instances.save(instance);
        reconcile(instanceId);
    }

    /**
     * Normalize the game address an instance row is about to be saved with; the caller saves it and then
     * {@link #reconcile}s (the Configuration's save hooks do both around the framework's write).
     *
     * @throws Violations on {@code game_address} when no enabled zone this installation is primary for covers the name
     */
    public static void prepare(@NonNull Row instance) {
        String address = instance.get(InstanceModel.GAME_ADDRESS);
        Violations uncovered = uncoveredRefusal(address);
        if (uncovered != null) {
            throw uncovered;
        }
        instance.set(InstanceModel.GAME_ADDRESS, normalized(address));
    }

    /**
     * The refusal {@link #set} meets for a name no enabled primary zone covers, asked before anything is made.
     *
     * @return the refusal on {@code game_address}, or null when a zone covers the name or none is given
     */
    public static @Nullable Violations uncoveredRefusal(@Nullable String address) {
        String hostname = normalized(address);
        return hostname == null || GameDomains.zoneFor(hostname) != null ? null
            : Violations.ofField(InstanceModel.GAME_ADDRESS.getName(), hostname,
                HohenheimMicrocopy.VIOLATIONS.of("game_address_no_zone").withArg("address", hostname));
    }

    /** Reconcile the instance's game records against its address, its game port's claim and its host's addresses. */
    public static void reconcile(int instanceId) {
        Row instance = StoredRows.byId(Models.get(InstanceModel.class), instanceId);
        String hostname = instance == null ? null : normalized(instance.get(InstanceModel.GAME_ADDRESS));
        if (hostname == null) {
            GameDomains.removeRecordsFor(InstanceModel.MODEL_ID, instanceId);
            return;
        }
        Map<String, Object> settings = instance.get(InstanceModel.SETTINGS) instanceof Map<?, ?> map
            ? castSettings(map) : Map.of();
        PublishedPort game = null;
        for (PublishedPort port : PublishedPort.of(settings)) {
            if (game == null && port.purpose().carriesSrv()) {
                game = port;
            }
        }
        String service = Texts.trimmedOrNull(settings.get(PublishedPort.SRV_SERVICE) instanceof String text
            ? text : null);
        String srvPrefix = game == null || service == null ? null : "_" + service + "._" + game.protocol();
        GameDomains.reconcileRecords(InstanceModel.MODEL_ID, instanceId, hostname, srvPrefix,
            game == null ? firstPublicPort(instanceId, null) : publicPort(instanceId, game),
            GameDomains.proxyServer(instanceId));
    }

    /** Take the instance's game records down; called when it is destroyed. */
    public static void removeFor(int instanceId) {
        GameDomains.removeRecordsFor(InstanceModel.MODEL_ID, instanceId);
    }

    /** Re-reconcile every game address on this host: its A/AAAA values are the host's declared addresses. */
    public static void afterServerAddressChange(int serverId) {
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.GAME_ADDRESS.isNotNull()).all()) {
            Integer id = instance.get(InstanceModel.ID);
            if (id != null && Objects.equals(instance.get(InstanceModel.SERVER_ID), serverId)) {
                reconcile(id);
            }
        }
    }

    /** The held public claim carrying this port's host port, or the first held public claim of its protocol. */
    private static @Nullable Integer publicPort(int instanceId, @NonNull PublishedPort game) {
        if (game.hostPort() != null) {
            for (Row claim : PortLedger.claimsOf(InstanceModel.MODEL_ID, instanceId)) {
                if (isHeldPublic(claim) && game.hostPort().equals(claim.get(PortAllocationModel.PORT))
                        && game.protocol().equals(claim.get(PortAllocationModel.PROTOCOL))) {
                    return game.hostPort();
                }
            }
            return null;
        }
        return firstPublicPort(instanceId, game.protocol());
    }

    private static @Nullable Integer firstPublicPort(int instanceId, @Nullable String protocol) {
        for (Row claim : PortLedger.claimsOf(InstanceModel.MODEL_ID, instanceId)) {
            if (isHeldPublic(claim) && (protocol == null || protocol.equals(claim.get(PortAllocationModel.PROTOCOL)))) {
                return claim.get(PortAllocationModel.PORT);
            }
        }
        return null;
    }

    private static boolean isHeldPublic(@NonNull Row claim) {
        return !PortLedger.isReleasing(claim) && "".equals(claim.get(PortAllocationModel.HOST_IP));
    }

    private static @Nullable String normalized(@Nullable String address) {
        String trimmed = Texts.trimmedOrNull(address);
        if (trimmed == null) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> castSettings(@NonNull Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
