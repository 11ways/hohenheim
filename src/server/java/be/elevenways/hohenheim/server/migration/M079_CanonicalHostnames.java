package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.proxy.RouteClaims;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;

/**
 * Heal-then-constrain for the two hostname spellings that were storable before
 * {@code SiteDomainModel} validated one: an FQDN carrying its root dot, and a glob-shaped
 * hostname parked under a {@code match_type} that does not match the tier it routes in.
 *
 * AIDEV-NOTE: the constraint half (the model's beforeValidate refusal) would otherwise
 * refuse the NEXT edit of a row that was already stored, which turns a security fix into an
 * operator lockout. Both spellings are rewritten to the canonical form the new code will
 * derive anyway -- the dotted name already routed as the dotless one at request time, and
 * the glob-shaped row already routed in the wildcard tier, so this migration changes what
 * is STORED, never what is served.
 *
 * AIDEV-NOTE: the claim column has to be re-derived rather than rewritten in place. Folding
 * a root dot can make two rows spell the SAME claim key, which the M045 unique index
 * refuses; nulling every claim and re-running {@link RouteClaims#backfill} re-arbitrates the
 * whole table under the exact first-wins rule the dispatcher uses, and leaves the loser of a
 * newly-collapsed pair unclaimed (unreachable already, and refused with the real conflict
 * message on its next edit).
 *
 * AIDEV-NOTE: lives in the SERVER source set, like M045, because it must spell the claim key
 * through RouteClaims -- the one authority the dispatcher uses. A second key derivation in
 * common would be free to drift.
 *
 * RESIDUE, deliberate: a hostname that is invalid for reasons this cannot repair (an
 * underscore, an empty label, an over-long label) is LOGGED and left alone. Rewriting it
 * would be inventing a route nobody configured; the row keeps serving what it served and its
 * next edit is refused with {@code hostname_invalid}.
 *
 * @author Jelle De Loecker
 */
public class M079_CanonicalHostnames extends HohenheimMigration {

    public M079_CanonicalHostnames() {
        super("2026_08_26_100000", "Canonical hostnames and honest match types");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.data("Fold root-dot hostnames and align drifted match types", "1",
            M079_CanonicalHostnames::healDomains);
        schema.data("Fold root-dot hostnames in the released-claim ledger", "1",
            M079_CanonicalHostnames::healLedger);
        schema.data("Re-derive every live route claim from the healed hostnames", "1",
            M079_CanonicalHostnames::restampClaims);
    }

    @Override
    public void down(MigrationBuilder schema) {
        // Nothing to undo: the canonical spelling is a strict subset of what was storable
        // before, and the pre-fold spelling is not recoverable (nor was it ever routed).
    }

    /** @return the number of domain rows whose hostname or match type was rewritten */
    private static int healDomains(@NonNull Datasource datasource) {
        // Fresh instances, not Models.get: a migration may run long before the model
        // singletons are registered (the M045 shape).
        Model domains = new SiteDomainModel();
        int healed = 0;
        int unrepairable = 0;
        for (Row domain : domains.find().on(datasource).all()) {
            Integer id = domain.get(SiteDomainModel.ID);
            String hostname = domain.get(SiteDomainModel.HOSTNAME);
            String matchType = domain.get(SiteDomainModel.MATCH_TYPE);
            if (id == null || hostname == null) {
                continue;
            }
            String canonical = SiteDomainModel.canonicalHostname(hostname, matchType);
            String tier = SiteDomainModel.effectiveMatchType(canonical, matchType);
            boolean hostnameMoved = !Objects.equals(canonical, hostname);
            // A regex column is never re-tiered: its source is a pattern, not a name, and
            // its shape says nothing about which tier it belongs in.
            boolean tierMoved = !SiteDomainModel.MATCH_REGEX.equals(matchType)
                && !tier.equals(matchType);
            if (!hostnameMoved && !tierMoved) {
                if (!isSpellable(canonical, matchType)) {
                    unrepairable++;
                }
                continue;
            }
            domains.find().on(datasource).where(SiteDomainModel.ID.eq(id))
                .assign(SiteDomainModel.HOSTNAME, canonical)
                .assign(SiteDomainModel.MATCH_TYPE, tier)
                .updateAll();
            healed++;
            Blast.log("M079: site domain", id, "healed", hostname, "/", matchType,
                "->", canonical, "/", tier);
        }
        if (unrepairable > 0) {
            Blast.log("M079:", unrepairable, "domain hostnames stay syntactically invalid and"
                + " were left untouched; their next edit will be refused");
        }
        return healed;
    }

    /** @return the number of ledger rows whose hostname or claim key was rewritten */
    private static int healLedger(@NonNull Datasource datasource) {
        Model ledger = new ReleasedRouteClaimModel();
        int healed = 0;
        for (Row claim : ledger.find().on(datasource).all()) {
            Integer id = claim.get(ReleasedRouteClaimModel.ID);
            String hostname = claim.get(ReleasedRouteClaimModel.HOSTNAME);
            String key = claim.get(ReleasedRouteClaimModel.CLAIM_KEY);
            String matchType = claim.get(ReleasedRouteClaimModel.MATCH_TYPE);
            if (id == null || hostname == null || key == null) {
                continue;
            }
            String canonical = SiteDomainModel.canonicalHostname(hostname, matchType);
            // Rebuilt through RouteClaims so the key keeps ONE spelling; only the hostname
            // component can have moved, the other two were always canonical.
            String canonicalKey = RouteClaims.keyOf(canonical, matchType,
                RouteClaims.pathOf(key), String.join(",", RouteClaims.listenersOf(key)));
            if (Objects.equals(canonical, hostname) && Objects.equals(canonicalKey, key)) {
                continue;
            }
            ledger.find().on(datasource).where(ReleasedRouteClaimModel.ID.eq(id))
                .assign(ReleasedRouteClaimModel.HOSTNAME, canonical)
                .assign(ReleasedRouteClaimModel.CLAIM_KEY, canonicalKey)
                .updateAll();
            healed++;
        }
        return healed;
    }

    /** @return the number of rows left unclaimed because an earlier row already held their route */
    private static int restampClaims(@NonNull Datasource datasource) {
        new SiteDomainModel().find().on(datasource)
            .assign(SiteDomainModel.LIVE_ROUTE_KEY, null)
            .updateAll();
        return RouteClaims.backfill(datasource);
    }

    /**
     * Whether the model's write-time refusal would accept this spelling -- asked by RUNNING
     * that refusal, so the migration's report can never disagree with the constraint it is
     * healing for.
     */
    private static boolean isSpellable(String hostname, String matchType) {
        try {
            SiteDomainModel.validateHostnameSyntax(hostname, matchType);
            return true;
        } catch (Violations refused) {
            return false;
        }
    }
}
