package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * The /manage view over projects: the tenant sees exactly the projects they are a
 * MEMBER of, with the same derived columns the admin resource shows (member count,
 * owned sites and instances, the project's instance quota). The generated
 * RowResource floor -- list, sort, filter, paginate -- with every write surface off.
 *
 * READ-ONLY BY DECISION, and the decision is a delegation one, not a UX one. Every
 * membership change is a {@code group.<slug>} grant write, which
 * {@code GrantAdministration.requireAuthorizedDiff} governs: it demands the explicit
 * {@code auth.grants.manage} boundary, which is declared NON-delegable, and it
 * refuses self-edits outright. A tenant therefore holds nothing that could authorize
 * add, remove or leave -- a member-editing surface here would be a surface that
 * always refuses, which is exactly the "delegation surface nobody designed" the plan
 * warns about. Membership stays edited through the zenit-auth grant editors (ONE UI
 * over those grants), and this panel reports what those editors decided.
 *
 * AIDEV-NOTE: renaming is admin-only for the same reason it is not cosmetic --
 * ProjectGuards mirrors the name onto the backing permission group's TITLE, so a
 * rename here would be a tenant editing an auth-tier record's label.
 */
public final class ManageProjectResource extends ProjectResource {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "manage_project"); }
    @Override public @NonNull String slug() { return "projects"; }
    @Override public int navOrder() { return 40; }

    @Override public boolean creatable() { return false; }
    @Override public boolean updatable() { return false; }
    @Override public boolean deletable() { return false; }

    /**
     * The operator resource's quick-add bar and inline cells are dropped WITH the write
     * surfaces they belong to.
     *
     * AIDEV-NOTE: these two are not decoration. Registration REFUSES a resource that
     * declares quickCreate() while creatable() is false, or inlineEditableFields() while
     * updatable() is false -- so this mirror would fail to register at boot without them.
     * That refusal is the mechanism working: a delegated peer inherits every declaration
     * of its base, and a narrowing subclass must say what it narrows.
     */
    @Override public @Nullable QuickCreateSpec quickCreate() { return null; }
    @Override public @NonNull List<Field<?, ?>> inlineEditableFields() { return List.of(); }

    /** Deliberately NOT frameworkSubpages(): activity and revision history are admin history. */
    @Override public @NonNull List<RecordScopedPage<Row>> subpages() { return List.of(); }

    /**
     * Admins see every project; everyone else exactly the ones THE visibility policy
     * enumerates -- membership, narrowed by an API key's scopes. An out-of-scope id
     * reads as MISSING (zenit-cms 404s an out-of-scope load), never as forbidden.
     */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> {
            Criteria scope = Projects.visibleScope(ctx);
            return scope == null ? AccessDecision.allowAll()
                : AccessDecision.allow(QueryPredicate.of(scope));
        };
    }

    /** NAV-ONLY (a principal in no project hides the empty list); the route stays scoped. */
    @Override
    public boolean hasInScopeRecords(@NonNull AccessContext access) {
        return !Projects.visibleTo(access).isEmpty();
    }

    /**
     * No inherited related pages: the operator resource names sibling peers of the ADMIN
     * panel, which this panel does not register at all -- inheriting one would fail
     * ManagePanel's own registration walk, and a 403 link is worse than no link.
     */
    @Override public @NonNull List<RelatedPage> relatedPages() { return List.of(); }

}
