# Raw NUL bytes in tracked sources — fix, proof, and guard

Date: 2026-08-01

## Summary

Five tracked source files contained raw NUL bytes (0x00) written as literal bytes
instead of escape sequences. GNU grep classifies any file containing a NUL as
binary and skips it SILENTLY, so every plain-grep sweep over those files returned
false negatives with no error. All five are fixed with escape sequences whose
compiled/runtime values are provably bit-for-bit identical, and two enforcement
points now make the class unrepeatable.

## Corrections to the brief's premises

| Brief said | Actual |
| --- | --- |
| `zenit/tools/zenit-dev` has 2 NULs | 1 NUL (line 1138). The file changed today; the count moved. |
| `duiventil` "NOT a git repo? check" | It IS a git repo, and had 6 NULs (not 1). The fix is committed. |
| `MicrocopyMongoImporter` 2 NULs, line ~257 | Correct (lines 257 and 258). |

I also found, in the same file as the NUL, raw ESC (0x1b) and DEL (0x7f) bytes in
`FileSecurityEventSinkTest`. Those do not trigger grep's binary classification
(they are valid ASCII), but they are the same defect class in the same literal, so
they were escaped too.

A full-workspace byte scan confirmed the defect set is EXACTLY those five files.
The only other NUL-bearing files are genuine binaries (PNG/ICO/`.DS_Store`, and
`alchemy/blessed/usr/*` terminfo databases — `alchemy` is not a git repo).

## The escape choice: octal, not backslash-u

Chose `\0` (and `\033`, `\177`). Reason: octal escapes are processed by the
TOKENIZER and are confined to the literal. A backslash-u unicode escape is
processed in the earlier lexical-translation pass (JLS 3.3) and is NOT confined —
it is why a backslash-u-000a inside a `//` comment breaks compilation. The
confined form is strictly safer and equally standard.

Octal escapes greedily consume up to 3 octal digits, so a following digit would
change meaning. Verified safe at every site: the Java/JS literals are exactly
`"\0"`, `'\0'` and the JS template-literal form, and in the test fixture `\0` is
followed by `d`, `\033` by `f`, `\177` by `g` — none an octal digit.

## Files fixed

| Repo | File | Site | NULs |
| --- | --- | --- | --- |
| zenit | `tools/zenit-dev:1138` | JS template-literal cache-key separator | 1 |
| zenit-microcopy | `.../imports/MicrocopyMongoImporter.java:257-258` | composite key separator | 2 |
| zenit-cms | `.../server/page/FormConcurrency.java:64` | null sentinel in the digest input | 1 |
| zenit | `.../security/FileSecurityEventSinkTest.java:50` | control-char test fixture (+ESC, +DEL) | 1 |
| duiventil | `.../mail/store/FileMailStore.java` (6 sites) | mailbox cache-key separator (char literals) | 6 |

## Proof 1 — grep visibility, verbatim before/after

"BEFORE" is `git show HEAD:<file>` from before the fix; "AFTER" is the worktree.

```
zenit-dev                  pattern gradle          BEFORE grep -c: <no output> (exit 1)  grep -ac: 199  AFTER grep -c: 200 (exit 0)
MicrocopyMongoImporter     pattern toLowerCase     BEFORE grep -c: <no output> (exit 1)  grep -ac: 2    AFTER grep -c: 2   (exit 0)
FormConcurrency            pattern canonicalize    BEFORE grep -c: <no output> (exit 1)  grep -ac: 4    AFTER grep -c: 4   (exit 0)
FileSecurityEventSinkTest  pattern SecurityEvent   BEFORE grep -c: <no output> (exit 1)  grep -ac: 18   AFTER grep -c: 18  (exit 0)
FileMailStore              pattern computeIfAbsent BEFORE grep -c: <no output> (exit 1)  grep -ac: 2    AFTER grep -c: 2   (exit 0)
```

zenit-dev's 199 -> 200 is accounted for: my own new `NUL_TEXT_EXTENSIONS` list
contains the string `gradle`. Diffed to confirm that is the only added line.

