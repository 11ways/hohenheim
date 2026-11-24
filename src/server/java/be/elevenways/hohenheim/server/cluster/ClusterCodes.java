package be.elevenways.hohenheim.server.cluster;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunnerGroup;
import be.elevenways.zenit.common.bootstrap.RuntimeJobs;
import be.elevenways.zenit.common.orm.code.CodeBlock;
import be.elevenways.zenit.common.orm.code.CodeBlockModel;
import be.elevenways.zenit.common.orm.code.CodeBlocks;
import be.elevenways.zenit.common.orm.code.CodeGrants;
import be.elevenways.zenit.common.orm.code.CodeNumbering;
import be.elevenways.zenit.common.orm.code.CodeWant;
import be.elevenways.zenit.common.orm.code.RecordCodeKinds;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.lease.LeaseKeys;
import be.elevenways.zenit.common.orm.lease.Leases;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.server.security.SignedPathTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One code per thing in the whole cluster, as Hohenheim wires it: where this server's code numbers come from, the
 * codes call that carries a managed server's wants to its master and the blocks back, and joining and leaving.
 *
 * AIDEV-NOTE: the switch between the own sequence and granted blocks is ONE fact read inside the allocating
 * transaction: this server has a master and holds a block that master granted. Joining writes both (the master's row,
 * then the blocks and the renumbering) in one transaction, and leaving removes the row with CodeBlocks.leave in one,
 * so no allocation sees half a switch. Every allocation and every switch first hold the lease {@link #SWITCH_KEY}
 * until their transaction ends (core's Leases.holdUntilTransactionEnds; on SQLite the open write transaction already
 * excludes and nothing is taken): an allocation that read "own sequence" has committed its number before a switch
 * reads the codes it renumbers, and one that waited behind a switch reads the switched fact. Without the fence a
 * PostgreSQL allocation could read the fact just before a join commits and draw its own number just after, inside a
 * granted block (core's CodeNumbering note).
 *
 * AIDEV-NOTE: at joining the master has no row of the server yet, so the codes call presents the link key with a
 * ticket the master signed into the join body (core SignedPathTokens, sealed, valid one call deadline): it names the
 * row id the master will give that server, which is the peer every grant is recorded under (the same key the master's
 * record keeps that server under). The two servers commit apart (the grant on the master, the join here, then the
 * master's row of this server), so the master records a join's grant void (core CodeGrants.grantJoining) and puts it
 * in force with that row (CodeGrants.joined): a refused join leaves a grant that names nobody, its numbers never
 * handed out again. The codes call of a join runs inside the join code's transaction, so on SQLite this server's
 * writers wait for it (at most one call deadline): that is what makes the join all or nothing here.
 * Removing a managed server's row, on any path, ends its grants in the same transaction (CodeGrants.released): its
 * codes then name nobody on the master, and its numbers are never handed out again.
 *
 * AIDEV-NOTE: localized content: none; codes are not localized.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class ClusterCodes {

    /** The fence every code allocation and every numbering switch holds until its transaction ends. */
    static final String SWITCH_KEY = LeaseKeys.singleton(HohenheimIds.id("cluster_codes"), "hohenheim:cluster_codes")
        .key();

    private static final SignedPathTokens.Claim<String> KEY_DIGEST = SignedPathTokens.Claim.text("key");

    /** The master's leave to grant numbers to the server it is adding; the subject is that server's coming row id. */
    private static final SignedPathTokens.Purpose JOINING = SignedPathTokens.Purpose.sealed(
        HohenheimIds.id("cluster_join_codes"), KEY_DIGEST);

    /** The one numbering this process installs; one instance, so a runtime booting twice installs the same one. */
    private static final CodeNumbering NUMBERING = ClusterCodes::sourceOf;

    private static final JobRunnerGroup.Member REFILLS =
        RuntimeJobs.GROUP.member("hh-cluster-codes", JobRunnerGroup.StopMode.DRAIN, 1);

    /** Asks the master for more numbers in the background: at most one call in flight and one waiting. */
    private static final Coalesced REFILL = new Coalesced(REFILLS, ClusterCodes::refill);

    private ClusterCodes() {
    }

    /** Makes this server's linked-ness decide where its code numbers come from; once per process. */
    static void install() {
        CodeNumbering.SEAM.installSole(NUMBERING);
    }

    // --- Where numbers come from ---

    /**
     * @return granted blocks while this server has a master and holds a block it granted, else the own sequence; read
     *         behind the fence, and on a managed server the master is asked for more after the commit when the
     *         numbers left fell below the mark
     * @throws DomainRefusal (IN_PROGRESS) when a switch held the fence for the whole wait
     */
    private static CodeNumbering.@NonNull Source sourceOf(@NonNull Datasource datasource) {
        if (datasource != datasource()) {
            return CodeNumbering.Source.OWN_SEQUENCE;
        }
        fence(datasource);
        if (grantingMaster(datasource) == null) {
            return CodeNumbering.Source.OWN_SEQUENCE;
        }
        datasource.afterCommit(REFILL::ask);
        return CodeNumbering.Source.GRANTED_BLOCKS;
    }

    /** @return this server's master when it holds a block that master granted, else null; at most three reads */
    private static @Nullable Row grantingMaster(@NonNull Datasource datasource) {
        CodeBlockModel blocks = new CodeBlockModel(datasource);
        // A server that never held a block (a standalone one, a master) reads one table and nothing of the link.
        if (blocks.find().noCache().only(CodeBlockModel.ID).first() == null) {
            return null;
        }
        Row master = LinkedServers.master();
        if (master == null) {
            return null;
        }
        Row granted = blocks.find().noCache().where(CodeBlockModel.GRANTED_BY.eq(peerKey(master)))
            .only(CodeBlockModel.ID).first();
        return granted == null ? null : master;
    }

    /** Holds the fence until the calling thread's transaction on the datasource ends. */
    private static void fence(@NonNull Datasource datasource) {
        if (!Leases.holdUntilTransactionEnds(datasource, SWITCH_KEY, Leases.DEFAULT_WAIT_SECONDS)) {
            throw new DomainRefusal(ZenitRefusalReason.IN_PROGRESS, "the switch of where code numbers come from "
                + "held " + SWITCH_KEY + " past the wait");
        }
    }

    // --- The managed side ---

    /**
     * Takes the blocks a joining server needs from its master and renumbers, inside the caller's transaction (the one
     * that makes the master's row): every coded thing gets a new number, its old spelling keeps naming its record.
     *
     * @param master the master's row, written in the caller's transaction
     * @param key    the key this server presents to that master, as the join brought it
     * @param ticket the master's leave to grant numbers before it holds this server's row
     * @throws DomainRefusal when the master granted no block of a prefix it was asked for, the blocks hold too few
     *                       numbers, or a switch held the fence for the whole wait (IN_PROGRESS); the caller's
     *                       transaction then rolls back with everything here
     */
    static void join(@NonNull Row master, @NonNull String key, @NonNull String ticket) {
        Datasource datasource = datasource();
        List<CodeWant> wants = CodeBlocks.joining(datasource);
        List<CodeBlock> blocks = granted(master.get(LinkedServerModel.BASE_URL), key, wants, ticket);
        for (CodeWant want : wants) {
            if (want.wanted() > 0 && blocks.stream().noneMatch(block -> block.prefix().equals(want.prefix()))) {
                throw new DomainRefusal(ZenitRefusalReason.SERVICE_UNAVAILABLE, "the master granted no "
                    + want.prefix() + " numbers");
            }
        }
        takeIn(datasource, master, blocks, true);
    }

    /** Asks the master for more numbers in the background, when this server has one; the minute check asks too. */
    static void ask() {
        REFILL.ask();
    }

    /**
     * Waits for the codes calls this process asked to end, for tests.
     *
     * @return false when one was still on its way after the timeout
     */
    public static boolean awaitIdle(@NonNull Duration timeout) {
        return REFILLS.awaitIdle(timeout);
    }

    /** Goes back to the own sequence, in the caller's transaction (the one that removes the master's row). */
    static void left() {
        Datasource datasource = datasource();
        fence(datasource);
        CodeBlocks.leave(datasource);
    }

    /** @return how many things on this server get a new code if it joins a master now (core's exact count) */
    public static int renumberedAtJoin() {
        return Math.toIntExact(CodeBlocks.renumberedAtJoining(datasource()));
    }

    /**
     * Asks the master for what this server wants: one below the mark of a prefix asks a block of it.
     *
     * AIDEV-NOTE: a server whose master granted it nothing keeps its own sequence: only a join renumbers, after the
     * join card said how many codes change. Every join since codes became cluster-wide takes its blocks in the join's
     * own transaction, and no link was made before, so no such server exists; one takes part once joined again.
     */
    private static void refill() {
        Row master = LinkedServers.master();
        if (master == null || LinkedServers.stopsSending(master)) {
            return;
        }
        Datasource datasource = datasource();
        String baseUrl = master.get(LinkedServerModel.BASE_URL);
        String key = master.get(LinkedServerModel.LINK_KEY);
        try {
            if (grantingMaster(datasource) == null) {
                return;
            }
            List<CodeWant> wants = new ArrayList<>();
            for (CodeWant want : CodeBlocks.wants(datasource)) {
                if (want.wanted() > 0) {
                    wants.add(want);
                }
            }
            if (!wants.isEmpty()) {
                List<CodeBlock> blocks = granted(baseUrl, key, wants, null);
                datasource.withTransaction(transaction -> takeIn(datasource, master, blocks, false));
            }
        } catch (DomainRefusal refused) {
            Blast.log("CLUSTER: no code numbers from the master this time -", refused.getMessage());
        }
    }

    /**
     * Behind the fence, in the caller's transaction: keeps the blocks, and renumbers what lies outside them, unless
     * the master that granted them is no longer this server's (it left or joined another while the call ran).
     *
     * @param renumber whether this switches to granted blocks (a join), else it only adds blocks
     */
    private static void takeIn(@NonNull Datasource datasource, @NonNull Row master, @NonNull List<CodeBlock> blocks,
                               boolean renumber) {
        fence(datasource);
        Row current = LinkedServers.master();
        if (current == null || !peerKey(current).equals(peerKey(master))) {
            return;
        }
        CodeBlocks.hold(datasource, peerKey(master), blocks);
        if (renumber) {
            CodeBlocks.renumber(datasource);
        }
    }

    /**
     * @param ticket the master's leave at joining, null once the master holds this server's row
     * @return the blocks the master at {@code baseUrl} granted for the wants
     * @throws DomainRefusal (SERVICE_UNAVAILABLE) when the master did not answer them
     */
    private static @NonNull List<CodeBlock> granted(@NonNull String baseUrl, @NonNull String key,
                                                    @NonNull List<CodeWant> wants, @Nullable String ticket) {
        LinkCalls.Answer answer = LinkCalls.codes(baseUrl, key, wantsOnWire(wants), ticket);
        List<CodeBlock> blocks = answer instanceof LinkCalls.Answered answered && answered.status() == 200
            ? blocksOf(answered.body().get("blocks")) : null;
        if (blocks == null) {
            throw new DomainRefusal(ZenitRefusalReason.SERVICE_UNAVAILABLE, "the master did not grant code numbers"
                + " (" + (answer instanceof LinkCalls.Answered answered ? "HTTP " + answered.status()
                : ((LinkCalls.Failed) answer).failure().token()) + ")");
        }
        return blocks;
    }

    // --- The master's side ---

    /** @return the ticket the join body carries: leave to grant numbers to the server whose row will have this id */
    static @NonNull String ticket(@NonNull UUID server, @NonNull String key) {
        return SignedPathTokens.standard().mint(JOINING, server.toString(), LinkCalls.DEADLINE,
            SignedPathTokens.Claims.NONE.with(KEY_DIGEST, LinkedServers.digestOf(key)));
    }

    /** @return the peer a valid ticket presented with its key names, null for anything else */
    static @Nullable String ticketPeer(@Nullable String ticket, @Nullable String key) {
        if (key == null || !(SignedPathTokens.standard().verify(JOINING, ticket) instanceof SignedPathTokens.Valid
                valid)) {
            return null;
        }
        String digest = valid.claim(KEY_DIGEST);
        return digest != null && LinkedServers.keyMatches(digest, key) ? valid.subject() : null;
    }

    /**
     * Grants a linked server its wants, one block each, from this server's own sequences; a prefix this build does
     * not declare is granted nothing (the linked server runs another version).
     *
     * @return the granted blocks
     */
    static @NonNull List<CodeBlock> grant(@NonNull String peer, @NonNull List<CodeWant> wants) {
        return CodeGrants.grant(datasource(), peer, known(wants));
    }

    /**
     * Grants a joining server its wants as {@link #grant} does, void until its add records it here
     * ({@link #joined}).
     *
     * @param peer the row id the ticket promised that server
     * @return the granted blocks
     */
    static @NonNull List<CodeBlock> grantJoining(@NonNull String peer, @NonNull List<CodeWant> wants) {
        return CodeGrants.grantJoining(datasource(), peer, known(wants));
    }

    /** Puts the grants made for a server's join in force, in the caller's transaction: the one that adds its row. */
    static void joined(@NonNull Row server) {
        CodeGrants.joined(datasource(), peerKey(server));
    }

    /** Ends every grant of a server this one stops managing, in the caller's transaction: the one removing its row. */
    static void released(@NonNull Row server) {
        CodeGrants.released(datasource(), peerKey(server));
    }

    /** @return the wants of prefixes this build declares */
    private static @NonNull List<CodeWant> known(@NonNull List<CodeWant> wants) {
        List<CodeWant> known = new ArrayList<>(wants.size());
        for (CodeWant want : wants) {
            if (RecordCodeKinds.byPrefix(want.prefix()) != null) {
                known.add(want);
            }
        }
        return known;
    }

    // --- The wire ---

    /** @return the wants as the codes call carries them */
    static @NonNull List<Map<String, Object>> wantsOnWire(@NonNull List<CodeWant> wants) {
        List<Map<String, Object>> wire = new ArrayList<>(wants.size());
        for (CodeWant want : wants) {
            Map<String, Object> each = new LinkedHashMap<>();
            each.put("prefix", want.prefix());
            each.put("left", want.left());
            each.put("wanted", want.wanted());
            each.put("above", want.above());
            wire.add(each);
        }
        return wire;
    }

    /** @return the wants a codes call carries, null for anything unreadable */
    static @Nullable List<CodeWant> wantsOf(@Nullable Object wire) {
        if (!(wire instanceof List<?> listed)) {
            return null;
        }
        List<CodeWant> wants = new ArrayList<>(listed.size());
        for (Object item : listed) {
            if (!(item instanceof Map<?, ?> each) || !(each.get("prefix") instanceof String prefix)
                    || !(each.get("left") instanceof Number left) || !(each.get("wanted") instanceof Number wanted)
                    || !(each.get("above") instanceof Number above) || wanted.longValue() > Integer.MAX_VALUE
                    || left.longValue() < 0 || wanted.longValue() < 0 || above.longValue() < 0
                    || above.longValue() > Long.MAX_VALUE - wanted.longValue()) {
                return null;
            }
            wants.add(new CodeWant(prefix, left.longValue(), (int) wanted.longValue(), above.longValue()));
        }
        return wants;
    }

    /** @return the blocks as the codes call answers them */
    static @NonNull List<Map<String, Object>> blocksOnWire(@NonNull List<CodeBlock> blocks) {
        List<Map<String, Object>> wire = new ArrayList<>(blocks.size());
        for (CodeBlock block : blocks) {
            Map<String, Object> each = new LinkedHashMap<>();
            each.put("prefix", block.prefix());
            each.put("first", block.first());
            each.put("last", block.last());
            wire.add(each);
        }
        return wire;
    }

    /** @return the blocks a codes answer carries, null for anything unreadable or a prefix this build lacks */
    private static @Nullable List<CodeBlock> blocksOf(@Nullable Object wire) {
        if (!(wire instanceof List<?> listed)) {
            return null;
        }
        List<CodeBlock> blocks = new ArrayList<>(listed.size());
        for (Object item : listed) {
            if (!(item instanceof Map<?, ?> each) || !(each.get("prefix") instanceof String prefix)
                    || RecordCodeKinds.byPrefix(prefix) == null || !(each.get("first") instanceof Number first)
                    || !(each.get("last") instanceof Number last) || first.longValue() < 1
                    || last.longValue() < first.longValue()) {
                return null;
            }
            blocks.add(new CodeBlock(prefix, first.longValue(), last.longValue()));
        }
        return blocks;
    }

    // --- Helpers ---

    /** @return the key the master's grants and record name a linked server by: its row id there */
    private static @NonNull String peerKey(@NonNull Row server) {
        return ClusterRecords.peerKey(server);
    }

    /** @return the database this server's records and their codes live in */
    private static @NonNull Datasource datasource() {
        return Models.get(LinkedServerModel.class).getResolvedDatasource();
    }
}
