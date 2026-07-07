package be.elevenways.hohenheim.server.cms;


import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Multi-server Docker host inventory. The implicit {@code local} host always
 * exists and cannot be edited or removed; remote hosts are reached over SSH.
 */
public final class ServerResource extends RowResource {

    // AIDEV-NOTE: SSH target must be a bare [user@]host[:port] that never starts with '-', so it
    // cannot be parsed as an ssh option (e.g. -oProxyCommand=...) when passed to the ssh argv.
    private static final Pattern SSH_TARGET = Pattern.compile(
        "^(?:[A-Za-z0-9_.][A-Za-z0-9_.-]*@)?(?:[A-Za-z0-9_.][A-Za-z0-9_.-]*|\\[[0-9A-Fa-f:]+\\])(?::[0-9]{1,5})?$");

    private final ServerService serverService = new ServerService();

    private final FormSpec formSpec = FormSpec.builder()
        .add(ServerModel.NAME)
        .add(ServerModel.SSH_TARGET)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(ServerModel.NAME).build())
        .column(ColumnSpec.fromField(ServerModel.MODE).build())
        .column(ColumnSpec.fromField(ServerModel.SSH_TARGET).build())
        .column(ColumnSpec.virtual("host_status", Microcopy.of("hohenheim.server.host_status")).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "server"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("hohenheim.server.plural"); }
    @Override public @NonNull String slug() { return "servers"; }
    @Override public @NonNull Model model() { return Models.get(ServerModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 20; }
    @Override public @NonNull Icon icon() { return Icon.of("server"); }


    /** mode is staged by persist/update but is not a form entry; stamp it here. */
    @Override
    public @NonNull Row valuesToRow(@NonNull Map<String, Object> coerced) {
        Row row = super.valuesToRow(coerced);
        if (coerced.get("mode") instanceof String mode) {
            row.set(ServerModel.MODE, mode);
        }
        return row;
    }

    @Override
    public void applyValuesToRow(@NonNull Row row, @NonNull Map<String, Object> coerced) {
        super.applyValuesToRow(row, coerced);
        if (coerced.get("mode") instanceof String mode) {
            row.set(ServerModel.MODE, mode);
        }
    }

    @Override
    public @NonNull List<Row> listRows(TableView.Applied<Row> applied,
                                       @NonNull AccessContext accessContext) {
        this.serverService.ensureLocal();
        this.hostStatus = buildHostStatus();
        return super.listRows(applied, accessContext);
    }

    // Reachability + host stats per server name, refreshed per list render.
    private Map<String, String> hostStatus = Map.of();

    private @NonNull Map<String, String> buildHostStatus() {
        Map<String, String> status = new LinkedHashMap<>();
        for (ServerService.Summary summary : this.serverService.summaries()) {
            if (!summary.reachable()) {
                status.put(summary.name(), "unreachable");
                continue;
            }
            status.put(summary.name(), String.format("up - %d cpu, %.1f GB, %d/%d containers, %d images",
                summary.cpus(), summary.memoryBytes() / 1_000_000_000.0,
                summary.containersRunning(), summary.containersTotal(), summary.images()));
        }
        return status;
    }

    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        if ("host_status".equals(column.name())) {
            return this.hostStatus.get(row.get(ServerModel.NAME));
        }
        return super.cellValue(row, column);
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        validate(values, null);
        values.put("mode", ServerService.MODE_SSH);
        return super.persistRow(values, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        validate(values, existing);
        values.put("mode", ServerService.MODE_SSH);
        super.updateRow(existing, values, accessContext);
    }

    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        if (ServerService.LOCAL.equals(existing.get(ServerModel.NAME))) {
            throw new IllegalStateException("The implicit local host cannot be removed");
        }
        super.deleteRow(existing, accessContext);
    }

    private static void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        Object nameValue = coerced.get("name");
        String name = nameValue != null ? String.valueOf(nameValue).trim()
            : existing != null ? existing.get(ServerModel.NAME) : "";
        if (ServerService.LOCAL.equals(name)) {
            throw new IllegalStateException(existing == null
                ? "'local' is reserved for this host"
                : "The implicit local host cannot be edited");
        }
        if (name == null || name.isEmpty() || !name.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalStateException("Name must be lowercase letters, digits, and dashes");
        }
        Object targetValue = coerced.get("ssh_target");
        String target = targetValue != null ? String.valueOf(targetValue).trim() : "";
        if (target.isEmpty() || !SSH_TARGET.matcher(target).matches()) {
            throw new IllegalStateException("SSH target must be a plain [user@]host[:port]");
        }
    }
}
