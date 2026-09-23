package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.annotation.ZenitAutoLoad;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.WidgetRegistry;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The declaring home of hohenheim's own widget types, registered at class-load.
 *
 * AIDEV-NOTE: each id below is STORED in widget trees; renaming one orphans every stored tree
 * that places it. App-local because the markup is about hohenheim's own evidence and nothing
 * generic renders it; "the page is a tree" never meant "every card is a built-in".
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@ZenitAutoLoad
public final class HohenheimWidgets {

    /**
     * Dashboard attention panel: operational states that need an operator (error certificates,
     * down/degraded sites, failed databases, failed latest deploys, failed task runs).
     */
    public static final DisplayWidget ATTENTION = register("attention", "cms/widget-attention",
        Microcopy.of("attention").withFilter("scope", "dashboard"), "bell");

    /** First-run guidance shown while the installation has no sites. */
    public static final DisplayWidget ONBOARDING = register("onboarding", "cms/widget-onboarding",
        Microcopy.of("onboarding").withFilter("scope", "dashboard"), "rocket");

    /**
     * Dashboard readiness checklist: the ordered steps between a fresh install and a deployed
     * workload, each one derived from the REAL gate rather than restating it. The dashboard omits
     * the whole band once every step is done, so it retires itself.
     */
    public static final DisplayWidget ONBOARDING_CHECKLIST = register("onboarding_checklist",
        "cms/widget-onboarding-checklist",
        Microcopy.of("checklist_title").withFilter("scope", "onboarding_checklist"), "list-check");

    /**
     * The host's live contact state: a status dot, the state word and the last-contact relative time.
     *
     * AIDEV-NOTE: deliberately NOT folded into {@code zenitwidget:status}. That widget renders
     * BADGES, and this cell's whole point is that it is not one -- the dot carries the verdict and
     * the relative time carries when it was last true. Reducing it to a pill would drop the
     * timestamp, which is the half an operator reads.
     */
    public static final DisplayWidget HOST_STATE = register("host_state", "cms/widget-host-state",
        Microcopy.of("state").withFilter("scope", "server_overview"), "tower-broadcast");

    /** Per-lane trust state: the pinned fingerprint, what the machine offers now, and this controller's own client material. */
    public static final DisplayWidget HOST_TRUST = register("host_trust", "cms/widget-host-trust",
        Microcopy.of("trust_ssh").withFilter("scope", "server_overview"), "key");

    /** The stored preflight report: kernel-truth isolation, every check with its own stamp, and the measured facts. */
    public static final DisplayWidget HOST_PREFLIGHT = register("host_preflight", "cms/widget-host-preflight",
        Microcopy.of("preflight_report").withFilter("scope", "server_overview"), "stethoscope");

    /** Everything this host carries: the same three populations that block cordon, drain and delete. */
    public static final DisplayWidget HOST_WORKLOADS = register("host_workloads", "cms/widget-host-workloads",
        Microcopy.of("workloads").withFilter("scope", "server_overview"), "cubes");

    /** Every port claim an instance holds, joined to its host's declared address. */
    public static final DisplayWidget INSTANCE_ENDPOINTS = register("instance_endpoints",
        "cms/widget-instance-endpoints",
        Microcopy.of("endpoint").withFilter("scope", "instance_overview"), "plug");

    private HohenheimWidgets() {
    }

    private static @NonNull DisplayWidget register(@NonNull String id, @NonNull String template,
                                                   @NonNull Microcopy label, @NonNull String icon) {
        DisplayWidget widget = new DisplayWidget(Identifier.of("hohenheim", id), label, Icon.of(icon),
            Identifier.of("hohenheim", template));
        WidgetRegistry.INSTANCE.register(widget);
        return widget;
    }
}
