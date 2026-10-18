package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.app.UsageLine;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.quota.OwnerBudget;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What a tenant holds of each budget, against the cap the operator set for them, for the /manage landing's usage card.
 *
 * AIDEV-NOTE: read against the tenant's CREATION owner, the bucket a create of theirs is charged to
 * (OwnerQuota.creationOwnerPack). A record an operator made and then granted to the tenant is charged to the
 * operator, so it does not count here; the card answers "how much may I still create", which is what a cap limits.
 *
 * AIDEV-NOTE: the listed budgets (apps, memory, disk, databases, previews) are drawn whether capped or not: an
 * uncapped one reads "No limit" and states no amount, since an amount beside "no limit" would read as what the tenant
 * holds while a granted app is charged to the operator. The card once showed only the one default-capped budget
 * (previews) and so said nothing about apps. Websites, which the list leaves out, show only when capped.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
final class TenantUsage {

    private TenantUsage() {
    }

    /** @return one line per budget the card shows on a tier this node runs, none for an operator */
    static @NonNull List<UsageLine> of(@NonNull AccessContext access) {
        if (HohenheimAccess.isAdmin(access)) {
            return List.of();
        }
        String pack = HohenheimAccess.packSubjects(HohenheimAccess.creationOwnerSubjects(access));
        List<UsageLine> lines = new ArrayList<>();
        for (OwnerBudget budget : OwnerBudget.values()) {
            Shown shown = shownAs(budget);
            if (shown == null || !HohenheimRoles.enabled(shown.role())) {
                continue;
            }
            Microcopy label = HohenheimMicrocopy.TENANT_USAGE.of(shown.key());
            Integer limit = budget.limitFor(pack);
            if (limit == null) {
                if (shown.uncapped()) {
                    lines.add(UsageLine.uncapped(label));
                }
                continue;
            }
            long used = budget.usedBy(pack);
            int percent = limit <= 0 ? 100 : (int) Math.min(100, used * 100 / limit);
            lines.add(new UsageLine(label, budget.worded(used), budget.worded(limit), budget.isSize(), percent, true));
        }
        return lines;
    }

    /** How the card words one budget, or null for one it leaves out; every member answers (no default). */
    private static @Nullable Shown shownAs(@NonNull OwnerBudget budget) {
        return switch (budget) {
            case INSTANCES -> new Shown("instances", Role.INSTANCES, true);
            case OWNER_MEMORY -> new Shown("memory", Role.INSTANCES, true);
            case DISK_GB -> new Shown("disk", Role.INSTANCES, true);
            // An extra network interface is a placement detail a tenant never asks for by name.
            case NICS -> null;
            // Not one of the listed budgets: shown only where the operator capped it.
            case SITES -> new Shown("sites", Role.PROXY, false);
            case DATABASES -> new Shown("databases", Role.DATABASES, true);
            case PREVIEWS -> new Shown("previews", Role.PROXY, true);
        };
    }

    /**
     * @param key  the label's key under {@code tenant_usage}
     * @param role the tier the budget belongs to
     * @param uncapped whether the card shows the budget while the operator set no cap ("No limit")
     */
    private record Shown(@NonNull String key, @NonNull Role role, boolean uncapped) {
    }
}
