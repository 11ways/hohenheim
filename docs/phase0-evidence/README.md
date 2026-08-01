# Phase 0 evidence

Checked-in evidence for the cross-repository Phase 0 remediation work. The
framework workspace at `/home/skerit/projects/javaweb` is NOT a git repository,
so the evidence produced there is durable only here. Everything under this
directory is a verbatim copy of what the working sessions wrote; nothing was
rewritten after the fact.

## 2026-07-31 remediation arc (flat files in this directory)

The original ledger (Waves A-G, 57 numbered issues) and its per-issue evidence.

- `REMEDIATION-2026-07-31.md` - the ledger itself, copied out of the javaweb
  workspace root.
- `ORCHESTRATION.md` - how that run was dispatched and what each wave concluded.
- `OWNER-DECISIONS.md` - the items that needed the owner's word.
- `reports/` - 21 per-issue reports with observed pre-fix failure text.
- `recon/` - 7 wave recon reports plus the B6/B8 assessment.

`../phase0-red-team-manifest.md` cites these paths directly.

## 2026-08-01 re-review arc (`2026-08-01-re-review/`)

An independent reviewer inspected source, diffs, tests, repo state and the
zenit-dev journal AFTER the 07-31 remediation was declared complete, producing
14 findings (F1-F15). This directory is the verification and close-out of those
findings, including the ones that were FALSIFIED with proof.

- `REVIEW-FINDINGS.md` - the reviewer's raw, unverified claims as received.
- `ORCHESTRATION.md` - wave dispatch, per-finding verdicts, the loose ends
  discovered along the way, and the endgame gate.
- `reports/` - 27 per-topic reports plus 6 raw command logs; each fix carries a
  recorded pre-fix failure and before/after measurements.
- `recon/` - the 4 read-only recon reports (zenit core, zenit-auth, tooling and
  hohenheim, frontend and compiler) that decided which findings were real.

The manifest does not yet cite this arc.
