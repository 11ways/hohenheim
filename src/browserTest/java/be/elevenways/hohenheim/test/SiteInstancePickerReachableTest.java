package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.SiteResource;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The site form must be able to move a site TO an instance upstream, not only away from one.
 *
 * AIDEV-NOTE: this pins a one-way door found on 2026-09-08 while rehearsing a rollback.
 * `instance_id` was bound to a record-aware FieldAccess that HID it whenever the STORED
 * upstream kind did not resolve to an instance. Selecting the Instance radio re-rendered
 * the per-kind settings sub-fields, which are reactive, but never the instance picker,
 * which is a top-level field resolved server-side from the stored row -- so the form
 * offered a kind it had no control to complete, and an address or static site could never
 * be moved (or moved back) to an instance. Neither the instance page's read-only
 * "Exposed by" band nor the Expose journey, which is a CREATE prefill, was a way back.
 *
 * What keeps the picker honest is the mechanism the CREATE form already relied on: its own
 * sibling narrowing stays inert until the instance kind is chosen, and the write hook
 * refuses an instance id on a kind that carries none.
 */
class SiteInstancePickerReachableTest {

    @Test
    void theInstancePickerIsNeverHiddenByTheStoredUpstreamKind() {
        List<ResourceFieldBinding> bindings = new SiteResource().fieldBindings();

        assertThat(bindings)
            .as("no field binding may gate the instance picker on the stored kind: that is the"
                + " one-way door -- a site saved as any other kind could never name an instance again")
            .noneMatch(binding -> SiteModel.INSTANCE_ID.getName().equals(binding.path()));
    }
}
