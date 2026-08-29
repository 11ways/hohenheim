package be.elevenways.hohenheim.server.api;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.server.auth.AccessRuleNodes;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.AccessListResource;
import be.elevenways.hohenheim.server.cms.AccessRuleResource;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.cms.ManageAccessListResource;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.hohenheim.server.cms.ManageAccessRuleResource;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.access.AccessRefusedException;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.body.FormSubmissionRawValues;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The PaaS API's access-list lane: named policies and the rule tree they carry, written
 * through the very resource pipeline the admin and /manage forms post to.
 *
 * AIDEV-NOTE: there is no model write here for anything an operator can type, on purpose.
 * The list is created through zenit-cms {@code ResourceWrites} and a rule is CONFIGURED
 * through it, which is what argon2-hashes a basic-auth password ({@code
 * AccessRuleResource.applyValuesToRow}) -- a raw {@code Model.save} of the same map stores
 * the plaintext, and {@code BasicCredentials.verifyPassword} then fails closed forever.
 * The one direct write left is the rule NODE's birth ({@link AccessRuleNodes}), shared
 * verbatim with the Rules tab, because a rule's place in the tree is not a form field and
 * {@code AccessRuleResource} is deliberately not creatable.
 *
 * Authorization mirrors the panels exactly, and both panels create access lists: an admin
 * key writes through {@link AccessListResource} (the operator form, {@code shared}
 * included), every other key through {@link ManageAccessListResource} -- the /manage form,
 * which drops {@code shared} and plants the creator's {@code manage} grant, so a tenant
 * owns what it authored. Reads and every write on an EXISTING list ask the same
 * {@code manage} walk the /manage resource scopes by (whose rules already demand the panel
 * permission as their gate), and {@code TenantWrites} re-asks it at the model as the real
 * gate. Only the CREATE asks a permission directly, because a record that does not exist
 * yet cannot be walked.
 */
public final class AccessListApi {

    private static final AccessListResource ADMIN_LISTS = new AccessListResource();
    private static final ManageAccessListResource TENANT_LISTS = new ManageAccessListResource();
    private static final AccessRuleResource ADMIN_RULES = new AccessRuleResource();
    private static final ManageAccessRuleResource TENANT_RULES = new ManageAccessRuleResource();

    private AccessListApi() {
    }

