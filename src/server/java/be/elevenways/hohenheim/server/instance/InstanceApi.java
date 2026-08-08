package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.body.FormSubmissionRawValues;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The TENANT-facing instance API (v1): the automation surface over the same record
 * capabilities the /manage panel renders.
 *
 * Three rules hold this file together, and each one is a rule about what is NOT here:
 *
 * 1. NO authorization decisions of its own. Power, snapshot, backup, console, log-tail,
 *    device and variable authority live in InstanceService / InstanceSnapshots /
 *    InstanceBackups / InstanceConsoles / InstanceDevices / InstanceVariables behind
 *    HohenheimAccess.requireOperationCapability, and creation authority lives in
 *    InstanceTemplates.createFromTemplate. A handler here that re-implemented any of them
 *    would be a second policy, and a second policy is how an API becomes a wider door
 *    than the UI it claims to mirror. The only check the handlers make themselves is the
 *    per-record VISIBILITY test, and that one is shared with the list scope.
 *
 *    AIDEV-NOTE: {@link #visibleInstance} checks {@code view} and NOTHING ELSE, by design
 *    -- it answers "may you see this record", never "may you do this to it". Every
 *    MUTATING handler below therefore has to reach a service that asks its own capability;
 *    the variable and log lanes once did not, and a view-only delegate could write the
 *    secrets that substitute into {@code command} at the next deploy. When adding a
 *    handler here, name the service gate it rides or it does not ship.
 *
 * 2. NO existence oracle. "You may not touch this instance", "this instance is trashed"
 *    and "there is no such id" produce the byte-identical 404 that conduit.notFound()
 *    emits. A tenant walking the id space learns nothing but its own inventory.
 *
 * 3. NO field that was not enumerated. {@link #projection} is a whitelist, so a column
 *    added later (a host name, an environment map, a token) is invisible until someone
 *    deliberately adds it here. Settings, variables, the quota bucket and the placement
 *    host are all absent BY NAME: a tenant needs none of them, and the image/env and
 *    secret-variable material is exactly what a leak would be made of.
 */
public final class InstanceApi {

    private InstanceApi() {
    }

    public static void init() {
        HohenheimEndpoints.API_INSTANCES.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            List<Map<String, Object>> instances = new ArrayList<>();
            for (Row row : visibleInstances(ctx)) {
                instances.add(projection(row));
            }
            return ApiConduits.json(Map.of("instances", instances));
        });

        HohenheimEndpoints.API_INSTANCE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleInstance(conduit, ctx);
            if (row == null) {
                return null;
            }
            return ApiConduits.json(projection(row));
        });

        HohenheimEndpoints.API_INSTANCE_POWER.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleInstance(conduit, ctx);
            if (row == null) {
                return null;
            }
            int instanceId = row.get(InstanceModel.ID);
            String action = ApiConduits.formValue(conduit, "action");
            InstanceService service = new InstanceService();
            try {
                switch (action) {
                    case "start" -> service.deploy(instanceId);
                    case "stop" -> service.stop(instanceId);
                    case "restart" -> {
                        service.stop(instanceId);
                        service.deploy(instanceId);
                    }
                    default -> {
                        return ApiConduits.refusal(conduit, Violations.ofField("action", action,
                            ApiConduits.violationText("unknown_power_action")));
                    }
                }
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            ActivityLog.record(Models.get(InstanceModel.class), instanceId, "power_" + action,
                ApiConduits.ORIGIN);
            return ApiConduits.json(Map.of("id", instanceId, "action", action,
                "status", String.valueOf((Object) reload(instanceId).get(InstanceModel.STATUS))));
        });

        HohenheimEndpoints.API_INSTANCE_COMMAND.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleInstance(conduit, ctx);
            if (row == null) {
                return null;
            }
            int instanceId = row.get(InstanceModel.ID);
            String command = ApiConduits.formValue(conduit, "command");
            if (command.isEmpty()) {
                return ApiConduits.refusal(conduit, Violations.ofField("command", command,
                    ApiConduits.violationText("console_command_required")));
            }
            try {
                // InstanceConsoles.sendCommand IS the console gate now (it asks
                // requireOperationCapability for CONSOLE itself), so this lane must not
                // carry a second, drifting copy of it.
                InstanceConsoles.sendCommand(instanceId, command);
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            ActivityLog.record(Models.get(InstanceModel.class), instanceId, "console_command",
                ApiConduits.ORIGIN);
            return ApiConduits.json(Map.of("id", instanceId, "status", "sent"));
        });

        HohenheimEndpoints.API_INSTANCE_BACKUP.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleInstance(conduit, ctx);
            if (row == null) {
                return null;
            }
            int instanceId = row.get(InstanceModel.ID);
            try {
                int backupId = new InstanceBackups().backupNow(instanceId);
                ActivityLog.record(Models.get(InstanceModel.class), instanceId, "backup",
                    ApiConduits.ORIGIN);
                return ApiConduits.json(Map.of("id", instanceId, "backup", backupId));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
        });

        HohenheimEndpoints.API_INSTANCE_SNAPSHOT.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleInstance(conduit, ctx);
            if (row == null) {
                return null;
            }
            int instanceId = row.get(InstanceModel.ID);
            try {
                int snapshotId = new InstanceSnapshots().create(instanceId,
                    emptyToNull(ApiConduits.formValue(conduit, "note")));
                ActivityLog.record(Models.get(InstanceModel.class), instanceId, "snapshot",
                    ApiConduits.ORIGIN);
                return ApiConduits.json(Map.of("id", instanceId, "snapshot", snapshotId));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
        });

        HohenheimEndpoints.API_INSTANCE_CREATE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);
            Row template = InstanceTemplates.templateFrom(form);
            if (template == null) {
                return ApiConduits.refusal(conduit,
                    Violations.ofField("template_id", form.get("template_id"),
                        ApiConduits.violationText("unknown_template")));
            }
            try {
                // The SAME funnel the create page posts to: create authority, template
                // approval, placement, typed variable coercion, image policy and quota.
                int instanceId = new InstanceTemplates().createFromTemplate(template,
                    InstanceTemplates.submittedString(form, "name"),
                    InstanceTemplates.submittedInteger(form, "server_id"), form, ctx);
                ActivityLog.record(Models.get(InstanceModel.class), instanceId, "created",
                    ApiConduits.ORIGIN);
                return ApiConduits.json(projection(reload(instanceId)));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
        });

        HohenheimEndpoints.API_INSTANCE_LOGS.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleInstance(conduit, ctx);
            if (row == null) {
                return null;
            }
            int instanceId = row.get(InstanceModel.ID);
            try {
                return ApiConduits.json(Map.of("id", instanceId,
                    "lines", InstanceConsoles.tail(instanceId, clampLines(conduit))));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
        });

        HohenheimEndpoints.API_INSTANCE_VARIABLES.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleInstance(conduit, ctx);
            if (row == null) {
                return null;
            }
            int instanceId = row.get(InstanceModel.ID);
            return ApiConduits.json(Map.of("id", instanceId, "variables", variableProjection(
                Models.get(InstanceVariableModel.class).findByInstanceId(instanceId))));
        });

        HohenheimEndpoints.API_INSTANCE_VARIABLE_SET.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleInstance(conduit, ctx);
            if (row == null) {
                return null;
            }
            int instanceId = row.get(InstanceModel.ID);
            try {
                new InstanceVariables().setValue(instanceId, null,
                    ApiConduits.formValue(conduit, "key"),
                    kindOrDefault(ApiConduits.formValue(conduit, "kind")),
                    ApiConduits.formValue(conduit, "value"));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            ActivityLog.record(Models.get(InstanceModel.class), instanceId, "variable_set",
                ApiConduits.ORIGIN);
            return ApiConduits.json(Map.of("id", instanceId, "status", "set",
                "key", ApiConduits.formValue(conduit, "key")));
        });

        HohenheimEndpoints.API_INSTANCE_VARIABLE_DELETE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleInstance(conduit, ctx);
            if (row == null) {
                return null;
            }
            int instanceId = row.get(InstanceModel.ID);
            String key = ApiConduits.formValue(conduit, "key");
            boolean removed;
            try {
                // AIDEV-NOTE: this lane alone used to run the service call OUTSIDE a
                // Violations catch, so a typed refusal escaped the handler and came back
                // as the framework's generic error render instead of this API's named
                // refusal -- the same act, two different answer shapes, and the one that
                // escaped carried resolved prose where every sibling carries the key.
                removed = new InstanceVariables().removeValue(instanceId, null, key);
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            if (!removed) {
                return ApiConduits.refusal(conduit, Violations.ofField("key", key,
                    ApiConduits.violationText("variable_not_found")));
            }
            ActivityLog.record(Models.get(InstanceModel.class), instanceId, "variable_deleted",
                ApiConduits.ORIGIN);
            return ApiConduits.json(Map.of("id", instanceId, "status", "deleted", "key", key));
        });

        initDeviceLane();
    }

    /**
     * Attach, resize and detach over the SAME {@link InstanceDevices} funnel the Devices
     * tab posts to -- so the quota refusals, the daemon's verbatim "In use", and the
     * row-reverted-on-daemon-refusal contract are identical on both surfaces, and this
     * file still makes no authorization decision of its own.
     */
    private static void initDeviceLane() {
        HohenheimEndpoints.API_INSTANCE_DEVICES.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleInstance(conduit, ctx);
            if (row == null) {
                return null;
            }
            int instanceId = row.get(InstanceModel.ID);
            return ApiConduits.json(Map.of("id", instanceId,
                "devices", deviceProjection(new InstanceDevices().rowsFor(instanceId))));
        });

        HohenheimEndpoints.API_INSTANCE_DEVICE_ATTACH.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleInstance(conduit, ctx);
            if (row == null) {
                return null;
            }
            int instanceId = row.get(InstanceModel.ID);
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);
            String name = InstanceTemplates.submittedString(form, "name");
            String type = InstanceTemplates.submittedString(form, "type");
            try {
                if (InstanceDeviceModel.TYPE_DISK.equals(type)) {
                    Integer sizeGb = InstanceTemplates.submittedInteger(form, "size_gb");
                    // Null (absent or unparseable) reaches the model's own size invariant
                    // as 0, so "no size" and "size 0" answer with the same named refusal.
                    new InstanceDevices().attachDisk(instanceId, name,
                        sizeGb != null ? sizeGb : 0);
                } else if (InstanceDeviceModel.TYPE_NIC.equals(type)) {
                    new InstanceDevices().attachNic(instanceId, name);
                } else {
                    return ApiConduits.refusal(conduit, Violations.ofField("type", type,
                        ApiConduits.violationText("device_type_unknown")));
                }
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            ActivityLog.record(Models.get(InstanceModel.class), instanceId, "device_attached",
                ApiConduits.ORIGIN);
            return ApiConduits.json(Map.of("id", instanceId, "status", "attached",
                "device", name, "type", type));
        });

        HohenheimEndpoints.API_INSTANCE_DEVICE_RESIZE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleInstance(conduit, ctx);
            if (row == null) {
                return null;
            }
            int instanceId = row.get(InstanceModel.ID);
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);
            String name = InstanceTemplates.submittedString(form, "name");
            Integer sizeGb = InstanceTemplates.submittedInteger(form, "size_gb");
            try {
                new InstanceDevices().resizeDisk(instanceId, name,
                    sizeGb != null ? sizeGb : 0);
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            ActivityLog.record(Models.get(InstanceModel.class), instanceId, "device_resized",
                ApiConduits.ORIGIN);
            return ApiConduits.json(Map.of("id", instanceId, "status", "resized",
                "device", name, "size_gb", sizeGb != null ? sizeGb : 0));
        });

        HohenheimEndpoints.API_INSTANCE_DEVICE_DETACH.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleInstance(conduit, ctx);
            if (row == null) {
                return null;
            }
            int instanceId = row.get(InstanceModel.ID);
            String name = ApiConduits.formValue(conduit, "name");
            try {
                new InstanceDevices().detach(instanceId, name);
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            ActivityLog.record(Models.get(InstanceModel.class), instanceId, "device_detached",
                ApiConduits.ORIGIN);
            return ApiConduits.json(Map.of("id", instanceId, "status", "detached",
                "device", name));
        });
    }

    /** Whitelist projection, rule 3: the quota bucket and the timestamps stay out. */
    private static @NonNull List<Map<String, Object>> deviceProjection(@NonNull List<Row> rows) {
        List<Map<String, Object>> devices = new ArrayList<>();
        for (Row row : rows) {
            Map<String, Object> device = new LinkedHashMap<>();
            device.put("name", row.get(InstanceDeviceModel.NAME));
            device.put("type", row.get(InstanceDeviceModel.TYPE));
            device.put("size_gb", row.get(InstanceDeviceModel.SIZE_GB));
            devices.add(device);
        }
        return devices;
    }

    // -- visibility -----------------------------------------------------------

    /**
     * The instances this context may see: admins everything live, everyone else exactly
     * the ones the walk confirms {@code view} on. The SAME scope the /manage list renders,
     * asked through the same helper -- never a second query shape.
     */
    private static @NonNull List<Row> visibleInstances(@NonNull AccessContext ctx) {
        // Generated (product-tier-owned) instances are managed through their owning
        // record's surface; the automation API never lists or drives them.
        var query = Models.get(InstanceModel.class).find()
            .where(InstanceModel.DELETED_AT.isNull())
            .where(InstanceModel.GENERATED_BY.isNull());
        Criteria scope = HohenheimAccess.instanceScope(ctx, HohenheimAccess.VIEW);
        if (scope != null) {
            query.where(scope);
        }
        return query.orderBy(InstanceModel.ID, SortOrder.ASC).all();
    }

    /**
     * Resolve the route's instance for this context, ending the response with a 404 when
     * it is absent, trashed OR not permitted.
     *
     * AIDEV-NOTE: one method for all three because they must be one ANSWER. Splitting
     * "missing" from "forbidden" here is precisely the existing-record oracle the
     * CertificateAuthority NOT_SERVED/NOT_MANAGED note warns about, one tier over.
     *
     * @return the row, or null when the response has already been ended
     */
    private static @Nullable Row visibleInstance(@NonNull Conduit conduit,
                                                 @NonNull AccessContext ctx) {
        Integer instanceId = conduit.getParameter(HohenheimEndpoints.INSTANCE_ID);
        Row row = instanceId == null ? null : Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
            .where(InstanceModel.GENERATED_BY.isNull())
            .first();
        if (row == null || !HohenheimAccess.hasInstanceCapability(ctx, instanceId,
                HohenheimAccess.VIEW)) {
            conduit.notFound();
            return null;
        }
        return row;
    }

    // -- projection -----------------------------------------------------------

    /**
     * THE enumerated tenant view of an instance. A whitelist, never a row dump.
     */
    static @NonNull Map<String, Object> projection(@NonNull Row instance) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", instance.get(InstanceModel.ID));
        entry.put("name", instance.get(InstanceModel.NAME));
        entry.put("kind", String.valueOf((Object) instance.get(InstanceModel.KIND)));
        entry.put("status", String.valueOf((Object) instance.get(InstanceModel.STATUS)));
        entry.put("install_state", String.valueOf((Object) instance.get(InstanceModel.INSTALL_STATE)));
        entry.put("crash_policy", String.valueOf((Object) instance.get(InstanceModel.CRASH_POLICY)));
        entry.put("template", templateNameOf(instance));
        entry.put("created_at", String.valueOf((Object) instance.get(InstanceModel.CREATED_AT)));
        return entry;
    }

    /**
     * The enumerated variable view: key, kind, and the value ONLY for plain rows.
     *
     * AIDEV-NOTE: a secret's value has NO representation here, deliberately -- a GET
     * that echoes a stored secret makes every CI log, shell history and proxy cache a
     * leak, and it is the ONE surface redaction doctrine cannot reach once emitted.
     * Secrets are write-only over the API: {@code has_value} says one is stored,
     * re-setting it is the recovery, and env injection is the only reader. This is the
     * same posture as the provisioning page, which never puts the value in its vars.
     */
    public static @NonNull List<Map<String, Object>> variableProjection(@NonNull List<Row> rows) {
        List<Map<String, Object>> variables = new ArrayList<>();
        for (Row row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            boolean secret = InstanceVariableModel.KIND_SECRET
                .equals(row.get(InstanceVariableModel.KIND));
            entry.put("key", row.get(InstanceVariableModel.KEY));
            entry.put("kind", secret
                ? InstanceVariableModel.KIND_SECRET : InstanceVariableModel.KIND_PLAIN);
            if (secret) {
                String stored = row.get(InstanceVariableModel.SECRET_VALUE);
                entry.put("has_value", stored != null && !stored.isEmpty());
            } else {
                entry.put("value", row.get(InstanceVariableModel.PLAIN_VALUE));
            }
            variables.add(entry);
        }
        return variables;
    }

    /** Unspecified kind means plain -- the model's own default, restated for the form lane. */
    public static @NonNull String kindOrDefault(@NonNull String kind) {
        return kind.isEmpty() ? InstanceVariableModel.KIND_PLAIN : kind;
    }

    /** The requested tail length, clamped to a sane window (default 200, max 2000). */
    static int clampLines(@NonNull Conduit conduit) {
        String raw = conduit.getQueryParam("lines");
        int lines = 200;
        if (raw != null && !raw.isEmpty()) {
            try {
                lines = Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                // An unparsable request keeps the default; the window is advisory.
            }
        }
        return Math.max(1, Math.min(lines, 2000));
    }

    private static @NonNull String templateNameOf(@NonNull Row instance) {
        Object templateId = instance.get(InstanceModel.TEMPLATE_ID);
        if (!(templateId instanceof Integer id)) {
            return "";
        }
        Row template = Models.get(InstanceTemplateModel.class).findById(id);
        return template == null ? ""
            : String.valueOf((Object) template.get(InstanceTemplateModel.NAME));
    }

    // -- plumbing -------------------------------------------------------------

    private static @NonNull Row reload(int instanceId) {
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        if (row == null) {
            throw new IllegalStateException("instance " + instanceId + " vanished mid-request");
        }
        return row;
    }

    private static @Nullable String emptyToNull(@NonNull String value) {
        return value.isEmpty() ? null : value;
    }
}
