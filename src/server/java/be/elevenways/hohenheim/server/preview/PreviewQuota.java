package be.elevenways.hohenheim.server.preview;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.server.quota.ChargedDimension;
import be.elevenways.hohenheim.server.quota.ChargedModel;
import be.elevenways.hohenheim.server.quota.OwnerBudget;
import be.elevenways.hohenheim.server.quota.OwnerDimension;
import be.elevenways.hohenheim.server.quota.OwnerQuota;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Concurrent-preview quota per owner, charged through {@link ChargedModel#PREVIEWS}. A
 * preview's owner IS its application's grant-derived owner (a project is one owner), so two
 * racing webhook-triggered previews of one project contest one atomic ledger row and the cap
 * holds under concurrency. Unreadable grants fail CLOSED -- a preview whose owner cannot be
 * derived is refused, never charged to nobody.
 */
public final class PreviewQuota {

    /** One slot per live preview, charged to its application's owner on create and restore. */
    public static final ChargedDimension PREVIEWS =
        new OwnerDimension("previews", OwnerBudget.PREVIEWS, PreviewDeploymentModel.QUOTA_BUCKET) {

            @Override
            protected long heldAmount(@NonNull Row stored) {
                return 1;
            }

            @Override
            public @Nullable Charge claim(@NonNull Row row, @Nullable Row stored,
                                          @NonNull Transition transition) {
                return switch (transition) {
                    case CREATE -> new Charge(this.budget.bucketOf(ownerPackOf(row)), 1, false);
                    // A restore is a new claim on headroom (unused today; correctness anyway).
                    case RESTORE -> new Charge(this.budget.bucketOf(ownerPackOf(stored)), 1, false);
                    case REBOOK -> this.held(stored);
                };
            }
        };

    private PreviewQuota() {
    }

    public static @NonNull String bucketKeyOf(@NonNull String packedSubjects) {
        return OwnerBudget.PREVIEWS.bucketOf(packedSubjects);
    }

    /** @return the cap, or null for uncapped (0 or less in settings) */
    public static @Nullable Integer limit() {
        return OwnerBudget.PREVIEWS.limitFor("");
    }

    public static long usedBy(@NonNull String packedSubjects) {
        return OwnerBudget.PREVIEWS.usedBy(packedSubjects);
    }

    /**
     * The owner pack of the preview's APPLICATION; fails closed on unreadable grants.
     *
     * AIDEV-NOTE: the charge followed the source when the source moved (phase-0 brief 7).
     * It is the same rule as before -- the owner of the thing being deployed pays -- and it
     * has to be re-derivable from the stored row on release, which is why it reads the
     * application id off the row rather than an actor identity.
     *
     * @throws Violations {@code preview_application_required} or {@code preview_owner_unreadable}
     */
    private static @NonNull String ownerPackOf(@NonNull Row previewRow) {
        Object applicationId = previewRow.has(PreviewDeploymentModel.APPLICATION_ID.getName())
            ? previewRow.get(PreviewDeploymentModel.APPLICATION_ID.getName()) : null;
        if (!(applicationId instanceof Number number)) {
            throw Violations.ofField(PreviewDeploymentModel.APPLICATION_ID.getName(),
                applicationId,
                Microcopy.of("preview_application_required").withFilter("scope", "violations"));
        }
        String pack = OwnerQuota.currentOwnerPack(InstanceModel.MODEL_ID, number.intValue());
        if (pack == null) {
            throw Violations.ofForm(Microcopy.of("preview_owner_unreadable")
                .withFilter("scope", "violations"));
        }
        return pack;
    }
}
