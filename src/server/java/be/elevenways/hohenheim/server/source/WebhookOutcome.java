package be.elevenways.hohenheim.server.source;

import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * What a git webhook delivery caused, stamped on its {@code webhook_deliveries} row and read back by the Deploys tab's
 * recent pushes.
 *
 * AIDEV-NOTE: the stored token is the delivery row's {@code action} column, so a token is data: renaming one orphans
 * every stored row. The label is the operator's sentence for it, keyed by the token in the {@code webhook_outcome}
 * scope.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public enum WebhookOutcome {

    DEPLOY_QUEUED("deploy_queued", true),
    PREVIEW_QUEUED("preview_queued", true),
    PREVIEW_TEARDOWN_QUEUED("preview_teardown_queued", true),
    REPOSITORY_MISMATCH("repository_mismatch", false),
    IGNORED_NOT_A_PUSH("ignored_not_a_push", false),
    IGNORED_DELETED_REF("ignored_deleted_ref", false),
    IGNORED_INVALID_REF("ignored_invalid_ref", false),
    IGNORED_BRANCH("ignored_branch", false),
    IGNORED_AUTO_DEPLOY("ignored_auto_deploy", false),
    IGNORED_PREVIEWS_DISABLED("ignored_previews_disabled", false),
    IGNORED_MALFORMED_PR("ignored_malformed_pr", false),
    IGNORED_PR_ACTION("ignored_pr_action", false);

    private final String token;
    private final boolean acted;

    WebhookOutcome(@NonNull String token, boolean acted) {
        this.token = token;
        this.acted = acted;
    }

    /** The value stored in the delivery row's action column. */
    public @NonNull String token() {
        return this.token;
    }

    /** Whether the delivery started work (a deploy or a preview change) rather than being refused or ignored. */
    public boolean acted() {
        return this.acted;
    }

    public @NonNull Microcopy label() {
        return Microcopy.of(this.token).withFilter("scope", "webhook_outcome");
    }

    /** @return the outcome a stored action token names, or null for a delivery still being decided */
    public static @Nullable WebhookOutcome ofToken(@Nullable String token) {
        if (token == null) {
            return null;
        }
        for (WebhookOutcome outcome : values()) {
            if (outcome.token.equals(token)) {
                return outcome;
            }
        }
        return null;
    }
}
