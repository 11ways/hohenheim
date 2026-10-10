package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.InstanceOperations.ExecRun;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.InstanceOperationHandlers;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionRequest;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.server.page.PageActions;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.operation.OperationResult;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;

/**
 * The one-off command mode of an instance's Console tab: run one arbitrary command inside the workload and read its
 * exit code and output, through the exec operation this mode places (its form posts to the one invoke route with this
 * mode's slug as {@code _tab}).
 *
 * AIDEV-NOTE: the mode exists exactly where the operation is offered (the console tab's shape): the exec capability on
 * THIS record, an authored instance. InstanceExec asks the capability once more on its funnel, because that funnel is
 * what a future API lane would reach too.
 */
public final class InstanceExecPage implements ConsoleModes.Mode {

    /** The exec operation over this tab's own record. */
    private static final PanelAction<Row> EXEC = PanelAction.<Row, ExecRun>places(InstanceOperations.EXEC,
            ActionPlacement.PAGE, InstanceExecPage::ran)
        // The form's title and submit: the card the tab always drew, its description the one line of context.
        .confirmation(Confirmations.of(HohenheimMicrocopy.INSTANCE_EXEC.of("title"),
            HohenheimMicrocopy.INSTANCE_EXEC.of("run"), HohenheimMicrocopy.INSTANCE_EXEC.of("description"),
            ActionStyle.DEFAULT))
        .selectedByRoute(instance -> String.valueOf((Object) instance.get(InstanceModel.ID)))
        .build();

    private final @NonNull ConsoleModes modes;

    InstanceExecPage(@NonNull ConsoleModes modes) {
        this.modes = modes;
    }

    @Override public @NonNull Identifier id() { return HohenheimIds.id("instance_exec"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.INSTANCE.of("exec"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.EXEC; }
    @Override public @NonNull Icon icon() { return Icon.of("code"); }

    /** A mode of the Console tab, reached through its mode switch. */
    @Override public boolean inTabs() { return false; }

    @Override
    public @NonNull Microcopy hint() {
        return HohenheimMicrocopy.CONSOLE_MODE.of("command");
    }

    /** Hide AND enforce (an unoffered slug 404s): exactly where the exec operation is offered on this record. */
    @Override
    public boolean offers(@NonNull Row record, @NonNull AccessContext accessContext) {
        return InstanceOperationHandlers.offered(InstanceOperations.EXEC, accessContext, record);
    }

    @Override
    public @NonNull List<PanelAction<Row>> actions() {
        return List.of(EXEC);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row instance) {
        Conduit conduit = request.conduit();
        boolean running = InstanceModel.STATUS_RUNNING.equals(instance.get(InstanceModel.STATUS));
        Map<String, Object> vars = this.modes.vars(request, instance, this);
        vars.put("running", running);
        if (running && PageActions.open(request, this, instance, EXEC.id()) instanceof PageActions.Form form) {
            vars.put("document", form.state());
        }
        InstanceExecResults.Run run = InstanceExecResults.pop(conduit, instance.get(InstanceModel.ID));
        vars.put("execOutput", run == null ? "" : run.output());
        vars.put("execExit", run == null ? "" : run.exitCode());
        return new RenderTemplateResult(HohenheimTemplateIds.INSTANCE_EXEC, vars);
    }

    /**
     * The run's output is page CONTENT: it rides the session to this tab's next render, never the URL or a toast.
     * This tab renders under /admin AND /manage, so the way back is built from the HOSTING panel.
     */
    private static @NonNull CmsActionResult ran(@NonNull ActionRequest<Row> request,
                                                @NonNull OperationResult<ExecRun> result) {
        int instanceId = request.subject().get(InstanceModel.ID);
        ExecRun run = result.value();
        InstanceExecResults.stash(request.request().conduit(), instanceId, run.exitCode(), run.output());
        return CmsActionResult.redirect(new Uri(CmsRoutes.subpage(request.request().panelSlug(),
            HohenheimSlugs.INSTANCES, instanceId, HohenheimSlugs.Tab.EXEC).toUrl()));
    }

}
