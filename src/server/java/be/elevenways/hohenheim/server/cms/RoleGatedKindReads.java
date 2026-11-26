package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.BuildHookModel;
import be.elevenways.hohenheim.model.DatabaseDumpModel;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.GameDomainModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RowScope;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.ModelRef;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Who reads the codes of the coded kinds whose only surfaces a host role gates, declared whatever roles the host runs.
 *
 * AIDEV-NOTE: these models' reads used to come only from the record source zenit-cms derives from their panel
 * entries, and HohenheimPanel.addIf leaves an entry out with its role, so a host running without that role stored
 * the rows and their codes while nothing said who reads them: RecordCodes.verifyReads refused the boot (stacks on
 * Robbedoes; ten kinds on Kuifje). A kind read here (RecordCodes.readThrough) is judged the same on every host: an
 * operator kind by the admin panel's admission its derived source carries, a kind with a /manage twin by that twin's
 * TenantScopes scope (which answers ALL for an operator). Every other coded kind is read through a source registered
 * whatever the roles (HohenheimSources, AdminSources, ManagePanel.declareSources, DeployReads) or an entry no role
 * gates. ManageSourceRegistrationTest replays the boot for every live role shape and refuses a kind left out here.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class RoleGatedKindReads {

    /** The registry refuses a second read of a kind: one process declares once. */
    private static final AtomicBoolean DECLARED = new AtomicBoolean();

    private RoleGatedKindReads() {
    }

    /** Declares every read once per process; installed by HohenheimHostWiring before the boot's checks run. */
    public static void declare() {
        if (!DECLARED.compareAndSet(false, true)) {
            return;
        }
        operatorsRead(BackupTargetModel.REF);
        operatorsRead(DatabaseDumpModel.REF);
        operatorsRead(DatabaseEngineModel.REF);
        operatorsRead(DnsPeerModel.REF);
        operatorsRead(GameDomainModel.REF);
        operatorsRead(ReconcileFindingModel.REF);
        operatorsRead(ReleasedRouteClaimModel.REF);
        operatorsRead(SiteAuthProviderModel.REF);
        operatorsRead(StackModel.REF);
        operatorsRead(StackServiceModel.REF);
        // A zone is read where its records are: a tenant reaches the zones holding his names (TenantScopes.DNS_ZONES).
        scopeReads(DnsZoneModel.REF, TenantScopes.DNS_ZONES);
        scopeReads(BuildHookModel.REF, TenantScopes.BUILD_HOOKS);
        scopeReads(InstanceBackupModel.REF, TenantScopes.INSTANCE_BACKUPS);
        scopeReads(InstanceSnapshotModel.REF, TenantScopes.INSTANCE_SNAPSHOTS);
    }

    /** A kind only the admin panel lists: read by whoever the admin panel admits. */
    private static <M extends Model> void operatorsRead(@NonNull ModelRef<M> model) {
        readThrough(model, RecordSource.of(model).projectPrimaryKey().permission(HohenheimPanel.ACCESS).build());
    }

    /** A kind with a /manage twin: read within the twin's scope. */
    private static <M extends Model> void scopeReads(@NonNull ModelRef<M> model, @NonNull RowScope scope) {
        readThrough(model, RecordSource.of(model).projectPrimaryKey().scopedBy(scope).build());
    }

    private static void readThrough(@NonNull ModelRef<?> model, @NonNull RecordSource<?> source) {
        RecordCodes.readThrough(model.id(), RoleGatedKindReads.class, (keys, access) -> {
            Set<String> readable = new LinkedHashSet<>();
            for (Object key : source.readableAmong(access, keys)) {
                readable.add(String.valueOf(key));
            }
            return readable;
        });
    }
}