    static void init() {
        HohenheimEndpoints.API_V1_ACCESS_LISTS.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            List<Map<String, Object>> lists = new ArrayList<>();
            for (Row list : visibleLists(ctx)) {
                lists.add(listProjection(list, false));
            }
            return ApiConduits.json(Map.of("access_lists", lists));
        });

        HohenheimEndpoints.API_V1_ACCESS_LIST.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row list = visibleList(conduit, ctx);
            return list == null ? null : ApiConduits.json(listProjection(list, true));
        });

        HohenheimEndpoints.API_V1_ACCESS_LIST_CREATE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            // The ONE gate a create needs of its own: there is no record yet, so the
            // capability walk that guards every other verb here has nothing to walk, and
            // the panel permission is what admits an operator to the form.
            if (!HohenheimAccess.isAdmin(ctx) && !ctx.hasPermission(ManagePanel.ACCESS.value())) {
                conduit.forbidden();
                return null;
            }
            try {
                int listId = (Integer) ResourceWrites.create(listResource(ctx),
                    FormSubmissionRawValues.fromConduit(conduit), ctx);
                ActivityLog.record(Models.get(AccessListModel.class), listId, "created",
                    ApiConduits.ORIGIN);
                return ApiConduits.json(listProjection(Objects.requireNonNull(
                    Models.get(AccessListModel.class).findById(listId)), true));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (AccessRefusedException refused) {
                conduit.forbidden();
                return null;
            }
        });

        HohenheimEndpoints.API_V1_ACCESS_LIST_DELETE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row list = visibleList(conduit, ctx);
            if (list == null) {
                return null;
            }
            try {
                // The resource's own delete: the rule rows cascade off the model hook and
                // whatever the list gated stops being gated -- exactly what the form's
                // confirmation warns about.
                ResourceWrites.delete(listResource(ctx), list, ctx);
                return ApiConduits.json(Map.of("id", list.get(AccessListModel.ID),
                    "status", "deleted"));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (AccessRefusedException refused) {
                conduit.forbidden();
                return null;
            }
        });

        HohenheimEndpoints.API_V1_ACCESS_LIST_RULE_CREATE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row list = visibleList(conduit, ctx);
            if (list == null) {
                return null;
            }
            return addRule(conduit, ctx, list.get(AccessListModel.ID));
        });
    }

    /**
     * Birth the node the Rules tab's add form births, then configure it through the rule
     * form's own pipeline -- the two steps an operator takes, in one call.
     *
     * AIDEV-NOTE: the node is born first and configured second because that is the only
     * order the tree allows: its list and parent are not form fields, and the completeness
     * half of {@code AccessRuleModel.validateData} only bites once the rule is switched
     * ON. A refused configure therefore leaves a switched-OFF, half-typed node behind --
     * which is exactly what the tab leaves behind, enforces nothing, and is visible in the
     * answer of the next read.
     */
    private static @Nullable ActionResult<Object> addRule(@NonNull Conduit conduit,
                                                          @NonNull AccessContext ctx,
                                                          int listId) {
        Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);
        String type = stringOf(form.get("type"));
        if (!AccessRuleModel.ALL_TYPES.contains(type)) {
            return ApiConduits.refusal(conduit, Violations.ofField("type", type,
                Microcopy.of("unknown_type").withFilter("scope", "access_rule")));
        }
        Row rule = AccessRuleNodes.add(listId,
            AccessRuleNodes.parentIn(stringOf(form.get("parent_id")), listId),
            type, ApiConduits.ORIGIN);
        // The rule model is deliberately NOT in ProxyReloadHooks' routing set, so the
        // birth reloads by hand exactly as the Rules tab does; the configure below
        // reloads again through AccessRuleResource.updateRow.
        CmsSupport.reloadProxy();
        // Only the rule form's own entries travel on: parent_id is a tree fact the birth
        // above consumed, and ResourceWrites refuses a key the form does not declare.
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(AccessRuleModel.TYPE.getName(), type);
        if (form.get(AccessRuleModel.DATA.getName()) != null) {
            values.put(AccessRuleModel.DATA.getName(), form.get(AccessRuleModel.DATA.getName()));
        }
        if (form.get(AccessRuleModel.ENABLED.getName()) != null) {
            values.put(AccessRuleModel.ENABLED.getName(),
                form.get(AccessRuleModel.ENABLED.getName()));
        }
        try {
            ResourceWrites.update(ruleResource(ctx), rule.get(AccessRuleModel.ID), rule,
                values, ctx);
        } catch (Violations refused) {
            return ApiConduits.refusal(conduit, refused);
        } catch (AccessRefusedException refused) {
            conduit.forbidden();
            return null;
        }
        return ApiConduits.json(ruleProjection(Objects.requireNonNull(
            Models.get(AccessRuleModel.class).findById(rule.get(AccessRuleModel.ID)))));
    }

    /**
     * The resource whose form the caller would have posted: the operator's on an admin
     * key, the delegated one otherwise. Choosing it is the whole authority decision this
     * class makes -- everything else is the resource's and TenantWrites'.
     */
    private static @NonNull RowResource listResource(@NonNull AccessContext ctx) {
        return HohenheimAccess.isAdmin(ctx) ? ADMIN_LISTS : TENANT_LISTS;
    }

    private static @NonNull RowResource ruleResource(@NonNull AccessContext ctx) {
        return HohenheimAccess.isAdmin(ctx) ? ADMIN_RULES : TENANT_RULES;
    }

    /** The lists this context manages; an admin's walk answers ALL, so it sees every one. */
    private static @NonNull List<Row> visibleLists(@NonNull AccessContext ctx) {
        var model = Models.get(AccessListModel.class);
        var query = model.find();
        Criteria scope = HohenheimAccess.grantScope(ctx, model, AccessListModel.MODEL_ID,
            HohenheimAccess.MANAGE, AccessListModel.ID::in);
        if (scope != null) {
            query.where(scope);
        }
        return query.orderBy(AccessListModel.ID, SortOrder.ASC).all();
    }

    /**
     * Resolve the route's list for this context, ending the response with the uniform 404
     * when it is absent OR not managed (never an existence oracle). A SHARED list a caller
     * may merely attach is not visible here: attaching is a picker's affordance, and this
     * lane edits.
     *
     * @return the row, or null when the response has already been ended
     */
    private static @Nullable Row visibleList(@NonNull Conduit conduit,
                                             @NonNull AccessContext ctx) {
        Integer listId = conduit.getParameter(HohenheimEndpoints.ACCESS_LIST_ID);
        Row list = listId == null ? null
            : Models.get(AccessListModel.class).findById(listId);
        if (list == null || !HohenheimAccess.reachesRecord(ctx, AccessListModel.MODEL_ID,
                listId, HohenheimAccess.MANAGE)) {
            conduit.notFound();
            return null;
        }
        return list;
    }

    /** THE enumerated view of a list; the detail form carries its whole rule tree. */
    private static @NonNull Map<String, Object> listProjection(@NonNull Row list,
                                                               boolean detail) {
        Map<String, Object> entry = new LinkedHashMap<>();
        Integer listId = list.get(AccessListModel.ID);
        entry.put("id", listId);
        entry.put("name", list.get(AccessListModel.NAME));
        entry.put("satisfy", String.valueOf((Object) list.get(AccessListModel.SATISFY)));
        entry.put("shared", Boolean.TRUE.equals(list.get(AccessListModel.SHARED)));
        if (detail && listId != null) {
            List<Map<String, Object>> rules = new ArrayList<>();
            for (Row rule : Models.get(AccessRuleModel.class).findForAccessList(listId)) {
                rules.add(ruleProjection(rule));
            }
            entry.put("rules", rules);
        }
        return entry;
    }

    /**
     * THE enumerated view of a rule row.
     *
     * AIDEV-NOTE: the stored basic-auth password is absent BY NAME. It is an argon2 hash
     * rather than a secret, but a hash is still credential material, and the API's promise
     * is that a value written as a credential has no representation afterwards.
     */
    private static @NonNull Map<String, Object> ruleProjection(@NonNull Row rule) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", rule.get(AccessRuleModel.ID));
        entry.put("access_list_id", rule.get(AccessRuleModel.ACCESS_LIST_ID));
        entry.put("parent_id", rule.get(AccessRuleModel.PARENT_ID));
        entry.put("type", String.valueOf((Object) rule.get(AccessRuleModel.TYPE)));
        entry.put("enabled", Boolean.TRUE.equals(rule.get(AccessRuleModel.ENABLED)));
        Map<String, Object> data = new LinkedHashMap<>();
        for (Map.Entry<String, Object> value : AccessRuleModel.dataOf(rule).entrySet()) {
            if (!AccessRuleModel.BASIC_AUTH_PASSWORD.getName().equals(value.getKey())) {
                data.put(value.getKey(), value.getValue());
            }
        }
        entry.put("data", data);
        entry.put("has_password", AccessRuleModel.dataOf(rule)
            .get(AccessRuleModel.BASIC_AUTH_PASSWORD.getName()) != null);
        return entry;
    }

    /** One raw submit value as a trimmed string; a list takes its first, absent is "". */
    private static @NonNull String stringOf(@Nullable Object value) {
        Object single = value instanceof List<?> list
            ? (list.isEmpty() ? null : list.get(0)) : value;
        return single == null ? "" : String.valueOf(single).trim();
    }
}
