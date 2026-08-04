# PR 261 Combined Review — Device Pairing

Review target: `feat/device-pairing` at `2ef47ccff3cbe953420c63180367661f249ccdfb`,
base `codex/lean-playback-worker-adr` at
`b8f43df69b2d961bb9f1be589fc7ae47fbdd585a`.

This document reconciles the two independent reviews supplied for PR 261. It records the
false-positive assessment, executable reproduction evidence where a claim defines observable
behavior, impact, and the merged recommendation. Production code was not changed while producing
this report.

## Method

Each claim is assigned one of four evidence classes:

1. **RED** — a focused test through a public interface fails on the PR head.
2. **GREEN coverage probe** — the implementation behaves correctly, but the committed suite does
   not pin the behavior claimed by documentation or comments.
3. **Static/mutation evidence** — the claim concerns test strength, source integrity, documentation,
   or observability and cannot honestly be represented as a failing production-behavior test.
4. **Withdrawn/policy** — the claim contradicts a settled contract, lacks a specified expected
   behavior, or is optional hardening rather than a defect.

Temporary reproduction tests are added and run one at a time, then removed so the normal build is
not left intentionally red. Exact test names, commands, and failure reasons are recorded below.

## Impact baseline

The closest available GitNexus index predates the final PR revision, so its counts are lower bounds.
It reports:

- `DeviceAuthorizationService`: 3 direct and 6 total upstream dependents.
- `CanonicalBaseUrl`: 4 direct and 20 total upstream dependents across auth and configuration.
- `DeviceAuthorizationSweeper`: 1 direct dependent.
- `DevicePollResult`: 1 direct dependent.
- `DeviceAuthController`: no indexed upstream caller.
- `DeviceAuthorizationRepositoryCustomImpl`: no concrete caller, explicitly a lower bound because
  consumers bind through the repository interface.

The per-symbol graph risk is LOW, but the overall change risk is **CRITICAL** under the review rubric
because the feature issues credentials, creates sessions, and mutates authentication state.

## Findings and reproduction evidence

### Summary

| ID | Combined-review claim | Evidence | Assessment | Priority |
| --- | --- | --- | --- | --- |
| F1 | Three normative error fixtures are not asserted despite the README saying all literal values are asserted byte-for-byte | Static audit plus three GREEN HTTP probes | Confirmed coverage/documentation defect; current responses are correct | P2 |
| F2 | `DeviceNameTest.java` contains a literal NUL and is binary to Git | Failing byte-level source check; `file` reports `data` | Confirmed source-integrity defect | P3 |
| F3 | Issuance treats every `DataIntegrityViolationException` as a user-code collision | RED unit reproduction | Confirmed production error-classification and diagnosability defect | P2 |
| F4 | The redemption "rollback" IT does not roll back | Static trace plus GREEN PostgreSQL rollback probe | Confirmed misleading/missing coverage; production rollback is correct | P2 test defect |
| F5 | Capacity warnings are level-triggered and use a separate count | Observed 25 consecutive warnings while filling a cap of 50 | Confirmed observability/performance concern; no specified edge-trigger contract | Follow-up |
| F6 | A lost decision that still reads live/PENDING is classified as expired | RED unit reproduction | Confirmed invariant masking; low-likelihood without another defect | P3 |
| F7 | Sweeper tests do not pin survival of live APPROVED grants | GREEN unit probe | Confirmed missing regression coverage; production behavior is correct | P3 |
| F8 | Tests do not pin that approval/denial releases issuance capacity | GREEN parameterized unit probe | Confirmed missing regression coverage; production behavior is correct | P3 |
| F9 | Tests pin post-disable redemption but not lookup/decision | GREEN unit probe | Confirmed missing regression coverage; production behavior is correct | P3 |
| F10 | `AutoSelection` permits profile-without-household | RED unit reproduction | Confirmed type-invariant gap; current factory-only call sites are safe | P3 |
| F11 | The issuance-cap concurrency IT counts every exception as an expected refusal | Stronger GREEN PostgreSQL probe plus source inspection | Confirmed mutation-insensitive test; current production behavior is correct | P2 test defect |
| F12 | Server base URL validation should reject client-side dot-segment vectors | Settled contract inspection | **Withdrawn false positive**: full normalization is deliberately client-side | None |

No P0/P1 implementation defect was reproduced. The core advisory-lock issuance cap, row-lock
redemption, rollback semantics, capacity release, and wire responses behaved correctly in the
stronger probes.

### RED reproductions

All temporary Java reproduction classes were removed after execution. The normal source tree is not
left intentionally red.

#### F3 — broad integrity-violation catch

Temporary test:
`PR261ReviewReproductionTest.shouldSurfaceIntegrityViolationUnrelatedToUserCodeCollision`.

