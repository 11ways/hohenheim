package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deployments tab on a stack: deploy history with captured logs. Deploy, stop
 * and rollback live on the stack's row actions (record toolbar), so this page
 * is pure history.
 */
public final class StackDeploymentsPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "stack_deployments"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("deployments").withFilter("scope", "stack"); }
    @Override public @NonNull String slug() { return "deployments"; }
    @Override public @NonNull Icon icon() { return Icon.of("rocket"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row stack) {
        Integer stackId = stack.get(StackModel.ID);

        List<Map<String, Object>> deployments = new ArrayList<>();
        for (Row row : Models.get(StackDeploymentModel.class).findByStackId(stackId, 50)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", row.get(StackDeploymentModel.ID));
            entry.put("statusLabel", scopedLabel(row.get(StackDeploymentModel.STATUS), "stack_deploy_status"));
            entry.put("statusVariant", statusVariant(row.get(StackDeploymentModel.STATUS)));
            entry.put("reasonLabel", scopedLabel(row.get(StackDeploymentModel.REASON), "stack_deploy_reason"));
            entry.put("duration", durationLabel(row.get(StackDeploymentModel.DURATION_MS)));
            entry.put("error", orEmpty(row.get(StackDeploymentModel.ERROR)));
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
        vars.put("recordTabs", recordTabs(conduit));
        vars.put("timeWording", RelativeTimeWording.resolve(
            conduit.getLocales(), conduit.getMessageResolver()));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/stack-deployments"), vars);
    }

    private static String orEmpty(@Nullable Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    /** Known status/reason tokens localize; null renders empty. */
    private static @Nullable Microcopy scopedLabel(@Nullable Object value, @NonNull String scope) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Microcopy.of(String.valueOf(value)).withFilter("scope", scope);
    }

    private static String statusVariant(@Nullable Object status) {
        return switch (String.valueOf(status)) {
            case "success" -> "success";
            case "failed" -> "destructive";
            case "running" -> "warning";
            default -> "secondary";
        };
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
