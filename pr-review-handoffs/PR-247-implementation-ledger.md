# PR #247 implementation ledger

This ledger executes the fix-phase queue from
[`PR-247-validation-ledger.md`](PR-247-validation-ledger.md). The validation ledger remains the
evidence record; this file records production changes, RED → GREEN cycles, verification, and
maintainer decisions.

## Working contract

1. Execute one vertical tracer bullet at a time: public behavior test → observed RED → minimal
   production change → GREEN → proportional regression verification.
2. Preserve existing production interfaces unless a separately approved design decision requires
   a change. Tests cross the same service/protocol seams as callers.
3. Run GitNexus impact analysis before editing an existing symbol. Record HIGH/CRITICAL warnings
   before proceeding and run `detect_changes` after every completed cycle.
4. Keep structural and behavioral work in separate cycles and commits. Do not mix a measured LOC
   reduction into a correctness fix.
5. Proposed-waiver items remain out of scope until the maintainer explicitly accepts or rejects
   their waiver.
6. Do not stage or commit automatically. The maintainer decides how the approved evidence snapshot
   and each implementation cycle are grouped.

## Status vocabulary

| Status | Meaning |
|---|---|
| `QUEUED` | Applicable and ordered, but no implementation work has started. |
| `IMPACTED` | Pre-edit blast radius and test seam are recorded. |
| `RED` | The promoted behavior test fails against the PR head for the expected reason. |
| `GREEN` | The focused test passes after the minimal production change. |
| `VERIFIED` | Proportional regression checks and GitNexus change detection pass. |
| `DECISION` | Maintainer input is required before implementation. |
| `WAIVER-PENDING` | Evidence exists, but implementation scope is not approved. |
| `STACKED` | Valid feature work deliberately assigned to a follow-up PR rather than this fix branch. |
| `DONE` | No production change is needed; the retained regression pin or disposition is complete. |

## Ordered task list

### Behavioral defects — RED → GREEN

| Order | Finding | Required behavior | Status | Evidence |
|---:|---|---|---|---|
| 1 | B10 | Destroy serializes with initial multi-variant startup; no producer starts or survives after destroy wins. | `VERIFIED` | RED reproduced, minimal mutex fix GREEN, direct-consumer suites and full Java 25 unit suite GREEN, Spotless/whitespace GREEN, GitNexus change risk LOW with zero affected processes. |
| 2 | B1-W | A failed or timed-out upload RPC is explicitly cancelled; failure-report arrival order is not constrained. | `VERIFIED` | Promoted real-mTLS IT reproduced the half-open RPC, then passed after cancellation was wired to gRPC's native client-call handle. Focused and proportional worker suites are GREEN. |
| 3 | B1-S | Reclaim proactively terminates the displaced upload observer and releases any retained upload data. | `VERIFIED` | Promoted test now uses an authorized dispatched job with valid metadata and partial data. Reclaim immediately returns `DEADLINE_EXCEEDED`, clears observer state, and releases slot/byte accounting; all remote upload unit and real-gRPC suites are GREEN. |
| 4 | B4 | Missing authenticated worker identity fails as `UNAUTHENTICATED`, never an NPE/`UNKNOWN`. | `VERIFIED` | Promoted registration-boundary test reproduced the NPE, then passed with a fail-closed observer guard. Registration, service, and identity-interceptor suites are GREEN. |
| 5 | S01 | Unknown completed/stopped results emit a diagnostic naming the attempt id. | `VERIFIED` | Promoted test pins both terminal-result branches. A narrow wrapper warns with worker, result, and unknown attempt id; focused and service suites are GREEN. |
| 6 | B9 | Logger appenders are always detached after each test. | `VERIFIED` | The interceptor test now owns one appender per test and its `@AfterEach` detaches, stops, and asserts detachment. Target suite GREEN. Shared-captor POC retained as LOC-failing/correctness-valid; the smaller local fix won. |
| 7 | B8 | Every PR-247 release-after-assertion park is bounded and the suite has a systemic timeout. | `VERIFIED` | Four risky parks now fail after five seconds and a two-minute JUnit default bounds every test. Both concurrency unit suites and the PostgreSQL authority IT are GREEN. |

### Missing regression pins

| Finding | Required pin | Status |
|---|---|---|
| C1 | Progress resets attempted recovery targets. | `DONE` |
| C3 | Live authority follows active household/profile selection. | `DONE` |
| B6 | HTTP access fails after live authority revocation. | `DONE` |
| G01 | A stale worker session cannot release its replacement attempt. | `DONE` — retained unit pin is mutation-adequate. |
| G09 | A stale refusal cannot exhaust a replacement attempt. | `DONE` — retained coordinator pin is mutation-adequate. |
| G10 | The exact stall threshold is inclusive. | `DONE` — retained boundary pin is mutation-adequate. |
| G03 | Secret scanning seeds recursion from every proto message descriptor. | `DONE` — exhaustive descriptor scan retained and mutation-adequate. |
| G02 | Start commands must match both the connected worker id and its current boot id. | `DONE` — two real-TLS pins independently reject a different worker and an earlier boot; both are mutation-adequate. |
| G05 | A missing live session returns not found before any database-backed authority query. | `DONE` — service-level short-circuit pin is mutation-adequate. |
| G07 | Segment data split across multiple 64 KiB frames is accumulated byte-for-byte. | `DONE` — service-boundary multi-frame pin is mutation-adequate. |
| G11 | Available remote capacity is the total across every eligible connected worker. | `DONE` — multi-worker capacity pin is mutation-adequate. |
| G13 | Password-change completion rejects a caller session owned by another account. | `DONE` — cross-account session pin is mutation-adequate. |
| G14 | Worker media keys reject empty/leading-slash input and allow symlinks only when their real target remains in the namespace. | `DONE` — boundary and safe-symlink pins are mutation-adequate. |
| S02 | An unknown server-to-worker control command is visible in worker diagnostics. | `VERIFIED` — a real-TLS RED required the worker to warn with `COMMAND_NOT_SET`; the focused and full suites are GREEN. |

