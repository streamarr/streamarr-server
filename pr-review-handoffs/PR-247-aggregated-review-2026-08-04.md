# PR #247 Review Handoff — Aggregated Findings

- **PR:** #247 "Live playback authority, outbound transcode workers, and recoverable JIT HLS segment delivery" (ADR 0018 + ADR 0019)
- **Branch:** `codex/lean-playback-worker-adr` → `main` · 267 files · +17,555/−6,278
- **Review date:** 2026-08-04
- **Method:** 6 parallel specialized reviews (general code, test coverage, comments/ADRs, silent failures, type design, simplification) plus one supplementary test/docs pass. Findings below are deduplicated; "(×N)" = independently found by N agents.
- **Verdict:** No merge-blocking production correctness defect. Three critical spec/test-integrity gaps, ~8 real bugs (medium), and a large mapped complexity-reduction opportunity: **~350–400 production LOC + ~1,200–1,450 test LOC** removable.

---

## 1. Critical — fix before merge

### C1. ADR 0019's core promise is unpinned; mutation survives the suite
`src/main/java/com/streamarr/server/services/streaming/SegmentDeliveryCoordinator.java:459-462`
Deleting `attemptedSinceProgress.clear()` in the `advanced` branch passes the **entire** suite (verified by trace). Regression allowed on default single-target (LOCAL-only) deployments: die → replace → publish → die again → no untried target → **permanent sticky 503 on a recoverable variant**, inverting ADR 0019's central promise.
**Fix (TDD red-first):** markDead → await replacement → publish (`Ready`) → markDead → deliver next segment → assert a *second* replacement on the same target, not `Unrecoverable`.

### C2. ADR 0019 states the opposite of the implemented cold-start behavior (×3)
`docs/adr/0019-recoverable-jit-hls-segment-delivery.adoc:36` vs `SegmentDeliveryCoordinator.java:218-228`
ADR: threshold "applies uniformly, including a fresh producer's cold start … rather than receiving a separate cold-start budget." Code grants unpublished runs `producerStallThreshold + targetSegmentDuration` (16s vs 10s at defaults); javadoc and `shouldNotReplaceColdStartingProducerWithinItsStartupBudget` (SegmentDeliveryCoordinatorTest:379-399) deliberately pin it. Code/javadoc/tests agree; the ADR is stale.
**Fix:** amend the ADR sentence (code rationale is sound) — record: uniform steady-state threshold plus one target-segment-duration grace for a run that has not yet published its first segment.

### C3. Active household/profile clauses of the authority query are shadowed and partially redundant (×2)
`src/main/java/com/streamarr/server/repositories/auth/AuthSessionRepositoryCustomImpl.java:36-48`, `src/test/java/com/streamarr/server/services/streaming/PlaybackAuthorityGateIT.java:103-110`
Every test uses `UUID.randomUUID()`, which fails the `HOUSEHOLD_MEMBERSHIP`/`ACCOUNT_PROFILE` joins (:38-44) before the `ACTIVE_HOUSEHOLD_ID`/`ACTIVE_PROFILE_ID` predicates (:47-48) ever discriminate — **deleting both predicates merges green**. Regression allowed: playback token minted under profile A keeps streaming after the session legitimately switches to granted profile B (the exact clause ADR 0018 names). Mirror finding: given those predicates + V045 composite FKs (`ON DELETE SET NULL`), the two joins are tautological.
**Fix:** pin with grant-second-membership/profile → switch active selection → assert deny (parameterize the current 4-assertion method). Then decide: drop the redundant joins, or keep as defense-in-depth with a comment saying so.

---

## 2. Important bugs

