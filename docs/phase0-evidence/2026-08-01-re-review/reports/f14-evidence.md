# F14 evidence durability report (2026-08-01)

## What was done

Committed to hohenheim as `ff9fdeb` ("Check in the remediation evidence the
manifest cites", 32 files, +5615/-20), on top of `b1de080`. Worktree was clean
of other agents' work before and after; only my files were staged.

## Where the evidence now lives, and why

`/home/skerit/projects/hohenext/hohenheim/docs/phase0-evidence/`:
- `reports/` -- all 21 per-issue reports (not just the 13 the manifest cites;
  the other 8 back gate rows that cite issues by number).
- `recon/` -- 7 wave recon files + b6-b8-assessment.md.
- `OWNER-DECISIONS.md`, `ORCHESTRATION.md`.
- `REMEDIATION-2026-07-31.md` -- a COPY of the ledger, because the javaweb
  workspace root is not a git repository, so the original path is not durable.

Reasoning: one indivisible evidence chain, sole consumer is the Phase 0 gate
manifest in hohenheim; individual report files span 2-4 repos each (e.g.
e3-e7-e10.md covers zenit+hohenheim, a3-a4-a7-a8.md covers seven repos), so a
per-repo split would sever the chain and break relative references. Placement
in per-repo docs/ was considered and rejected for that reason. Evidence content
was copied byte-for-byte, unedited.

## Dangling references: 16 found, 16 now resolve

- 13 distinct `reports/*.md` references (wave-g-audit, e4-e5-e6-e8, e3-e7-e10
  x2, f1-f4-f11, e1-e2 x2, b1-b2-b3-b9, d1-d5-d6, e9-f6, d3-d4, a1-a10,
  a2-a5-a9, a3-a4-a7-a8, a6-a11) -> rewritten to `phase0-evidence/reports/...`
  (relative to docs/). All 13 files existed in the preserved copy; none were
  missing.
- 2 `OWNER-DECISIONS.md` references -> `phase0-evidence/OWNER-DECISIONS.md`.
- 1 ledger reference (`/home/skerit/projects/javaweb/REMEDIATION-2026-07-31.md`)
  -- path exists on disk but is outside any git repo; the new evidence-location
  paragraph names the checked-in copy.
- `instance-tier-plan.md` (line 3) already resolved; unchanged.

## Reports that genuinely do not exist

Only F13 and F14 -- exactly as the manifest already stated ("no per-issue
report file was written for them. Their commit messages are the only prose
record"), with real commit hashes (zenit 8b6a60b, zenit-cms 5408bcd,
zenit-widget 7ffe1de, hohenheim f38c8d9 -- all verified to exist). No further
marking was needed; no dangling link pointed at an F13/F14 report.

## Evidence-tier honesty check at current HEAD

Every "Now" hash in the manifest tables matches the actual current HEAD of all
21 repos (hohenheim has only the docs-only b1de080 + my ff9fdeb on top of
f38c8d9). Per-row verification:

- Stored XSS (c)+(b): honest as written. hohenheim tests not re-run reverted;
  already stated.
- Process IPC (c): honest -- already downgraded for C13 (SQLite engine swap,
  zenit 6125ef1) and E10 (harness rewrite, hohenheim dc68e5b).
- API-key authority (c)+(a): honest -- verified `CsrfMiddleware` has ZERO
  commits since 0.A hash 3ca4a7b (`git log 3ca4a7b..HEAD -- '*CsrfMiddleware*'`
  is empty).
- RecordSource (a): stands -- only 8b6a60b (F14 itself, +8 lines to
  RecordSource.java, registry additions, test-only additions) touched
  RecordSource after the D1 counterfactual commit 0fd1705.
- Published shell (c): honest -- plumage 4a47471 bridge rewrite already
  acknowledged.
- Secrets (a)/(c): stands -- zero FormSecrets churn after 24d40d4, zero
  revision-code churn after b4822a6.
- WebSocket (a)+(b): stands -- zero websocket churn after 9fc2846.
- Grants (a): stands -- zero grants churn in zenit-auth after 0dc57fc
  (af25fa6 touched only AuthorizationMiddleware).
- Route ownership (a): KEPT at (a) but a caveat was ADDED (the one row that
  overclaimed by omission): the C14 counterfactual (a19e1dd, 07-31 18:28) was
  observed on the post-C13 engine but BEFORE D4's SiteModel lifecycle change
  (1a56057, 20:23) and the E10 harness rewrite (dc68e5b, 22:10); post-E10 the
  classes passed only inside the full suite, and the overlapping-listener
  counterfactual was not re-observed under the rewritten harness.

No row was upgraded. All ~30 commit hashes cited by the manifest were verified
to exist in their repos (`git cat-file -t`).

## Other manifest corrections

- Collapsed-subject commit list corrected 8 -> 12: the four F13/F14 commits
  (zenit 8b6a60b, zenit-cms 5408bcd, zenit-widget 7ffe1de, hohenheim f38c8d9)
  are also collapsed and postdate the original count.
- Numbering reconciliation CONFIRMED correct: instance-tier-plan open decision
  1 = ledger D7 (historical plaintext, same rows same words), open decision 13
  (live-install checksum stamping + migration_integrity=fail flip) has no
  ledger/owner-decision counterpart, D9 = manifest open item 5 (0.6c), not a
  0.B gate. Added one clarifying line: ledger C8 (shipped-migration checksum
  repair after helper renames) is adjacent but distinct from decision 13.

## Verification

- Every `phase0-evidence/...` reference in the manifest tested with `[ -f ]`
  from docs/: all resolve.
- `git log -1`: subject on its own line, 53 chars, body separate, 3 lines total.
- `git status` clean before and after; no other agents' changes touched.
- No source code modified anywhere.
