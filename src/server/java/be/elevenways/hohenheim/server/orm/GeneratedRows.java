package be.elevenways.hohenheim.server.orm;

import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * THE system-write scope for rows nothing operator-facing authored, shared by every
 * generated-row family (DNS records via {@code GeneratedDnsRecords}, instance config
 * files via {@code GeneratedInstanceFiles}), so ONE scope covers a materialization that
 * writes both.
 *
 * @author Jelle De Loecker
 */
public final class GeneratedRows {

    private GeneratedRows() {
    }

    /**
     * What a generated row is, and what declared it.
     *
     * @param source   a token this codebase owns ({@code "acme"}, {@code "game_domain"});
     *                 doubles as the Accountability origin of the scope
     * @param forModel model id of the DECLARING record, null when nothing declares it
     * @param forId    primary key of the declaring record
     */
    public record Attribution(@NonNull String source, @Nullable String forModel,
                              @Nullable Integer forId) {
    }

    /** A scope body that may fail the way the write it wraps fails. */
    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    private record Scope(@Nullable Attribution attribution) {
    }

    private static final ThreadLocal<@Nullable Scope> ACTIVE = new ThreadLocal<>();

    /**
     * Run a generated write: every guarded row written inside is stamped with
     * {@code attribution}, whatever the caller staged.
     */
    public static void as(@NonNull Attribution attribution, @NonNull Body body) throws Exception {
        enter(new Scope(attribution), attribution.source(), body);
    }

    /**
     * Run a system REMOVAL of generated rows without authoring any: the declaring
     * container is going away and takes its generated rows with it. It grants no
     * authority to write attribution, only to remove rows that already carry it.
     */
    public static void sweeping(@NonNull String origin, @NonNull Runnable body) {
        try {
            enter(new Scope(null), origin, body::run);
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * AIDEV-NOTE: the scope is a ThreadLocal, so a generated write that hops threads must
     * re-enter it, exactly like {@link Accountability}. Entering it also enters the
     * source's accountability origin, because a system write on a tenant's behalf must
     * attribute to the system and not to whoever happens to be logged in on another thread.
     */
    private static void enter(@NonNull Scope scope, @NonNull String origin,
                              @NonNull Body body) throws Exception {
        Scope outer = ACTIVE.get();
        ACTIVE.set(scope);
        try {
            Exception[] failure = new Exception[1];
            Accountability.runAs(new Accountability(null, null, null, null, origin), () -> {
                try {
                    body.run();
                } catch (Exception e) {
                    failure[0] = e;
                }
            });
            if (failure[0] != null) {
                throw failure[0];
            }
        } finally {
            if (outer == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(outer);
            }
        }
    }

    /** @return true when a system scope over generated rows is active on this thread */
    public static boolean inSystemScope() {
        return ACTIVE.get() != null;
    }

    private static @Nullable Attribution stamping() {
        Scope scope = ACTIVE.get();
        return scope != null ? scope.attribution() : null;
    }

    /**
     * The attribution the active scope stamps onto guarded rows, or null when no scope is
     * active (or when the active one is a {@link #sweeping} removal, which authors nothing).
     *
     * AIDEV-NOTE: public so a policy hook can read WHO a system write is being performed FOR
     * without depending on hook ORDER. The alternative -- reading the stamped columns off the
     * row -- only works if the stamping beforeWrite hook happens to have run first, and both
     * are beforeWrite hooks on the same schema. The ambient scope is the SOURCE those columns
     * are derived from, so reading it is reading the same answer one step earlier.
     */
    public static @Nullable Attribution currentAttribution() {
        return stamping();
    }

    /** The four attribution columns of one guarded model. */
    public record Columns(@NonNull StringField by, @NonNull StringField forModel,
                          @NonNull IntegerField forId, @NonNull DateTimeField at) {
    }

    /**
     * Install the attribution invariant on one model's write pipeline: in scope the four
     * columns are DERIVED; out of scope a row that carries them is read-only and a write
     * that would move them is refused rather than silently stripped.
     */
    public static void installGuards(@NonNull Schema schema,
                                     @NonNull Class<? extends Model> modelClass,
                                     @NonNull Columns columns,
                                     @NonNull String readOnlyKey,
                                     @NonNull String attributionKey) {
        schema.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            Model model = Models.get(modelClass);
            String pkName = model.getPrimaryKeyField().getName();
            Attribution attribution = stamping();
            if (attribution != null) {
                row.set(columns.by(), attribution.source());
                row.set(columns.forModel(), attribution.forModel());
                row.set(columns.forId(), attribution.forId());
                if (!row.has(pkName)) {
                    row.set(columns.at(), Instant.now());
                }
                return;
            }
            Row stored = row.has(pkName) ? model.findById(row.get(pkName)) : null;
            if (stored != null && stored.get(columns.by()) != null) {
                throw readOnly(columns, stored, readOnlyKey);
            }
            if (changesAttribution(row, stored, columns)) {
                throw Violations.ofField(columns.by().getName(), row.get(columns.by()),
                    CmsSupport.violationText(attributionKey));
            }
        });
        schema.addBeforeRemoveHook(context -> {
            if (inSystemScope()) {
                return;
            }
            for (Row doomed : doomedRows(context)) {
                if (doomed.get(columns.by()) != null) {
                    throw readOnly(columns, doomed, readOnlyKey);
                }
            }
        });
    }

    /**
     * The rows a criteria delete is about to remove.
     *
     * AIDEV-NOTE: a remove context carries CRITERIA, not rows, so the guard re-runs the
     * same query -- the ActivityLog.captureDoomed idiom. Enforcing on a resource's delete
     * method instead would leave every criteria delete outside the guard, which is the
     * whole point of the funnel.
     */
    private static @NonNull List<Row> doomedRows(@NonNull RemoveFromDatasource context) {
        Model model = context.getModel();
        QueryContext queryContext = context.getQueryContext();
        if (model == null || queryContext == null) {
            return List.of();
        }
        return model.executeFindQuery(new QueryContext(
            queryContext.getCriteria(), List.of(), null, null, List.of(), null,
            queryContext.getRelatedFilters(), queryContext.getLocaleChain(),
            queryContext.isAcrossLocales(), true, true, queryContext.getHints()));
    }

    private static @NonNull Violations readOnly(@NonNull Columns columns, @NonNull Row stored,
                                                @NonNull String readOnlyKey) {
        return Violations.ofField(columns.by().getName(), stored.get(columns.by()),
            CmsSupport.violationText(readOnlyKey)
                .withArg("source", String.valueOf((Object) stored.get(columns.by()))));
    }

    /**
     * Whether an unscoped write would MOVE any attribution column.
     *
     * AIDEV-NOTE: presence of the key is not the question. Every writer that loads a row
     * and saves it back carries all four columns with their stored values, so a presence
     * check would refuse every ordinary save of an ordinary record. What a caller may not
     * do is CHANGE them -- set one, or clear one.
     */
    private static boolean changesAttribution(@NonNull Row row, @Nullable Row stored,
                                              @NonNull Columns columns) {
        return differs(row, stored, columns.by().getName())
            || differs(row, stored, columns.forModel().getName())
            || differs(row, stored, columns.forId().getName())
            || differs(row, stored, columns.at().getName());
    }

    private static boolean differs(@NonNull Row row, @Nullable Row stored, @NonNull String field) {
        if (!row.has(field)) {
            return false;
        }
        return !Objects.equals(row.get(field), stored != null ? stored.get(field) : null);
    }
}
