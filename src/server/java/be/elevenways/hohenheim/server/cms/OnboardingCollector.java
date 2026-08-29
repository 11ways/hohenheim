package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.OnboardingStep;
import be.elevenways.hohenheim.instance.WorkloadIsolation;
import be.elevenways.hohenheim.model.InstanceModel;
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

    private static final String ADMIN = "admin";

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
        }

        if (HohenheimRoles.enabled(Role.INSTANCES)) {
            List<Row> instances = Models.get(InstanceModel.class).find()
                .where(InstanceModel.DELETED_AT.isNull())
                .limit(1)
                .all();
            steps.add(instanceCreated(!instances.isEmpty()));
            steps.add(instanceRunning());
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
            admitted ? OnboardingStep.DONE : OnboardingStep.TODO,
            "server",
            copy("checklist_host"),
            copy(!admitted && !servers.isEmpty() ? "checklist_host_pending" : "checklist_host_detail"),
            listTarget("servers"));
    }

    private static OnboardingStep hostAcceptsWorkloads(boolean placeable, @Nullable Microcopy refusal) {
        return new OnboardingStep(
            placeable ? OnboardingStep.DONE : OnboardingStep.BLOCKED,
            // The step's SUBJECT, never its state -- the template picks the state marker.
            "shield-halved",
            copy("checklist_admit"),
            // The gate's OWN words when it refuses -- the operator reads the same sentence the
            // deploy would have produced, which is what makes this a route to the fix.
            refusal != null ? refusal : copy("checklist_admit_detail"),
            listTarget("servers"));
    }

    private static OnboardingStep instanceCreated(boolean any) {
        return new OnboardingStep(
            any ? OnboardingStep.DONE : OnboardingStep.TODO,
            "cube",
            copy("checklist_create_instance"),
            copy("checklist_create_instance_detail"),
            listTarget("instances"));
    }

    private static OnboardingStep instanceRunning() {
        long running = Models.get(InstanceModel.class).find()
            .where(InstanceModel.DELETED_AT.isNull())
            .where(InstanceModel.STATUS.eq(InstanceModel.STATUS_RUNNING))
            .count();

        return new OnboardingStep(
            running > 0 ? OnboardingStep.DONE : OnboardingStep.TODO,
            "rocket",
            copy("checklist_deploy"),
            copy("checklist_deploy_detail"),
            listTarget("instances"));
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
