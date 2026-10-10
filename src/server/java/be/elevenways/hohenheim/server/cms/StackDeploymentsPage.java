package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deployments tab on a stack: deploy history with captured logs. Deploy, stop
 * and rollback live on the stack's row actions (record toolbar), so this page
 * is pure history.
 */
public final class StackDeploymentsPage implements RecordTab.Rendered<Row> {

    @Override public @NonNull Identifier id() { return HohenheimIds.id("stack_deployments"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.STACK.of("deployments"); }

    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.DEPLOYMENTS; }
    @Override public @NonNull Icon icon() { return Icon.of("rocket"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row stack) {
        Conduit conduit = request.conduit();
        Integer stackId = stack.get(StackModel.ID);

        List<Map<String, Object>> deployments = new ArrayList<>();
        for (Row row : Models.get(StackDeploymentModel.class).findByStackId(stackId, 50)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", row.get(StackDeploymentModel.ID));
            OperationStatus status = StackDeploymentModel.LIFECYCLE.read(row.get(StackDeploymentModel.STATUS));
            entry.put("statusLabel", status != null ? status.label() : null);
            // AIDEV-NOTE: the same classifier supplies roles, hues and unknown-key honesty on every badge surface.
            WidgetBadge.Colors colors = WidgetBadge.colorsOf(StackDeploymentModel.STATUS,
                row.get(StackDeploymentModel.STATUS));
            entry.put("statusVariant", colors.variant());
            entry.put("statusColorSet", colors.colorSet());
            entry.put("statusKnown", colors.known());
            entry.put("reasonLabel",
                scopedLabel(row.get(StackDeploymentModel.REASON), HohenheimMicrocopy.STACK_DEPLOY_REASON));
            entry.put("duration", durationLabel(row.get(StackDeploymentModel.DURATION_MS)));
            entry.put("error", Objects.toString(row.get(StackDeploymentModel.ERROR), ""));
            Instant startedAt = row.get(StackDeploymentModel.STARTED_AT);
            entry.put("startedAtIso", startedAt != null ? startedAt.toString() : "");
            String log = row.get(StackDeploymentModel.LOG);
            entry.put("log", log != null ? log : "");
            entry.put("hasLog", log != null && !log.isBlank());
            deployments.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", stack.get(StackModel.NAME));
        vars.put("stackName", stack.get(StackModel.NAME));
        vars.put("deployments", deployments);
        vars.put("head", recordHead(conduit));
        vars.put("timeWording", CmsSupport.timeWording(conduit));
        return new RenderTemplateResult(HohenheimTemplateIds.STACK_DEPLOYMENTS, vars);
    }

    /** Known status/reason tokens localize; null renders empty. */
    private static @Nullable Microcopy scopedLabel(@Nullable Object value, @NonNull HohenheimMicrocopy scope) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return scope.of(String.valueOf(value));
    }

    private static String durationLabel(@Nullable Object durationMs) {
        if (!(durationMs instanceof Number number)) {
            return "";
        }
        long ms = number.longValue();
        if (ms < 1000) {
            return ms + "ms";
        }
        return (ms / 1000) + "s";
    }
}
