# PR #247 — Retire token counters and add outbound transcode workers

**Branch:** `codex/lean-playback-worker-adr` → `main` · 100 changed files (+10,439 / −5,397) · Closes #76, #213

**Verdict:** 🟠 **Approve with changes** — no Critical/High correctness defects. The security core (fail-closed playback authority, mTLS/SPIFFE identity, RFC 9068 `aud`, revocation) is sound and independently verified. Open items are a lock-liveness fix, two PR-claim-contradicting logging gaps, a permanent-failure UX gap, one dead method, one unvalidated command type, and a cluster of resilience/security test gaps (two of which need small production seams to be testable).

**Legend:** 🔴 critical/high · 🟠 medium · 🟡 low · ⚪ nit. `CONFIRMED` = traced to code / reproduced by ≥1 independent verifier; `NEEDS-VERIFICATION` = plausible, severity depends on intended design.

Reviewed by 5 specialized agents (auth, streaming/remote/worker, silent-failure, test-coverage, type-design) + orchestrator spot-verification of the security-critical core.

---

## Empirical validation (reproductions run)

Every test-shaped finding was reproduced and run (worktree `repro/pr-247-findings`, 9 test files, nothing committed). **No false positives** — every CONFIRMED finding reproduced as predicted. Two "findings" were reclassified from bug to coverage, and one (L2) had its severity revised down when a faithful repro proved unreachable.

**Failing tests** (assert desired behavior → red on current code):
- **M1** `Pr247RegistryReproTest.m1_…` — ❌ CONFIRMED; concurrent `disconnect` blocked **2.04s** behind an in-progress publish (log timestamps: connected 33.846 → disconnected 35.882).
- **M2** `Pr247SegmentUploadObserverLoggingReproTest` — ❌ CONFIRMED; `onError` logged nothing (empty appender).
- **L1** `Pr247CreateAuthSessionCommandReproTest` — ❌ CONFIRMED; null `accountId` raises nothing.
- **L4** `Pr247StreamSessionAuthorityReproTest` — ❌ CONFIRMED; builds with null `authority`.

**Characterization tests** (document the defect → green; independently re-run):
- **M3** `RemoteFailureVisibilityIT` — ✅ `1/0/0`; real mTLS worker fails variant (`JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED`) → client gets bare 404, not in {410,502,503,500}; no FAILED state.
- **M4** `WorkerUploadBackpressureIT` (`1/0/0`) + `SegmentUploadAdmissionExhaustionTest` (`2/0/0`) — ✅; single `RESOURCE_EXHAUSTED` → `failVariant`, `uploadAttempts==1`, engine stopped. No retry.

**Reclassified by running the test:**
- **GAP-T1** `Pr247RegistryReproTest.gapT1_…` ✅ passes → failover **works**; downgrade to *missing coverage*, not a latent bug.
- **GAP-T3** `Pr247FailClosedConsumerReproTest` ✅ passes → `accessSession` propagates a gate exception; fail-closed holds today → *missing regression guard*, not a defect.
- **N1** `Pr247AuthenticatedIdentityFromJwtReproTest` ✅ passes → raw NPE real but unreachable → stays a nit.

**Severity revised down:**
- **L2** — code smell real (inspection + 2 agents) but NOT faithfully reproducible: a closed gRPC send silently drops rather than throwing, and any wound that makes it throw also fires the disconnect→engine-reap path. Practical exploitability is below Low / informational-defensive.

**Inspection-validated:** M5 (0 production callers of `reissueFor`, one test). L3 / L5 / L7-GAP-T2 confirmed by inspection (not test-shaped; L7/GAP-T2 blocked on an injectable-interval seam).

---

## Verified holds (do not re-litigate)

