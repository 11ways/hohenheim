package be.elevenways.hohenheim.server.cms;


import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.options.ServerOptions;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.routing.RouteLocales;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.Computed;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
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

    private static final StringField LIVE_OVERVIEW = StringField.builder("live_overview")
        .label(HohenheimFormCopy.label("live_overview"))
        .visibleIn(EditView.EDIT)
        .build();

    private final FormSpec formSpec = FormSpec.builder()
        .add(ServerModel.NAME)
        .add(ServerModel.SSH_TARGET)
        .add(Computed.of(LIVE_OVERVIEW, values -> serverOverview(String.valueOf(values.get("name"))))
            .dependsOn("name")
            .build())
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(ServerModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(ServerModel.MODE).filterable().build())
        .column(ColumnSpec.fromField(ServerModel.SSH_TARGET).filterable().build())
        .column(ColumnSpec.virtual("host_status", Microcopy.of("host_status").withFilter("scope", "server")).build())
        .filter(FilterSpec.forField(ServerModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(ServerModel.NAME)).build())
        .filter(FilterSpec.forField(ServerModel.MODE, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(ServerModel.MODE)).build())
        .filter(FilterSpec.forField(ServerModel.SSH_TARGET, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(ServerModel.SSH_TARGET)).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "server"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "server"); }
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
        if (coerced.containsKey("name")) {
            row.set(ServerModel.NAME, (String) coerced.get("name"));
        }
        if (coerced.containsKey("ssh_target")) {
            row.set(ServerModel.SSH_TARGET, (String) coerced.get("ssh_target"));
        }
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
                status.put(summary.name(), hostCopy(Microcopy.of("host_unreachable")));
                continue;
            }
            status.put(summary.name(), formatSummary(summary));
        }
        return status;
    }

    private @NonNull String serverOverview(@NonNull String name) {
        ServerService.Summary summary = this.serverService.summary(name);
        return summary == null || !summary.reachable()
            ? hostCopy(Microcopy.of("host_docker_unavailable"))
            : formatSummary(summary);
    }

    private static @NonNull String formatSummary(ServerService.@NonNull Summary summary) {
        String docker = summary.dockerVersion().isBlank() ? "Docker" : "Docker " + summary.dockerVersion();
        String platform = summary.osType();
        if (!summary.architecture().isBlank()) {
            platform = platform.isBlank() ? summary.architecture() : platform + "/" + summary.architecture();
        }
        String operatingSystem = summary.operatingSystem().isBlank() ? platform : summary.operatingSystem();
        if (!platform.isBlank() && !operatingSystem.equals(platform)) {
            operatingSystem += " (" + platform + ")";
        }
        if (operatingSystem.isBlank()) {
            operatingSystem = hostCopy(Microcopy.of("host_unknown_platform"));
        }
        double memoryGib = Math.round(summary.memoryBytes() / 1_073_741_824.0 * 10) / 10.0;
        return hostCopy(Microcopy.of("host_summary")
            .withArg("docker", docker)
            .withArg("os", operatingSystem)
            .withArg("cpus", String.valueOf(summary.cpus()))
            .withArg("memory", String.valueOf(memoryGib))
            .withArg("running", String.valueOf(summary.containersRunning()))
            .withArg("total", String.valueOf(summary.containersTotal()))
            .withArg("images", String.valueOf(summary.images())));
    }

    /**
     * Host stats are computed once per list render without a requesting conduit, so
     * they speak the server's default locale.
     */
    private static @NonNull String hostCopy(@NonNull Microcopy microcopy) {
        return microcopy.withFilter("scope", "server")
            .resolve(LocaleChain.of(RouteLocales.get().getDefaultLocale()),
                Zenit.getMessageResolver());
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
        Object id = super.persistRow(values, accessContext);
        ServerOptions.refresh();
        return id;
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        validate(values, existing);
        values.put("mode", ServerService.MODE_SSH);
        super.updateRow(existing, values, accessContext);
        ServerOptions.refresh();
    }

    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        if (ServerService.LOCAL.equals(existing.get(ServerModel.NAME))) {
            throw Violations.ofForm(CmsSupport.violationText("local_server_undeletable"));
        }
        super.deleteRow(existing, accessContext);
        ServerOptions.refresh();
    }

    private static void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        Object nameValue = coerced.get("name");
        String name = nameValue != null ? String.valueOf(nameValue).trim()
            : existing != null ? existing.get(ServerModel.NAME) : "";
        if (ServerService.LOCAL.equals(name)) {
            throw Violations.ofField("name", name, CmsSupport.violationText(
                existing == null ? "local_server_reserved" : "local_server_immutable"));
        }
        if (name == null || name.isEmpty() || !name.matches("[a-z0-9][a-z0-9-]*")) {
            throw Violations.ofField("name", name, CmsSupport.violationText("name_format"));
        }
        Object targetValue = coerced.get("ssh_target");
        String target = targetValue != null ? String.valueOf(targetValue).trim() : "";
        if (target.isEmpty() || !SSH_TARGET.matcher(target).matches()) {
            throw Violations.ofField("ssh_target", target, CmsSupport.violationText("ssh_target_format"));
        }
    }
}
