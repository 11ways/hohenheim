# Remediation orchestration state - 2026-07-31

Master ledger: /home/skerit/projects/javaweb/REMEDIATION-2026-07-31.md
Recon reports: scratchpad/recon/wave-a.md, wave-b.md, wave-c.md (wave-d/e/f pending)
Fix reports collected in: scratchpad/reports/ (one file per implementation agent)

Rules being enforced by orchestrator:
- ONE implementation agent at a time (or strictly non-overlapping repos, never two
  publishing the same dependency chain). Recon (read-only) may run in parallel.
- Fable model for hard/structural fixes, Opus for easy ones (user directive).
- Wave order A -> B -> C -> D -> E -> F -> G -> final verification is mandatory.
- Agents commit their own repos (gitmoji subject, <=3 lines), never push.
- Owner-decision items are NOT implemented; collect assessments for final report:
  D7 (historical plaintext), D9 (at-rest encryption scope), E11 (CSRF-exempt Origin),
  B6/B8 (hohenheim type-level manage contract), C4 partially (Couchbase contract if
  CAS impl infeasible), F13/F14 (design decisions - assess, small fix if obvious).

## Status board

Wave A:
- [DONE] Fable impl: A2, A5, A9 -> zenit d62ef3b. Report: reports/a2-a5-a9.md. All proofs recorded.
- [DONE] Fable impl: A1 + A10 -> hawkeye 9cf4cd41, zenit efb7c6e. Report: reports/a1-a10.md. Bridge v3 republished hawkeye->protoblast.
- [DONE] Fable impl: A3+A4+A7+A8 -> protoblast 8552280, zenit bd29474, hawkeye 1dcd1542+f0b2982c, textum 047115a, zenit-cms 4a058e7, zenit-media bf2d84f, zenit-auth 31e95e2; arcana build.gradle edit UNCOMMITTED (not a git repo). Report: reports/a3-a4-a7-a8.md
- [DONE] Fable impl: A6+A11 -> protoblast c76381a, spamservice 9039ea5, thoth 4cdb2b6, herald ab55f14. Report: reports/a6-a11.md. FOLLOW-UPS: hohenheim has same INCLUDE dup pattern (build.gradle:230,318) - fold into a hohenheim batch; upstream -server jars leak previous-compilation-data.bin.
- [DONE] Recon wave-a saved.

Wave B (recon DONE, saved wave-b.md; all claims real):
- [RUNNING] Fable impl: B1 + B2 + B3 + B9 (zenit-auth grant policy, last-admin invariant,
        slug migration, fault injection). Cross-cutting notes in recon.
- [DONE] B4+B5 -> zenit-auth 4e458f4. Report: reports/b4-b5.md. NEW GrantableModel with
        liveWhen() liveness seam -> C6 MUST EXTEND IT (isLive(Row), declareGrantable,
        grantableModel(id)); hohenheim needed no change. Recon claim 'zero production callers'
        FALSIFIED (HohenheimHandlers.java:877 SITES_ACCESS_ADD, already resolves target).
        NOTE: declareGrantable stays last-wins deliberately (lambda not value-comparable). (idempotent-or-loud rules registry; grant existence validation
        via GrantableModel declaration shared with C6)
- [TODO] BUNDLE B7 + F9 + F10 as ONE permissions-editor batch (same templates: zenit-forms
        permissions-edit.hwk, plumage permissions-editor.hwk, proteus dup, qq copy).
- [REPORT-ONLY] B6, B8 (owner decisions)