The repository adapter threw a specific
`DataIntegrityViolationException("uq_device_authorization_device_code_digest")`; the public
`DeviceAuthorizationService.issue` interface was expected to surface that same failure.

Command:

```text
JAVA_HOME=/Users/stuckya/.jenv/versions/25 ./mvnw -q \
  -Dtest=PR261ReviewReproductionTest test
```

Result: **RED**. The service logged five user-code-collision warnings, then threw
`IllegalStateException: Could not mint a unique pairing code in 5 attempts.` without the original
cause. This also proves that a device-code-digest collision cannot be repaired by retrying only the
user code: the same device code/digest is reused for all five attempts.

Impact: issuance returns an unexplained 500 and suppresses the actionable constraint/schema cause.
The affected module issues anonymous pairing credentials; GitNexus found 3 direct and 6 total
upstream dependents for `DeviceAuthorizationService`.

#### F6 — impossible live/PENDING lost decision

Temporary test:
`PR261ReviewReproductionTest.shouldFailFastWhenLostDecisionStillAppearsLiveAndPending`.

The fake repository returned zero from the conditional decision write while retaining the same live,
PENDING row. The public `decide` interface was expected to throw `IllegalStateException`.

Result: **RED**. It threw `DeviceCodeExpiredException` from
`DeviceAuthorizationService.classifyLostDecision`.

Impact: an internal invariant failure is hidden as a stable client 400, making the defect look like
ordinary user expiry. The state is not expected from the real query under normal invariants, so the
likelihood is lower than F3.

#### F10 — invalid `AutoSelection`

Temporary assertion:

```java
assertThatThrownBy(() -> new AutoSelection(null, profileId))
    .isInstanceOf(IllegalArgumentException.class);
```

Result: **RED**; construction succeeded. V045 explicitly delegates the profile-requires-household
rule to the application layer because the database cannot enforce it during its `ON DELETE SET NULL`
sequence. `TokenContext` already enforces the same rule.

Impact: current production call sites use `none`, `household`, and `householdAndProfile`, so no live
invalid session was reproduced. A future caller of the public canonical constructor could create a
profile-only session.

#### Type-hardening suggestions

Two suggestions also produced RED results but are not current-flow defects:

- `new DevicePollResult.Success(null, null)` succeeds instead of rejecting missing credentials.
  The production redemption path supplies non-null issuer outputs.
- `CanonicalBaseUrl.of("https://home.example.com", false).resolve("link")` accepts a relative path
  despite the method contract saying absolute path. `DeviceAuthProperties` validates the only
  production caller before this interface is reached.

These are worthwhile compact-constructor/precondition hardening, but should not be represented as
evidence that pairing currently emits null credentials or a malformed verification URI.

#### F2 — binary NUL

Source-hygiene assertion:

```text
perl -0777 -ne 'if (index($_, chr(0)) >= 0) { print "FAIL: Java source contains a NUL byte\n"; exit 1 }' \
  src/test/java/com/streamarr/server/services/auth/DeviceNameTest.java
```

Result: **RED**, exit 1 with `FAIL: Java source contains a NUL byte`; `file` classifies the Java file
as `data`. Replace the raw byte with Java's textual `\0` octal escape.

Impact: runtime behavior is unaffected, but Git cannot provide a normal textual diff, blame, or
three-way merge for the test file.

### GREEN coverage probes

GREEN means the review correctly identified absent or misleading coverage, while the current
implementation passed the stronger behavior test.

| Claim | Temporary probe and result | Impact if later regressed |
| --- | --- | --- |
| F1 unconfigured response | `PR261UnconfiguredContractProbeIT` asserted `devicePairingEnabled: false`, HTTP 503, and exact `not-configured-error.json`; GREEN | Clients cannot reliably discover or handle disabled pairing |
| F1 expired response | `PR261ExpiredContractProbeIT` seeded an expired row and asserted the authenticated decision response byte-for-byte against `expired-error.json`; GREEN | Approver clients branch on the pinned error code/message |
| F1 capacity response | `PR261TooManyAttemptsContractProbeIT` filled the cap and asserted HTTP 429, `Retry-After`, and exact `too-many-attempts-error.json`; GREEN | Anonymous clients lose the retry contract |
| F4 rollback | `PR261RedemptionRollbackProbeIT` replaced `AccessTokenIssuer` with a throwing primary bean after session creation; PostgreSQL showed no session/token committed and the grant remained APPROVED; GREEN | A transient post-session failure could burn a grant or leave an orphan session |
| F7 live APPROVED sweep | `PR261ReviewCoverageProbeTest.shouldRetainLiveApprovedGrantDuringSweeping`; GREEN | An approved device waiting to poll becomes `expired_token` |
| F8 decision releases cap | Parameterized APPROVE/DENY probe issued at cap 1, decided, then issued again; GREEN for both | Repeated successful/denied pairings can starve all new issuance until TTL |
| F9 finish after disable | Probe issued while configured, then used an unconfigured service to lookup and approve; GREEN | A code already displayed on a TV becomes stranded |
| F11 refusal types | `PR261IssuanceCapProbeIT` raced eight issuers against one free slot and required every loser to be `TooManyDeviceAttemptsException`; GREEN on PostgreSQL | The committed test can otherwise pass when SQL/transaction failures replace expected refusals |

