# "fc89eaf never compiled" — forensics

## Verdict

1. **The committed content of `fc89eaf` was NOT compilable.** The hotfix message is
   correct about the annotation and correct that HEAD was broken.
2. **The green runs were REAL.** Yesterday's chain build and today's 2056-test run
   compiled a *different*, legal version of `CouchbaseDatasource.java` that was on
   disk. The build tooling never served a stale or skipped compile.
3. **There is no tooling defect.** The failure mode is git index/worktree drift:
   `fc89eaf` committed a version of the file that was never the version being built.
   HEAD was uncompilable for ~19.5 h while every local verification was honest.

## Step 1 — The annotation

`src/server/java/be/elevenways/zenit/server/orm/CouchbaseDatasource.java:58`
imports `org.checkerframework.checker.nullness.qual.NonNull`.

`javap -v` on `checker-qual-4.2.0.jar`:

    java.lang.annotation.Target(value=[ElementType.TYPE_USE, ElementType.TYPE_PARAMETER])

No `PARAMETER`. It is a pure type-use annotation, so it may not precede a
fully-qualified type.

## Step 2 — Empirical reproduction (javac 25.0.3, real checker-qual 4.2.0)

Pre-hotfix spelling:

    Repro.java:4: error: type annotation @org.checkerframework.checker.nullness.qual.NonNull is not expected here
        private boolean bad(@NonNull com.couchbase.client.java.Collection c) { return c != null; }
                                                              ^
      (to annotate a qualified type, write com.couchbase.client.java.@org.checkerframework.checker.nullness.qual.NonNull Collection)
    1 error
    exit=1

Post-hotfix spelling: `exit=0`.

`git log -S` confirms the illegal text existed in HEAD from `fc89eaf`
(2026-07-31 16:06) through `380318d` (2026-08-01 11:23), fixed by `50f8148`
(11:44).

## Step 3 — What the green runs actually compiled

`:test`'s compileClasspath includes `sourceSets.server.output` (build.gradle:194),
so a unit run cannot happen without a successful `:compileServerJava`.

The local Gradle build cache (`org.gradle.caching=true`, 2-day retention) still
holds every `compileServerJava` output across the window. Extracting
`CouchbaseDatasource.class` from each:

| cache entry time | has `assignDocumentWithCas` | md5 |
| --- | --- | --- |
| 07-31 15:56:11 | no  | (pre-CAS) |
| 07-31 16:01:17 | yes | (intermediate, 85070 B) |
| 07-31 16:03:41 | yes | 65a834cb… |
| 07-31 17:03 … 08-01 11:01:34 | yes | 65a834cb… (unchanged) |
| 08-01 11:11:42 (post-hotfix) | yes | e04b97f4… |

So the CAS method was compiled from 07-31 16:01 onward, continuously, and the
compiled content did not change at all between 07-31 16:03 and 08-01 11:01.
The cache entries at **10:46:06, 10:55:04, 10:59:05 and 11:01:34** bracket the
2056-test run (10:48–10:53) — that run compiled and exercised the real CAS code.

### The disk source was a different, legal spelling

`javap -v` diff between the 07-31 16:03 class and today's post-hotfix class shows
the *only* difference is the ordering of `RuntimeVisibleTypeAnnotations`:

    old: param_index = 0,1,2,3,4,5,6
    new: param_index = 1,2,3,4,5,6,0

Both annotate parameter 0. Controlled javac experiment reproduces exactly this:

- `com.couchbase.client.java.@NonNull Collection` (post-hotfix) → param 0 emitted **last**
- `@NonNull Collection` with a simple-name import → param 0 emitted **first**

The pre-hotfix on-disk file therefore carried a leading-position `@NonNull` on a
*simple* type name — a legal spelling — while the committed file carried the
fully-qualified illegal one. The committed text was never the built text.

## Step 4 — Timeline

- 07-31 15:56 — last pre-CAS compile.
- 07-31 16:01 / 16:03 — CAS code compiled, legal spelling on disk.
- 07-31 16:01–16:04Z(+2) — `AtomicUpdateTest --datasources all`, 48 tests, exit 0. **Real.**
- 07-31 16:06 — `fc89eaf` committed. **Committed content ≠ disk content.**
- 07-31 16:09 — chain build, zenit `assemble` exit 0. **Real** (legal disk file).
- 08-01 10:46 → 11:01 — four more successful `compileServerJava` runs, identical output.
- 08-01 10:48–10:53 — full unit run, 2056 total / 1981 passed / 0 failed. **Real.**
- 08-01 ~11:01–11:02 — the worktree file was overwritten with HEAD's content
  (a checkout/restore/stash by some agent; git leaves no trace of this).
- 08-01 11:02–11:11 — zenit `assemble` fails repeatedly. The toolchain caught it
  immediately and correctly, exactly as it should.
- 08-01 11:11:31 — file rewritten (qualified spelling); 11:11:40 class emitted; build green.
- 08-01 11:23 — `380318d` committed, still carrying the illegal line (partial `git add`).
- 08-01 11:44 — `50f8148` finally commits the fix.

The 11:11 class-file-vs-11:44-commit gap the question flagged is explained: the
worktree was fixed 33 minutes before the fix was committed.

## Step 5 — Is there a tooling defect?

**No green-without-compiling defect exists.** Gradle recompiled on every content
change and failed loudly the moment the bad text hit disk. Nothing needs fixing in
zenit-dev's build path, and I changed no repo file.

Two real gaps worth a decision (not implemented — architectural choice):

1. **Nothing verifies that HEAD compiles.** Every verification runs against the
   worktree. A staged-then-further-edited file (`git add`, edit, `git commit`)
   ships a tree that was never built. This is the actual root cause here and it
   will recur. Cheapest honest guard: a `zenit-dev verify-head` that builds from a
   throwaway checkout of `HEAD`, run before push rather than before every commit.
2. **Secondary, independent:** the 2056-test invocation reported
   `total=2056 passed=1981 failed=0` but `invocation.end exitCode=1`
   (task graph `test --rerun t01Verification --rerun`, exit 1). An agent reading
   only the test counts would call that green when zenit-dev returned failure.
   Whatever failed in that run was never investigated. Worth its own look.
