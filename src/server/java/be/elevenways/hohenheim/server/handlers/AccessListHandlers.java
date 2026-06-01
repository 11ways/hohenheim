package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.RenderTemplateResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Access list request handlers.
 */
public final class AccessListHandlers {

    private AccessListHandlers() {
    }

    public static void init() {
        AccessListModel accessListModel = Models.get(AccessListModel.class);

        // List
        HohenheimEndpoints.ACCESS_LISTS.setHandler(conduit -> {
            List<Row> rows = accessListModel.find().all();
            List<Map<String, Object>> lists = new ArrayList<>();
            for (Row row : rows) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", row.get(AccessListModel.ID));
                item.put("name", row.get(AccessListModel.NAME));
                item.put("satisfy", row.get(AccessListModel.SATISFY));
                item.put("hasAuth", row.get(AccessListModel.BASIC_AUTH_USER) != null);
                item.put("hasIpRules",
                    row.get(AccessListModel.ALLOWED_IPS) != null
                    || row.get(AccessListModel.DENIED_IPS) != null);
                lists.add(item);
            }
            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/access-lists/list"),
                Map.of("accessLists", lists, "listCount", lists.size())
            );
        });

        // Create form
        HohenheimEndpoints.ACCESS_LISTS_CREATE_FORM.setHandler(conduit ->
            new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/access-lists/create"),
                Map.of("error", "")
            )
        );

        // Create (POST)
        HohenheimEndpoints.ACCESS_LISTS_CREATE.setHandler(conduit -> {
            Map<String, String> form = HandlerSupport.formMap(conduit);

            String name = form.getOrDefault("name", "").trim();
            if (name.isEmpty()) {
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/access-lists/create"),
                    Map.of("error", "Name is required")
                );
            }

            Row row = accessListModel.createEmptyRow();
            row.set(AccessListModel.NAME, name);
            row.set(AccessListModel.SATISFY, form.getOrDefault("satisfy", AccessListModel.SATISFY_ANY));
            setAccessListFields(row, form);
            accessListModel.save(row);

            HandlerSupport.audit(conduit, AuditLogModel.ACTION_CREATED, AuditLogModel.RESOURCE_ACCESS_LIST,
                row.get(AccessListModel.ID), name);
            return HandlerSupport.redirectUntyped("/access-lists");
        });

        // Edit form
        HohenheimEndpoints.ACCESS_LISTS_EDIT.setHandler(conduit -> {
            Integer id = conduit.getParameter(HohenheimEndpoints.ACCESS_LIST_ID);
            Row row = accessListModel.findById(id);
            if (row == null) return HandlerSupport.redirectUntyped("/access-lists");

            return HandlerSupport.renderUntyped(
                Identifier.of("hohenheim", "hohenheim/access-lists/edit"),
                buildAccessListEditVars(row, "")
            );
        });

        // Update (POST)
        HohenheimEndpoints.ACCESS_LISTS_UPDATE.setHandler(conduit -> {
            Integer id = conduit.getParameter(HohenheimEndpoints.ACCESS_LIST_ID);
            Map<String, String> form = HandlerSupport.formMap(conduit);

            Row row = accessListModel.findById(id);
            if (row == null) return HandlerSupport.redirectUntyped("/access-lists");

            String name = form.getOrDefault("name", "").trim();
            if (name.isEmpty()) {
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/access-lists/edit"),
                    buildAccessListEditVars(row, "Name is required")
                );
            }

            row.set(AccessListModel.NAME, name);
            row.set(AccessListModel.SATISFY, form.getOrDefault("satisfy", AccessListModel.SATISFY_ANY));
            setAccessListFields(row, form);
            accessListModel.save(row);

            HandlerSupport.audit(conduit, AuditLogModel.ACTION_UPDATED, AuditLogModel.RESOURCE_ACCESS_LIST,
                id, name);
            HandlerSupport.reloadProxy();
            return HandlerSupport.redirectUntyped("/access-lists");
        });

        // Delete (POST)
        HohenheimEndpoints.ACCESS_LISTS_DELETE.setHandler(conduit -> {
            Integer id = conduit.getParameter(HohenheimEndpoints.ACCESS_LIST_ID);
            Row row = accessListModel.findById(id);
            if (row != null) {
                String name = row.get(AccessListModel.NAME);
                accessListModel.find().where(AccessListModel.ID.eq(id)).delete();
                HandlerSupport.audit(conduit, AuditLogModel.ACTION_DELETED, AuditLogModel.RESOURCE_ACCESS_LIST,
                    id, name);
                HandlerSupport.reloadProxy();
            }
            return HandlerSupport.redirectUntyped("/access-lists");
        });
    }

    private static void setAccessListFields(Row row, Map<String, String> form) {
        String user = form.getOrDefault("basic_auth_user", "").trim();
        String pass = form.getOrDefault("basic_auth_pass", "").trim();
        row.set(AccessListModel.BASIC_AUTH_USER, user.isEmpty() ? null : user);
        row.set(AccessListModel.BASIC_AUTH_PASS, pass.isEmpty() ? null : pass);

        String allowed = form.getOrDefault("allowed_ips", "").trim();
        String denied = form.getOrDefault("denied_ips", "").trim();
        row.set(AccessListModel.ALLOWED_IPS, allowed.isEmpty() ? null : allowed);
        row.set(AccessListModel.DENIED_IPS, denied.isEmpty() ? null : denied);
    }

    private static Map<String, Object> buildAccessListEditVars(Row row, String error) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("id", row.get(AccessListModel.ID));
        vars.put("name", row.get(AccessListModel.NAME));
        vars.put("satisfy", row.get(AccessListModel.SATISFY));
        vars.put("basicAuthUser", HandlerSupport.valueOrEmpty(row.get(AccessListModel.BASIC_AUTH_USER)));
        vars.put("basicAuthPass", HandlerSupport.valueOrEmpty(row.get(AccessListModel.BASIC_AUTH_PASS)));
        vars.put("allowedIps", HandlerSupport.valueOrEmpty(row.get(AccessListModel.ALLOWED_IPS)));
        vars.put("deniedIps", HandlerSupport.valueOrEmpty(row.get(AccessListModel.DENIED_IPS)));
        vars.put("error", error);
        return vars;
    }
}