- **PR-202 revocation-bypass class is ABSENT.** All three write paths are safe: `SessionScopeService.revalidateStoredContext` clears selection via the conditional `updateSelectionIfLive` (`WHERE ID=? AND REVOKED_AT IS NULL`), `selectHousehold/Profile` re-lock `FOR UPDATE` and re-check `revokedAt`, and `PasswordChangeCompletionService.complete` re-locks via `lockById` + conditional `revoke`. No detached blind-merge remains.
- **Fail-closed playback authority.** `hasLivePlaybackAuthority` is a jOOQ `fetchExists` **scalar** read (dodges the jOOQ+JPA first-level-cache footgun), a single atomic statement (no check-then-act), enforcing all 8 ADR-0018 conditions. `LivePlaybackAuthorityGate.allows` returns the boolean unwrapped; every caller (`HlsStreamingService.createSession:52` / `accessSession:122`, `PlaybackTokenIssuer.issue`) propagates a thrown `DataAccessException` → deny, never allow.
- **RFC 9068 `aud`** minted by both issuers (`.audience(List.of(properties.audience()))`) and enforced by the decoder (`JwtClaimValidator`, fail-closed on null/mismatch); defaults consistent (`streamarr`/`streamarr`) → no availability regression.
- **SPIFFE/mTLS identity fail-closed.** Rejects CA-flagged leaves (basicConstraints/keyCertSign/crlSign), requires exactly one URI SAN, canonical-UUID round-trip equality, wrong trust domain; interceptor rejects null TLS session / non-X.509 leaf / `SSLPeerUnverifiedException` with `UNAUTHENTICATED`. `ClientAuth.REQUIRE`.
- **Segment upload admission** is balanced (one acquire per stream, idempotent `close()` releases once), bounded (16 MB/segment, 64 MB budget, 32 slots, 128 KB frame), fenced under the per-connection monitor (rejects late uploads that would re-create a destroyed session dir).
- **Reaper isolation + no orphaned segments** (`try/catch` per session; `destroySession` deletes segments in `finally`). **Startup rollback** attaches cleanup failures via `addSuppressed`, rethrows original.
- **FFmpeg engine extraction is behavior-preserving** (pure rewire; only `startNumber`→`startSequenceNumber`; `availableSlots()==MAX_VALUE` keeps local gating intact). No coverage lost.
- **Migration V049** drops 3 columns + 1 sequence in correct order (column before its owned sequence). jOOQ regenerated. No dangling references.
- **Design tradeoff (documented, not a defect):** counter removal means an ordinary **API access token stays valid to its ≤15-min `exp`**; logout/password-change bite at the refresh checkpoint. Playback stays instantly revocable via the live gate. Recorded in ADR 0016/0018, internally consistent with code.
- **Tests:** zero hard-rule violations (no `verify()`/`ArgumentCaptor`/reflection/`spy()`/made-public-for-testing). Revocation, `aud` both-sides, SPIFFE-at-real-mTLS-boundary, segment-race, jOOQ/JPA staleness net all covered.

---

## Findings to address

**Destination tracks** (tagged per finding; full homed inventory in Recommended action):
**[A]** PR-247, pre-merge — blocks the PR · **[B]** ADR-0019 robustness branch (`codex/adr-0019-recoverable-hls-delivery`) · **[C]** PR-247 cleanup — should-fix / nice-to-have, non-blocking.

### 🟠 Medium

- [ ] **[A] M1 — `publishIfAuthorized` holds the registry-wide monitor across filesystem I/O.** CONFIRMED. `LiveWorkerConnectionRegistry.java:119` (`synchronized`) runs `prepared::publish` (`Files.move`, with full-byte-copy fallback cross-device) under the whole-registry lock; `register`/`disconnect` share that monitor. With ≤32 concurrent uploads, publishes serialize globally **and block new worker connect/disconnect** for the move duration. Same lock-across-slow-I/O class as the Hikari-exhaustion incident. Correctness doesn't need it — the per-connection monitor + `workerSessionId` fence already make check-then-publish atomic. **Fix:** drop `synchronized` from registry-level `publishIfAuthorized`/`authorizesUpload`, keep per-connection sync.

- [ ] **[A] M2 — Transport error reasons discarded (`onError(Throwable ignored)`).** CONFIRMED (2 sites, orchestrator-verified). `WorkerSessionGrpcService.java:132` (control plane) and `SegmentUploadObserver.java:91` (upload) drop the throwable. **Directly contradicts the PR body's "failure reasons logged on both sides instead of discarded."** Behavior is safe (admission released, disconnect handled) but every abnormal worker drop / mid-upload reset is undebuggable. **Fix:** `log.warn(... , throwable)` before `disconnect()`/`close()`; rename the param.