Wave C (recon DONE, saved wave-c.md; all real; C7 false for Couchbase only):
- [DONE] C1,C2,C8,C9,C10,C11,C12 -> zenit 1d18d89, zenit-auth 128a60a, zenit-media ecde56d,
        zenit-microcopy 3ab242c, spamservice 2f43b7c, hohenheim d45b344. reports/c-migrations.md
        NEW: M009_PurgeOrphanRecordGrants (upgrade purge), IndexNames helper, DataOperation
        bodyVersion (data steps now checksum their body identity -> MigrationBuilder.data is
        3-arg, no 2-arg overload), AddIndexOperation.superseding (framework-declared index
        rename equivalence). Falsified: zenit-pages unaffected; quirkyquarters needs nothing
        (no installations). zenit full suite 2023/2024 (1 unrelated ChannelProtocolTest flake).
- [DONE] C3,C4,C5,C6,C7 -> zenit-auth 0dc57fc, zenit fc89eaf, hohenheim 78cc25b.
        reports/c3-c7.md. C4 = OUTCOME (1): Couchbase updateAll rewritten to CAS-guarded KV
        read-modify-write, portable guarantee HOLDS, proven on live Couchbase 7.6.1 ->
        NO owner decision needed, remove from OWNER-DECISIONS. C6 extended GrantableModel
        + M009 bodyVersion 1->2. C7 proven on all 8 backends (7 counterexamples + Couchbase
        documented exemption).
- [DONE] C13 -> zenit 6125ef1, hohenheim 9dd6de4. reports/c13.md. Chose option (b)
        independent connections by MEASUREMENT: shared-cache :memory: rejected
        (SQLITE_LOCKED_SHAREDCACHE), :memory: URL now maps to a private temp FILE;
        WAL + busy_timeout=5000 + transaction_mode=IMMEDIATE; tx owns a dedicated pooled
        connection (mirrors PooledSqlDatasource). FOR C14: scan+claim is now serialized by
        the engine on SQLite, so ClaimConflict via site enables is unreachable; route-claim
        transactions must finish under the 5s busy_timeout.
- [DONE] C14 -> hohenheim a19e1dd. reports/c14.md. WAVE C COMPLETE.
        Chose SERIALIZED CLAIM REGISTRY (transactional scan+stamp+write in one unit);
        rejected 'expand any to configured listeners' as structurally unsound (listen_on is
        a bare StringField validated only on the CMS form path; empty conflicts with EVERY
        address incl. undiscovered; discovered set refreshes hourly). NO migration needed.
        Guarantee is engine-scoped: relies on SQLite BEGIN IMMEDIATE; hohenheim refuses
        non-SQLite at boot (requireSqlite). All 8 ledger proof cases covered.

