package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.ConsoleModeView;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one home of an instance's Console tab and its modes: the live console, the shell, a one-off command and a
 * VM's screen are modes of ONE tab, each still its own routed and gated page.
 *
 * AIDEV-NOTE: a mode keeps its own route, its own hide-and-enforce gate ({@link Mode#offers}) and its own actions, so
 * nothing a mode authorizes moved; only the hub ({@link InstanceConsolePage}) sits in the record strip, and it is
 * offered while ANY mode is. The other modes stay out of the strip ({@code inTabs() == false}) and stand under the
 * hub ({@link Mode#standsUnder}), so the framework keeps Console marked active while one of them renders.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ConsoleModes {

    /** One mode of the Console tab: a rendered tab whose gate is its own offer. */
    public interface Mode extends RecordTab.Rendered<Row> {

        /** Whether THIS mode is offered on the record: the gate it had as a tab of its own. */
        boolean offers(@NonNull Row record, @NonNull AccessContext access);

        /** One line saying what the mode is for. */
        @NonNull Microcopy hint();

        @Override
        default boolean visibleFor(@NonNull Row record, @NonNull AccessContext access) {
            return this.offers(record, access);
        }

        /** A mode kept out of the strip stands under the Console hub, so the strip keeps Console marked. */
        @Override
        default @Nullable String standsUnder() {
            return this.inTabs() ? null : HohenheimSlugs.Tab.CONSOLE;
        }
    }

    private final @NonNull List<Mode> modes;

    /**
     * @param withCommand whether the panel offers the one-off command: an operator verb with deliberately no /manage
     *                    surface (exec is ADMIN-sensitivity; the shell is the delegable tenant verb)
     */
    private ConsoleModes(boolean withCommand) {
        List<Mode> list = new ArrayList<>();
        list.add(new InstanceConsolePage(this));
        list.add(new InstanceShellPage(this));
        if (withCommand) {
            list.add(new InstanceExecPage(this));
        }
        list.add(new InstanceFramebufferPage(this));
        this.modes = List.copyOf(list);
    }

    /** @return the operator panel's console: every mode */
    public static @NonNull ConsoleModes operator() {
        return new ConsoleModes(true);
    }

    /** @return the delegated panel's console: every mode but the one-off command */
    public static @NonNull ConsoleModes delegated() {
        return new ConsoleModes(false);
    }

    /** @return the record tabs the modes are routed as, the hub first */
    public @NonNull List<RecordTab<Row>> tabs() {
        return List.copyOf(this.modes);
    }

    /** @return whether any mode is offered on the record: the hub's own gate */
    boolean anyOffered(@NonNull Row record, @NonNull AccessContext access) {
        for (Mode mode : this.modes) {
            if (mode.offers(record, access)) {
                return true;
            }
        }
        return false;
    }

    /** @return the first offered mode other than the hub, which the hub renders when its own mode is not offered */
    @Nullable Mode firstOfferedBesidesHub(@NonNull Row record, @NonNull AccessContext access) {
        for (Mode mode : this.modes.subList(1, this.modes.size())) {
            if (mode.offers(record, access)) {
                return mode;
            }
        }
        return null;
    }

    /** @return the vars every mode's page starts from: the instance it shows, its record head and the mode switch */
    @NonNull Map<String, Object> vars(@NonNull PanelRequest request, @NonNull Row instance, @NonNull Mode mode) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", instance.get(InstanceModel.NAME));
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("instanceId", instance.get(InstanceModel.ID));
        vars.put("head", mode.recordHead(request.conduit()));
        vars.put("consoleModes", this.views(request, instance, mode.slug()));
        return vars;
    }

    /**
     * The mode switch: every offered mode as a link, the active one marked; empty while one mode is offered, because a
     * switch with one position offers nothing.
     */
    @NonNull List<ConsoleModeView> views(@NonNull PanelRequest request, @NonNull Row record,
                                         @NonNull String activeSlug) {
        Object instanceId = record.get(InstanceModel.ID);
        List<ConsoleModeView> views = new ArrayList<>();
        for (Mode mode : this.modes) {
            if (!mode.offers(record, request.access())) {
                continue;
            }
            views.add(new ConsoleModeView(mode.slug(), mode.label(),
                CmsRoutes.subpage(request.panelSlug(), HohenheimSlugs.INSTANCES, instanceId, mode.slug()).toUrl(),
                mode.hint(), mode.slug().equals(activeSlug)));
        }
        return views.size() < 2 ? List.of() : List.copyOf(views);
    }

}
