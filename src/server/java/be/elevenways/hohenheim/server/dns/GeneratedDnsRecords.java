package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The system-write scope for DNS records nothing operator-facing authored, and the write
 * invariant that makes its attribution DERIVED rather than submitted.
 *
 * A generated row records WHO made it ({@code generated_by}), WHICH record authorized it
 * ({@code generated_for_model} / {@code generated_for_id}) and WHEN. That is enough for the
 * reclaim doctrine {@code DockerReclaim} already states one seam over, transposed to rows:
 * a sweep may remove a generated row only when {@code generated_by} names a source THIS
 * codebase owns AND the declaring record is gone. A row with NO attribution is never swept
 * automatically -- it may just as well be an operator's, and an unattributable resource is
 * only ever reclaimed on an explicit operator action. There is deliberately no sweeper yet:
 * {@code InternalDnsTxtPublisher.cleanup} removes its own row by exact tuple, and a sweeper
 * with no orphan to sweep would be scaffolding.
 *
 * AIDEV-NOTE: attribution is NEVER a unique key. Two concurrent ACME orders for the same
 * name legitimately publish two TXT rows with identical attribution, and a unique key would
 * make the second order fail for the first order's benefit.
 *
 * AIDEV-NOTE: the scope is a ThreadLocal, so a generated write that hops threads must
 * re-enter it, exactly like {@link Accountability}. Entering it also enters the
 * {@code "acme"} accountability origin, because a system write on a tenant's behalf must
 * attribute to the system and not to whoever happens to be logged in on another thread.
 *
 * @author Jelle De Loecker
 */
public final class GeneratedDnsRecords {

    /** {@code generated_by} token for the ACME DNS-01 publisher. */
    public static final String SOURCE_ACME = "acme";

    /** Accountability origin for the writes this scope covers. */
    public static final String ORIGIN_ACME = "acme";

    private static volatile boolean installed;

    private GeneratedDnsRecords() {
    }

    /**
     * What a generated row is, and what declared it.
     *
     * @param source   a token this codebase owns, e.g. {@link #SOURCE_ACME}
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
     * Run a generated write: every DNS record written inside is stamped with
     * {@code attribution}, whatever the caller staged.
     */
    public static void as(@NonNull Attribution attribution, @NonNull Body body) throws Exception {
        enter(new Scope(attribution), body);
    }

    /**
     * Run a system REMOVAL of generated rows without authoring any: the declaring container
     * is going away and takes its generated rows with it (a zone delete). It grants no
     * authority to write attribution, only to remove rows that already carry it.
     */
    public static void sweeping(@NonNull Runnable body) {
        try {
            enter(new Scope(null), body::run);
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void enter(@NonNull Scope scope, @NonNull Body body) throws Exception {
        Scope outer = ACTIVE.get();
        ACTIVE.set(scope);
        try {
            Exception[] failure = new Exception[1];
            Accountability.runAs(new Accountability(null, null, null, null, ORIGIN_ACME), () -> {
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
     * Install the attribution invariant on the DnsRecordModel write pipeline.
     *
     * AIDEV-NOTE: the write pipeline, not the resource or the API handler, for the same
     * reason SiteDomainResource.installRouteInvariant gives: the CMS form, the peer API, the
     * zone-file import and any direct model.save all pass here and nothing else does. A
     * caller-supplied marker is REFUSED rather than silently stripped -- a write that claims
     * system ownership must never look like it succeeded.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        DnsRecordModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            Attribution attribution = stamping();
            if (attribution != null) {
                row.set(DnsRecordModel.GENERATED_BY, attribution.source());
                row.set(DnsRecordModel.GENERATED_FOR_MODEL, attribution.forModel());
                row.set(DnsRecordModel.GENERATED_FOR_ID, attribution.forId());
                if (!row.has(DnsRecordModel.ID.getName())) {
                    row.set(DnsRecordModel.GENERATED_AT, Instant.now());
                }
                return;
            }
            Row stored = storedRowOf(row);
            if (stored != null && stored.get(DnsRecordModel.GENERATED_BY) != null) {
                throw readOnly(stored);
            }
            if (changesAttribution(row, stored)) {
                throw Violations.ofField(DnsRecordModel.GENERATED_BY.getName(),
                    row.get(DnsRecordModel.GENERATED_BY),
                    CmsSupport.violationText("dns_generated_attribution"));
            }
        });
        DnsRecordModel.SCHEMA.addBeforeRemoveHook(context -> {
            if (inSystemScope()) {
                return;
            }
            for (Row doomed : doomedRows(context)) {
                if (doomed.get(DnsRecordModel.GENERATED_BY) != null) {
                    throw readOnly(doomed);
                }
            }
        });
    }

    /**
     * The rows a criteria delete is about to remove.
     *
     * AIDEV-NOTE: a remove context carries CRITERIA, not rows, so the guard re-runs the same
     * query -- the ActivityLog.captureDoomed idiom. Enforcing on the resource's delete method
     * instead would leave every criteria delete (the zone-file import, the peer API, the
     * publisher's own cleanup) outside the guard, which is the whole point of the funnel.
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

    private static @NonNull Violations readOnly(@NonNull Row stored) {
        return Violations.ofField(DnsRecordModel.GENERATED_BY.getName(),
            stored.get(DnsRecordModel.GENERATED_BY),
            CmsSupport.violationText("dns_generated_readonly")
                .withArg("source", String.valueOf(stored.get(DnsRecordModel.GENERATED_BY))));
    }

    private static @Nullable Row storedRowOf(@NonNull Row row) {
        return row.has(DnsRecordModel.ID.getName())
            ? Models.get(DnsRecordModel.class).findById(row.get(DnsRecordModel.ID)) : null;
    }

    /**
     * Whether an unscoped write would MOVE any attribution column.
     *
     * AIDEV-NOTE: presence of the key is not the question. Every writer that loads a row and
     * saves it back (the dyndns update path, the CMS edit form) carries all four columns
     * with their stored values, so a presence check refused every ordinary save of an
     * ordinary record. What a caller may not do is CHANGE them -- set one, or clear one.
     */
    private static boolean changesAttribution(@NonNull Row row, @Nullable Row stored) {
        return differs(row, stored, DnsRecordModel.GENERATED_BY.getName())
            || differs(row, stored, DnsRecordModel.GENERATED_FOR_MODEL.getName())
            || differs(row, stored, DnsRecordModel.GENERATED_FOR_ID.getName())
            || differs(row, stored, DnsRecordModel.GENERATED_AT.getName());
    }

    private static boolean differs(@NonNull Row row, @Nullable Row stored, @NonNull String field) {
        if (!row.has(field)) {
            return false;
        }
        return !Objects.equals(row.get(field), stored != null ? stored.get(field) : null);
    }
}
