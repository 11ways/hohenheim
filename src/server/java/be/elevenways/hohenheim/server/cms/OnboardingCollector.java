package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.OnboardingStage;
import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.hohenheim.OnboardingState;
import be.elevenways.hohenheim.OnboardingStep;
import be.elevenways.hohenheim.instance.WorkloadIsolation;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the dashboard readiness checklist, one step per {@link OnboardingStage}: enrol a host, let it run apps,
 * choose where backups go, put the first app online.
 *
 * AIDEV-NOTE: every step is DERIVED, never a restatement. The host step asks
 * {@link HostAdmission#instancePlacementRefusal} -- the very call the deploy lane makes --
 * so a refusal added to that gate shows up here with no edit, and this checklist can never
 * tell an operator they are ready while the gate disagrees. It is also why the step's detail
 * is the gate's own sentence rather than prose written beside it.
 *
 * The whole thing is state-derived, with no dismissed flag: it disappears because the fleet
 * is running, which is the only honest reason for onboarding to stop being shown.
 *
 * @author Jelle De Loecker
 * @since  0.5.0
 */
public final class OnboardingCollector {

    private static final String ADMIN = HohenheimSlugs.ADMIN;

    private OnboardingCollector() {
    }

    /**
     * Every step DECLARES the role that can act on it and is omitted when that role is
     * off: the host steps belong to the tiers that place workloads on enrolled hosts
     * (the gate {@link be.elevenways.hohenheim.server.cms.HohenheimPanel} puts the
     * Servers list behind), the instance steps to the instance tier.
     *
     * AIDEV-NOTE: a proxy/DNS appliance has no Servers list and no Instances list, so
     * the ungated checklist told it to walk to two pages that 404 and could never reach
     * "done" -- an onboarding card that retires itself by state can only retire if every
     * step it shows is reachable.
     *
     * @return the ordered steps; a list whose every entry is done means there is nothing to show
     */
    public static @NonNull List<OnboardingStep> collect() {
        return collect(AttentionCollector.collect());
    }

    /**
     * The steps, each open one presenting the attention item that states its condition ({@link #presentedBy}).
     *
     * @param attention the dashboard's attention items, unfolded
     */
    static @NonNull List<OnboardingStep> collect(@NonNull List<AttentionItem> attention) {

        List<OnboardingStep> steps = new ArrayList<>(4);

        if (HohenheimRoles.hostWorkloadsEnabled()) {
            List<Row> servers = Models.get(ServerModel.class).find().all();
            steps.add(hostEnrolled(servers));

            Microcopy placementRefusal = firstPlacementRefusal(servers);
            boolean placeable = !servers.isEmpty() && placementRefusal == null;
            steps.add(hostAcceptsWorkloads(placeable, placementRefusal));
            steps.add(backupDestination());
        }

        if (HohenheimRoles.enabled(Role.INSTANCES)) {
            steps.add(firstAppOnline());
        }

        List<OnboardingStep> presented = new ArrayList<>(steps.size());
        for (OnboardingStep step : steps) {
            AttentionItem item = presentedBy(step, attention);
            presented.add(item != null ? step.presenting(item) : step);
        }
        return presented;
    }

    /**
     * The attention item an open step presents: the first one stating that step's stage. The dashboard's fold asks the
     * same question, so an item the checklist presents is never drawn a second time in the band.
     *
     * @return the item, null for a done step or a stage no item states
     */
    static @Nullable AttentionItem presentedBy(@NonNull OnboardingStep step, @NonNull List<AttentionItem> attention) {
        if (step.isDone()) {
            return null;
        }
        for (AttentionItem item : attention) {
            if (item.stage() == step.stage()) {
                return item;
            }
        }
        return null;
    }

    /** True while any step still has something to do -- the dashboard's render condition. */
    public static boolean hasWork(@NonNull List<OnboardingStep> steps) {
        for (OnboardingStep step : steps) {
            if (!step.isDone()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Done once a host is ENROLLED, naming it ("local, Docker"): admission is the next stage's step.
     *
     * AIDEV-NOTE: D8 reversed the "done only once admitted" rule. It made this step say "enrolled but not admitted yet"
     * right above the admission step saying the same, one problem twice; the boards tick enrolment and leave admission
     * to its own step, which is BLOCKED (warning tone) while no host accepts work, so nothing reads as false progress.
     */
    private static OnboardingStep hostEnrolled(List<Row> servers) {
        if (servers.isEmpty()) {
            return new OnboardingStep(OnboardingStage.HOST, OnboardingState.TODO, "server", copy("checklist_host"),
                copy("checklist_host_detail"), listTarget("servers"));
        }
        Row first = servers.get(0);
        EnumField.EnumValue runtime = ServerModel.RUNTIME.getValues().get(first.get(ServerModel.RUNTIME));
        return new OnboardingStep(OnboardingStage.HOST, OnboardingState.DONE, "server", copy("checklist_host"),
            copy(servers.size() > 1 ? "checklist_host_enrolled_more" : "checklist_host_enrolled")
                .withArg("name", String.valueOf((Object) first.get(ServerModel.NAME)))
                .withArg("runtime", runtime != null ? FieldLabels.labelFor(runtime)
                    : Microcopy.literal(String.valueOf((Object) first.get(ServerModel.RUNTIME))))
                .withArg("more", servers.size() - 1),
            listTarget("servers"));
    }

    /**
     * Done once some host accepts a workload. While none does, the dashboard has this step present the host's own
     * attention item (its failed checks, what it holds back and Check and admit); without one, the gate's own words.
     */
    private static OnboardingStep hostAcceptsWorkloads(boolean placeable, @Nullable Microcopy refusal) {
        return new OnboardingStep(
            OnboardingStage.ADMISSION,
            placeable ? OnboardingState.DONE : OnboardingState.BLOCKED,
            // The step's SUBJECT, never its state -- the template picks the state marker.
            "shield-halved",
            copy("checklist_admit"),
            // The gate's OWN words when it refuses -- the operator reads the same sentence the
            // deploy would have produced, which is what makes this a route to the fix.
            placeable ? copy("checklist_admit_done")
                : refusal != null ? refusal : copy("checklist_admit_detail"),
            listTarget("servers"));
    }

    /**
     * Done once the control-plane backup has an off-host destination: the same fact as the attention item
     * (AttentionCollector.controlPlaneBackupDestination), which this step presents while it is open. Offered with the
     * host steps: a node with no workload tier keeps no checklist, and the attention item alone says it there.
     */
    private static OnboardingStep backupDestination() {
        String destination = ControlPlaneBackups.configuredDestinationName();
        return new OnboardingStep(
            OnboardingStage.BACKUPS,
            destination != null ? OnboardingState.DONE : OnboardingState.TODO,
            "box-archive",
            copy("checklist_backups"),
            destination != null ? copy("checklist_backups_done").withArg("target", destination)
                : copy("checklist_backups_detail"),
            AttentionCollector.controlPlaneBackupTarget());
    }

    /**
     * Done once something is online: an app that runs, or a website whose verdict serves its visitors (a redirect or a
     * proxy put online needs no workload). "Put something online" creates and starts it in one flow, so the former
     * "create an instance" and "deploy it" steps are this one step.
     *
     * AIDEV-NOTE: D7f replaced "any website at all": a site whose workload cannot start ticked this step while nothing
     * ran. The answer is the app verdict's own serving half ({@link AppHealth#anyOnline}), so the checklist and every
     * app's health band agree on what online means.
     */
    private static OnboardingStep firstAppOnline() {
        boolean online = AppHealth.anyOnline();

        return new OnboardingStep(
            OnboardingStage.FIRST_APP,
            online ? OnboardingState.DONE : OnboardingState.TODO,
            "rocket",
            copy("checklist_put_online"),
            copy("checklist_put_online_detail"),
            listTarget(PutOnlinePage.SLUG));
    }

    /**
     * The first host that refuses a shared-kernel workload, in the gate's words; null when
     * some host accepts one.
     */
    private static @Nullable Microcopy firstPlacementRefusal(List<Row> servers) {

        Microcopy first = null;

        for (Row server : servers) {

            Integer id = server.get(ServerModel.ID);

            if (id == null) {
                continue;
            }

            Microcopy refusal;

            try {
                // Null owner bucket: this asks "could ANY ordinary workload land here", which
                // is the question the checklist is about. A dedicated host answers no, honestly.
                refusal = HostAdmission.instancePlacementRefusal(id, WorkloadIsolation.SHARED_KERNEL, null);
            } catch (RuntimeException unreadable) {
                // One bad host record must never take out the dashboard.
                continue;
            }

            if (refusal == null) {
                return null;
            }

            if (first == null) {
                first = refusal;
            }
        }

        return first;
    }

    private static Microcopy copy(String key) {
        return Microcopy.of(key).withFilter("scope", "onboarding_checklist");
    }

    private static RouteTarget listTarget(String slug) {
        return CmsRoutes.list(ADMIN, slug);
    }
}
