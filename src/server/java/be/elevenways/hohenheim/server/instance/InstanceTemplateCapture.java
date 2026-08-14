package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.hohenheim.server.runtime.ImageOrigin;
import be.elevenways.hohenheim.server.runtime.ImagePublishSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Capture a STOPPED instance's current state as a PREPARED template: publish the
 * rootfs as an image in the host daemon's own store ({@code incus publish}) and mint
 * an UNAPPROVED template row whose settings clone the instance's with the image
 * repointed at the published alias. The capture half of install-once/clone-many;
 * approval stays a separate deliberate act (the uniform template gate).
 *
 * The published image lives on ONE host, so the minted template is HOST-PINNED in
 * effect: placement's prepared-alias preflight refuses every other host by name until
 * an operator exports/imports the image there under the same alias (the
 * prepare-windows-template step 7 procedure). The template's {@code source} column
 * records where it came from so that fact is readable.
 */
public final class InstanceTemplateCapture {

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final @NonNull InstanceService instances;

    public InstanceTemplateCapture() {
        this(new InstanceService());
    }

    InstanceTemplateCapture(@NonNull InstanceService instances) {
        this.instances = instances;
    }

    /**
     * @return the minted template row id
     * @throws Violations {@code instance_not_permitted},
     *         {@code template_capture_unsupported},
     *         {@code template_capture_requires_stopped}, {@code template_capture_failed}
     */
    public int capture(int instanceId) {
        HohenheimAccess.requireOperatorOperation();
        Resolved resolved = this.instances.resolve(instanceId);
        InstanceOperationGuard.requireOperable(resolved.row());
        if (!resolved.handler().supportsTemplateCapture()
                || !(resolved.runtime() instanceof ImagePublishSupport publisher)) {
            throw Violations.ofForm(violationText("template_capture_unsupported")
                .withArg("name", nameOf(resolved.row())));
        }
        if (!InstanceModel.STATUS_STOPPED.equals(resolved.row().get(InstanceModel.STATUS))) {
            // The DRIVER also refuses a non-stopped publish on daemon truth; this is the
            // record-status twin so the refusal happens before any status is stamped.
            throw Violations.ofForm(violationText("template_capture_requires_stopped")
                .withArg("name", nameOf(resolved.row())));
        }

        String alias = aliasFor(resolved.row());
        long fence = this.instances.leases().requireFence(resolved.serverId());
        InstanceOperationGuard.stamp(this.instances.leases(), instanceId,
            resolved.serverId(), fence, InstanceModel.STATUS_CAPTURING,
            nameOf(resolved.row()));
        try {
            String description = "Captured from instance '" + nameOf(resolved.row())
                + "' (#" + instanceId + ")";
            publisher.publishImage(resolved.spec(), alias, description);
        } catch (IOException e) {
            throw Violations.ofForm(violationText("template_capture_failed")
                .withArg("name", nameOf(resolved.row()))
                .withArg("reason", e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            // A publish READS the stopped workload and never changes it; both outcomes
            // settle the record back to the state the capture started from.
            InstanceOperationGuard.stamp(this.instances.leases(), instanceId,
                resolved.serverId(), fence, InstanceModel.STATUS_STOPPED,
                nameOf(resolved.row()));
        }

        int templateId = mintTemplate(resolved, instanceId, alias);
        ActivityLog.record(Models.get(InstanceModel.class), instanceId,
            "template_captured", alias);
        Blast.log("TEMPLATE: captured instance", nameOf(resolved.row()), "as alias",
            alias, "-> template", templateId, "(unapproved)");
        return templateId;
    }

    /** The minted row: instance settings cloned, image repointed, UNAPPROVED. */
    private static int mintTemplate(@NonNull Resolved resolved, int instanceId,
                                    @NonNull String alias) {
        Map<String, Object> settings = new LinkedHashMap<>();
        if (resolved.row().get(InstanceModel.SETTINGS) instanceof Map<?, ?> current) {
            current.forEach((key, value) -> settings.put(String.valueOf(key), value));
        }
        settings.put("image", alias);
        settings.put("image_origin", ImageOrigin.PREPARED.key());
        String host = ServerModel.nameOf(resolved.serverId());

        InstanceTemplateModel templates = Models.get(InstanceTemplateModel.class);
        Row template = templates.createEmptyRow();
        template.set(InstanceTemplateModel.NAME, nameOf(resolved.row())
            + " (" + STAMP.format(Instant.now()) + ")");
        template.set(InstanceTemplateModel.KIND, resolved.row().get(InstanceModel.KIND));
        template.set(InstanceTemplateModel.SETTINGS, settings);
        template.set(InstanceTemplateModel.SOURCE, "captured from instance #" + instanceId
            + " (" + nameOf(resolved.row()) + ") on host " + host);
        // APPROVED_AT stays null by construction: capture and approval are two acts.
        templates.save(template);
        int templateId = template.get(InstanceTemplateModel.ID);
        ActivityLog.record(templates, templateId, ActivityLog.ACTION_CREATE, alias);
        return templateId;
    }

    /** A daemon-safe alias derived from the instance: {@code tpl-<slug>-<stamp>}. */
    static @NonNull String aliasFor(@NonNull Row instance) {
        String slug = nameOf(instance).toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
        if (slug.isEmpty()) {
            slug = "instance";
        }
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        return "tpl-" + slug + "-" + STAMP.format(Instant.now());
    }

    private static @NonNull String nameOf(@NonNull Row instance) {
        return String.valueOf((Object) instance.get(InstanceModel.NAME));
    }

    private static Microcopy violationText(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
