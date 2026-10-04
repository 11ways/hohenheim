package be.elevenways.hohenheim.server.cms;

import be.elevenways.zenit.cms.common.resource.RowFormSupport;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.security.AccessContext;

import java.util.Map;

/** DNS domain writers use the shared CMS form projection for partial row saves. */
final class DnsRowWrites {
    private DnsRowWrites() {}

    static Object create(Model model, FormSpec form, Map<String, Object> values, AccessContext access) {
        return RowFormSupport.create(model.getModelId().toString(), model, form, input -> {
            Row row = model.createEmptyRow();
            RowFormSupport.applyValuesToRow(model.getModelId().toString(), form, row, input);
            return row;
        }, values, access, save -> {}, save -> {});
    }

    static void update(Model model, FormSpec form, Row row, Map<String, Object> values, AccessContext access) {
        RowFormSupport.update(model, form,
            (existing, input) -> RowFormSupport.applyValuesToRow(model.getModelId().toString(), form, existing, input),
            row, values, access, save -> {}, save -> {});
    }

    static Map<String, Object> values(Model model, FormSpec form, Row row) {
        return RowFormSupport.valuesFromRow(model.getModelId().toString(), form, row);
    }
}
