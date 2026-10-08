package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
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
@BlastAutoLoad
public final class HohenheimWidgets {

    /**
     * Dashboard attention panel: operational states that need an operator (error certificates,
     * down/degraded sites, failed databases, failed latest deploys, failed task runs).
     */
    public static final DisplayWidget ATTENTION = register("attention", HohenheimTemplateIds.WIDGET_ATTENTION,
        Microcopy.of("attention").withFilter("scope", "dashboard"), "bell");

    /** First-run guidance shown while the installation has no sites. */
    public static final DisplayWidget ONBOARDING = register("onboarding", HohenheimTemplateIds.WIDGET_ONBOARDING,
        Microcopy.of("onboarding").withFilter("scope", "dashboard"), "rocket");

    /**
     * Dashboard readiness checklist: the ordered steps between a fresh install and a deployed
     * workload, each one derived from the REAL gate rather than restating it. The dashboard omits
     * the whole band once the first app is online (or every step is done), so it retires itself.
     */
    public static final DisplayWidget ONBOARDING_CHECKLIST = register("onboarding_checklist",
        HohenheimTemplateIds.WIDGET_ONBOARDING_CHECKLIST,
        Microcopy.of("checklist_title").withFilter("scope", "onboarding_checklist"), "list-check");

    /**
     * The host's live contact state: a status dot, the state word and the last-contact relative time.
     *
     * AIDEV-NOTE: deliberately NOT folded into {@code zenit:status}. That widget renders
     * BADGES, and this cell's whole point is that it is not one -- the dot carries the verdict and
     * the relative time carries when it was last true. Reducing it to a pill would drop the
     * timestamp, which is the half an operator reads.
     */
    public static final DisplayWidget HOST_STATE = register("host_state", HohenheimTemplateIds.WIDGET_HOST_STATE,
        Microcopy.of("state").withFilter("scope", "server_overview"), "tower-broadcast");

    /** Per-lane trust state: the pinned fingerprint, what the machine offers now, and this controller's own client material. */
    public static final DisplayWidget HOST_TRUST = register("host_trust", HohenheimTemplateIds.WIDGET_HOST_TRUST,
        Microcopy.of("trust_ssh").withFilter("scope", "server_overview"), "key");

    /** The stored preflight report: kernel-truth isolation, every check with its own stamp, and the measured facts. */
    public static final DisplayWidget HOST_PREFLIGHT = register("host_preflight",
        HohenheimTemplateIds.WIDGET_HOST_PREFLIGHT,
        Microcopy.of("preflight_report").withFilter("scope", "server_overview"), "stethoscope");

    /** Everything this host carries: the same three populations that block cordon, drain and delete. */
    public static final DisplayWidget HOST_WORKLOADS = register("host_workloads",
        HohenheimTemplateIds.WIDGET_HOST_WORKLOADS,
        Microcopy.of("workloads").withFilter("scope", "server_overview"), "cubes");

    /** Every port claim an instance holds, joined to its host's declared address. */
    public static final DisplayWidget INSTANCE_ENDPOINTS = register("instance_endpoints",
        HohenheimTemplateIds.WIDGET_INSTANCE_ENDPOINTS,
        Microcopy.of("endpoint").withFilter("scope", "instance_overview"), "plug");

    /** An app's addresses with whether HTTPS works for each: the body of the overview's Addresses card. */
    public static final DisplayWidget APP_ADDRESSES = register("app_addresses", HohenheimTemplateIds.WIDGET_APP_ADDRESSES,
        Microcopy.of("addresses").withFilter("scope", "app_overview"), "globe");

    /** An app's protected paths, an open one said as open: the body of the overview's Protection card. */
    public static final DisplayWidget APP_PROTECTION = register("app_protection",
        HohenheimTemplateIds.WIDGET_APP_PROTECTION,
        Microcopy.of("protection").withFilter("scope", "app_overview"), "lock");

    /** The dashboard's Apps band (board Main): each app with what it is, where, and whether HTTPS works. */
    public static final DisplayWidget APPS = register("apps", HohenheimTemplateIds.WIDGET_APPS,
        Microcopy.of("apps").withFilter("scope", "dashboard"), "cubes");

    /**
     * The admin dashboard's count tile (board Main): a count with the line saying what it holds, which the framework's
     * record-count tile cannot say (an app is a reading, not a record; "2 of 3" is no count of one source).
     */
    public static final DisplayWidget STAT = register("stat", HohenheimTemplateIds.WIDGET_STAT,
        Microcopy.of("stat_tile").withFilter("scope", "dashboard"), "gauge");

    /** The /manage landing's usage card (board Manage-Home): each capped budget of the tenant against its cap. */
    public static final DisplayWidget TENANT_USAGE = register("tenant_usage", HohenheimTemplateIds.WIDGET_TENANT_USAGE,
        Microcopy.of("title").withFilter("scope", "tenant_usage"), "gauge");

    private HohenheimWidgets() {
    }

    private static @NonNull DisplayWidget register(@NonNull String id, @NonNull Identifier template,
                                                   @NonNull Microcopy label, @NonNull String icon) {
        DisplayWidget widget = new DisplayWidget(HohenheimIds.id(id), label, Icon.of(icon), template);
        WidgetRegistry.INSTANCE.register(widget);
        return widget;
    }
}
