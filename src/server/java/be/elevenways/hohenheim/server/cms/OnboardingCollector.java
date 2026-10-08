package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
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
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the dashboard readiness checklist: enrol a host, make it accept workloads, create
 * an instance, deploy it.
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

        return steps;
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
     * Done only once a host is ADMITTED: an enrolled host that is still blocked has not
     * been brought into the fleet, and a green first step above a blocked second one read
     * as progress that had not happened.
     */
    private static OnboardingStep hostEnrolled(List<Row> servers) {
        boolean admitted = false;
        for (Row server : servers) {
            if (ServerModel.ADMISSION_ADMITTED.equals(server.get(ServerModel.ADMISSION))) {
                admitted = true;
                break;
            }
        }
        return new OnboardingStep(
            admitted ? OnboardingState.DONE : OnboardingState.TODO,
            "server",
            copy("checklist_host"),
            copy(!admitted && !servers.isEmpty() ? "checklist_host_pending" : "checklist_host_detail"),
            listTarget("servers"));
    }

    private static OnboardingStep hostAcceptsWorkloads(boolean placeable, @Nullable Microcopy refusal) {
        return new OnboardingStep(
            placeable ? OnboardingState.DONE : OnboardingState.BLOCKED,
            // The step's SUBJECT, never its state -- the template picks the state marker.
            "shield-halved",
            copy("checklist_admit"),
            // The gate's OWN words when it refuses -- the operator reads the same sentence the
            // deploy would have produced, which is what makes this a route to the fix.
            refusal != null ? refusal : copy("checklist_admit_detail"),
            listTarget("servers"));
    }

    /**
     * Done once the control-plane backup has an off-host destination: the same fact and the same place to fix it as the
     * attention item (AttentionCollector.controlPlaneBackupDestination), so the two can never disagree. Offered with the
     * host steps: a node with no workload tier keeps no checklist, and the attention item alone says it there.
     */
    private static OnboardingStep backupDestination() {
        boolean chosen = ControlPlaneBackups.configuredDestinationName() != null;
        return new OnboardingStep(
            chosen ? OnboardingState.DONE : OnboardingState.TODO,
            "box-archive",
            copy("checklist_backups"),
            copy("checklist_backups_detail"),
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
