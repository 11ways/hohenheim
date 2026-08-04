package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.model.ApiKeyPrincipal;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.ErrorResponse;
import be.elevenways.zenit.common.result.ErrorResult;
import be.elevenways.zenit.common.result.JsonResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violation;
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
 * 1. NO authorization decisions of its own. Power, snapshot and backup authority live in
 *    InstanceService / InstanceSnapshots / InstanceBackups behind
 *    HohenheimAccess.requireOperationCapability, and creation authority lives in
 *    InstanceTemplates.createFromTemplate. A handler here that re-implemented any of them
 *    would be a second policy, and a second policy is how an API becomes a wider door
 *    than the UI it claims to mirror. The only check the handlers make themselves is the
 *    per-record VISIBILITY test, and that one is shared with the list scope.
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
            AccessContext ctx = requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            List<Map<String, Object>> instances = new ArrayList<>();
            for (Row row : visibleInstances(ctx)) {
                instances.add(projection(row));
            }
            return json(Map.of("instances", instances));
        });

        HohenheimEndpoints.API_INSTANCE.setHandler(conduit -> {
            AccessContext ctx = requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleInstance(conduit, ctx);
            if (row == null) {
                return null;
            }
            return json(projection(row));
        });

        HohenheimEndpoints.API_INSTANCE_POWER.setHandler(conduit -> {
            AccessContext ctx = requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleInstance(conduit, ctx);
            if (row == null) {
                return null;
            }
            int instanceId = row.get(InstanceModel.ID);
            String action = formValue(conduit, "action");
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
                        return refusal(conduit, Violations.ofField("action", action,
                            violationText("unknown_power_action")));
                    }
                }
            } catch (Violations refused) {
                return refusal(conduit, refused);
            }
            ActivityLog.record(Models.get(InstanceModel.class), instanceId, "power_" + action, ORIGIN);
            return json(Map.of("id", instanceId, "action", action,
                "status", String.valueOf((Object) reload(instanceId).get(InstanceModel.STATUS))));
        });

        HohenheimEndpoints.API_INSTANCE_COMMAND.setHandler(conduit -> {
            AccessContext ctx = requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleInstance(conduit, ctx);
            if (row == null) {
                return null;
            }
            int instanceId = row.get(InstanceModel.ID);
            String command = formValue(conduit, "command");
            if (command.isEmpty()) {
                return refusal(conduit, Violations.ofField("command", command,
                    violationText("console_command_required")));
            }
            try {
                // The console hub owns the manage check for the socket; the command lane
                // asks the same funnel gate every other write does.
                HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.MANAGE);
                InstanceConsoles.sendCommand(instanceId, command);
            } catch (Violations refused) {
                return refusal(conduit, refused);
            }
            ActivityLog.record(Models.get(InstanceModel.class), instanceId, "console_command", ORIGIN);
            return json(Map.of("id", instanceId, "status", "sent"));
        });

        HohenheimEndpoints.API_INSTANCE_BACKUP.setHandler(conduit -> {
            AccessContext ctx = requireKey(conduit);
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
                ActivityLog.record(Models.get(InstanceModel.class), instanceId, "backup", ORIGIN);
                return json(Map.of("id", instanceId, "backup", backupId));
            } catch (Violations refused) {
                return refusal(conduit, refused);
            }
        });

        HohenheimEndpoints.API_INSTANCE_SNAPSHOT.setHandler(conduit -> {
            AccessContext ctx = requireKey(conduit);
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
                    emptyToNull(formValue(conduit, "note")));
                ActivityLog.record(Models.get(InstanceModel.class), instanceId, "snapshot", ORIGIN);
                return json(Map.of("id", instanceId, "snapshot", snapshotId));
            } catch (Violations refused) {
                return refusal(conduit, refused);
            }
        });

        HohenheimEndpoints.API_INSTANCE_CREATE.setHandler(conduit -> {
            AccessContext ctx = requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);
            Row template = InstanceTemplates.templateFrom(form);
            if (template == null) {
                return refusal(conduit, Violations.ofField("template_id", form.get("template_id"),
                    violationText("unknown_template")));
            }
            try {
                // The SAME funnel the create page posts to: create authority, template
                // approval, placement, typed variable coercion, image policy and quota.
                int instanceId = new InstanceTemplates().createFromTemplate(template,
                    InstanceTemplates.submittedString(form, "name"),
                    InstanceTemplates.submittedInteger(form, "server_id"), form, ctx);
                ActivityLog.record(Models.get(InstanceModel.class), instanceId, "created", ORIGIN);
                return json(projection(reload(instanceId)));
            } catch (Violations refused) {
                return refusal(conduit, refused);
            }
        });
    }

    /** Accountability origin of everything this surface does (zenit-auth's convention). */
    private static final String ORIGIN = "api";

    // -- visibility -----------------------------------------------------------

    /**
     * The instances this context may see: admins everything live, everyone else exactly
     * the ones they hold {@code manage} on. The SAME scope the /manage list renders,
     * asked through the same helper -- never a second query shape.
     */
    private static @NonNull List<Row> visibleInstances(@NonNull AccessContext ctx) {
        var query = Models.get(InstanceModel.class).find()
            .where(InstanceModel.DELETED_AT.isNull());
        Criteria scope = HohenheimAccess.instanceScope(ctx, HohenheimAccess.MANAGE);
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
            .first();
        if (row == null || !HohenheimAccess.hasInstanceCapability(ctx, instanceId,
                HohenheimAccess.MANAGE)) {
            conduit.notFound();
            return null;
        }
        return row;
    }

    /**
     * Refuse anything that is not an API key. A browser session reaching an automation
     * endpoint would be a CSRF-exempt, cookie-authenticated mutation, which is what
     * csrfExempt would otherwise cost; it is also how the two surfaces stay honestly
     * separate ("HTML routes are not the automation API").
     *
     * @return the access context, or null when the response has already been ended
     */
    private static @Nullable AccessContext requireKey(@NonNull Conduit conduit) {
        if (!(conduit.getAttribute(ConduitAttributes.PRINCIPAL) instanceof ApiKeyPrincipal)) {
            conduit.forbidden();
            return null;
        }
        return AccessContext.of(conduit);
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

    /**
     * Map a typed refusal onto 422 carrying the violation's MACHINE KEY as the error
     * code, so an API caller and an HTML caller are told the same named thing (the
     * HTML surface renders the same Microcopy).
     */
    @SuppressWarnings("unchecked")
    private static @NonNull ActionResult<Object> refusal(@NonNull Conduit conduit,
                                                        @NonNull Violations violations) {
        List<Violation> all = violations.all();
        String code = "REFUSED";
        String message = violations.getMessage();
        if (!all.isEmpty()) {
            code = all.get(0).message().key();
            message = all.get(0).message()
                .resolve(conduit.getLocales(), conduit.getMessageResolver());
        }
        return (ActionResult<Object>) (ActionResult<?>) new ErrorResult(
            ErrorResponse.of(422, code, message));
    }

    @SuppressWarnings("unchecked")
    private static @NonNull ActionResult<Object> json(@NonNull Map<String, Object> body) {
        return (ActionResult<Object>) (ActionResult<?>) new JsonResult<>(body);
    }

    private static @NonNull String formValue(@NonNull Conduit conduit, @NonNull String name) {
        return InstanceTemplates.submittedString(FormSubmissionRawValues.fromConduit(conduit), name);
    }

    private static @Nullable String emptyToNull(@NonNull String value) {
        return value.isEmpty() ? null : value;
    }

    private static @NonNull Microcopy violationText(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
