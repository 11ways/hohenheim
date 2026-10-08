package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeGlobal;

/**
 * The stages of the dashboard readiness checklist, one step each: the declaring home of what a step is about.
 *
 * AIDEV-NOTE: an attention item that states the very condition a stage's open step stands for declares that stage
 * ({@link AttentionItem#stage}), and while the step is open the step presents that item instead of the band repeating
 * it (DashboardAttention). The relation is this member, never a comparison of titles or catalog keys.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@HawkeyeGlobal(namespace = "OnboardingStages")
public enum OnboardingStage {

    /** A machine to run on is enrolled. */
    HOST,

    /** Some enrolled host accepts workloads. */
    ADMISSION,

    /** The control-plane backup has an off-host destination. */
    BACKUPS,

    /** Something is online. */
    FIRST_APP
}