### B1. Worker never cancels a failed segment-upload RPC (×2) + server retains the buffer after age-reclaim
- `src/main/java/com/streamarr/transcode/worker/TranscodeWorker.java:252-275` — mid-stream `IOException` or ack timeout propagates without `upload.onError(...)`/cancel. Half-open call pins 1/32 global slots, 1/8 per-worker allowances, up to 16 MB byte reservation until the 120s age reclaim. On the timeout path the server may still publish a segment the worker already reported `JOB_ATTEMPT_FAILED` for. **Fix:** try/catch around metadata/chunks/half-close → `upload.onError(e)` (or `ClientCallStreamObserver.cancel`) before rethrowing; cancel on ack timeout.
- `src/main/java/com/streamarr/server/services/streaming/remote/SegmentUploadAdmission.java:83-94` + `SegmentUploadObserver.java:40-45` — `reclaimExpired` frees *accounting* but the observer only notices at its next `onNext`; a wedged-but-alive worker never sends one, so the `ByteArrayOutputStream` heap stays referenced (~528 MB exposure per connection at `maxConcurrentCallsPerConnection=33` × 16 MB). **Fix:** give the `Ticket` a cancellation hook so reclaim cancels the underlying `ServerCall`.

### B2. `startVariant` failure path deletes the output directory before stopping FFmpeg
`TranscodeWorker.java:158-168` — `deleteOutputDirectory` (:164) runs before `failVariant` (:168), which is what stops the engine. In the exact `send()`-fails-after-`engine.start()` case the comment names, FFmpeg repopulates during deletion; `deleteDirectory` throws (warn-only :216), directory leaks for the worker's lifetime. **Fix:** swap the two lines.

### B3. `WorkerSessionServer` — contention defect + dead lenient branches + redundant layer (×4)
`src/main/java/com/streamarr/server/services/streaming/remote/WorkerSessionServer.java:84-136`
(a) Every method is `synchronized` on the instance, but the only guarded state is the `server != null` flag — `dispatch` holds the monitor across gRPC `onNext`, so one flow-control-blocked worker freezes the 100ms `isRunning` liveness polls for **every** session/variant, corrupting the coordinator's stall clock. (b) `dispatch/stopStreamSession/isRunning/availableSlots/port` throw via `requireStarted()` while `eligibleWorkers/stopVariant/hasConnectedWorker` silently return empty/false — the lenient branches are unreachable (bean `initMethod = "start"`, `RemoteTranscodeConfiguration.java:20`) and would mask a wiring bug as "no workers" → inexplicable 503, zero log evidence. (c) The class mirrors the registry API (~12 one-line delegates) that already exists at `RemoteTranscodeExecutor` and `LiveWorkerConnectionRegistry`.
**Fix (one change resolves all three):** confine `synchronized` to `start`/`close`; volatile started check; hand `RemoteTranscodeExecutor` the registry directly (or package-private accessor); uniform `requireStarted()`. ~80 LOC deleted. Also: if `server.start()` throws, the virtual-thread executor leaks (`:45-77`, `:144-161`) — fix cleanup ordering.

### B4. Null-identity guard asymmetry in `WorkerSessionGrpcService` (×2)
`:46-50` (uploadSegment: guards, logs, UNAUTHENTICATED) vs `:55-65` (establishWorkerSession: no guard → NPE at `:136` → opaque `Status.UNKNOWN` + confusing "Worker null session null" log). Same misconfiguration, night-and-day diagnosis. **Fix:** guard both (registration is the more privileged stream).

### B5. Dead profile-existence load in `AccessTokenIssuer` (×2)
`src/main/java/com/streamarr/server/services/auth/AccessTokenIssuer.java:117` — `profileRepository.findById(...).orElseThrow(...)` with result discarded; leftover from the deleted `pv` claim; FK-unreachable (composite FK `fk_account_profile_profile`; :112-115 already verified the row). **Fix:** delete the line, field, import, wiring (repo convention: 100% coverage, delete dead branches).

### B6. HTTP-boundary revocation pin lost with the counter deletion
Old `StreamControllerIT.shouldRejectPlaybackOnStreamPathAfterSessionVersionBumped` deleted; enforcement moved to `HlsStreamingService.accessSession` (:108-119) but both controller test classes stub `StreamingService`. No test drives controller → real service → real gate → PostgreSQL. **Fix:** repurpose `LivePlaybackAccessIT` (68 lines, duplicates two existing pins) into: token → GET playlist 200 → revoke → GET denied.