Wave D batches (all zenit core -> SERIALIZE):
- [DONE] D1+D5+D6 -> zenit 0fd1705, 4353a29. reports/d1-d5-d6.md.
        NEW REAL LEAK FOUND (beyond ledger): title fallback stringified a SchemaField raw,
        echoing the secret sub-key project() had just stripped (plaintext key AS the title).
        D5 decision: secret primary keys are ILLEGAL, refused at Models.registerInstance.
        D6: real fix - narrow self-terminating daemon sweeper in server source set (NOT
        ScheduledTask: cluster-claimed would prune only one instance's heap).
        D1 checked all 151 RecordSource.of sites workspace-wide: zero production breakage.
- [DONE] D2+D8 -> zenit 24d40d4, zenit-cms 5a37138. reports/d2-d8.md.
        Exhaustive pattern switch on sealed FormEntry set (new kind = compile error).
        NEW FieldRedaction.hidesWholeValue (editor tier) beside redactsWholeValue (derived).
        FALSIFIED gap 6 (isEncrypted omission): NOT a defect - zenit/CLAUDE.md says
        .encrypted() alone does NOT hide from admin editors; hohenheim StackFileModel.CONTENT
        is .encrypted() WITHOUT .secret() and is normally edited.
        D8: NO real Records-row secret consumer exists workspace-wide (enumerated, grep=0);
        mechanism wired + test-level consumer, absence stated. Smell: hohenheim
        StackServiceModel.ENVIRONMENT not .secret() while site-type env maps are.
- [DONE] D3+D4 -> zenit b4822a6, hohenheim 1a56057. reports/d3-d4.md. WAVE D IMPL COMPLETE.
        NEW ModelBehaviour.lifecycleFieldNames() + Schema.addLifecycleField (declaration-based,
        not hardcoded). KEY: hohenheim SiteModel has NO SoftDeleteBehaviour (hand-stamped), so
        the framework fix alone would NOT have protected the only production consumer -> wired
        addLifecycleField there. Restore now recordId-authoritative, one transaction,
        updateExisting refuses INSERT fallback. Proven on ALL 8 backends (56 tests).

Wave E batches:
- [DONE] E1+E2 -> zenit 01afdf6 + e11d1ae, zenit-auth af25fa6. reports/e1-e2.md.
        E1 seam: AuthorizationMiddleware takes the concrete HttpConduit Middleware.Action
        already hands it (mirrors ScopedCspMiddleware); rejected a ConduitAttributes key
        (parallel API) and a Conduit default (silent degradation). No other authorization
        consumer had the bug (checked repo-wide).
        E2 found TWO EXTRA holes beyond the ledger: (a) overflow CLEARS the queue, so a
        release queued by an earlier close was discarded by the lane that accepted it ->
        callbackLaneFailed re-delivers on RUNNER; (b) handler.onError throwing skipped
        onClose entirely. G9 real-socket tests all preserved and green.
- [DONE] E4+E5+E6+E8 -> zenit 9fc2846. reports/e4-e5-e6-e8.md.
        *** PROCESS FAILURE CAUGHT: zenit did NOT COMPILE at e11d1ae (E2's commit).
        WebSocketTeardown.java:45,51 had `@NonNull ZenitHttpServer.SerialExecutor` which javac
        rejects (must be `ZenitHttpServer.@NonNull SerialExecutor`). The E2 agent verified
        against a STALE ARTIFACT. Fixed in 9fc2846; E2's tests re-covered by this agent's full
        suite run (2039 tests). LESSON for final verification: a clean chain build is
        mandatory, per-agent claims are not sufficient.
        E4: ModelParam has ZERO production consumers, so both halves were LATENT; fixed anyway
        (shipped+documented mechanism) and the structural-match seam then paid for E6.
        E5: chose a bounded rejection LIMITER (10/IP/60s on the diagnostics only), rejected
        async sinks; the victim-budget invariant re-pinned as its own step.
        E8: setting contract change - a deployment with the key set to 0 now FAILS LOADING
        loudly instead of booting with revalidation off. No app sets it (grepped). (zenit HTTP/WS: route param timing, origin limiter,
        claimingRoutesOf, revalidation interval validation)
- [DONE] E3+E7+E10 -> hohenheim e9a0eb7, b772041, dc68e5b. reports/e3-e7-e10.md.
        E3: THIN WIRING - the revalidate seam already existed (WebSocketHandler.revalidate);
        what was missing was the DECLARATION. Token now stored as sha256 digest so
        revalidation re-runs resolution without retaining the secret. Ownership/grant changes
        do NOT close a tunnel (token-to-site auth, no principal) - stated, not implied.
        E10: new discovered ZenitModule HohenheimHostWiring at MODULES(200), after MODELS(300)
        and before STARTHTTP(50). Harness CONVERGED - DevTunnelTest's compensating lines
        REMOVED. Window proved with a weight-49 boot-stage probe doing a REAL GET /api/health:
        pre-fix 404. hohenheim full suite 578 passed / 124 classes.

## MODEL POLICY CORRECTED (user, 2026-07-31, second correction)
Opus was over-applied. User's original instruction was Opus for the ONE stalled task, not
for all security waves. Evidence: Fable produced C13/C14/A1/A10/A3-A8 with no refusals; the
only process failure (non-compiling commit claimed green) came from an OPUS agent (E2).
FABLE FOR ALL REMAINING WORK: E9, Wave F, and the follow-up queue. (hohenheim: dev-tunnel revalidation, terminal CSP predicate,
        startup order)
- [TODO] Fable: bundle E9 + F6 (both plumage terminal lifecycle)

Wave F batches (Fable, serialized bottom-up):
- [DONE] F1,F2,F3,F4,F11 -> hawkeye 6c443d84, 01c63dec, d7a71b35, 4d05e77f, c7903b19;
        zenit 2c7d8fb. reports/f1-f4-f11.md. Bridge NOT bumped (no bridged signature changed);
        automatic protoblast repackage OBSERVED firing. Chain rebuilt green incl. hohenheim.
        F3 also fixed the flagged FormActionTranspiler sibling (c7903b19).
        Downstream sweeps: ZERO `attr:on*` occurrences workspace-wide; only a prose comment
        mentions data-confirm. No consumer authors `disabled` on a use:List.* control.
        NEW ADJACENT DEFECT (reported, NOT fixed): IRElement start offsets include the
        preceding whitespace text node, so EXISTING element-open source markers anchor one
        line early (and an element with no preceding text emits no marker). Predates this
        work; affects every element mapping by one line. Positions sweep with its own proof
        burden -> follow-up queue.
- [DONE] E9+F6 -> plumage 4a47471 (one commit, same two production files). reports/e9-f6.md.
        E9 pre-fix produced NO output at all (callbacks hung silently). Sticky failure +
        resource-timing check for an already-settled tag; no watchdog timer (would kill slow
        but healthy WASM loads).
        F6 UNKEYED Cleanup.on, justified (@mount runs once per connected lifetime).
        All 4 proof assertions ran IN PLUMAGE against a real WebSocketEndpoint; hohenheim
        rebuilt + ProcessTerminalHandlerTest 3/3 as the consumer check, no hohenheim commit.
        TEST-INTEGRITY FINDING: terminal-test.hwk was a DEAD fixture (tag declaration nothing
        instantiated; the route rendered an empty page) - any test pointed at it would
        trivially pass. Rewritten.
        TRAP RECORDED: an isConnected-based dispose guard is WRONG - @mount legitimately runs
        while soft-nav content is still detached; a generation tombstone is the right signal.
- [DONE-UNREPORTED] B7+F9+F10 -> zenit-forms 59f6f57, plumage 37bde67, proteus 35f28ae,
        quirkyquarters 36ab28c. ALL WORKTREES CLEAN. The agent died on FINAL WORKTREE
        VERIFICATION when Fable hit the account's monthly spend limit; it had reported
        13/13 downstream passing incl. the grants-gate journey. NO written per-issue report
        -> RECONSTRUCT FROM DIFFS during final verification, and re-verify independently.

## *** FABLE 5 UNAVAILABLE (2026-08-01): monthly spend limit reached. ***
Remaining work continues on OPUS 5 until the user raises the limit (/usage-credits).
- [DONE] F5,F8,F12,F15 -> zenit-cms 670fd98, hawkeye ab61cb43, zenit-flow 03e0914.
        reports/f5-f8-f12-f15.md. F5 marker now bounded by try/finally around requestSubmit.
        F8 uses the already-localized setting label (an arg-based microcopy variant was tried
        and REVERTED: the browser harness installs an identity resolver that discards args).
        F12 added CmsConfirmApiDriftTest - scans CLAUDE.md AND every .hwk for CmsConfirm.<name>
        and checks it against the ACTUAL @HawkeyeDirective/@HawkeyeFunction annotations.
        F15 FALSIFIED BOTH halves of the recon claim with proof: the `mountref + ""` line could
        never re-trigger (AbstractRef.setStoredValue early-returns on equal values) and NOTHING
        asserted it (git blame: filler). Replaced with a real assertion.
        FALSIFIED F8 sub-claim: no "Reset Reset staged" double-name (app.scss display:none
        removes the inactive span from the a11y tree).
        TRAP: Playwright setName(Pattern.quote(x)) silently matches NOTHING (Java Pattern goes
        straight to the JS regex engine, no \Q...\E).
        NOT A BLOCKER (investigated by orchestrator): the zenit-dev self-test failure that agent
        hit is a CONCURRENCY FLAKE - full suite re-run 26/26 PASS. Its own guard
        ('m2 jar replaced outside this workspace') correctly detected a concurrent publish.
        Hermeticity gap -> follow-up queue. (cms confirm replay, a11y reset names, docs, concat judgment)
- [DONE] F7 -> orcono ecd8707 + 36ae266, textum 6ae80d5, zenit f6a89b6. reports/f7.md.
        ALL SIX sub-claims REAL. Sub-claim 5 was USER-VISIBLE and shipping: after a soft nav the
        editor kept pushing into the PREVIOUS page's detached panel, so every page after the
        first had a permanently dead sidebar until a full reload.
        New instance-scoped EditorSession owned by the editor element; KEYED registerDisposer
        registered BEFORE start(); late async work cancelled by a `disposed` flag, never
        isConnected (cites the plumage lesson).
        Also found: sibling lookups queried the DOCUMENT, so during a soft nav where both pages
        coexist they could bind the OUTGOING page's save button.
        NEW: TextumToolbar.destroy() (the toolbar had NO teardown API), zenit
        SyncedRefs.subscriberCount (observability, the only outside view of a leaked subscription).
        BLOCKER CLEARED EN ROUTE: orcono ALSO reused the teavm plugin's own `teavmClasspath`
        (3766 duplicate FQNs) - same defect as spamservice/thoth/herald; migrated to teavmInput.
        Any other app still on the plugin-owned name will hit the same wall.
- [TODO] Opus: FOLLOW-UP QUEUE (zenit-dev.test.js:737 not hermetic vs concurrent ~/.m2 writes; IRElement whitespace source-marker offset; DuckDbDatasource C13-shape defect; previous-compilation-data.bin
        in hawkeye/zenit/textum server jars; hohenheim INCLUDE fat-jar pattern x2; arcana not a
        git repo; zenit-cms CmsShellFunctions panel-from-raw-path)
- [RUNNING] Opus: F13 + F14 (assess + implement the clearly-correct part)
- [DONE] WAVE G AUDIT: ALL TEN INTACT (G1-G10, incl. all 12 G10 sub-items).
        reports/wave-g-audit.md. Assertions verified REAL, not merely present.
        Two observations (NOT regressions, -> follow-up queue):
        (1) G9's exactly-once assertions rely on Thread.sleep(INTERVAL_MS*4) settling windows -
            timing-shaped, a slow box weakens them into passing early. Pre-existing.
        (2) G3's claim derivation WAS rewritten by 9fc2846 but the locale property it protects
            is unchanged and its test GREW a companion rather than being replaced.
- [REPORT-ONLY] E11 -> OWNER-DECISIONS.md
- [REPORT-ONLY] D7, D9 -> OWNER-DECISIONS.md

Wave D: recon DONE (wave-d.md). All real; D7/D9 owner surfaces enumerated.
Wave E: recon DONE (wave-e.md). All real; E11 consumer inventory captured.
Wave F: recon DONE (wave-f.md). All real; F15 no production hits; F13 statekey half already wired (pl-button), only imperative lane unconsumed.
Wave G: verify-preserved pass at end (Opus read-only).

Final: chain build, justified broad suites, refresh
/home/skerit/projects/hohenext/hohenheim/docs/phase0-red-team-manifest.md, final report
with per-issue Reporting Format blocks + owner-decision list.

## Repo baselines (pre-remediation HEADs, all clean at start)
protoblast 8b66d50, hawkeye cd993fa4, zenit d721844, plumage 64e8f14,
zenit-auth 857fcb3, zenit-cms 98f4573, zenit-forms 9fae394, zenit-ai 13b5248,
zenit-microcopy 0334a1d, zenit-media befd2dc, zenit-widget 6451aaf, zenit-flow f11b9da,
zenit-oidc 1f005c8, zenit-pages feb6aa5, zenit-comms 404cb45, zenit-a2ui 332f997,
textum f6b360a, janeway fd170c9, duiventil 9fb69b7, orcono c5e4cbe, herald 2ea85ae,
spamservice 4165126, proteus e60cc0e, quirkyquarters b944bbd, thoth 0d57429,
hohenheim 690ef94


## FINAL INVENTORY (before F13/F14 landed): 61 commits across 18 repos
protoblast 8b66d50->c76381a (2), hawkeye cd993fa4->ab61cb43 (9), zenit d721844->f6a89b6 (16),
plumage 64e8f14->37bde67 (2), zenit-auth 857fcb3->af25fa6 (6), zenit-cms 98f4573->670fd98 (3),
zenit-forms 9fae394->59f6f57 (1), zenit-microcopy 0334a1d->3ab242c (1),
zenit-media befd2dc->ecde56d (2), zenit-flow f11b9da->03e0914 (1), textum f6b360a->6ae80d5 (2),
orcono c5e4cbe->ecd8707 (2), herald 2ea85ae->ab55f14 (1), spamservice 4165126->2f43b7c (2),
proteus e60cc0e->35f28ae (1), quirkyquarters b944bbd->36ab28c (1), thoth 0d57429->4cdb2b6 (1),
hohenheim 690ef94->b772041 (8)
Untouched: zenit-ai, zenit-widget, zenit-oidc, zenit-pages, zenit-comms, zenit-a2ui, janeway,
duiventil (all still at their pre-remediation HEADs).

## GIT HYGIENE DEFECT (report to user, do NOT amend without instruction - rule 17)
8 commits have subject+body COLLAPSED onto one overlong line (a heredoc newline-loss issue an
agent reported mid-session). From THIS remediation:
  hawkeye c7903b19 (183), 6c443d84 (187), d7a71b35 (188), 4d05e77f (174), 01c63dec (166),
          9cf4cd41 (202)
  zenit   2c7d8fb (164), efb7c6e (168)
Pre-existing (NOT this session): hawkeye a51bf44c (141), da9be534 (158).
All are UNPUSHED, so an interactive rebase to split subject/body is safe if the user wants it.


## FINAL VERIFICATION (2026-08-01)
- Chain build (caching on, from zenit-cms): GREEN, 129s, exit 0.
- zenit full unit suite: 2055 of 2056 PASS.
  The single failure is BrandTest "stylesheet registry orders by weight, then by registration
  order" - a PRE-EXISTING test-isolation defect, NOT caused by this work. Proof:
    (a) git log d721844..HEAD over src/test/.../brand/, Stylesheets.java and common/brand/ is
        EMPTY - the remediation never touched them;
    (b) BrandTest passes 3/3 in isolation;
    (c) BrandTest has no @BeforeEach/@AfterEach resetting the process-global Stylesheets
        registry, so /brand.css leaks in from whichever class ran before it.
  Order-dependent; surfaced only because the Couchbase failures perturbed execution order.
- Couchbase root cause FOUND AND CLEARED: the reused testcontainer had been up 4 DAYS and
  accumulated 57 per-worker scopes / 341 collections. Failure progression as it was fixed:
  32 fail (exhausted) -> 9 fail (cold restart, all "Mapped port ... after the container is
  started") -> 1 fail (warm, clean: 9 scopes / 46 collections). zenit-dev prunes stale
  CONTAINERS but nothing prunes INSIDE a surviving reused one.
- All 19 repos CLEAN. 66 commits. NOTHING PUSHED.
- Manifest refreshed: hohenheim b1de080.
