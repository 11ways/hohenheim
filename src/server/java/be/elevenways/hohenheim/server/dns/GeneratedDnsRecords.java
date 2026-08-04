package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

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
 * {@code InternalDnsTxtPublisher.cleanup} removes its own row by exact tuple, and
 * {@code GameDomains} reconciles its rows by exact attribution on every change.
 *
 * AIDEV-NOTE: attribution is NEVER a unique key. Two concurrent ACME orders for the same
 * name legitimately publish two TXT rows with identical attribution, and a unique key would
 * make the second order fail for the first order's benefit.
 *
 * AIDEV-NOTE: since the game-domains wave the scope itself lives in {@link GeneratedRows},
 * SHARED with GeneratedInstanceFiles -- one {@code as(...)} block covers a materialization
 * that writes config files AND DNS rows, and TenantWrites' system-scope bypass keeps
 * answering for both. This class remains the DNS face: its source tokens and the guard
 * installation on DnsRecordModel.
 *
 * @author Jelle De Loecker
 */
public final class GeneratedDnsRecords {

    /** {@code generated_by} token for the ACME DNS-01 publisher. */
    public static final String SOURCE_ACME = "acme";

    /** {@code generated_by} token for the game-domains materializer. */
    public static final String SOURCE_GAME_DOMAIN = "game_domain";

    /** Accountability origin for the ACME writes this scope covers. */
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

        GeneratedRows.Attribution shared() {
            return new GeneratedRows.Attribution(this.source, this.forModel, this.forId);
        }
    }

    /** A scope body that may fail the way the write it wraps fails. */
    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    /**
     * Run a generated write: every DNS record (and generated instance file) written inside
     * is stamped with {@code attribution}, whatever the caller staged.
     */
    public static void as(@NonNull Attribution attribution, @NonNull Body body) throws Exception {
        GeneratedRows.as(attribution.shared(), body::run);
    }

    /**
     * Run a system REMOVAL of generated rows without authoring any: the declaring container
     * is going away and takes its generated rows with it (a zone delete). It grants no
     * authority to write attribution, only to remove rows that already carry it.
     */
    public static void sweeping(@NonNull Runnable body) {
        GeneratedRows.sweeping(ORIGIN_ACME, body);
    }

    /** @return true when a system scope over generated rows is active on this thread */
    public static boolean inSystemScope() {
        return GeneratedRows.inSystemScope();
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
        GeneratedRows.installGuards(DnsRecordModel.SCHEMA, DnsRecordModel.class,
            new GeneratedRows.Columns(DnsRecordModel.GENERATED_BY,
                DnsRecordModel.GENERATED_FOR_MODEL, DnsRecordModel.GENERATED_FOR_ID,
                DnsRecordModel.GENERATED_AT),
            "dns_generated_readonly", "dns_generated_attribution");
    }
}
