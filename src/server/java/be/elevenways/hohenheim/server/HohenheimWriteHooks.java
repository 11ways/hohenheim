package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.server.cms.SiteDomainResource;
import be.elevenways.hohenheim.server.cms.SiteResource;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.cms.SiteTerminalCsp;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.docker.SiteInstances;
import be.elevenways.hohenheim.server.dns.DnsClaimReleases;
import be.elevenways.hohenheim.server.dns.GeneratedDnsRecords;
import be.elevenways.hohenheim.server.game.GameDomains;
import be.elevenways.hohenheim.server.instance.GeneratedInstanceFiles;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceDeviceQuota;
import be.elevenways.hohenheim.server.instance.InstanceImagePin;
import be.elevenways.hohenheim.server.instance.InstanceImagePolicy;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.instance.InstanceRootDiskQuota;
import be.elevenways.hohenheim.server.process.ReservedEnv;
import be.elevenways.hohenheim.server.project.ProjectGuards;
import be.elevenways.hohenheim.server.quota.DatabaseQuota;
import be.elevenways.hohenheim.server.quota.SiteQuota;
import be.elevenways.hohenheim.server.process.SiteApiKeys;
import be.elevenways.zenit.common.ZenitModule;

/**
 * The one owner of every model-funnel write hook: secret normalization (site
 * api keys, dyndns tokens), the reserved-env refusal and the site enable
 * invariant all install here, at the MODULES boot stage.
 *
 * AIDEV-NOTE: this is a discovered {@link ZenitModule} ON PURPOSE, so the
 * ordering is STRUCTURAL, not incidental: the boot-stage weights guarantee
 * MODULES (200) completes before STARTHTTP (50) binds the server, and
 * discovery means no reordering of ServerMain.main() (which used to install
 * the dyndns hook AFTER the server was bound, leaving a window where an admin
 * write persisted a plaintext token) can regress it. Tests exercise the same
 * path via ServerZenitRuntime.init(), so they can no longer hide an ordering
 * defect by hand-installing hooks before boot.
 *
 * @author Jelle De Loecker
 */
public final class HohenheimWriteHooks implements ZenitModule {

    @Override
    public void init() {
        // No plaintext site api key reaches the datasource, on ANY write path.
        SiteApiKeys.install();
        // No plaintext dyndns token reaches the datasource, on ANY write path.
        DynamicDnsService.installTokenHashing();
        // No reserved control variable (HOHENHEIM_*, PORT, the security-report
        // pair) can be persisted as an operator environment variable.
        ReservedEnv.install();
        // No disabled site can go live on a hostname an enabled site already
        // owns (form, toggle, delegated save, revision restore).
        SiteResource.installEnableInvariant();
        // No domain row can take a route an enabled site already owns, and every row
        // stamps the live-route claim its unique index arbitrates (form, clone, seeder,
        // API writeback, direct model save).
        SiteDomainResource.installRouteInvariant();
        // A DNS record a system authored carries derived attribution, and no caller can
        // hand-write that attribution onto a row of its own.
        GeneratedDnsRecords.install();
        // A released hostname's authoritative records stop being served and its dyndns
        // credential and record grants die with the claim (site soft delete, domain row
        // delete or rename) -- the DNS half of the release quarantine.
        DnsClaimReleases.install();
        // The same derived-attribution discipline for instance config files a system
        // authored (the game-domains Velocity forced-hosts materialization).
        GeneratedInstanceFiles.install();
        // A game-domains mapping dies with its domain row, and its generated output
        // (forced-hosts config, DNS rows) dies with it.
        GameDomains.install();
        // A delegated tenant may set only the delegated domain columns, and may author only
        // the allow-listed DNS record types -- on every writer, not just the /manage forms.
        TenantWrites.install();
        // The pl-terminal page gets the wasm concessions; no other admin page does.
        SiteTerminalCsp.install();
        // A tenant-originated instance write may only run an APPROVED template's image;
        // anything else needs the image_any record capability. Installed BEFORE the
        // quota hook fires (beforeValidate vs beforeWrite), so a refused image never
        // spends a reservation.
        InstanceImagePolicy.install();
        // Concurrent instance creates cannot both spend the last quota slot, and the
        // soft-delete transition hands the slot back (the remove hooks never fire on
        // the destroy path -- it soft-deletes through save()).
        InstanceQuota.install();
        // Per-HOST memory bookings charge adjacent to the same write: a migration moves
        // the charge between host buckets, and the soft-delete transition hands it back.
        InstanceCapacity.install();
        // Disk-GB and extra-NIC reservations charge adjacent to the device-row write;
        // hard deletes (detach, destroy cleanup) release through the remove pairing.
        InstanceDeviceQuota.install();
        // The ROOT disk charges the SAME owner disk-GB bucket the attached devices do:
        // a cap that rationed only attached disks would ignore the one disk every
        // workload already has. Also the surface that refuses an unusable or
        // unenforceable root-disk declaration by name.
        InstanceRootDiskQuota.install();
        // A change to the DECLARED image invalidates the pinned resolved fingerprint,
        // so a recreate after an image change resolves fresh instead of silently
        // reviving the old pin.
        InstanceImagePin.installInvalidation();
        // A project's auth group exists from its first write and dies with it; a
        // non-empty project cannot be deleted; an environment can only group records
        // its project OWNS (grouping never disagrees with the grants).
        ProjectGuards.install();
        // An OWNED instance (a Docker site's lowered running release, a managed
        // database's lowered engine) carries derived attribution, is read-only outside
        // its owning tier's system scope, and a generatedOnly() kind cannot be authored
        // standalone at all. One funnel, declared per kind -- see OwnedInstances.
        SiteInstances.install();
        DatabaseInstances.install();
        be.elevenways.hohenheim.server.stack.StackInstances.install();
        // A generated preview hostname row carries derived attribution: read-only outside
        // the preview system scope, swept by exact attribution, and a hand-authored row
        // with the same hostname is never adopted or deleted.
        be.elevenways.hohenheim.server.preview.PreviewDomains.install();
        // Concurrent preview creates cannot both spend an owner's last preview slot, and
        // the soft-delete transition (destroy/expiry) hands the slot back.
        be.elevenways.hohenheim.server.preview.PreviewQuota.install();
        // A site RECORD is one owner slot whether or not it lowers a container (eight of
        // eleven site types run none), released on the deleted_at transition SiteResource
        // stamps -- there is no hard site delete outside tests.
        SiteQuota.install();
        // A managed database is one owner slot ON TOP of the instance slot its engine
        // container spends; databases have no deleted_at, so the remove pairing is the one
        // release lane (TenantDatabases.abandon's compensating delete included).
        DatabaseQuota.install();
        // Detaching a database from an INSTANCE revokes the workload's reachability at
        // the daemon in the same breath as the row delete; the instance tier has no
        // release switch to defer the sweep to, so deferring it would fail open.
        InstanceDatabaseLinks.install();
    }
}
