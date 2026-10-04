package be.elevenways.hohenheim.test;

import be.elevenways.zenit.common.ModuleClassPresence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class MediaRuntimePresenceTest {
    @Test
    void theHostStillShipsMediaAndItsCmsBoot() {
        assertFalse(ModuleClassPresence.SEAM.require().isAbsent("be.elevenways.zenit.media.common.MediaModel"));
        assertFalse(ModuleClassPresence.SEAM.require().isAbsent("be.elevenways.zenit.media.server.cms.CmsMediaBoot"));
    }
}