Static fixture-reference audit:

```text
expired-error.json
not-configured-error.json
too-many-attempts-error.json
```

Those are the three normative fixtures with no filename reference in `DeviceAuthContractIT`, despite
`docs/contracts/device-pairing/v1/README.adoc` claiming every literal value is asserted
byte-for-byte.

### Static, policy, and optional findings

- **F5 warning behavior:** filling a cap of 50 emitted one warning for every count from 25 through
  50, then one per refusal. This is real log volume and the post-insert count can race with decisions,
  but no contract requires edge-triggering. Treat as observability/performance work, not correctness.
- **Dot-segment finding:** withdrawn. `docs/contracts/server-endpoint/v1/README.adoc` explicitly says
  the server does not implement the client normalization table and lists the deliberately light
  startup checks. Expanding server validation would revisit that settled division of responsibility.
- **Throttle-before-normalize:** policy is unspecified. Decide whether malformed authenticated
  guesses spend budget, document it, then test the chosen behavior.
- **Row-lock timeout:** a timeout/SLO could limit connection-pool damage from a wedged transaction,
  but the desired timeout and client-visible result are not specified. Handle as a focused
  availability design decision rather than embedding an arbitrary test expectation.
- **`Math.addExact` overflow:** theoretically returns 500, but reaching overflow from the configured
  maximum interval requires roughly 429 million early polls within a maximum 30-minute grant. It is
  not an actionable PR defect.
- **`DeviceAuthorizationInsertCommand` temporal validation, entity setter removal, and promoting
  `UserCode` to a record:** optional type/module deepening. Current production builders maintain the
  invariants; no public behavior failure was reproduced.
- **Documentation cleanup:** reword ADR 0021's nonexistent "normal session reaping," remove the
  implication that mDNS behavior exists today, and fix the discovery document's unsupported ADR
  attribution. The claim that `seam` is a banned word is unsupported; the project's codebase-design
  vocabulary explicitly defines and uses that term.
- **Minor test/config cleanup:** no-op `@Valid` annotations, the untyped sweep interval property,
  order-sensitive success-field assertions, missing `uncacheable()` on the decision helper, and
  exact-boundary/absent-body cases are legitimate cleanup or added coverage, not reproduced defects.

## Merged recommendations

### Before merge

1. **Narrow the issuance integrity catch (F3).** Recognize only
   `uq_device_authorization_user_code`, as `SetupService` already does for its expected unique
   constraint. Re-throw every other integrity violation unchanged, retain the cause when attempts
   are exhausted, and add tests for one retry followed by success, exhausted user-code collisions,
   and an unrelated constraint failure.
2. **Make lost-decision classification fail fast (F6).** Keep not-found and non-PENDING outcomes as
   client errors; log and throw `IllegalStateException` when the scalar reread is still PENDING.
   Add deterministic tests for all arms.
3. **Make contract claims true (F1).** Add exact assertions for the unconfigured status/503,
   expired decision, and capacity 429 fixtures. Reword the README so placeholders and configured or
   random success values are described accurately.
4. **Replace false-confidence tests (F4, F11).** Replace or rename the approver-deletion test and add
   the throwing-issuer PostgreSQL rollback IT. Require every losing issuance racer to be
   `TooManyDeviceAttemptsException`, not merely any exception.
5. **Replace the literal NUL (F2).** Use `\0` so the source remains textual.

These are small, behavior-preserving corrections except F3/F6's error classification. They should be
separate behavioral commits from any structural/type hardening under the repository's Tidy First
discipline.

### Strong follow-up coverage

6. Add the GREEN probes for live APPROVED sweep survival, capacity release after APPROVE/DENY, and
   completion of lookup/decision after issuance is disabled. The behavior is already correct; these
   tests protect high-value availability invariants.
7. Add compact-constructor invariants to `AutoSelection` and `DevicePollResult.Success`. Consider
   validating `CanonicalBaseUrl.resolve`'s absolute-path precondition for interface locality, while
   preserving the settled client/server normalization split.

### Discuss separately

8. Decide an operational policy for capacity-warning frequency and whether the atomic insertion
   result should return the observed count. The current output is noisy but not incorrect.
9. Define a lock-wait SLO and client-visible failure before adding a PostgreSQL lock timeout.
10. Apply the documentation/config/style cleanup as a structural change; do not expand it into new
    protocol behavior or a full server-side endpoint-normalization implementation.

