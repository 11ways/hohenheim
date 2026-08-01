# Second-review findings on the 2026-07-31 remediation

Source: an independent reviewer inspected source, diffs, tests, repo state and the
zenit-dev journal AFTER the remediation was declared complete. No files were changed
by that reviewer. Its claims are UNVERIFIED and must be treated as hypotheses.

Prior remediation ledger: /home/skerit/projects/javaweb/REMEDIATION-2026-07-31.md
Prior recon + per-issue reports: <this scratchpad>/prior/{recon,reports}/

Repo HEADs at the start of this session (all worktrees clean):
zenit 8b6a60b, zenit-auth af25fa6, hawkeye ab61cb43, protoblast c76381a,
plumage 37bde67, zenit-cms 5408bcd, zenit-forms 59f6f57, textum 6ae80d5,
orcono ecd8707.

---

## F1 (High) - D1 remains exploitable through explicit vocabularies
`RecordSource.Builder.build()` canonicalizes projection, sort, search and timestamp
fields, but NOT fields captured by `vocabulary(...)` / `vocabularyFrom(...)`:
`zenit/src/common/java/be/elevenways/zenit/common/data/RecordSource.java:1146-1174,1248-1255`.
`VariableDefinition.forField()` retains the supplied field in its criteria compiler:
`zenit/src/common/java/be/elevenways/zenit/common/orm/query/rules/VariableDefinition.java:70-79`
and `FieldRules.java:56-69`. A forged non-secret field named like a secret schema column
can therefore become a filtering oracle. The canonical-field test omits vocabulary facets.

## F2 (High) - B2 still has an unguarded last-administrator mutation path
`GrantService.createDirectGrant(...)` can overwrite an existing wildcard grant with
`false` without invoking `AdministratorGuard`:
`zenit-auth/src/server/java/be/elevenways/zenit/auth/server/GrantService.java:38-69`.
Deletion is guarded at 88-96, CMS writes guarded separately, but the ordinary-grant
mutation path is not. Violates the required cross-write invariant.

## F3 (High) - B1 permits indirect self-pinning through roles
Self-edit refusal only covers direct `"user"` targets:
`zenit-auth/.../GrantAdministration.java:58-65`. Role edits call the same policy with
`"group"`: `AuthRolesResource.java:224-233`. A role member can add a currently held
delegable permission to their own role and preserve it after its original source is
revoked. Tests cover direct self-edits only: `AuthGrantAdministrationTest.java:146-186`.

## F4 (High) - C14 does not cover real wildcard/exact hostname overlap
Conflict detection compares canonical hostname strings for equality:
`hohenheim/src/server/java/be/elevenways/hohenheim/server/cms/SiteDomainResource.java:257-263`.
Claims retain the literal hostname: `server/proxy/RouteClaims.java:81-100`. Exact
`foo.example.com` and wildcard `*.example.com` can coexist despite routing the same
hostname. The test marks the identical literal `identity.example.com` as wildcard
instead of testing an actually overlapping wildcard:
`RouteOwnershipInvariantTest.java:383-396`.

## F5 (High) - A5 still omits Textum's Hawkeye dependency
`baseDepsFor("textum")` returns only Protoblast: `zenit/tools/zenit-dev:937-949`.
Textum directly consumes `hawkeye-common`, `hawkeye-server`, `hawkeye-client`:
`textum/build.gradle:99-123`. Hawkeye is absent from `OPTIONAL_LIBRARIES`, so scanning
cannot recover the edge. A Hawkeye-only change can leave Textum and consumers falsely fresh.

## F6 (High) - F14 still allows silent source-gate widening
`reportGateLoss()` detects only COMPLETE absence of permission / access criteria /
login requirements: `zenit/.../data/RecordSourceRegistry.java:145-167`. Replacing a
strong permission with a weaker one, or a restrictive access predicate with a broader
one, remains silent because both facets are present. Loss of `editUrl` and inline-create
behaviour is also not diagnosed.

## F7 (High) - final verification is not green
zenit-dev journal records two forced zenit unit reruns failing:
- 2019 tests: 1932 passed, 19 failed, 68 skipped
- 1848 tests: 1771 passed, 32 failed, 45 skipped
A targeted `BrandTest` rerun passed 3/3, but there is NO final passing broad suite.
The 129s dependency-chain build did pass.