### Inspection-backed fixes and documentation

| Findings | Work | Status |
|---|---|---|
| C2, D01–D10, D11 test fakes, D13–D14 | Align ADR 0019, code comments, vocabulary, TLS fixture recipe, and the missing unit tag with verified behavior. | `VERIFIED` — source inspection, 68 focused unit tests, and 34 real-TLS/gRPC integration tests are GREEN. |
| D11 `IgnoredUploadObserver` | Evaluate removing its three no-op comments/method duplication with the P14b structural POC. | `VERIFIED` — replaced the named no-op type with one static anonymous observer; the focused remote suites remain GREEN. |
| D12 | Decide whether ADR 0016's declared in-place update needs a historical-body restoration. | `DONE` — accepted no-change: its Implementation Status and supersession note make the rewrite explicit; restoring obsolete security guidance would reduce clarity. |
| D15 | Mark a worker session registered only after registry acceptance succeeds. | `VERIFIED` — public-boundary RED reproduced the stale flag after response delivery failed; the one-line ordering fix and service/real-gRPC suites are GREEN. |
| D04 | Align the lifecycle class contract with B10's mutex-protected initial startup. | `DONE` |
| B3-B, B3-D | Choose uniform pre-start failure semantics and guarantee executor cleanup after failed startup without widening the registry interface. | `VERIFIED` — every capability now fails fast before start; startup publishes its executor only after a successful bind and cleans the local executor on failure. |
| B7-A | Prevent or explicitly accept the bounded `VariantDeliveryState` re-growth race. | `VERIFIED` — a post-insert registry recheck removes the exact entry if destroy won before insertion. |
| B7-B | Fence replacement recording like refusal recording, or document the intentionally self-healing write. | `VERIFIED` — replacement recording now uses the ticket's expected attempt id; a deterministic planned-restart race is RED → GREEN. |
| H0 | Add opt-in `/dev/dri` passthrough to shipped deployment examples. | `VERIFIED` — separate Compose and Kubernetes overlays plus deployment contract tests keep CPU defaults unchanged. |
| H2, H3 | Keep as separately reviewable stacked feature work: hardware init-probe gating and all-variants-dead propagation. | `STACKED` — validated feature requests with distinct runtime/API policy; intentionally not folded into PR #247's fix branch. |

### Proven structural work — separate commits

Re-derive the recorded POC before each structural edit and preserve its interface constraints.

| Findings/POCs | Status |
|---|---|
| B5; P02–P06; P08; P10–P11; P13–P15; P18 | `VERIFIED` — measured structural POCs adopted without widening production interfaces; proportional unit and real-seam integration suites are GREEN. |
| P07 | `DONE` — measured LOC pass (net -8) but production-design failure: the candidate retained a zero-count semaphore forever for every historical worker identity instead of removing it. Not adopted. |
| T01 | `VERIFIED` — one `RemoteWorkerFixtures` owns TLS resources, server/worker configuration builders, and the remux-engine seam across all six remote integration suites; all 57 affected ITs are GREEN. |
| T02 | `VERIFIED` — the registry-backed `FakeStreamingService` replaced five local stubs without changing their observed contracts; 49 focused unit tests and 20 direct ITs are GREEN. |
| T03 | `VERIFIED` (authorized 4 of 5) — removed two assertion-identical worker tests, centralized media-root preparation, and reused the shared segment-producing fake; 16 worker ITs and five protocol-contract tests are GREEN. The fifth sub-claim remains waiver-pending. |
| T04 | `VERIFIED` — adopted despite the measured net −6 LOC because seven suites now share one lifecycle/coordinator wiring seam. A clean-build static-import RED exposed and pinned the required Lombok annotation-processing order; 158 unit tests and nine remote ITs are GREEN. |
| T05 | `VERIFIED` — one supplier-backed `FakeAuthorizationService` replaced both local authorization stubs; the 36 controller units and seven remote-playback ITs are GREEN. |
| T06 | `VERIFIED` — reused media-probe, options, and upload-rejection helpers at 28 repeated call sites; the affected unit and real-gRPC suites are GREEN. |
| T07 | `VERIFIED` — `abrSessionBuilder()` centralizes the shared ABR session shape while registry/start behavior remains rig-local; both lifecycle suites are GREEN. |
| T09 | `VERIFIED` — `TokenTestSupport` owns the shared signing key, properties, decoder, and decode helper; the focused token units and all 34 `AuthEndpointsIT` cases using the real decoder are GREEN. |
| T10 | `VERIFIED` — exact playlist/recovery duplicates delegate to one helper and redundant resume cases are folded into a stronger parameterized matrix; no assertion was lost. |
| T11 | `VERIFIED` — the shared atomic `MutableClock` replaced both local copies; all 174 focused unit tests in its tranche are GREEN. |
| T12 | `VERIFIED` — three suites now reuse production `ProtoUuid` conversion while the independent protocol contract copy remains; 45 direct ITs and the focused units are GREEN. |
| T13a | `VERIFIED` — `AuthTestSupport.playbackBearer` replaced both private token helpers; the HIGH-impact shared support wiring compiled for all consumers and 27 direct ITs are GREEN. |
| T13b | `VERIFIED` (measured partial scope) — the valid-default `AuthenticatedIdentityFixture` replaced the ten worthwhile blocks; low-value identifier-heavy blocks remain intentionally local. |
| T15 | `VERIFIED` — three remote ITs no longer boot unused Spring/Testcontainers infrastructure; all 36 cases are GREEN without a Spring context, preserving the measured 41% warm-run improvement. |
| T13c | `DONE` — `POC-FAIL` for LOC (net +25 at one site), `POC-PASS` for cleanup safety. The shared abstraction was not adopted for B9 because direct ownership is smaller and clearer; the experiment remains a candidate only if several log-capturing tests are consolidated together. |
| P01, P09, P12, P14c–P14d, P16–P17, T14 | `DONE` — measured rejection or no LOC case; take only via an explicit non-LOC design decision. |