- [ ] **[B] M3 — Permanent remote transcode failure reaches the player as a generic 404.** CONFIRMED behavior / NEEDS-VERIFICATION severity. `JOB_ATTEMPT_FAILED` is logged and the slot freed, but nothing transitions the `StreamSession`/variant to FAILED (no remote analogue to the reaper's local dead-process handling). The client, blocked in `waitForSegment` (bounded 10s), gets the same 404 as "not ready yet"; the session lingers until idle-reaped. Bounded (not an infinite wait) but the permanent-vs-transient distinction is lost end-to-end. **Fix / confirm:** transition the variant to FAILED so the controller can return a distinguishable status (502/410) or trigger re-dispatch; if client-retry-until-timeout is the intended UX, document it in ADR 0018.

- [ ] **[B] M4 — Admission pressure → permanent whole-variant failure, no backpressure/retry.** CONFIRMED behavior / severity depends on scale. `RESOURCE_EXHAUSTED` (64 MB budget ≈ 4 max segments, or 32 slots) surfaces on the worker as `uploadVariant` catch → `failVariant(TRANSCODE_FAILED)`, killing FFmpeg and failing the entire variant for a *transient* condition. **Fix / confirm:** if intended concurrency stays well under budget, acceptable; otherwise add bounded retry on `RESOURCE_EXHAUSTED`. (Related to M3 — both are "one signal → permanent variant death.")

- [ ] **[A] M5 — Dead production method `reissueFor`.** CONFIRMED. `RefreshTokenService.java:89` — sole former caller (old `PasswordChangeService`) now delegates to `PasswordChangeCompletionService`; only a test (`RefreshTokenServiceTest.java:316`) keeps it reachable. Works against SonarCloud Maintainability-A. **Fix:** delete method + orphaned test, or wire to a real caller.

### 🟡 Low

- [ ] **[A] L1 — `CreateAuthSessionCommand` has no validating compact constructor** (type agent's one pre-merge ask). CONFIRMED. `CreateAuthSessionCommand.java:7` — the only command in the PR with zero enforcement; `accountId` is required and dereferenced in `RefreshTokenService.createSessionAndToken` but a null slips through to fail late. **Fix:** `Objects.requireNonNull(accountId, ...)` (keep the two selection UUIDs nullable — legitimately optional).

- [ ] **[C] L2 — Worker `startVariant` can leak an FFmpeg process.** CONFIRMED by **2 agents** (silent-failure L2 = streaming L4). `TranscodeWorker.java:148-165` — if `send(JobAttemptStarted)` throws after `engine.start` + `activeVariants.put`, the catch only deletes the output dir + `sendFailure`; it does **not** `engine.stop` / remove the entry (process keeps writing to a deleted dir). Self-healing when the send fails because the stream is dead (`onError → stopActiveVariants`); bites only on a non-fatal send failure. **Fix:** route the catch through `failVariant` (stops engine, removes entry).

- [ ] **[C] L3 — Identity interceptor catches only two exception types.** CONFIRMED (defensive). `WorkerIdentityServerInterceptor.java:42` catches `SSLPeerUnverifiedException | WorkerIdentityException`; any other `RuntimeException` from `workerId(leaf)` escapes as a framework-level abort (still fail-closed, not a bypass) instead of a logged `UNAUTHENTICATED`. **Fix:** add a final `catch (RuntimeException e) { return reject(call, e.getMessage()); }`.

- [ ] **[C] L4 — `StreamSession.authority` is nullable/unenforced** (new security field). CONFIRMED (latent). `StreamSession.java:22` — Lombok `@Builder`, no construction validation; `isOwnedBy` null-guards (fails closed) but `getAuthority()` can hand out null → NPE in `PlaybackRequest`. Both production sites always set it. **Fix:** `@NonNull` on the field (Lombok honors it on builder-set fields) or validate at the single production site.

- [ ] **[B] L5 — `readSegment` after positive `waitForSegment` can 500 on a destroy race.** CONFIRMED (very narrow). `StreamController.serveSegment` — if `destroySession` removes the dir between the wait and the read, `resolveSegmentPath` throws `TranscodeException` (uncaught; no `@ExceptionHandler`) → 500 instead of 404. **Fix:** catch `TranscodeException` in `readSegment`, return not-found.

- [ ] **[C] L6 — Stale test name.** CONFIRMED. `RefreshTokenServiceTest.java:135` still says `...SupersededReplayWithinGrace` after the record rename to `SupersededRetry`. Assertion is correct; rename for consistency.

- [ ] **[B] L7 — "Dead worker fails over" only for known-cancelled calls, not half-open.** NEEDS-VERIFICATION. `dispatch`/`trySend` treats `onNext` throwing as dead → next worker; a truly half-open connection buffers (no throw) until server keepalive reaps it (~40s). Bounded degradation. Confirm the worst-case window is acceptable.

### ⚪ Nit — all Track C

- [ ] **N1 — `roles` claim `getFirst()` NPE instead of clean 401.** NEEDS-VERIFICATION (unreachable — own issuers always set single-element `roles`; `aud` validator rejects pre-existing tokens first). `AuthenticatedIdentity.java:56`. Optional guard.
- [ ] **N2 — proto-leak ArchUnit rule doesn't cover the worker package.** `ArchitectureTest` scopes `com.streamarr.server..`; the worker (`com.streamarr.transcode..`) proto-at-edge discipline is convention-only. Optional: second `@AnalyzeClasses`.
- [ ] **N3 — `LocalTranscodeExecutor.availableSlots()` returns `Integer.MAX_VALUE`**, rendered verbatim as `"availableSlots": 2147483647` in health output. Omit the detail in local mode.
- [ ] **N4 — `AuthenticatedIdentity.playbackAuthority()`** has an unexpressed PROFILE/PLAYBACK-scope precondition (leaks NPE from `PlaybackAuthority`). Callers guard; optional domain-exception guard.
- [ ] **N5 — cosmetic:** `TokenClaims.STREAM_SESSION_ID` verbose vs sibling short codes; missing `requireNonNull` messages in `PemTlsIdentity`/`TranscodeWorkerConfiguration`/`PasswordChangeCompletionCommand`; `TranscodeRequest`/`TranscodeHandle` lack null/range validation (always builder-built).

---

## Test-coverage gaps (SonarCloud 80% on new code + security surface)

- [ ] **[B] GAP-T1 — Cross-worker dispatch failover untested** (crit 7, drops to 3 if single-worker-per-namespace is the intended topology). `LiveWorkerConnectionRegistry.dispatch:60-67` loop has zero coverage; existing test only proves a *single* dead worker returns false. **Clarify intent first**, then add a two-registration (one cancelled, one healthy) failover test.
- [ ] **[B] GAP-T2 — Server-side keepalive eviction + slot release untested** (crit 7). Only the *worker* side observes half-open (`TranscodeWorkerKeepaliveIT`); the server's 30s keepalive reaping a frozen worker and releasing slots is unexercised. **Needs production seam:** make `WorkerSessionServer` keepalive interval injectable (currently hardcoded `KEEPALIVE_TIME_SECONDS=30`), then add a `FreezableRelay` recovery test.
- [ ] **[A] GAP-T3 — Fail-closed authority not pinned at the consumers** (crit 6). Throw→deny proven only at the gate; `FakePlaybackAuthorityGate` can't throw, so a future `try/catch → return session` in `HlsStreamingService` would merge green. **Fix:** add a `failWith(RuntimeException)` mode to the fake + `shouldPropagateAuthorityFailureWhenAccessingSession` (and `createSession`) tests.
- [ ] **[B] GAP-T4 — Worker→server failure-reason fidelity not asserted e2e** (crit 5). ITs assert the slot is freed, never which `JobAttemptFailure` enum crossed the wire. Add a captured-log assertion that `TRANSCODE_FAILED` reaches the server.
- [ ] **[C] GAP-T5 — Real-cert CA-flagged-leaf rejection is mock-only** (crit 5). Add a `ca-flagged-worker-cert.pem` fixture + `WorkerSessionServerIT` rejection test (closes the one real-X.509-parsing hole the mock can't cover).
- [ ] **[C] GAP-T6 — K8s manifest mTLS wiring unasserted** (crit 5). `KubernetesManifestContractTest` checks hardening but not: worker `command:[worker]`, read-only `workload-identity` mount in both containers, TLS env → mount path, worker SPIFFE SAN trust-domain == server's, key-usage split, `limits.memory`.
- [ ] **[C · +B: `LocalSegmentStore` interrupt branch] Minor:** plain (non-race) `PasswordChangeRevocationIT`; new `application.yml` keys in `PackagedConfigurationTest` (remote-disabled default, issuer/audience); `AuthTokenProperties` defaulting; `LocalSegmentStore.waitForSegment` interrupt branch; TLS-material startup parse-failure; actuator health-group exclusion invariant.

### Test-quality — all Track C

- [ ] **`HlsStreamingServiceTest:218,227` assert `authorityGate.checkCount()==0`** through the Fake — Fake-based call-counting (borderline hard-rule adjacency; flagged by 2 reviewers). The outcome (empty `Optional`) is already asserted. Recommend dropping the `checkCount` assertions or reframing the short-circuit as an explicit outcome contract.
- `aud` rejection assertions are type-only (`JwtValidationException`); add an `OAuth2Error`-code check + a truly-absent-`aud` case.
- `MembershipTokenRevocationIT` name over-promises (tests the authority *window*, not access-token revocation) — consider renaming.

---

## Recommended action — homed inventory

Every review finding is assigned to exactly one destination track. Nothing is left un-homed.

### Track A — PR-247, pre-merge (blocks the PR)

- **M1** — registry lock across `Files.move` (drop registry-level `synchronized`, keep per-connection).
- **M2** — `onError(Throwable ignored)` discards the reason at 2 sites (also corrects the false "logged on both sides" PR-body claim).
- **M5** — delete dead `reissueFor` + its orphaned test.
- **L1** — `requireNonNull(accountId)` on `CreateAuthSessionCommand`.
- **GAP-T3** — add `FakePlaybackAuthorityGate.failWith(...)` + the fail-closed-at-consumer tests (restored to pre-merge; it was dropped from the earlier §1–3 list).

### Track B — ADR-0019 robustness branch (`codex/adr-0019-recoverable-hls-delivery`)

These fold into the ADR-0019 build plan (recommendation §1–3), written TDD-first through the conformance corpus. On PR-247 itself, **M3/M4 ship as a documented known-limitation note (ADR 0018) pointing at ADR-0019** — not band-aided.

- **M3** — pull-based recovery (re-dispatch to another worker), not a distinct-status band-aid.
- **M4** — bounded retry of the transient `RESOURCE_EXHAUSTED` at the producer.
- **L5** — catch `TranscodeException` in `readSegment` (segment-serving path reworked here).
- **L7** — half-open failover window (recovery/redundancy behavior).
- **GAP-T1** — cross-worker failover regression test (behavior verified; the test is still owed).
- **GAP-T2** — server keepalive eviction test + the injectable-interval production seam.
- **GAP-T4** — worker→server failure-reason fidelity e2e (depends on M2 landing on A).
- **Minor** — `LocalSegmentStore.waitForSegment` interrupt-branch coverage.

### Track C — PR-247 cleanup (should-fix / nice-to-have, non-blocking)

- **Should-fix:** L2 (FFmpeg leak — also touches B's recovery path), L3 (interceptor catch-all), L4 (`StreamSession.authority` `@NonNull` — security-adjacent), GAP-T5 (CA-flagged-leaf real cert), GAP-T6 (K8s mTLS wiring).
- **Nice-to-have:** L6 (stale test name); N1–N5 (nits); minor coverage (`PasswordChangeRevocationIT`, `application.yml` keys, `AuthTokenProperties` defaulting, actuator health-group invariant); test-quality (`checkCount` assertions, `aud` type-only assertions, `MembershipTokenRevocationIT` naming).

### Reclassified (verified not-a-defect — the "fix" is a test/doc, not code)

- **GAP-T1**, **GAP-T3** — behaviors pass under reproduction; each owes only a regression guard (homed to B / A above).
- **M3 / M4** on PR-247 — carried as a documented v1 limitation pointing to ADR-0019, so PR-247 ships honestly while the robust fix lands on B.