## F8 (Medium) - E4 still does contextual DB work before path-baseline auth
Deferral is selected only from endpoint-declared authorization:
`zenit/.../routing/Endpoint.java:362-379`. `HttpConduit` resolves endpoints before
middleware: `zenit/.../server/http/HttpConduit.java:434-478,523-553`. An endpoint
protected only through an `AuthRegistry` path baseline can execute `ModelParam`
queries before authentication.

## F9 (Medium) - F1 explicitly retains the reactive authored-disabled bug
`hawkeye/hawkeye-core/.../common/directive/ListDirectives.java:226-244` acknowledges
that an authored `disabled` added after directive ownership is removed when the list
boundary relaxes. Does not meet the required independent reactive-writer behaviour.

## F10 (Medium) - F2/F4 use locale-sensitive normalization
`TemplateWiringAdvisor.java:568-570` and `RetiredAttributeRegistry.java:49-61` use
no-arg `toLowerCase()`. Under a Turkish default locale, uppercase `I` can bypass the
inline-handler or retired-attribute sets. Should use locale-independent ASCII normalization.

## F11 (Medium) - E6 does not apply `Endpoint.acceptsMatch()`
Actual routing applies `matchWhen` through `HttpConduit.java:548-553`;
`ScopedCspMiddleware.claimingRoutesOf()` uses structural matching only. An endpoint can
claim CSP policy for paths its own route guard rejects.

## F12 (Medium) - F7 has a detached-page failure path
Successful saves check `disposed`, but the exception branch always calls
`showSaveStatus`: `orcono/mvp-v01/src/client/java/be/elevenways/orcono/client/EditorSession.java:650-681`.
A failed in-flight save completing after navigation can still mutate its detached page.

## F13 (Medium) - A4 retains an explicit order-dependent exception
`RuntimeClasspathGuard` exempts every `org/teavm/**` duplicate:
`protoblast-gradle-plugin/.../RuntimeClasspathGuard.java:44-49,232-240`.
`TeaVmPatchLane.java:16-20` confirms the patched copy wins through classpath order.
May be a sanctioned lane, but it contradicts A4's categorical no-duplicate-FQN outcome
and is not covered by emitted-code inspection.

## F14 (Medium) - durable review evidence is incomplete
The refreshed manifest references `reports/*.md` and `OWNER-DECISIONS.md`, but neither
exists in hohenheim's repository: `hohenheim/docs/phase0-red-team-manifest.md:396-509,685,722-745`.
The manifest also acknowledges not all counterfactuals were repeated at current hashes.

---

## Reviewer's per-wave status table
A: fixed A2,A6,A7,A10. proof incomplete A1,A3,A8,A11. partial A4,A9. open defect A5.
B: fixed B3,B4,B5,B7,B9. partial/open B1,B2. owner B6,B8.
C: fixed C1-C3,C5-C13. C4 impl correct but concurrency test lacks deterministic CAS-retry barrier. C14 partial.
D: fixed D2-D6,D8. D1 partial + security relevant. owner D7,D9.
E: fixed E1,E2,E5,E7-E10. E3 impl complete but tests only token rotation. E4,E6 partial. owner E11.
F: fixed F3,F6,F8,F10,F11,F13. proof incomplete F5,F9. partial/open F1,F2,F4,F7,F12,F14,F15.
G: G1-G10 structurally intact.

## Reviewer's "other proof gaps" list
- A1 tests `ProjectCompiler` directly rather than the real Gradle incremental/build-cache path.
- A3 proves scheduling with a fake timestamp JAR, not changed Hawkeye compiler contents in the actual plugin.
- A8 uses a persistent build-directory Maven repository rather than proving a newly empty repository.
- A9's workspace inventory scan misses deeper nested build roots.
- A11 has no actual Maven consumer-resolution test.
- F5 does not exercise `formmethod` or `formnovalidate`.
- F9 proves node identity, but not focus/caret/popup state or reorder.
- F12 still has authoritative guidance drift in hohenheim and the uncommittable `resources/` tree.
- F15 leaves the Alchemy coercion occurrence without a durable scope decision.