### Proposed waivers — no implementation authority yet

| Findings | Status |
|---|---|
| T08, T13d, T03 sub-claim 5 | `WAIVER-PENDING` |

## Cycle log

| Cycle | Finding | Phase | Evidence/result |
|---:|---|---|---|
| 1 | B10 | IMPACT | `startAll` impact is HIGH: 16 direct callers, 142 impacted symbols, one affected creation flow, and three modules. The module stays behind its existing public interface; verification must cover initial start, resume, relocate, replacement coordination, and session creation. |
| 1 | B10 | RED | Promoted `ProducerLifecycleDestroyRaceTest` into the normal unit suite. Against `3d93dc41`, the deterministic two-variant race fails in 0.17 s: destroy stops the first producer, the uncoordinated second start then creates an orphan, and `isRunning(sessionId)` is `true`. |
| 1 | B10 | GREEN | Wrapped `startAll` in the existing per-session reentrant mutex. The focused race test is GREEN: destroy queues behind startup, then stops every producer. No production interface changed. |
| 1 | B10/D04 | REFACTOR | Updated the lifecycle class contract after GREEN: initial startup is mutex-protected because the session is published before all producers finish starting. |
| 1 | B10 | VERIFY | Focused race GREEN; `ProducerLifecycleServiceTest`, `HlsStreamingServiceTest`, and `SegmentDeliveryCoordinatorTest` GREEN together. Full `./mvnw test` is GREEN on Java 25 outside the network/attach sandbox. A Java 26 sandbox run failed environmentally (Byte Buddy self-attach denied and ephemeral gRPC bind denied) and was superseded by the configured-runtime run. |
| 1 | B10 | SCOPE | `spotless:check`, `git diff --check`, and `git diff --cached --check` GREEN. GitNexus `detect_changes(scope: unstaged)` reports three touched symbols in two files, LOW risk, and zero affected processes; the extra `ensurePositioned` symbol is adjacent-hunk attribution, not a source edit. |
| 2 | B1-W | IMPACT | `uploadSegment` impact is LOW: one direct caller (`uploadWhenProduced`), three affected worker symbols through `uploadVariant`, and zero affected process traces. No interface change is needed. |
| 2 | B1-W | RED | Moved the validated real-mTLS test into the normal suite as `TranscodeWorkerUploadCancellationIT`. Against the PR head it fails after 7.24 s: the five-second response wait times out and the job fails, but the server observes no upload cancellation within the following second. |
| 2 | B1-W | GREEN | Captured gRPC's native `ClientCallStreamObserver` through `ClientResponseObserver.beforeStart` and cancel it whenever upload I/O, interruption, acknowledgement failure/timeout, or client-stream failure aborts the operation. The focused real-mTLS integration test is GREEN; no production interface changed and no ordering across HTTP/2 streams is asserted. |
| 2 | B1-W | VERIFY | `TranscodeWorkerUploadCancellationIT` is GREEN. `TranscodeWorkerContractTest`, `TranscodeWorkerSettingsTest`, `WorkerMediaSourceResolverTest`, `TranscodeWorkerIT`, `TranscodeWorkerKeepaliveIT`, and `TranscodeWorkerApplicationIT` are GREEN together under Java 25. |
| 2 | B1-W | SCOPE | `spotless:check`, `git diff --check`, and `git diff --cached --check` GREEN. Aggregate GitNexus `detect_changes(scope: unstaged)` after cycles 1–2 reports seven touched symbols in four production/test files, LOW risk, and zero affected processes. `ensurePositioned` and `awaitSegment` are adjacent-hunk attribution, not source edits. |
| 3 | B1-S | IMPACT | `reclaimExpired` and `Ticket` are LOW: one direct caller, 15 impacted remote-upload symbols, and zero affected processes. `SegmentUploadObserver.close` is LOW: three direct callers, six impacted symbols, and two existing observer execution traces. |
| 3 | B1-S | RED | Promoted and upgraded `SegmentUploadReclaimTest` to register a real worker, dispatch an authorized job, accept valid metadata plus partial segment data, advance past the lease, and trigger reclaim through successor admission. Against the pre-fix implementation, accounting is released but no response error arrives; focused Maven test exits 1 at the proactive-termination assertion. |
| 3 | B1-S | GREEN | Admission tickets now notify their owning observer when age reclaim closes them. The observer serializes that external callback with inbound frames, rejects the stream as `DEADLINE_EXCEEDED`, clears its partial byte buffer through `close()`, and leaves the ticket's idempotent accounting release intact. |
| 3 | B1-S | VERIFY | Focused `SegmentUploadReclaimTest` GREEN. `SegmentUploadAdmissionTest`, `SegmentUploadObserverTest`, and `WorkerSessionGrpcServiceTest` are GREEN together under Java 25, covering admission accounting, late frames after reclaim, upload validation/publication, and service limits. |
| 3 | B1-S | SCOPE | Spotless and both diff checks GREEN. Aggregate GitNexus detection after cycles 1–3 is HIGH: 17 touched symbols in six production/test files and nine affected upload parsing/publication traces. The HIGH scope comes from synchronizing all three observer entry points with the new external reclaim callback. Every affected flow was inspected, then the complete remote package unit suite plus `RemotePlaybackIT`, `RemoteRecoveryIT`, and `WorkerSessionServerIT` passed under Java 25 with real local TLS/gRPC. |
| 4 | B4 | IMPACT | `RegistrationObserver.onNext` is LOW with zero indexed callers, affected symbols, or process traces; it is invoked by the gRPC runtime. |
| 4 | B4 | RED | Promoted `WorkerSessionRegistrationIdentityTest`. Against the pre-fix implementation, a valid registration with no `AUTHENTICATED_WORKER_ID` throws `NullPointerException` at the identity equality check; Surefire reports one error and exits 1. |
| 4 | B4 | GREEN | Added a first-branch guard in `RegistrationObserver.onNext` that rejects every identity-less frame as `UNAUTHENTICATED` before validation or registry mutation. No interface or normal authenticated path changed. |
| 4 | B4 | VERIFY | Focused test GREEN. `WorkerSessionGrpcServiceTest` and `WorkerIdentityServerInterceptorTest` are GREEN with it under Java 25, covering the service contract and the correctly wired authentication boundary. |
| 4 | B4 | SCOPE | Spotless and both diff checks GREEN. Aggregate GitNexus detection remains HIGH solely from B1-S's reviewed observer synchronization: 20 touched symbols in seven production/test files and the same nine affected upload traces; B4 adds no affected process. |
| 5 | S01 | IMPACT | `RegistrationObserver.handleSessionEvent` is LOW: one direct observer caller, one remote-session process family, and one affected module. |
| 5 | S01 | RED | Promoted `WorkerSessionUnknownResultLoggingTest` and extended it to send both completed and stopped results for distinct unknown attempts. Against the pre-fix implementation, the captured service log is empty; Surefire reports one failure and exits 1. |
| 5 | S01 | GREEN | Routed completed/stopped results through `finishOrWarn`: known attempts keep the existing release/debug behavior, while missing attempts produce one warning naming worker, terminal result, and attempt id. Failed-attempt handling remains unchanged, avoiding a duplicate warning. |
| 5 | S01 | VERIFY | Focused test and `WorkerSessionGrpcServiceTest` are GREEN together under Java 25. |
| 6 | B9 | EVIDENCE | The retained ordered characterization is GREEN by design: it mirrors the real attach-without-detach and proves a later test receives the leaked appender. A product RED would be artificial because the defect is the test fixture's global side effect. |
| 6 | B9/T13c | POC | The archived shared-`LogCaptor` experiment measured net +25 lines for one migrated site: LOC claim failed, cleanup-by-construction succeeded. For the single leaking class, explicit ownership plus `@AfterEach` is the smaller interface. |
| 6 | B9 | IMPACT | `WorkerIdentityServerInterceptorTest.attachAppender` is LOW with exactly two direct test callers and zero process traces. |
| 6 | B9 | GREEN | The test instance now owns its current appender. `@AfterEach` detaches and stops it, then asserts it is no longer attached to the process-global logger. `WorkerIdentityServerInterceptorTest` is GREEN under Java 25. |
| 7 | B8 | POC/RED | The archived policy POC supplied the RED: a deliberately parked scratch test failed by JUnit timeout in 5.16 seconds instead of hanging. The two-minute candidate then ran all affected suites GREEN before implementation. |
| 7 | B8 | IMPACT | All four edited test gates are LOW. `PlaybackAuthorityGateIT.awaitThen` has one direct test caller; the two registry gates and trap-store override have zero indexed callers/processes (the override result is a lower bound because dispatch is dynamic). |
| 7 | B8 | GREEN | Added `junit.jupiter.execution.timeout.default = 2 m`. Replaced the four PR-247 untimed release parks with explicit five-second barrier/latch waits that fail with a diagnostic when their release never arrives. |
| 7 | B8 | VERIFY | `SegmentDeliveryCoordinatorTest`, `LiveWorkerConnectionRegistryTest`, and `PlaybackAuthorityGateIT` are GREEN together under Java 25; the IT used real PostgreSQL through Testcontainers. |
| 1–7 | Tranche | FULL VERIFY | `./mvnw verify` completed with `BUILD SUCCESS` under Java 25 in 3:24. Checkstyle reported zero violations; the complete unit suite passed; all 504 integration tests passed with zero failures, errors, or skips; and the merged JaCoCo report was generated. |
| 1–7 | Tranche | FINAL SCOPE | Final GitNexus `detect_changes(scope: unstaged)` reports 36 touched symbols in 11 production/test files, HIGH risk, and 12 affected process traces. The risk is concentrated in the nine previously reviewed upload parsing/publication traces plus three worker-session event traces. Those paths are covered by the focused RED → GREEN tests, the complete remote/worker proportional suites, and the successful full build. Adjacent-hunk attribution accounts for reported symbols such as `ensurePositioned`, `awaitSegment`, `copyAuthority`, and unrelated test helpers that were not source-edited. |
| 8 | G01 | IMPACT | The test class is LOW/isolated. The fenced production `releaseJobAttempt` method is HIGH: 19 impacted symbols, two direct callers, three affected process families, and one module. No production change is retained. |
| 8 | G01 | MUTATION-RED | Added a registry-level behavior test in which an old worker session reports the replacement connection's live attempt id. Removing only the `workerSessionId` predicate makes the stale result release that replacement attempt; the focused test fails at the expected non-empty result assertion. The mutation was immediately restored. |
| 8 | G01 | GREEN | With the production fence restored, the focused test and complete eight-test `LiveWorkerConnectionRegistryTest` suite are GREEN under Java 25. The pin observes the replacement attempt through release result, running state, and remaining capacity. |
| 8 | G01 | SCOPE | Aggregate GitNexus detection reports one additional test symbol (37 total), the same 11 files, HIGH risk, and the same 12 previously reviewed production traces. G01 adds no production symbol or execution-flow change. |
| 9 | G09 | IMPACT | The coordinator test class is LOW/isolated. Production `recordRefusal` is HIGH because its four impacted symbols participate in four HTTP segment-delivery flows; no production change is retained. |
| 9 | G09 | MUTATION-RED | Added a coordinator-level race test that returns a real target refusal, inserts and synchronizes a planned restart before the refusal is recorded, then kills that new attempt. Removing only the expected-attempt comparison wrongly charges the old refusal to the new window, exhausts its sole target, and times out the assertion waiting for the target retry. The log confirms the intended erroneous `Recovery exhausted` mechanism. The mutation was immediately restored. |
| 9 | G09 | GREEN | With the fence restored, the planned attempt retries its only target and the pending segment becomes ready. The focused pin and all 32 `SegmentDeliveryCoordinatorTest` cases are GREEN under Java 25. |
| 9 | G09 | SCOPE | Aggregate GitNexus detection reports 40 touched symbols, the same 11 files, HIGH risk, and the same 12 production traces. The new race fixture is test-only; adjacent-hunk attribution accounts for the extra reported existing test methods. |
| 10 | G10 | IMPACT | The private state comparison is LOW with no graph-resolved callers (a lower bound); its coordinator wrapper is LOW with 27 impacted symbols and two direct callers. No production change is retained. |
| 10 | G10 | MUTATION-RED | Added a frozen-clock boundary test that publishes initial progress, advances by exactly the configured steady-state stall threshold, and expects replacement. Changing only `compareTo(budget) >= 0` to `> 0` leaves the producer alive forever at the frozen equality point and the focused test times out at the missing replacement. The mutation was immediately restored. |
| 10 | G10 | GREEN | With inclusive comparison restored, replacement occurs at equality. All 33 `SegmentDeliveryCoordinatorTest` cases, including G09 and G10, are GREEN under Java 25. |
| 10 | G10 | SCOPE | Aggregate GitNexus detection reports 39 touched symbols, the same 11 files, HIGH risk, and the same 12 production traces. G10 is test-only; detector count movement is adjacent-hunk attribution. |
| 11 | G03 | IMPACT | `TranscodeWorkerContractTest` is LOW/isolated with zero dependent symbols or process traces. The change is confined to the wire-contract test. |
| 11 | G03 | MUTATION-RED | Replaced six hand-maintained request-oriented descriptor roots with every top-level message descriptor from `worker_session.proto`, retaining nested recursion. `accepted_length_bytes` is an explicit response-only coverage marker. Restoring the hand-picked roots makes the focused test fail because that response field is absent, proving both protocol directions are scanned. |
| 11 | G03 | GREEN | The exhaustive scan and all five `TranscodeWorkerContractTest` cases are GREEN under Java 25. No protobuf or production source changed. |
| 11 | G03 | SCOPE | Aggregate GitNexus detection reports 41 touched symbols in 12 production/test files, HIGH risk, and the same 12 previously reviewed production traces. G03 adds only its contract-test method and class. |
| 12 | D15 | IMPACT | `RegistrationObserver.onNext` is LOW with zero indexed callers (the gRPC runtime invokes it); its enclosing `WorkerSessionGrpcService` is MEDIUM with six direct and 44 total dependents in the Remote module. The existing registry interface is unchanged. |
| 12 | D15 | RED | Added a service-boundary test whose response observer fails once while registry acceptance is being sent, then retries the same valid registration on the same request observer. Before the fix, `registered` was already true, so the retry was classified as an unexpected event and `hasConnectedWorker` stayed false. |
| 12 | D15 | GREEN | Assigned `registered = true` only after `workerConnections.register(...)` returns. The focused test and all seven `WorkerSessionGrpcServiceTest` cases are GREEN under Java 25. |
| 12 | C2/D01–D10/D11/D13–D14 | DOC/HYGIENE | Reconciled ADR 0019 with the implemented cold-start budget, live target derivation, attempted-target reset rules, conformance-test scope, and local-store fallback; replaced stale vocabulary and constant-derived prose; tightened the read-race comment; removed seven duplicate no-op comments; added the missing unit tag and a checked OpenSSL fixture recipe. ADR 0016's already-declared in-place supersession is retained unchanged. The three `IgnoredUploadObserver` methods remain assigned to P14b. |
| 12 | Inspection tranche | VERIFY | Spotless and `git diff --check` are GREEN. The touched unit batch passed 68 tests outside the Mockito attach sandbox. `WorkerSessionServerIT` and `RemotePlaybackIT` passed 34 real-TLS/gRPC tests with zero failures or errors. |
| 12 | Inspection tranche | SCOPE | Aggregate GitNexus detection reports 80 changed symbols across the existing dirty implementation tree, HIGH risk, and 13 affected traces. The one additional trace is `tryRead`, attributed to a comment-only edit; D15 remains within the three already tested worker-session event families. The remaining traces are the previously reviewed upload changes. |
| 13 | B3-B/D | IMPACT | `WorkerSessionServer.start` is CRITICAL with 36 direct dependents. `eligibleWorkers` and `hasConnectedWorker` are HIGH with four direct callers each; the former reaches four delivery flow families and the latter reaches Remote, Health, and FFmpeg modules. The rejected P01 registry-visibility widening is not used. |
| 13 | B3-B | RED | Replaced the test's lenient pre-start expectations with one uniform fail-fast contract. The old implementation returned an empty target set at the first assertion, so the focused three-test suite failed as expected. |
| 13 | B3-B/D | GREEN | All capability delegates now call `requireStarted`. `start` keeps its new virtual-thread executor local until Netty binds successfully and shuts it down on `IOException` or runtime failure, preserving the invariant that `server == null` implies no owned executor. The focused server suite is GREEN. |
| 14 | B7-A/B | IMPACT | `deliverOnce` is HIGH: 33 impacted symbols and three HTTP segment controller families. `recordReplacement` is HIGH: four recovery-flow symbols and four affected process families. |
| 14 | B7-B | RED | A deterministic lifecycle hook lets a real targeted replacement return after a planned restart has already installed and synchronized a newer attempt. Before the fence, the coordinator logged the obsolete replacement as current; the focused test failed on that false diagnostic. |
| 14 | B7-A/B | GREEN | `recordReplacement` now rejects a mismatched expected attempt before mutating state or logging success. After state insertion, `deliverOnce` rechecks the registry and conditionally removes the exact inserted entry when destroy won. All 34 coordinator tests are GREEN. |
| 15 | H0 | RED | Packaging contract tests required opt-in Compose and Kubernetes hardware overlays; both failed with `NoSuchFileException` against the prior tree. |
| 15 | H0 | GREEN | Added a Compose overlay mapping `/dev/dri` and the host render-group GID, plus a Kubernetes strategic-merge patch mounting the same host path with an explicit supplemental-group adaptation point. Documentation keeps both overlays opt-in and recommends a device plugin for heterogeneous/managed clusters. All 13 packaging tests are GREEN. |
| 13–15 | Decision tranche | VERIFY | B3/B7/H0 proportional unit suites passed 57 tests with zero failures or errors. Aggregate GitNexus detection reports 104 changed symbols across the dirty implementation tree, CRITICAL due to `WorkerSessionServer.start`, and 27 affected traces: 12 newly attributed delivery traces from B7, two B3 recovery/start-state traces, plus the previously reviewed worker/upload paths. H2 and H3 remain explicitly stacked features. |
| 16 | B5/P11/P13–P15/P18 | IMPACT | `AccessTokenIssuer.addProfileClaims` is CRITICAL across six authentication flows; `PlaybackTokenIssuer.issue` and the two-argument `TranscodeHandle` constructor are HIGH; the collection, observer, repository-query, and upload-precheck edits are MEDIUM/LOW. The production auth, authority, worker, and upload seams were therefore included in proportional verification. |
| 16 | B5/P11/P13–P15/P18 | STRUCTURAL | Removed the dead profile-repository access-token check and its impossible fake-only test; delegated playback authority solely to `AuthenticatedIdentity`; replaced a mutable empty list with `List.of()`; collapsed the named ignored upload observer; removed redundant live-authority joins already represented by token claims; removed the non-atomic upload authorization precheck; and confined random attempt-id minting to a test fixture by deleting two production constructors. |
| 16 | Structural tranche A | VERIFY | The focused unit batch passed 199 tests after one compile-only import correction. Spotless and Checkstyle are GREEN. The targeted auth/authority/worker integration run passed every selected suite except one stale B3 lifecycle expectation, which was corrected and then passed with all 27 `WorkerSessionServerIT` cases. |
| 17 | P02/P04/P06/P08 | IMPACT | Lifecycle entry points and request builders are HIGH because they coordinate session creation, resume, relocation, replacement, and destruction. Coordinator recovery/exhaustion paths are also HIGH across HTTP segment-delivery flows. No public production interface was widened. |
| 17 | P02/P04/P06/P08 | STRUCTURAL | Centralized per-session lock execution, centralized base transcode-request construction, replaced an allocation-only sealed exhaustion result with a boolean transition, inlined single-use recovery flow, and consolidated producer-end warning selection into one switch and log site. The stalled diagnostic now describes recovery across execution targets. |
| 17 | Lifecycle tranche | VERIFY | `ProducerLifecycleServiceTest`, `SegmentDeliveryCoordinatorTest`, `ProducerLifecycleDestroyRaceTest`, and `HlsStreamingServiceTest` passed 114 tests with zero failures or errors. Spotless and Checkstyle are GREEN. |
| 18 | P03/P05/P10 | IMPACT | The one-argument `TranscodeExecutor.isRunning` and its fake implementation are CRITICAL across five delivery flows and 13 direct test consumers; concrete implementations are MEDIUM/LOW. Attempt-id-only `stopVariant` and worker format-mapping changes are LOW. Callers were migrated before the overloads were removed. |
| 18 | P03/P05/P10 | STRUCTURAL | Removed ambiguous session-only running checks in favor of explicit variant labels, removed attempt-id-only server/registry stop overloads in favor of `(sessionId, variantLabel)`, and made the worker map protobuf container values to the domain once so filename extension and content type derive from the exhaustive domain enum. |
| 18 | Executor/worker tranche | VERIFY | Full test compilation is GREEN. The focused unit batch passed 200 tests; worker contract/settings/source-resolver tests are GREEN; and the selected real auth/authority/remote-worker integration suites completed 78 tests with the sole stale lifecycle assertion subsequently corrected and re-run GREEN. |
| 18 | P07 | DISPOSITION | The throwaway POC removed eight lines but changed bounded cleanup into unbounded per-worker semaphore retention. The LOC claim passes; the production-quality experiment fails and is not adopted. |
| 19 | T06/T07/T10/T11/T12 | IMPACT | All edited test classes/helpers were LOW. The batch stayed within established fixture and production-conversion interfaces. |
| 19 | Test helper tranche | STRUCTURAL | Reused probe/options/status helpers, centralized the ABR session builder, folded exact playlist/recovery/resume duplication, moved two clocks to the shared atomic fake, and replaced local UUID conversions with production `ProtoUuid`. |
| 19 | Test helper tranche | VERIFY | The focused batch passed 174 unit tests; `WorkerSessionServerIT` plus `TranscodeWorkerIT` passed 45 real-TLS/gRPC cases. Spotless and Checkstyle are GREEN. |
| 20 | T01/T15 | IMPACT | All six remote IT classes were LOW. Removing `AbstractIntegrationTest` was restricted to the three classes proven to use no Spring bean or database facility. |
| 20 | Remote fixture/runtime tranche | STRUCTURAL | Added `RemoteWorkerFixtures`, migrated all six repeated TLS/server/worker/remux setups, and removed unused Spring/Testcontainers inheritance from three suites. |
| 20 | Remote fixture/runtime tranche | VERIFY | All 57 affected integration tests are GREEN. The three T15 suites run without Spring/Testcontainers boot and preserve the POC's 25.87 s → 15.19 s warm-run measurement. |
| 21 | T02/T05 | IMPACT | The five streaming-stub consumers and two authorization-stub consumers were LOW. Each fake preserves the union of observed behaviors without widening a production interface. |
| 21 | Shared fake tranche | STRUCTURAL | Added registry-backed `FakeStreamingService` and supplier-backed `FakeAuthorizationService`; replaced seven local stub implementations. |
| 21 | Shared fake tranche | VERIFY | The focused unit batch passed 49 tests; `StreamControllerIT` and `RemotePlaybackIT` passed 20 integration tests. Test compilation and Checkstyle are GREEN. |
| 22 | T09/T13a/T13b | IMPACT | `AuthTestSupport` is HIGH with 17 direct test consumers and `AuthTestSupportConfig` is MEDIUM; the token/identity test classes were LOW. The shared context and every direct auth/controller seam were included in verification. |
| 22 | Auth fixture tranche | STRUCTURAL | Added `TokenTestSupport` and `AuthenticatedIdentityFixture`; moved playback-token minting into `AuthTestSupport`; removed duplicated token keys, decoders, injected fields, and ten identity-construction blocks. |
| 22 | Auth fixture tranche | VERIFY | The focused batch passed 101 unit tests. `AuthEndpointsIT`, `SecurityFilterChainIT`, and `StreamControllerIT` passed all 61 integration cases with the real injected decoder and shared support wiring. |
| 23 | T03/T04 | IMPACT | `FakeSegmentProducingFfmpegProcessManager` is MEDIUM with eight direct test consumers; all seven streaming-rig consumers and `TranscodeWorkerIT` are LOW. No production symbol changed. |
| 23 | T03/T04 | STRUCTURAL | Removed two assertion-identical worker tests, centralized 14 media-root preambles, reused the shared process-manager fake, and introduced one builder-backed streaming rig for seven suites. T04 is adopted for navigation despite its marginal −6 LOC measurement; T03 sub-claim 5 remains waiver-pending. |
| 23 | T04 | CLEAN-BUILD RED/GREEN | A clean Java 25 compile initially failed because seven static imports referenced a Lombok-generated builder before annotation processing could run, cascading into every test-scope Lombok annotation. Qualifying calls through `StreamingRigFixture` defers method attribution until after processing; a second clean build compiled all 296 test sources successfully. No Maven configuration workaround was retained. |
| 23 | T03/T04 | VERIFY | `TranscodeWorkerIT` passed 16 cases and the worker contract passed five. The clean focused streaming batch passed 158 unit tests; `RemotePlaybackIT` and `RemoteRecoveryIT` passed all nine real-TLS/gRPC cases. Spotless and Checkstyle are GREEN. |
| 24 | Full implementation tranche | VERIFY | Java 25 `./mvnw verify` is GREEN: 1,709 unit tests and 502 integration tests passed with zero failures or errors. Checkstyle, Spotless, packaging, and merged JaCoCo reporting also completed successfully in 3:29. |
| 24 | Full implementation tranche | FINAL SCOPE | GitNexus rates both scopes CRITICAL, as expected for core playback orchestration. The full branch comparison with `main` spans 3,500 indexed symbols, 514 files, and 200 flows; the current uncommitted implementation spans 307 symbols, 73 files, and 35 flows. All 35 narrowed flows belong to the intended segment retrieval, recovery, remote dispatch/upload, or worker-session lifecycle surface. The full green gate is the controlling regression evidence. |
| 25 | Snyk hardcoded-password hook | IMPACT | `AuthTestSupport` is HIGH in test scope with 17 direct consumers and no production execution flow; its sole external password consumer, `PlaybackTokenRevocationIT`, is LOW. The support class does not exist on `main`, so the hook finding is part of the PR rather than a pre-existing mainline issue. |
| 25 | Snyk hardcoded-password hook | RED/GREEN | The targeted Snyk Code scan reproduced one medium `java/HardcodedPassword` finding. Replaced the shared literal with a random credential owned by each `AuthTestSupport` instance and routed the sole password-changing test through that instance value; identical rescans of the hook and canonical files report zero issues. |
| 25 | Snyk hardcoded-password hook | VERIFY | Both worktrees compile all test sources with Checkstyle and Spotless GREEN. `PlaybackTokenRevocationIT` passed its real PostgreSQL password-change/revocation case in both worktrees. Snyk prevention feedback was accepted for `sast:java/HardcodedPassword/test`. |
| 26 | G05 | IMPACT | `HlsStreamingService.accessSession` is CRITICAL: eight direct callers, 30 impacted symbols, five playback flows, and four modules. The permanent edit is test-only; the production method's order is unchanged. |
| 26 | G05 | MUTATION-RED/GREEN | The missing-session test now arms the authority fake to throw if queried. Moving the authority gate ahead of the live-registry lookup made the focused test error with `Authority must not be checked`; restoring registry-first lookup returned it to GREEN. |
| 27 | G07 | IMPACT | `SegmentUploadObserver.receiveData` is LOW: one direct observer caller and two affected upload flows. The public test seam is `WorkerSessionGrpcService.uploadSegment`; no production change is retained. |
| 27 | G07 | MUTATION-RED/GREEN | A dispatched, authorized upload sends 64 KiB followed by a 4 KiB tail and asserts the exact 69,632 stored bytes plus the accepted length. Resetting the buffer for each data frame made completion fail as incomplete; restoring accumulation returned the test to GREEN. |
| 28 | G11 | IMPACT | `LiveWorkerConnectionRegistry.availableSlots` is HIGH: three direct callers, nine impacted symbols, and three modules covering remote admission, health, and FFmpeg selection. The permanent edit is test-only. |
| 28 | G11 | MUTATION-RED/GREEN | Two distinct eligible workers advertise one and two slots. The test requires total capacity three and two after one dispatch. Limiting aggregation to the first worker produced `expected: 3 but was: 1`; restoring the sum returned it to GREEN. |
| 29 | G13 | IMPACT | `PasswordChangeCompletionService.complete` is LOW: one direct caller, 11 impacted symbols, one password-change flow, and two auth/resolver modules. No production change is retained. |
| 29 | G13 | MUTATION-RED/GREEN | Valid credentials for account A paired with account B's live session must fail without changing A's password or minting a token. Removing the session-account filter let the call succeed and failed the expected-exception assertion; restoring the filter returned it to GREEN. |
| 30 | G14 | IMPACT | Worker key-segment validation is LOW and the resolver is MEDIUM, confined to worker job startup. No production change is retained. |
| 30 | G14 | MUTATION-RED/GREEN | Empty and leading-slash keys must fail specifically as unsafe path segments; ignoring empty segments made both cases reach different filesystem errors. An in-namespace symlink must resolve to its real file; rejecting every symlink made that test error. Both mutations were immediately restored and all 14 resolver tests are GREEN. |
| 31 | S02 | IMPACT | `WorkerResponseObserver.onNext` is HIGH: two direct dependents, 15 impacted symbols, two worker lifecycle/startup processes, and three modules. The change remains behind the existing gRPC protocol seam. |
| 31 | S02 | RED/GREEN | A real mutual-TLS control server sent an empty response whose command case is `COMMAND_NOT_SET`. Before the fix the warning capture stayed empty and Awaitility timed out after five seconds. The observer now warns with the unexpected command case after all known branches; the focused integration test is GREEN. |
| 32 | G02 | IMPACT | `TranscodeWorker.startVariant` is LOW with one direct control-observer caller and eight impacted worker/remote symbols. The permanent edit is test-only; full target equality remains production behavior. |
| 32 | G02 | MUTATION-RED/GREEN | Two real mutual-TLS tests address valid jobs first to a different worker id with the same boot, then to the same worker id with a different boot. Comparing only boot id or only worker id made FFmpeg start and emitted `jobAttemptStarted` instead of the required `INVALID_SPECIFICATION` failure. Restoring full identity equality returned both tests to GREEN. |
| 26–32 | Safety tranche | VERIFY | The focused unit batch passed 72 tests and the new real-TLS control-plane class passed all three cases. Java 25 `./mvnw verify` is GREEN in 3:29: 1,715 unit tests and 505 integration tests passed with zero failures or errors; Checkstyle, Spotless, packaging, and merged JaCoCo reporting completed successfully. Both staged and unstaged whitespace checks are GREEN. |
| 26–32 | Safety tranche | FINAL SCOPE | GitNexus detects 321 changed symbols in 69 files and 42 affected flows across the already-dirty implementation tree, so aggregate risk remains CRITICAL. The safety tranche adds one production behavior—the unknown-command warning—and test-only pins around existing playback, upload, capacity, auth, path, and worker-fencing behavior. The 42-flow aggregate is covered by the complete green gate; no temporary mutation remains. |