## TDD implementation follow-up — 2026-08-04

The actionable recommendations above were implemented after this review was written. Each
production defect or hardening change began with a focused failing test; behaviors already proven
correct by the review's temporary probes were committed as green characterization tests.

| Recommendation | Durable implementation and evidence |
| --- | --- |
| F1 contract gaps | Added exact expired-decision, unconfigured 503, and capacity 429 fixture assertions; added the disabled status assertion and corrected the fixture README. |
| F2 literal NUL | Replaced the raw source byte with Java's textual `\0`; `file` now classifies the source as UTF-8 text and `DeviceNameTest` remains green. |
| F3 integrity classification | A public-service PostgreSQL RED test proved jOOQ reports a real user-code collision as `DuplicateKeyException` → `PSQLException`, not Hibernate's exception. The repository adapter now translates only `uq_device_authorization_user_code` to `UserCodeCollisionException`; the service and fake share that error contract, unrelated failures escape unchanged, and exhaustion retains the final cause. |
| F4 rollback coverage | Renamed the approver-deletion refusal test and added a PostgreSQL test with an injected access-token issuance failure. It proves session creation rolls back, the grant remains APPROVED, and a retry succeeds. |
| F5 capacity observability | Chose edge-trigger warnings at half-full and full. `tryInsertWithinCap` now returns an atomic `{inserted, outstanding}` result from under the advisory lock, eliminating the post-insert recount. |
| F6 lost-decision classification | Added RED coverage for a live PENDING reread and deterministic coverage for terminal and vanished rows. PENDING now fails fast with `IllegalStateException`. |
| F7–F9 availability coverage | Added live APPROVED sweep survival, APPROVE/DENY capacity release, and post-disable lookup/decision/redeem coverage. |
| F10 and type hardening | Added compact-constructor invariants for `AutoSelection` and `DevicePollResult.Success`, plus the documented absolute-path precondition in `CanonicalBaseUrl.resolve`. |
| F11 concurrency assertion | Every losing issuance racer must now be `TooManyDeviceAttemptsException`. |
| Structural cleanup | Removed no-op `@Valid` annotations, bound the sweep interval as a validated `Duration`, made success-field assertions order-independent, asserted decision cache headers, added boundary/absent-body cases, and corrected the ADR/config/discovery wording. |

The row-lock timeout remains deliberately unimplemented: recommendation 9 requires a separately
settled lock-wait SLO and client-visible failure contract before code can encode it. The withdrawn
server-side endpoint-normalization finding was not implemented.

## Verification record

- Six focused RED probes behaved as predicted: a real PostgreSQL user-code collision, unrelated
  integrity violation, live/PENDING lost decision, invalid `AutoSelection`, null
  `DevicePollResult.Success`, and relative-path `resolve`.
- The NUL source-hygiene assertion failed as predicted.
- Eight focused GREEN probes passed, including five Testcontainers/PostgreSQL integration probes and
  three exact HTTP fixtures. The unconfigured, expired, too-many, rollback, and concurrency probes
  were run through Maven Failsafe against PostgreSQL 18.
- The final `./mvnw verify` passed on Java 25, covering Surefire unit tests, Failsafe integration
  tests against PostgreSQL 18, Checkstyle, and Spotless.
- Temporary reproduction-only sources were removed after each run. The durable regression tests,
  production remediations, and this review record remain as the intentional worktree changes.

## Additional adversarial follow-up — 2026-08-04

Four later findings were reviewed against the remediated PR head:

| Finding | Adversarial result and disposition |
| --- | --- |
| Unicode format characters in device names | Confirmed for bidi controls: a RED probe showed U+202E surviving into the sanitized value. The fix removes Unicode's bidi-control set specifically rather than every `FORMAT` character; a ZWJ emoji characterization test protects legitimate Unicode names. |
| Missing or malformed JSON body | Confirmed with a corrected manifestation: `/token`, lookup, and decision returned HTTP 400 with an empty body in MockMvc, not a populated default error shape. RED contract probes now pass against the exact `INVALID_REQUEST` fixture for both absent and malformed bodies. |
| Raw advisory-lock SQL | A real policy exception, not a reproduced defect. The isolated PostgreSQL dialect bridge remains unchanged; deciding whether generic jOOQ function construction is preferable to an explicit exception belongs in the repository's SQL-style policy, not a behavioral test. |
| Extra warning `COUNT` | Stale after the earlier remediation. `tryInsertWithinCap` returns its atomic outstanding count and the focused regression test remains green if any post-insert recount is attempted. |

Post-follow-up verification passed through the complete `./mvnw verify` lifecycle on Java 25,
including the PostgreSQL 18 integration and concurrency suites, Checkstyle, and Spotless.
