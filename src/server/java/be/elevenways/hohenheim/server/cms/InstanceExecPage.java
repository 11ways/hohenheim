package be.elevenways.hohenheim.server.cms;

import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceExec;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.page.CmsFormBody;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Exec tab on an instance: run one arbitrary command inside the workload and read its
 * exit code and output. The tab itself is gated on the {@code exec} capability, so a
 * console or config delegate never sees it -- and {@code InstanceExec.run} asks the same
 * capability again, because a hidden tab is an affordance and a direct POST is not.
 */
public final class InstanceExecPage implements RecordScopedPage<Row> {

    public static final String SLUG = "exec";

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_exec"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("exec").withFilter("scope", "instance"); }
    /**
     * Housekeeping, not an everyday destination: the tab lives in the strip's "More"
     * menu so the visible strip stays the handful of tabs an operator opens daily.
     */
    @Override public boolean secondaryTab() { return true; }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("code"); }

    /**
     * The tab exists only for a principal that may actually exec on THIS record -- the
     * per-record half of the hide-and-enforce pair (zenit-cms 404s an unoffered slug, so
     * this is a gate on the route as well as on the nav).
     */
    @Override
    public boolean visibleFor(@NonNull Row record, @NonNull AccessContext accessContext) {
        return HohenheimAccess.hasInstanceCapability(
            accessContext, record.get(InstanceModel.ID), HohenheimAccess.EXEC);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        String status = instance.get(InstanceModel.STATUS);
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", instance.get(InstanceModel.NAME));
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("instanceId", instance.get(InstanceModel.ID));
        vars.put("running", InstanceModel.STATUS_RUNNING.equals(status));
        InstanceExecResults.Run run = InstanceExecResults.pop(conduit,
            instance.get(InstanceModel.ID));
        vars.put("execOutput", run == null ? "" : run.output());
        vars.put("execExit", run == null ? "" : run.exitCode());
        vars.put("returnUrl", ReturnTarget.capture(conduit));
        // AIDEV-NOTE: the hidden field NAME comes from the framework constant --
        // ReturnTarget is server-only, so the common template cannot reach it.
        vars.put("returnParam", ReturnTarget.PARAM);
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-exec"), vars);
    }

    @Override
    public boolean submittable() {
        return true;
    }

    /**
     * Run the submitted command. The framework has already re-checked
     * {@link #visibleFor} for this POST, and {@code InstanceExec.run} asks the exec
     * capability a third time on the funnel -- deliberately, because that funnel is
     * what a future API lane would reach too.
     */
    @Override
    public @NonNull CmsActionResult submit(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        Map<String, Object> body = conduit.getBody(CmsFormBody.BODY);
        Object raw = body == null ? null : body.get("command");
        String command = raw == null ? "" : String.valueOf(raw).strip();
        int instanceId = instance.get(InstanceModel.ID);

        // AIDEV-NOTE: this tab renders under /admin AND /manage, so the destination is
        // built from the HOSTING panel rather than from conduit.getPath().
        RouteTarget back = CmsRoutes.subpage(CmsSupport.panelSlug(conduit),
            "instances", instanceId, SLUG);
        try {
            InstanceExec.Run run = new InstanceExec().run(instanceId, command);
            // Output is page CONTENT, so it rides the session, never the URL.
            InstanceExecResults.stash(conduit, instanceId, run.exitCode(), run.output());
            return CmsActionResult.redirect(new Uri(back.toUrl()));
        } catch (Violations refused) {
            // NEVER a silent swallow: the refusal becomes the page's error toast.
            return CmsActionResult.errorToast(violationMessage(refused));
        }
    }

    /** The first violation's own message, so a refusal keeps its localized text. */
    private static @NonNull Microcopy violationMessage(@NonNull Violations violations) {
        return violations.all().isEmpty()
            ? Microcopy.of("refused").withFilter("scope", "violations")
            : violations.all().get(0).message();
    }
}
