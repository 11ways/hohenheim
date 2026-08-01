# Clean-clone buildability — 2026-08-01

Fixes for the three repos the final audit found unbuildable from a fresh
checkout. Every before/after below was produced by this agent from a tree
extracted with `git archive HEAD` into `/tmp/.../scratchpad/cleanclone/`,
never from the working copy.

## Commits

| repo | hash | branch | subject |
| --- | --- | --- | --- |
| hawkeye | `f5e12cf1` | type-system-v1 | 🐛 Register hawkeye tasks from the declaration, not the filesystem |
| zenit-forms | `db9ade9` | master | 🔧 Track gradle.properties so a fresh clone can build |
| zenit-media | `adca0bf` | master | 🔧 Track gradle.properties so a fresh clone can build |

All three: real Unicode gitmoji (U+1F41B, U+1F527), subject 52-65 chars,
subject and body on separate lines, verified with `git log -1`.

---

## PROBLEM 1 — zenit-ai: the design fork, and the decision

### Diagnosis, re-verified before acting

Confirmed independently. `zenit-ai/build.gradle` declares
`hawkeye { templateDirs = ['src/common/templates'] }`; that directory holds no
tracked file, so `git archive HEAD` omits it:

    $ git archive HEAD | tar -x -C .../before/zenit-ai
    $ ls -d .../before/zenit-ai/src/common/templates
    ls: cannot access '.../src/common/templates': No such file or directory

### BEFORE (verbatim, extracted tree, HEAD 7b70e74)

    $ cd .../cleanclone/before/zenit-ai && zenit-dev build --skip-deps
    ── Building zenit-ai (--skip-deps) ──
      fail  zenit-ai
    .../src/server/java/be/elevenways/zenit/ai/server/AiDrySerializers.java:3: error: package be.elevenways.hawkeye.generated.zenitai does not exist
    .../src/server/java/be/elevenways/zenit/ai/server/AiDrySerializers.java:37: error: cannot find symbol
    FAILURE: Build failed with an exception.
    EXIT=1

### DECISION: (b) — unconditional registration. Argued.

I read the registration code before choosing:
`HawkeyeTemplateStage.configureCompilationTasks`
(`hawkeye/hawkeye-compile/src/main/groovy/.../HawkeyeTemplateStage.groovy`).

The reason the existence check *looked* load-bearing turned out to be already
covered one level up, and that is what decided it:

    List<String> templateDirs = extension.templateDirs.get()
    if (templateDirs.isEmpty()) {
        project.logger.info('No template directories configured; nothing to do.')
        return          // <-- THIS is the "repo with no templates" case
    }

`HawkeyeExtension` gives `templateDirs` a convention of `[]`. So a project that
genuinely has no templates **declares nothing** and is filtered by the
`isEmpty()` return, which is untouched. The per-directory `if (!dir.exists())
continue` was therefore not protecting the no-templates repos at all — it was
conflating two different things:

- "this project has no template feature" — a **declaration** fact, already handled;
- "this declared directory currently holds zero files" — a **filesystem** fact,
  which git cannot even represent.

The second is not an error condition. It is a template set of size zero. And the
tasks it was silently de-registering do more than compile `.hwk` files: they also
generate the per-namespace `HawkeyeClassSerializers`,
`HawkeyeCustomElementRegistrations` and `HawkeyeDeclaredClassRegistrations` from
`@HawkeyeClass` **Java** types, which have nothing to do with templates. That is
the whole defect — a module with `@HawkeyeClass` types and no templates yet only
ever compiled on a machine where somebody had `mkdir`'d the directory by hand.

`.gitkeep` (option a) would have left that conflation in the plugin and required
five files nobody may ever delete. Option (b) removes the conflation itself and
disarms every repo at once, so it is what shipped.

### The change (two halves, both minimal)

1. `HawkeyeTemplateStage.configureCompilationTasks` — a declared-but-absent
   directory is still mapped to its source set and still registers
   `preCompile<SS>HawkeyeClasses` + `compile<SS>HawkeyeTemplates`. The
   `warn`-and-`continue` became an `info`. Source-set matching is pure string
   matching on the path, so it never needed the directory to exist.
2. `HawkeyeCompile.execute` — `getSourceDirectories().files.findAll {
   it.isDirectory() }`, so the phantom root is dropped at execution time and the
   compiler bridge only ever receives real roots.

Both carry AIDEV-NOTEs naming the failure mode. `getSourceDirectories` is an
`@InputFiles ConfigurableFileCollection`, which tolerates a missing entry, so no
new Gradle validation failure mode is introduced.

### AFTER (verbatim, same commit 7b70e74, directory still absent)

    $ ls -d .../after/zenit-ai/src/common/templates
    ls: cannot access '.../src/common/templates': No such file or directory
    $ zenit-dev build --skip-deps
    ── Building zenit-ai (--skip-deps) ──
      ok  zenit-ai
      ok  Build completed in 28s
    EXIT=0

