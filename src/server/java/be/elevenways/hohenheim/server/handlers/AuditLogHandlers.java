package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.RenderTemplateResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Audit log request handler.
 */
public final class AuditLogHandlers {

    private AuditLogHandlers() {
    }

    public static void init() {
        AuditLogModel auditModel = Models.get(AuditLogModel.class);

        HohenheimEndpoints.AUDIT_LOG.setHandler(conduit -> {
            List<Row> rows = auditModel.findRecent(100);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Row row : rows) {
                Map<String, Object> entry = HandlerSupport.auditEntryToMap(row);
                entry.put("resourceId", row.get(AuditLogModel.RESOURCE_ID));
                entries.add(entry);
            }

            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/audit"),
                Map.of("entries", entries)
            );
        });
    }
}