### B7. `states` map destroy-race: comment overclaims, entry leaks (×3)
`SegmentDeliveryCoordinator.java:104-107` vs `:152-154` — registry lookup (:93) and `computeIfAbsent` (:105-106) aren't atomic with destroy; a lost race permanently leaks one `VariantDeliveryState` while the comment asserts it can't. **Fix:** re-check registry after insert, or fix the comment to state the bounded-leak contract. Related: `recordReplacement` (:494-501) is unfenced while `recordRefusal` (:508-513) fences the identical window — fence identically or document the tolerance.

### B8. Unbounded parks in new concurrency tests; no JUnit timeout backstop
No `junit-platform.properties` / `junit.jupiter.execution.timeout.*` exists. A failed assertion before the release step parks a non-daemon thread forever and hangs the fork:
- `SegmentDeliveryCoordinatorTest.java:913` (`releaseTrap.await()` untimed; :978 shows the correct bounded form)
- `LiveWorkerConnectionRegistryTest.java:173`, `:332` (wedged task blocks try-with-resources `executor.close()` forever; :276 is correct)
- `PlaybackAuthorityGateIT.java:137` (untimed barrier; `executor.close()` at :123 never returns)
**Fix:** bound every park (`await(5, SECONDS)`); add a default method timeout in a new `junit-platform.properties` as systemic backstop.

### B9. Log appender leak
`WorkerIdentityServerInterceptorTest.java:143-149` — `ListAppender` attached to the process-global logger, never detached. **Fix:** detach in `@AfterEach` (or the shared `LogCaptor` from §4).

### B10. `startAll` lock-free claim doesn't match wiring (×2)
`ProducerLifecycleService.java:21-23` claims no lock needed because the session isn't reachable — but `HlsStreamingService.createSession` publishes to the registry **before** `startAll` (:76-77). `SessionReaper`/shutdown iterate `findAll()` and can destroy mid-`startAll`, orphaning FFmpeg processes. **Fix:** take the mutex in `startAll`, or defer the registry save until after `startAll`; align the comment either way.

---

## 3. Production complexity/LOC reduction (~350–400 LOC)

All behavior-preserving unless noted; land as **structural** commits (B3 above is structural+behavioral — split it).

| # | Item | Location | LOC | ×N |
|---|------|----------|-----|----|
| 1 | Collapse `WorkerSessionServer` delegation (see B3) | `WorkerSessionServer.java:84-136` | ~80 | 4 |
| 2 | `withSessionLock(id, Supplier/Runnable)` helper for 7× lock/try/finally | `ProducerLifecycleService.java:110-119,144-153,159-168,176-185,227-236,308-317,340-349` | ~35 | 2 |
| 3 | Delete dead one-arg `isRunning(UUID)` chain (no production caller; verify tests) | `TranscodeExecutor.java:25` → `LocalTranscodeExecutor:45-47`, `RemoteTranscodeExecutor:59-61`, `WorkerSessionServer:118-121`, `LiveWorkerConnectionRegistry:141-144` + `WorkerConnection:295-298`, `FfmpegTranscodeEngine:55-57` | ~30 | 1 |
| 4 | `baseRequest(session, seek, startSeq)` shared builder for 3× `TranscodeRequest` construction | `ProducerLifecycleService.java:277-306,416-436,438-462` | ~25 | 2 |
| 5 | Delete dead `stopVariant(UUID jobAttemptId)` (IT-only callers; migrate to session/label overload) | `WorkerSessionServer.java:101-104`, `LiveWorkerConnectionRegistry.java:109-116` | ~16 | 2 |
| 6 | `ExhaustResult` sealed (2 empty records) → `boolean tryMarkExhausted(...)` (repo `try`-prefix rule); kills the fall-through switch | `ProducerLifecycleService.java:73-77,227-260`, `SegmentDeliveryCoordinator.java:311-324` | ~15 | 2 |
| 7 | Hand-rolled `boolean[1]`/`compute` counting semaphore → `ConcurrentHashMap<UUID,Semaphore>` (needs-test-verify; entries no longer removed at zero — state the choice) | `SegmentUploadAdmission.java:96-114` | ~15 | 1 |
| 8 | Inline `recover()` pass-through into `deliverOnce`; `logProducerEnd` 3× near-identical warns → switch | `SegmentDeliveryCoordinator.java:244-248,378-406` | ~16 | 1 |
| 9 | `RefreshTokenService.createSession` overload chain — migrate `AuthController.java:122`, `LoginCompletionService.java:29` to the command builder; inline `createSessionAndToken` | `RefreshTokenService.java:46-66` | ~14 | 1 |
| 10 | Container-format switch triplicated on worker (2 unreachable throws post-mapper-validation) — map once to domain enum | `TranscodeWorker.java:320-338`, `WorkerVariantJobMapper.java:104-111` | ~12 | 1 |
| 11 | `TranscodeHandle` test-only minting constructors → fixture (`StreamSessionFixture.activeHandle()`); also closes the attempt-id-fencing hole | `TranscodeHandle.java:22-29` (~20 test call sites) | ~8 | 2 |
| 12 | `SegmentStore.storeSegment` default method is test-only — inline `prepareSegment(...).publish()` in `LocalSegmentStoreTest` | `SegmentStore.java:11-17` | ~7 | 2 |
| 13 | Redundant profile guard — `identity.playbackAuthority()` performs the identical check+throw | `PlaybackTokenIssuer.java:40-42` | 3 | 2 |
| 14 | Misc: `Collections.emptyList()` → `List.of()` (`HlsStreamingService.java:170`); `SegmentUploadObserver.DEFAULT_VARIANT_LABEL` → `StreamSession.defaultVariant()`; shared static `IgnoredUploadObserver` + one class-level comment (`WorkerSessionGrpcService.java:87-103`); add `@Builder` to `RemoteTranscodeProperties` | — | ~15 | — |

