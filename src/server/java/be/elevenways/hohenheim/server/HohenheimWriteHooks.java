package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.server.cms.SiteResource;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.hohenheim.server.process.ReservedEnv;
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
    }
}
