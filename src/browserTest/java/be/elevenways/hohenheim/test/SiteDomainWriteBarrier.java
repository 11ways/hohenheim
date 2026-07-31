package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteDomainModel;

/**
 * The DOMAIN-row sibling of {@link SiteEnableWriteBarrier}: parks a designated thread
 * inside a SiteDomainModel write, AFTER the route-conflict scan (installed at the MODULES
 * boot stage, so it runs first) and BEFORE the row hits the datasource.
 *
 * AIDEV-NOTE: same one-hook-per-JVM contract as SiteEnableWriteBarrier -- Schema has no
 * hook removal, so the hook is permanent and driven through the volatile coordinator;
 * tests MUST clear() it. Reuses SiteEnableWriteBarrier.Coordinator so both barriers share
 * one park/release protocol.
 */
final class SiteDomainWriteBarrier {

    private static volatile SiteEnableWriteBarrier.Coordinator active;

    static {
        SiteDomainModel.SCHEMA.addBeforeValidateHook(context -> {
            SiteEnableWriteBarrier.Coordinator coordinator = active;
            if (coordinator != null) {
                coordinator.onBeforeValidate();
            }
        });
    }

    private SiteDomainWriteBarrier() {
    }

    static void install(SiteEnableWriteBarrier.Coordinator coordinator) {
        active = coordinator;
    }

    static void clear() {
        active = null;
    }
}