**Considered and rejected:** merging `RemoteVariantJobMapper`/`WorkerVariantJobMapper` (inverse serialization directions; exhaustive switches are compile-checked against proto regen — if touched, colocate as a codec with round-trip test, don't shrink). `PlaybackAuthorityGate` single-method interface stays (fakes-over-mocks justification) — collapse only if it ever widens. `SessionGone` vs `Superseded` merge — keep the self-documenting case. `TranscodeWorkerSettings`/`Configuration` split — low priority.

---

## 4. Test consolidation (~1,200–1,450 LOC; overlaps verified by diff)

| Extraction | Sites | LOC |
|---|---|---|
| `WorkerSessionTestSupport` / `RemoteWorkerFixtures` — `server(...)` byte-identical ×6, `worker(...)` ×4, `resource()` ×7, TLS identity, `variantJob` builders | `WorkerSessionServerIT:921-934`, `RemotePlaybackIT:249-262`, `RemoteRecoveryIT:176-189`, `TranscodeWorkerIT:536-549`, `TranscodeWorkerKeepaliveIT:77-90`, `TranscodeWorkerApplicationIT:124-137` | ~240 |
| `FakeStreamingService` replacing 5 hand-rolled stubs | `RemotePlaybackIT:297-328`, `SessionReaperTest:259-295`, `StreamingShutdownHookTest:51-94`, `StreamControllerTest:656-696`, `StreamControllerIT:280-317` | ~192 |
| `TranscodeWorkerIT` merges: 2 disconnect tests (:167-221), dispatch smoke ⊂ close test (:62-120), TS-upload+completion (:305-369), `EndingFfmpegProcessManager` (:758-780) re-implements existing fake, 14× media-root preamble | `TranscodeWorkerIT` | ~170–200 |
| `StreamingRigFixture` (`DeliveryRig` record — shape already invented at `SegmentDeliveryCoordinatorTest:77-104`) for 9 wiring sites | SegmentDeliveryCoordinatorTest, ProducerLifecycleServiceTest, SessionReaperTest, HlsStreamingServiceTest ×2, both smoke tests, RemoteRecoveryIT, RemotePlaybackIT, StreamControllerTest | ~135 |
| `FakeAuthorizationService` replacing two 10-method stubs | `StreamControllerTest:594-654`, `RemotePlaybackIT:330-382` | ~114 |
| Same-file helper ignorance: 12× inline `MediaProbe` builder + 7× inline `defaultOptions()` body (`HlsStreamingServiceTest`); 8× inline gRPC status assertion vs `assertUploadRejected:1075` (`WorkerSessionServerIT`) | — | ~130 |
| `startedSession()/startedAbrSession()` byte-identical → `StreamSessionFixture` | `SegmentDeliveryCoordinatorTest:106-134` ≡ `ProducerLifecycleServiceTest:57-85` | ~58 |
| Wire-level validation matrices (~15 permutations through full TLS/gRPC) → parameterized `SegmentUploadObserverTest`; keep 1 representative wire rejection; drop duplicated 16MiB+1 pin and IT copy of ownership-lost latch choreography | `WorkerSessionServerIT:469-539,580-636,708-742` | ~60 |
| `TokenTestSupport`: JWT-decode ×4 + `TEST_KEY_BASE64`/`AuthTokenProperties` ×7; `AuthEndpointsIT:1123-1135` should `@Autowired JwtDecoder` | AuthEndpointsIT, StreamingResolverTest, AccessTokenIssuerTest, PlaybackTokenIssuerTest | ~60 |
| Core dedup: exhaust helper re-inlined (`SegmentDeliveryCoordinatorTest:493-508`), resume tests → existing `@CsvSource` rows (`ProducerLifecycleServiceTest:332-344,222-234,111-121`), `HlsPlaylistServiceTest:51-117` near-identical pair + ignores `sessionWithDurationBuilder` | — | ~100 |
| Third private `MutableClock` copy (fake exists in `fakes/`) | `SegmentDeliveryCoordinatorTest:986-1008`, `SegmentUploadAdmissionTest:181-203` | ~46 |
| `ProtoUuid` re-implementations → static-import production class | `TranscodeWorkerIT:679-688`, `WorkerMediaSourceResolverTest:165-170`, `WorkerSessionServerIT:1094-1103` (keep copy in contract test only) | ~30 |
| `playbackBearer` → `AuthTestSupport`; `AuthenticatedIdentityFixture` (~21 builder sites); `LogCaptor` (AutoCloseable) for 9 ListAppender sites; fold `LivePlaybackAccessIT` (→ B6) and `MembershipTokenRevocationIT` → `AuthEndpointsIT` | — | ~150 |
| Drop `AbstractIntegrationTest` from 3 remote ITs that never touch a bean or DB (3 context spin-ups saved) | `WorkerSessionServerIT`, `RemotePlaybackIT`, `RemoteRecoveryIT` | — |
| `FfmpegCommandBuilderTest` `job(...)` 10-positional-arg helper (+5/6/7-arg variants, ~90% identical bodies) → builder-returning fixture (CLAUDE.md builder rule) | `FfmpegCommandBuilderTest:29,39,60-132,141` | ~70 |

**Not bloat (keep):** three-layer identity testing (SPIFFE grammar / interceptor / real handshake), unit-vs-E2E token pins, the ~115 lines of race scaffolding (`TrapSegmentStore`, `EvidenceGatingExecutor`, `MutableClock`) — that scaffolding is why the race tests are deterministic.

---

## 5. Additional test gaps (secondary)

- **Stale-connection *results* fence untested** — fenced worker's late `JOB_ATTEMPT_COMPLETED/FAILED` must not release the replacement's attempt (`LiveWorkerConnectionRegistry.releaseJobAttempt:165-171`); unit test next to the pinned disconnect fence.
- **Worker boot/target fence branches unreachable via harness** — `TranscodeWorker.startVariant:141-144` (`INVALID_SPECIFICATION`), `stopVariant:378-380`; needs a raw gRPC stub server.
- **Proto secret-scan doesn't recurse** — `TranscodeWorkerContractTest:127-152` walks 6 hand-picked descriptors; seed from `getMessageTypes()` (one line). Only enforcement of ADR 0018's no-secrets-on-wire.
- **FFmpeg-leak guard has no red test** — `TranscodeWorker:158-169` post-start dispatch failure path (relates to B2).
- **Registry-miss-404-without-DB-query unpinned** — `HlsStreamingServiceTest:244-250`; one line via `authorityGate.failWith(...)`.
- **Timeline continuity across replacement only in excluded smoke suite** (`HlsRecoveryContinuitySmokeTest`); CI keeps only flag-level pins.
- **Cross-chunk upload accumulation never executed** (`SegmentUploadObserver.receiveData:79-97` across a 64 KiB boundary).
- **Worker keepalive default (30s) vs server floor (10s) unpinned.**
- Lower: `recordRefusal` fence untested (delete-guard passes); exact stall-boundary `>=`; multi-connection capacity derivation; compose `restart: unless-stopped`; `PasswordChangeCompletionService.java:37` cross-account filter; resolver edges (leading-slash key, empty key, in-namespace symlink).
- Silent-failure LOWs: unknown `JOB_ATTEMPT_COMPLETED/STOPPED` dropped without log (`WorkerSessionGrpcService:209-223` — mirror the failed-case warn); worker silently ignores unknown control events (`TranscodeWorker:469-482` — add the `default ->` warn its server twin has); `LocalFfmpegProcessManager` `catch (Exception)` broader than mechanism + inconsistent levels (:102-104 vs :174-176); failed quit-write debug-only (:107-115).
- Quality: `LatchingWarnAppender` gates a race on a literal log string; `awaitPolls(2)` under-constrains the two-waiter race test (:586-607; deterministic superset exists at :705-748); misplaced tests (`shouldRejectInvalidWorkerSessionServerConfiguration` → unit; `TranscodeWorkerSettingsTest` capacity test targets wrong class); `PackagedConfigurationTest` `contains("replicas: 1")` matches `replicas: 12` (fold into `KubernetesManifestContractTest`); ~137 new `@DisplayName`s missing the "when" clause (clusters: KubernetesManifestContractTest, AuthenticatedIdentityTest, RemoteTranscodeConfigurationTest, StreamSessionTest, AccountProfileRepositoryIT, HouseholdMembershipRepositoryIT, TokenCryptoConfigTest).

---

## 6. Docs / ADR / comment fixes

1. **ADR 0019:36** — cold-start contradiction (see C2).
2. **ADR 0019:65** — claims deviations are "explicit, non-gating cases" in the conformance suite; they exist only in javadoc (`HlsPlaylistStructureTest:25-34`). Add the cases or soften the claim.
3. **ADR 0019:54 vs :56** — "budget resets whenever the eligible-target set changes" vs actual live re-derivation + post-FAILED-only reset. Align :54 with :53/:56.
4. **ADR 0019:12** — "owns every producer mutation" → "…of a published session" (startAll exception; and see B10).
5. **`SegmentDeliveryCoordinator.java:26-40`** — class javadoc misstates window reset (contradicts its own nested `VariantDeliveryState` doc at :416-424); replace with the mechanism, trim ~6 lines of ADR re-narration.
6. **Banned jargon "bookkeeping"** — ADR 0019:58 ("not per-request state"), `LiveWorkerConnectionRegistryTest.java:76` ("before its `activeVariants` put").
7. **Stale vocabulary** — "cycle" (`SegmentDeliveryCoordinatorTest:559,805-806` → "window"); "failure evidence" (`WorkerSessionServerIT:797` → "`JobAttemptFailed` report").
8. **Constant-derived rot** — "four such streams" (`SegmentUploadAdmission.java:24`; derived from constants in two other classes → "a handful"); "(segments run to 16 MB)" (`SegmentDelivery.java:11`).
9. **`moveIntoPlace` non-atomic fallback** (`LocalSegmentStore.java:183-184`) weakens the unconditional "existence is readiness" claim (ADR 0019:37, `SegmentDelivery.java:9`) — document the caveat or fail instead of falling back.
10. **`tryRead` comment** (`SegmentDeliveryCoordinator.java:161-171`) implies `log.debug` is a safety net; the catch is actually only reachable via the exists/read TOCTOU — tighten the comment.
11. Duplicated comments: "no-op for test fake" ×5 (`SegmentUploadObserverTest:82-111`), rig-ownership ×2 (`RemotePlaybackIT:311,316`).
12. **ADR 0016** rewrites the Decision body in place rather than pure supersession (0017 is the textbook form). Declared and defensible; if strict Nygard discipline is wanted, restore the counter paragraphs with a "superseded by 0018" marker.
13. TLS fixtures (`src/test/resources/tls/`) — add a short openssl regeneration recipe + note on `.fixture` naming (certs valid to 2036).
14. `ContinueWatchingResolverTest.java:39` — missing `@Tag("UnitTest")` (pre-existing; add while nearby).
15. `SegmentUploadObserver.java:114` pre-check of `authorizesUpload` before the authoritative `publishIfAuthorized` re-check — annotate as I/O-avoidance fast path or delete; as written it reads like the atomicity guarantee. `RegistrationObserver` (`WorkerSessionGrpcService.java:143-146`) sets `registered = true` before `register()` returns — safe only via a non-local ordering invariant; set it after.

---

## 7. Already tracked — no action in this PR

- Worker upload flow-control/back-pressure and the worker's stall-bounded segment wait: **deferred to #253** per the PR description. The hardcoded 30s `awaitSegment` (`TranscodeWorker.java:56,277-294` — can fail healthy slow encodes while `isAttemptProducing` is true) and graceful-shutdown-reads-as-failure (`:439-443`, `shutdownNow` before half-close reaches server) should be carried into #253's scope explicitly.
- Event-driven waits follow-up ("what transfers from MoQ") — pinned separately; `awaitSegment`'s 50ms poll belongs there.

---

## 8. Verified clean (multi-agent)

- **No Critical production defect.** Identity/authorization fails closed at every branch (SPIFFE mapper, interceptor, live gate, `requireTokenBoundTo`); canonical-form UUID re-check is load-bearing (verified, keep).
- **CLAUDE.md hard rules:** zero `verify()`/`ArgumentCaptor`/reflection/`Thread.sleep`/H2 in ~10k new test lines (grep-verified); no `@Query` JPQL; `DSLContext` in repository fragments only; domain imports nothing; new ArchUnit rule pins proto types inside `services.streaming.remote`; audit columns set in jOOQ updates; no jOOQ-mutated row JPA-re-read in-transaction.
- **Concurrency doctrine:** check-then-acts under the session mutex with write-point attempt-fence re-verification, or pushed into SQL `WHERE`; no lock held across backoff sleeps; virtual threads throughout; destroy/replace and publish/disconnect races closed and IT-covered; deleted counter stack fully re-pinned (clause-by-clause audit clean).
- **Secrets:** no raw tokens/passwords logged or persisted; record+builder `toString` redaction on `PasswordChangeCompletionCommand`; TLS material path-only; test CA clearly test-only.
- **Build/CI/deploy:** V049 no collision; grpc-bom + shaded netty correct; proto excluded from Sonar/JaCoCo/CPD; buf format/lint/breaking gated; smoke isolation correct; compose/k8s env vars match settings; bruno requests match controller routes.
- **Standout code:** `SegmentUploadAdmission` (best-designed type in the PR), `WorkerMediaSourceResolver` (exemplary fail-closed path handling), `WorkerSpiffeIdentityMapper`, deterministic race-test scaffolding, `HlsStreamingService.createSession` startup rollback (suppressed-exception pattern done right).

---

## 9. Recommended sequencing

1. **Criticals, red-first** (behavioral): C1 test, C2 ADR amendment, C3 pin + join decision.
2. **Bug fixes** (behavioral, each with failing test where feasible): B1 (both halves), B2 (line swap), B4, B5, B8 (park bounds + junit-platform.properties), B9, B10, B7.
3. **`WorkerSessionServer` rework** (B3): split structural collapse from the volatile/monitor behavioral change.
4. **Production LOC pass** (§3) as structural commits; **test consolidation** (§4) + naming/doc fixes (§6) in follow-up structural commits — helps the SonarCloud new-lines budget.
5. Re-run targeted reviews (tests, simplify) after fixes.

Commit discipline: structural vs behavioral never mixed; signed commits; no AI attribution; messages <200 words.