## Proof 2 — behaviour preservation (the strongest available)

These are load-bearing separators and sentinels, so a behavioural test alone would
be weak. Instead I snapshotted the `.class` files compiled from the NUL-byte
sources, rebuilt from the escaped sources, and compared bytes.

All four Java class files are **byte-identical** before and after:

```
IDENTICAL  ec87ae57eb296417  MicrocopyMongoImporter.class
IDENTICAL  341ad2fada3f67eb  FormConcurrency.class
IDENTICAL  e799eff65fc2179b  FileSecurityEventSinkTest.class
IDENTICAL  2546217e223a9ea8  FileMailStore.class
```

The NUL survives in the constant pool as the modified-UTF-8 overlong form
`C0 80`, in the same counts before and after: 2 / 1 / 1 / 2. (duiventil's six
char-concat sites share 2 pooled `makeConcatWithConstants` recipes, which is why
6 sites yield 2 occurrences.)

Byte-identical class files mean the runtime behaviour cannot have changed — this
is stronger than any test.

For `zenit/tools/zenit-dev` (JavaScript, not compiled):
- `node --check` passes.
- Runtime value proven directly: the template literal now yields a string whose
  middle character is a real NUL — `charCodeAt(1) === 0`, and
  `=== "a" + String.fromCharCode(0) + "b"` is true. The `\0` is followed by `$`,
  not a digit, so it is a valid (non-legacy-octal) escape under `'use strict'`.
- `tools/zenit-dev.test.js`: 38/38 passed after the edit (39/39 after I added a test).
- Functional smoke: `zenit-dev status` resolved the whole dependency chain, which
  runs through `directDepsFor` — the very function holding this cache key.

`FileSecurityEventSinkTest`: 5/5 unit tests pass, including
`stripsAllControlCharactersFromEveryValue`, the test that consumes the fixture.

## Proof 3 — the guard catches a deliberate reintroduction

Build-time gate, in zenit-microcopy:

```
> Task :checkNulBytes FAILED
> Raw NUL byte(s) (0x00) found in text sources. GNU grep treats a file containing a NUL
  as BINARY and skips it SILENTLY, so every plain-grep sweep over that file returns false
  negatives with no error. Write the escape sequence instead: \0 in a Java or JS string or
  char literal (\033, \177 for other control characters) -- the compiled value is
  bit-for-bit identical. A deliberately raw NUL takes a "nul-byte: deliberate" comment on
  the same line.
    .../MicrocopyMongoImporter.java:257: raw NUL byte at offset 9712
```

Sequence proven: clean tree passes -> reintroduced NUL fails the build (and `grep -c`
goes blind again) -> restored tree passes.

Commit-time gate, in duiventil (a PLUGIN-LESS repo where the hook is the only
protection), via a real `git commit`:

```
fail  COMMIT REFUSED: raw NUL byte(s) in 1 staged location(s)
    src/main/java/be/elevenways/duiventil/mail/store/FileMailStore.java:128
git exit status: 1
```

## The guard: two enforcement points, one rule

I followed the `CheckLocaleFoldsTask` pattern but argued for a second enforcement
point, because coverage analysis showed the Gradle-plugin shape alone would have
missed 2 of the 5 actual defect sites (7 of the 10 NUL bytes):

- `zenit/tools/zenit-dev` is a node script in NO Java source set.
- `duiventil` does not apply the protoblast plugin at all.

NUL-blindness is a whole-file BYTE property of any text file in any repo, not a
Java-syntax property. So:

**1. `CheckNulBytesTask`** (protoblast Gradle plugin), wired exactly like
`checkLocaleFolds`: registered always, gates every non-test compile when a
committed `nul-bytes.guard` marker is present. Deliberate departures from the
locale task, both documented in AIDEV-NOTEs:
- It scans the whole PROJECT DIRECTORY, not source sets, so `tools/zenit-dev`,
  `.hwk`, `.scss` and `.gradle` files are covered.