Re-run once more after the hawkeye commit landed: `ok`, 45s.

This is a **single-variable A/B**: identical commit, identical extracted tree,
directory absent in both; the only thing that changed is the plugin.

Positive proof the mechanism (not the symptom) was fixed — the generated package
now exists and both tasks are back in the graph:

    $ find build/generated-sources -path '*generated/zenitai*'
    .../generated/zenitai/HawkeyeClassSerializers.java
    .../generated/zenitai/HawkeyeCustomElementRegistrations.java
    .../generated/zenitai/HawkeyeDeclaredClassRegistrations.java

    $ ./gradlew assemble --dry-run | grep Hawkeye
    :preCompileCommonHawkeyeClasses SKIPPED
    :compileCommonHawkeyeTemplates SKIPPED

(Before the fix these two tasks were absent from the graph entirely.)

### Disarming the armed repos — proven, not asserted

Workspace-wide sweep of every `templateDirs` declaration (git repos only),
asking whether the declared directory has any tracked content:

| repo | declared dir | tracked content | status |
| --- | --- | --- | --- |
| zenit-ai | src/common/templates | **none** | was BROKEN, now builds |
| zenit-a2ui | src/common/templates | **none** | was armed, disarmed |
| zenit-comms | src/common/templates | **none** | was armed, disarmed |
| zenit-microcopy | src/common/templates | **none** | was armed, disarmed |
| zenit-oidc | src/common/templates | **none** | was armed, disarmed |
| herald, plumage (x2), proteus, spamservice, thoth, zenit, zenit-auth, zenit-cms (x2), zenit-flow, zenit-forms, zenit-media (x2), zenit-pages, zenit-widget | — | yes | never affected |

All four armed repos extracted with the directory confirmed ABSENT from the
archive, then built:

| repo | result | HawkeyeClassSerializers generated |
| --- | --- | --- |
| zenit-a2ui | ok, 21s | yes |
| zenit-comms | ok, 34s | yes |
| zenit-microcopy | ok, 30s | yes |
| zenit-oidc | ok, 24s | yes |

**Definitive disarm proof** — I reproduced the exact zenit-ai shape in an armed
repo. Planted into the extracted zenit-a2ui tree (directory absent):

    package be.elevenways.trapprobe;
    import be.elevenways.hawkeye.generated.zenita2ui.HawkeyeClassSerializers;
    public final class TrapProbe {
        public static Class<?> probe() { return HawkeyeClassSerializers.class; }
    }

    ── Building zenit-a2ui (--skip-deps) ──
      ok  zenit-a2ui
      ok  Build completed in 21s
    EXIT=0

That is the precise construct that fails on the old plugin, compiling green with
no template directory present. The trap is gone, not merely unsprung.

### arcana — the audit's arcana row is a FALSE POSITIVE

arcana does **not** have this shape. Its declared `src/common/templates` holds
four real templates (`base.hwk`, `home.hwk`, `note_edit.hwk`,
`notes_list.hwk`), so the directory was never empty and never vanished. It is
also not a git repository at all (`fatal: not a git repository`), so
"fresh clone" does not apply to it. **Nothing is uncommittable here** — there is
no arcana fix to make. It is covered by the mechanism fix regardless, since it
consumes the same plugin.

---

## PROBLEM 2 — zenit-forms / zenit-media

### What I found about the ignore rule (it is NOT what the audit assumed)

Both repos ignore `gradle.properties` at `.gitignore:51`, under the comment
`### Gradle daemon marker (written by zenit-dev) ###`. The comment is **accurate
about authorship**: `zenit-dev` really does own that file. `ensureDaemonMarker`
(zenit-dev:1370) reconciles three managed lines — `org.gradle.jvmargs` (heap from
the `PROJECT_HEAP` table, 2g for both repos), `org.gradle.daemon.idletimeout`,
`org.gradle.caching` — writing them on every build.

But tool-authored is not the same as machine-local, and the file's own owner says
so (zenit-dev:1366-1369): *"zenit-dev owns these lines outright; machine-specific
overrides go in `~/.config/zenit-dev/config.json`, not in the repo file."* There
is nothing machine-specific in the content — a heap size, a metaspace cap, a
`/tmp` ErrorFile path, an idle timeout, a caching flag. The per-repo values are
reproducible from zenit-dev's own table.

Convention check, all 26 workspace git repos: **24 track it, 2 ignore it.** The
two are exactly zenit-forms and zenit-media, and in both the rule arrived in the
repo's **initial commit** (`61d48a9`, `73f6c7a`) — copied from a scaffold, never
a deliberate later decision. So the majority convention is the answer, and no
machine-local/build-required split is needed: the file has no machine-local half.