- It does NOT exempt tests — `FileSecurityEventSinkTest` is exactly the case a
  test exemption would have missed.
- Text detection is an extension ALLOWLIST plus "extensionless file starting with
  a shebang". Allowlist direction chosen deliberately: a missed file type is a
  silent gap, whereas a missed BINARY type turns the build red for everyone.
- Suppression: `nul-byte: deliberate` on the same line.

**2. `zenit-dev precommit-guard`** now also refuses raw NULs in STAGED blobs.
This is the universal net: the hook is installed in 29 repos (including the 6
plugin-less ones and external repos hohenheim + testbeds), covers every file type,
and catches the defect at the moment of introduction. Same allowlist, same shebang
rule, same suppression marker.

I did NOT write mirrored Java drift tests for the 6 plugin-less repos: the
pre-commit guard covers them strictly better (it also sees their non-Java files),
and a second mirrored mechanism would be the duplication the brief warned against.

### The trap was respected, and it bit once anyway

Files were fixed and proven FIRST; the guard landed LAST. That ordering paid off:
the first version of the task scanned `public/`, which is a GENERATED sink
(`compileHawkeyeScss` writes css/css.map, TeaVM writes js). An input file
collection overlapping another task's output is a Gradle validation FAILURE, and
it broke the zenit-forms build. Fixed by excluding `**/public/**` (served assets
are machine-produced; a NUL there was never typed by a human) and re-verified.

Before opting anything in, I simulated the exact rule over all 26 repos: 6,505
files scanned, 1 flagged — my own `CheckNulBytesTask.java`, because the task
prompt's literal NUL character came through into my docblock. That is why the
docblock now says "a backslash-u unicode escape" in prose: it cannot be written
literally in a Java comment without becoming one. Fixing it brought the workspace
to zero, which is why opting in every repo was safe.

Final sweep: **26 repos, 6,525 text files, 0 raw NUL bytes.**

## Missed-sweep audit (re-run with `grep -a`)

The locale-fold sweep, re-run over all five previously-invisible files:

- `MicrocopyMongoImporter` lines 317 and 320 — the two sites the 250-site recon
  sweep missed and the compile-time guard later caught. They are ALREADY FIXED
  (both now `toLowerCase(Locale.ROOT)` / `toUpperCase(Locale.ROOT)`), and this is
  a `server/` source set where `Locale.ROOT` is the legal spelling. Nothing else
  was hiding in that file. The guard caught everything.
- `FormConcurrency`, `FileSecurityEventSinkTest`, `FileMailStore` — clean.
- `zenit/tools/zenit-dev` — 5 no-arg `toLowerCase()`/`toUpperCase()` calls.
  **These are NOT defects and must not be "fixed".** The Turkish-I hazard is a
  JAVA property: `String.toLowerCase()` follows `Locale.getDefault()`. In
  JavaScript the polarity is reversed — `String.prototype.toLowerCase` is
  locale-INdependent (Unicode Default Case Conversion) and `toLocaleLowerCase`
  is the locale-sensitive one. There are no `toLocaleLowerCase`/`toLocaleUpperCase`
  calls in the file.

Other sweeps re-run over `zenit-dev` now that it is greppable (6,625 lines, the
most-swept file in the workspace): no hardcoded `/home/skerit` paths, no
cron/timers/daemons (every "daemon" hit is Gradle-daemon prose), no shell-injection
interpolation into `exec`, no TODO/FIXME/XXX/HACK. It is clean.

## Findings surfaced (NOT fixed — separate issues)

1. **`textum/locale-folds.guard` is INERT.** textum applies only
   `be.elevenways.protoblast.publish`, and `PublishModulePlugin` does not wire
   `configureLocaleFoldGuard` — only the full `ProtoblastGradlePlugin` does. The
   marker file gives false confidence. textum has no mirrored drift test either.
   Its production sources are clean today (its 5 no-arg folds are all in
   `browserTest`, which the rules exempt), so nothing is broken — but the guard
   everyone believes is running there is not.