Note on the audit's reproduction: `zenit-dev build` in an extracted tree
**writes the file first** and then succeeds, so it cannot show this defect. The
honest fresh-clone/CI path is the raw wrapper, which is what I used below.

### BEFORE (verbatim, extracted tree, raw wrapper)

zenit-forms, HEAD `59f6f57`, `gradle.properties` absent from the archive
(`git ls-tree HEAD` → NOT IN TREE):

    $ ./gradlew --init-script ~/.local/share/zenit-dev/gradle-init.gradle assemble
    > Task :preCompileCommonHawkeyeClasses
    The Daemon will expire immediately since the JVM garbage collector is thrashing.
    The project memory settings are likely not configured or are configured to an insufficient value.
    The memory settings for this project must be adjusted to avoid this failure.
    These settings can be adjusted by setting 'org.gradle.jvmargs' in 'gradle.properties'.
    The currently configured max heap space is '512 MiB' and the configured max metaspace is '384 MiB'.

    FAILURE: Build failed with an exception.
    * What went wrong:
    Gradle build daemon has been stopped: since the JVM garbage collector is thrashing
    EXIT=1

Gradle itself names the missing file and the missing property.

zenit-media, pre-fix HEAD `ecde56d`, same conditions:

    BUILD FAILED in 18s
    * What went wrong:
    Could not receive a message from the daemon.
    EXIT=1

(Same OOM class — the daemon dies; the wording differs by how far it got.)

### The fix

In both repos: deleted the ignore rule, replaced it with a comment explaining why
the file is tracked and where machine-local overrides belong, and committed the
existing `gradle.properties` unchanged (165 bytes, `-Xmx2g -XX:MaxMetaspaceSize=512m`,
idle timeout, caching).

### AFTER (verbatim, extracted tree, raw wrapper)

zenit-forms `db9ade9` — `gradle.properties: PRESENT` in the archive:

    BUILD SUCCESSFUL in 36s
    26 actionable tasks: 25 executed, 1 from cache
    EXIT=0

zenit-media `adca0bf` — `gradle.properties: PRESENT`:

    BUILD SUCCESSFUL in 37s
    32 actionable tasks: 28 executed, 4 from cache
    EXIT=0

---

## Regression verification for the hawkeye change

The change is in the Gradle plugin, so extracted-tree builds are the verification
that matters. Green from clean extracted trees with the new plugin: zenit-ai,
zenit-a2ui (+ planted probe), zenit-comms, zenit-microcopy, zenit-oidc,
zenit-forms, zenit-media.

Plus a full chain build through a template-heavy consumer — plumage exercises the
`sourceSetNamespaces` path (common + browserTest template sets, the case the
per-source-set registration is most likely to disturb):

    $ cd plumage && zenit-dev build
      ok  protoblast / hawkeye / protoblast-gradle-plugin / zenit / plumage
      ok  Build completed in 148s
    PLUMAGE_EXIT=0

One transient red on the first attempt, worth recording so it is not mistaken for
a regression: `:zenitDevTest` failed on
`zenit-dev.test.js:737 "a hawkeye change repackages the protoblast plugin fat jar
before consumers build"` with `protoblast: m2 jar replaced outside this
workspace`. That is a **cross-session race**, not my change: publishing my
hawkeye fix repackaged `protoblast-gradle-plugin` into mavenLocal while a
concurrently-running agent's zenit-dev test was asserting that a fresh chain
republishes nothing. `zenit`'s javac all passed in that same run. The retry, once
my publishes had settled, was green. That test has a real machine-wide
isolation weakness (it can be perturbed by any concurrent publish) — named, not
fixed, since `zenit-dev.test.js` is owned by another agent this session.

---

## Known limitations / follow-ups

- **hawkeye is committed on branch `type-system-v1`**, not master — that is the
  branch the repo was already on; I did not switch branches.
- **Nothing was pushed.** All three commits are local.
- The fix only takes effect for consumers once the repackaged
  `protoblast-gradle-plugin` fat jar is published (zenit-dev does this
  automatically after every hawkeye publish; it did so during this work). A CI
  that builds hawkeye from source gets it for free.
- `zenit-dev`'s `ensureDaemonMarker` will now rewrite a **tracked** file in
  zenit-forms/zenit-media if the managed values ever drift from `PROJECT_HEAP`,
  showing up as a dirty worktree. That is exactly what already happens in the
  other 24 repos, so it is the convention, not a new problem.
- **Not fixed here** (out of scope, from the audit's list): verify-head
  misreporting daemon OOM as "committed tree does not compile"; the
  clean-worktree fast path; hohenheim's missing pre-commit hook; thoth's
  committed 4.4 MB TeaVM bundle.
- Discovered incidentally: **`grep` in this sandbox silently returns nothing**
  for some files (it reported 0 matches for "gradle" in a file containing 221).
  Every search in this report was done with Python instead. Anyone relying on
  Bash `grep` for audit evidence in this environment should re-check it.