2. **Locale-guard coverage is partial.** 9 of 26 repos carry the marker; 6 repos
   are plugin-less and only 2 of those (protoblast, hawkeye) have mirrored drift
   tests. duiventil, emberglyph, janeway and textum have no locale enforcement at
   all. (duiventil is clean; I checked.)
3. **`emberglyph/.../term/TerminalEmulator.java` contains raw ESC (0x1b) bytes.**
   Not a grep-blindness defect (ESC is valid ASCII, so grep still reads the file),
   and arguably defensible in a terminal emulator, but it is the same authoring
   smell. Left alone deliberately.
4. **Flaky test under concurrency.** `zenit-dev.test.js` -> "a hawkeye change
   repackages the protoblast plugin fat jar before consumers build" failed ONCE
   during a gradle-hosted run whose log showed it waiting on the machine-wide
   gradle exclusion held by another PID. The test asserts on gradle INVOCATION
   ORDER, which that contention perturbs. It passed standalone 3x and
   gradle-hosted on re-run. Not caused by this work, but it is order-sensitive
   and will bite again while agents run in parallel.

## Verification performed

- `zenit-dev build`: protoblast, zenit, zenit-microcopy, zenit-cms, zenit-forms,
  plumage, duiventil, orcono/mvp-v01 — all green with the gate active.
- `zenit-dev test --unit --class FileSecurityEventSinkTest`: 5/5.
- `node tools/zenit-dev.test.js`: 39/39 (38 pre-existing + 1 added).
- Class-file byte-identity for all four Java sites.
- Gate proven to fail and recover on a deliberate reintroduction (build-time).
- Hook proven to refuse a real `git commit` in a plugin-less repo (commit-time).
- Full-workspace byte sweep: 0 NULs in 6,525 text files across 26 repos.
- External repos (hohenheim, testbeds x2) scanned BEFORE the hook rollout:
  0 pre-existing NULs, so nobody's commits are newly blocked.

## Tests added/updated

- `zenit/tools/zenit-dev.test.js`: new 5-step behaviour journey
  "precommit guard refuses raw NUL bytes, which make grep skip the whole file" —
  refusal with file:line, the escape sequence passing, binary files never flagged,
  extensionless shebang scripts checked, and the same-line deliberate marker.

## Commits (23, one per repo)

- `protoblast` 9b37ebc — 🛡️ Gate builds on raw NUL bytes in text sources
- `zenit` — 🐛 Escape raw NUL bytes and refuse new ones at commit time
- `zenit-cms` — 🐛 Escape the raw NUL null-sentinel in FormConcurrency
- `zenit-microcopy` — 🐛 Escape the raw NUL key separator in the Mongo importer
- `duiventil` — 🐛 Escape the raw NUL mailbox cache-key separator
- 18 marker-only — 🛡️ Opt into the checkNulBytes compile gate
  (herald, orcono, plumage, proteus, quirkyquarters, spamservice, thoth,
  zenit-a2ui, zenit-ai, zenit-auth, zenit-comms, zenit-flow, zenit-forms,
  zenit-media, zenit-oidc, zenit-pages, zenit-widget; zenit's marker rode its
  own commit)

All subjects are under 72 chars with a real Unicode gitmoji first character and a
blank line before the body. Nothing pushed.

## Known limitations / follow-ups

- `protoblast` itself is not gated by its own task (it does not apply its own
  plugin). It is covered by the pre-commit hook.
- The Gradle gate is per-Gradle-PROJECT: in multi-project builds only the root
  project's marker is consulted. Same property as the locale gate.
- `**/public/**` is unscanned by the Gradle gate (generated sink). The pre-commit
  hook still checks those files if they are ever staged.
- Finding 1 (textum's inert locale marker) is a real gap in a DIFFERENT guard and
  is left for a decision: either move textum to the full plugin or give it a
  mirrored drift test.
