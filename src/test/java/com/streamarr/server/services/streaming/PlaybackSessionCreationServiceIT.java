package com.streamarr.server.services.streaming;

import static com.streamarr.server.jooq.generated.Tables.STREAM_SESSION;
import static com.streamarr.server.jooq.generated.tables.AccountProfile.ACCOUNT_PROFILE;
import static com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION;
import static com.streamarr.server.jooq.generated.tables.StreamSessionTerminationIntent.STREAM_SESSION_TERMINATION_INTENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamSessionTerminalReason;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.jooq.generated.enums.StreamSessionStatus;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.streaming.MediaStreamTermination;
import com.streamarr.server.repositories.streaming.StreamSessionAuthority;
import com.streamarr.server.repositories.streaming.StreamSessionTermination;
import com.streamarr.server.services.auth.AccessToken;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.services.auth.SessionScopeService;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Playback Session Creation Service Integration Tests")
class PlaybackSessionCreationServiceIT extends AbstractIntegrationTest {

  private static final IllegalStateException TOKEN_FAILURE =
      new IllegalStateException("simulated playback token encoding failure");
  private static final IllegalStateException TRANSCODE_FAILURE =
      new IllegalStateException("simulated second variant startup failure");
  private static final ControllableTranscodeExecutor TRANSCODE_EXECUTOR =
      new ControllableTranscodeExecutor();
  private static final FakeFfprobeService FFPROBE_SERVICE = new FakeFfprobeService();
  private static final ObservingFailingSegmentStore SEGMENT_STORE =
      new ObservingFailingSegmentStore();

  @TestBean TranscodeExecutor transcodeExecutor;
  @TestBean FfprobeService ffprobeService;
  @TestBean SegmentStore segmentStore;

  @Autowired private StreamingService streamingService;
  @Autowired private PlaybackSessionCreationService playbackSessionCreationService;
  @Autowired private PlaybackTokenIssuer playbackTokenIssuer;
  @Autowired private StreamingProperties streamingProperties;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private SessionScopeService sessionScopeService;
  @Autowired private DSLContext dsl;
  @Autowired private StreamSessionLifecycleTransactions lifecycleTransactions;
  @Autowired private RuntimeStreamSessionRegistry runtimeRegistry;
  @Autowired private StreamSessionCleanup cleanup;
  @Autowired private StreamSessionTransactionRetry transactionRetry;
  @Autowired private TerminatingStreamSessionCleanupWorker cleanupWorker;
  @Autowired private Clock clock;
  @Autowired private MockMvc mockMvc;
  @Autowired private PlatformTransactionManager transactionManager;

  private TestIdentity testIdentity;
  private UUID libraryId;
  private UUID mediaFileId;
  private UUID streamSessionId;

  static TranscodeExecutor transcodeExecutor() {
    return TRANSCODE_EXECUTOR;
  }

  static FfprobeService ffprobeService() {
    return FFPROBE_SERVICE;
  }

  static SegmentStore segmentStore() {
    return SEGMENT_STORE;
  }

  @BeforeEach
  void setUp() {
    TRANSCODE_EXECUTOR.reset();
    FFPROBE_SERVICE.setDefaultProbe(defaultProbe("h264"));
    SEGMENT_STORE.reset(dsl);
    testIdentity = authTestSupport.createIdentity();
    sessionScopeService.selectHousehold(
        testIdentity.account().getId(),
        testIdentity.session().getId(),
        testIdentity.household().getId());
    sessionScopeService.selectProfile(
        testIdentity.account().getId(),
        testIdentity.session().getId(),
        testIdentity.profile().getId());
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    libraryId = library.getId();
    mediaFileId = saveMediaFile(libraryId).getId();
  }

  @AfterEach
  void tearDown() {
    SEGMENT_STORE.allowDeletion();
    if (streamSessionId != null) {
      streamingService.terminateRuntime(streamSessionId);
    }
    deleteStreamRowIfSchemaExists();
    mediaFileRepository.deleteById(mediaFileId);
    libraryRepository.deleteById(libraryId);
    authTestSupport.deleteIdentity(testIdentity);
  }

  @Test
  @DisplayName(
      "Should retain terminal authority and stop runtime when playback token issuance fails")
  void shouldRetainTerminalAuthorityAndStopRuntimeWhenPlaybackTokenIssuanceFails() {
    SEGMENT_STORE.failDeletion();
    var stateProbe = new StreamSessionStateProbe(dsl);
    var tokenIssuer = new ObservingFailingPlaybackTokenIssuer(stateProbe);
    PlaybackSessionCreationService creationService =
        new DefaultPlaybackSessionCreationService(
            streamingService,
            tokenIssuer,
            streamingProperties,
            lifecycleTransactions,
            runtimeRegistry,
            cleanup,
            transactionRetry,
            clock);

    assertThatThrownBy(
            () ->
                creationService.create(
                    CreatePlaybackSessionCommand.builder()
                        .mediaFileId(mediaFileId)
                        .options(defaultOptions())
                        .sourceIdentity(sourceIdentity())
                        .build()))
        .isSameAs(TOKEN_FAILURE);

    streamSessionId = tokenIssuer.streamSessionId();
    assertThat(tokenIssuer.observedActiveAuthority()).isTrue();
    assertThat(SEGMENT_STORE.observedTerminatingAuthority()).isTrue();
    assertThat(stateProbe.status(streamSessionId)).contains(StreamSessionStatus.TERMINATING);
    assertThat(stateProbe.terminalReason(streamSessionId))
        .contains(
            com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason.STARTUP_FAILURE);
    assertThat(stateProbe.activeCount(streamSessionId)).isZero();
    assertThat(streamingService.getActiveSessionCount()).isZero();
    assertThat(TRANSCODE_EXECUTOR.isRunning(streamSessionId)).isFalse();

    SEGMENT_STORE.allowDeletion();
    cleanupWorker.cleanupTerminating();

    assertThat(stateProbe.status(streamSessionId)).isEmpty();
  }

  @Test
  @DisplayName("Should retry terminal transition after creation compensation exhausts")
  void shouldRetryTerminalTransitionAfterCreationCompensationExhausts() {
    var stateProbe = new StreamSessionStateProbe(dsl);
    var tokenIssuer = new ObservingFailingPlaybackTokenIssuer(stateProbe);
    var retryingLifecycle = new FailingThenDelegatingLifecycle(lifecycleTransactions, 0, 3);
    PlaybackSessionCreationService creationService =
        new DefaultPlaybackSessionCreationService(
            streamingService,
            tokenIssuer,
            streamingProperties,
            retryingLifecycle,
            runtimeRegistry,
            cleanup,
            transactionRetry,
            clock);

    assertThatThrownBy(
            () ->
                creationService.create(
                    CreatePlaybackSessionCommand.builder()
                        .mediaFileId(mediaFileId)
                        .options(defaultOptions())
                        .sourceIdentity(sourceIdentity())
                        .build()))
        .isSameAs(TOKEN_FAILURE);

    streamSessionId = tokenIssuer.streamSessionId();
    assertThat(stateProbe.status(streamSessionId)).contains(StreamSessionStatus.ACTIVE);
    assertThat(lifecycleTransactions.findTerminationIntents()).hasSize(1);
    assertThat(streamingService.getActiveSessionCount()).isZero();

    new TerminatingStreamSessionCleanupWorker(retryingLifecycle, cleanup, transactionRetry, clock)
        .cleanupTerminating();

    assertThat(lifecycleTransactions.findTerminationIntents()).isEmpty();
    assertThat(stateProbe.status(streamSessionId)).isEmpty();
  }

  @Test
  @DisplayName("Should recover exhausted terminal transition after restart")
  void shouldRecoverExhaustedTerminalTransitionAfterRestart() {
    var stateProbe = new StreamSessionStateProbe(dsl);
    var tokenIssuer = new ObservingFailingPlaybackTokenIssuer(stateProbe);
    var retryingLifecycle = new FailingThenDelegatingLifecycle(lifecycleTransactions, 0, 3);
    PlaybackSessionCreationService creationService =
        new DefaultPlaybackSessionCreationService(
            streamingService,
            tokenIssuer,
            streamingProperties,
            retryingLifecycle,
            runtimeRegistry,
            cleanup,
            transactionRetry,
            clock);

    assertThatThrownBy(
            () ->
                creationService.create(
                    CreatePlaybackSessionCommand.builder()
                        .mediaFileId(mediaFileId)
                        .options(defaultOptions())
                        .sourceIdentity(sourceIdentity())
                        .build()))
        .isSameAs(TOKEN_FAILURE);

    streamSessionId = tokenIssuer.streamSessionId();
    assertThat(stateProbe.status(streamSessionId)).contains(StreamSessionStatus.ACTIVE);
    assertThat(streamingService.getActiveSessionCount()).isZero();

    new TerminatingStreamSessionCleanupWorker(
            lifecycleTransactions, cleanup, transactionRetry, clock)
        .cleanupTerminating();

    assertThat(stateProbe.status(streamSessionId)).isEmpty();
  }

  @Test
  @DisplayName("Should recover when terminal transition and intent persistence both exhaust")
  void shouldRecoverWhenTerminalTransitionAndIntentPersistenceBothExhaust() {
    var stateProbe = new StreamSessionStateProbe(dsl);
    var tokenIssuer = new ObservingFailingPlaybackTokenIssuer(stateProbe);
    var failingLifecycle = new FailingThenDelegatingLifecycle(lifecycleTransactions, 0, 3, 3);
    PlaybackSessionCreationService creationService =
        new DefaultPlaybackSessionCreationService(
            streamingService,
            tokenIssuer,
            streamingProperties,
            failingLifecycle,
            runtimeRegistry,
            cleanup,
            transactionRetry,
            clock);

    assertThatThrownBy(
            () ->
                creationService.create(
                    CreatePlaybackSessionCommand.builder()
                        .mediaFileId(mediaFileId)
                        .options(defaultOptions())
                        .sourceIdentity(sourceIdentity())
                        .build()))
        .isSameAs(TOKEN_FAILURE);

    streamSessionId = tokenIssuer.streamSessionId();
    assertThat(stateProbe.status(streamSessionId)).contains(StreamSessionStatus.ACTIVE);
    assertThat(lifecycleTransactions.findTerminationIntents()).isEmpty();
    assertThat(streamingService.getActiveSessionCount()).isZero();
    assertThat(
            dsl.fetchCount(
                STREAM_SESSION_TERMINATION_INTENT,
                STREAM_SESSION_TERMINATION_INTENT.STREAM_SESSION_ID.eq(streamSessionId)))
        .isEqualTo(1);
    assertThat(
            dsl.select(STREAM_SESSION_TERMINATION_INTENT.ARMED)
                .from(STREAM_SESSION_TERMINATION_INTENT)
                .where(STREAM_SESSION_TERMINATION_INTENT.STREAM_SESSION_ID.eq(streamSessionId))
                .fetchSingle(STREAM_SESSION_TERMINATION_INTENT.ARMED))
        .isFalse();
    dsl.update(STREAM_SESSION_TERMINATION_INTENT)
        .set(STREAM_SESSION_TERMINATION_INTENT.REPLAY_AFTER, Instant.EPOCH.atOffset(ZoneOffset.UTC))
        .where(STREAM_SESSION_TERMINATION_INTENT.STREAM_SESSION_ID.eq(streamSessionId))
        .execute();

    new TerminatingStreamSessionCleanupWorker(
            lifecycleTransactions, cleanup, transactionRetry, clock)
        .cleanupTerminating();

    assertThat(stateProbe.status(streamSessionId)).isEmpty();
  }

  @Test
  @DisplayName("Should retry activation without starting FFmpeg again")
  void shouldRetryActivationWithoutStartingFfmpegAgain() {
    var retryingLifecycle = new FailingThenDelegatingLifecycle(lifecycleTransactions, 2, 0);
    PlaybackSessionCreationService creationService =
        new DefaultPlaybackSessionCreationService(
            streamingService,
            playbackTokenIssuer,
            streamingProperties,
            retryingLifecycle,
            runtimeRegistry,
            cleanup,
            transactionRetry,
            clock);

    var created =
        creationService.create(
            CreatePlaybackSessionCommand.builder()
                .mediaFileId(mediaFileId)
                .options(defaultOptions())
                .sourceIdentity(sourceIdentity())
                .build());
    streamSessionId = created.sessionId();

    assertThat(retryingLifecycle.activationAttempts()).isEqualTo(3);
    assertThat(TRANSCODE_EXECUTOR.startAttempts()).isEqualTo(1);
    assertThat(new StreamSessionStateProbe(dsl).status(streamSessionId))
        .contains(StreamSessionStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should ignore an unexpired provisioning compensation guard")
  void shouldIgnoreUnexpiredProvisioningCompensationGuard() {
    streamSessionId = UUID.randomUUID();
    var admitted =
        lifecycleTransactions.admit(
            authority(streamSessionId, sourceIdentity()),
            streamingProperties.provisioningTimeout());

    cleanupWorker.cleanupTerminating();

    assertThat(admitted).isPresent();
    assertThat(new StreamSessionStateProbe(dsl).status(streamSessionId))
        .contains(StreamSessionStatus.PROVISIONING);
    assertThat(lifecycleTransactions.findTerminationIntents()).isEmpty();
  }

  @Test
  @DisplayName("Should renew the compensation guard when activation succeeds")
  void shouldRenewCompensationGuardWhenActivationSucceeds() {
    streamSessionId = UUID.randomUUID();
    var sessionAuthority = authority(streamSessionId, sourceIdentity());
    assertThat(lifecycleTransactions.admit(sessionAuthority, Duration.ofSeconds(1))).isPresent();
    var provisioningDeadline = replayAfter(streamSessionId);

    var activated = lifecycleTransactions.activate(sessionAuthority, Duration.ofMinutes(2));

    assertThat(activated).isTrue();
    assertThat(replayAfter(streamSessionId)).isAfter(provisioningDeadline);
    assertThat(terminationIntentReason(streamSessionId))
        .isEqualTo(
            com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason.STARTUP_FAILURE);
    assertThat(terminationIntentArmed(streamSessionId)).isFalse();
  }

  @Test
  @DisplayName("Should roll back activation when the compensation guard is missing")
  void shouldRollBackActivationWhenCompensationGuardIsMissing() {
    streamSessionId = UUID.randomUUID();
    var sessionAuthority = authority(streamSessionId, sourceIdentity());
    assertThat(
            lifecycleTransactions.admit(
                sessionAuthority, streamingProperties.provisioningTimeout()))
        .isPresent();
    assertThat(lifecycleTransactions.deleteTerminationIntent(streamSessionId)).isTrue();

    assertThatThrownBy(
            () ->
                lifecycleTransactions.activate(
                    sessionAuthority, streamingProperties.provisioningTimeout()))
        .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
        .hasMessage("Stream session compensation guard is missing")
        .hasRootCauseInstanceOf(IllegalStateException.class);

    assertThat(new StreamSessionStateProbe(dsl).status(streamSessionId))
        .contains(StreamSessionStatus.PROVISIONING);
    assertThat(lifecycleTransactions.deleteTerminationIntent(streamSessionId)).isFalse();
  }

  @Test
  @DisplayName("Should ignore a stale guard candidate when creation completion wins")
  void shouldIgnoreStaleGuardCandidateWhenCreationCompletionWins() {
    streamSessionId = prepareExpiredActiveGuard();
    var staleCandidate = lifecycleTransactions.findTerminationIntents().getFirst();

    var completed = lifecycleTransactions.completeCreation(streamSessionId);
    var replayed = lifecycleTransactions.replayTerminationIntent(staleCandidate.streamSessionId());

    assertThat(completed).isTrue();
    assertThat(replayed).isFalse();
    assertThat(new StreamSessionStateProbe(dsl).status(streamSessionId))
        .contains(StreamSessionStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should reject creation completion when guard replay wins")
  void shouldRejectCreationCompletionWhenGuardReplayWins() {
    streamSessionId = prepareExpiredActiveGuard();

    var replayed = lifecycleTransactions.replayTerminationIntent(streamSessionId);
    var completed = lifecycleTransactions.completeCreation(streamSessionId);

    assertThat(replayed).isTrue();
    assertThat(completed).isFalse();
    assertThat(new StreamSessionStateProbe(dsl).status(streamSessionId))
        .contains(StreamSessionStatus.TERMINATING);
  }

  @Test
  @DisplayName("Should refuse termination-intent replay when the session is missing")
  void shouldRefuseTerminationIntentReplayWhenSessionIsMissing() {
    var replayed = lifecycleTransactions.replayTerminationIntent(UUID.randomUUID());

    assertThat(replayed).isFalse();
  }

  @Test
  @DisplayName("Should complete replay and delete the intent when termination already won")
  void shouldCompleteReplayAndDeleteIntentWhenTerminationAlreadyWon() {
    streamSessionId = UUID.randomUUID();
    var sessionAuthority = authority(streamSessionId, sourceIdentity());
    assertThat(
            lifecycleTransactions.admit(
                sessionAuthority, streamingProperties.provisioningTimeout()))
        .isPresent();
    assertThat(
            lifecycleTransactions.terminalize(
                StreamSessionTermination.builder()
                    .streamSessionId(streamSessionId)
                    .reason(StreamSessionTerminalReason.OWNER_DESTROY)
                    .terminalAt(clock.instant())
                    .build()))
        .isTrue();

    var replayed = lifecycleTransactions.replayTerminationIntent(streamSessionId);

    assertThat(replayed).isTrue();
    assertThat(new StreamSessionStateProbe(dsl).status(streamSessionId))
        .contains(StreamSessionStatus.TERMINATING);
    assertThat(lifecycleTransactions.deleteTerminationIntent(streamSessionId)).isFalse();
  }

  @Test
  @DisplayName(
      "Should return runtime session and mirror committed timestamp when authority matches")
  void shouldReturnRuntimeSessionAndMirrorCommittedTimestampWhenAuthorityMatches() {
    var created =
        playbackSessionCreationService.create(
            CreatePlaybackSessionCommand.builder()
                .mediaFileId(mediaFileId)
                .options(defaultOptions())
                .sourceIdentity(sourceIdentity())
                .build());
    streamSessionId = created.sessionId();
    var playbackIdentity =
        AuthenticatedIdentity.fromJwt(jwtDecoder.decode(created.playbackToken().value()));
    PlaybackSessionAccessService accessService =
        new DefaultPlaybackSessionAccessService(
            runtimeRegistry, lifecycleTransactions, transactionRetry);

    var result = accessService.access(streamSessionId, playbackIdentity);

    assertThat(result).isPresent();
    var durableAccess = new StreamSessionStateProbe(dsl).lastAccessedAt(streamSessionId);
    assertThat(durableAccess).isPresent();
    assertThat(result.orElseThrow().getLastAccessedAt()).isEqualTo(durableAccess.orElseThrow());
  }

  @Test
  @DisplayName("Should initialize runtime access timestamp from committed database authority")
  void shouldInitializeRuntimeAccessTimestampFromCommittedDatabaseAuthority() {
    createPlaybackSession();

    var durableAccess =
        new StreamSessionStateProbe(dsl).lastAccessedAt(streamSessionId).orElseThrow();
    var runtimeAccess = runtimeRegistry.findById(streamSessionId).orElseThrow().getLastAccessedAt();

    assertThat(runtimeAccess).isEqualTo(durableAccess);
    assertThat(
            dsl.fetchCount(
                STREAM_SESSION_TERMINATION_INTENT,
                STREAM_SESSION_TERMINATION_INTENT.STREAM_SESSION_ID.eq(streamSessionId)))
        .isZero();
  }

  @Test
  @DisplayName("Should commit provisioning authority before FFmpeg starts")
  void shouldCommitProvisioningAuthorityBeforeFfmpegStarts() {
    TRANSCODE_EXECUTOR.observeFirstStart(
        sessionId ->
            new StreamSessionStateProbe(dsl)
                .status(sessionId)
                .filter(StreamSessionStatus.PROVISIONING::equals)
                .isPresent());

    createPlaybackSession();

    assertThat(TRANSCODE_EXECUTOR.observedProvisioningAtFirstStart()).isTrue();
  }

  @Test
  @DisplayName("Should reject an outer transaction before admission or FFmpeg")
  void shouldRejectOuterTransactionBeforeAdmissionOrFfmpeg() {
    var transactionTemplate = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transactionTemplate.execute(
                    _ ->
                        playbackSessionCreationService.create(
                            CreatePlaybackSessionCommand.builder()
                                .mediaFileId(mediaFileId)
                                .options(defaultOptions())
                                .sourceIdentity(sourceIdentity())
                                .build())))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    assertThat(TRANSCODE_EXECUTOR.startAttempts()).isZero();
    assertThat(new StreamSessionStateProbe(dsl).countForAuthSession(testIdentity.session().getId()))
        .isZero();
  }

  @Test
  @DisplayName("Should refuse admission when the media source is missing")
  void shouldRefuseAdmissionWhenMediaSourceIsMissing() {
    mediaFileRepository.deleteById(mediaFileId);
    mediaFileRepository.flush();

    var admitted =
        lifecycleTransactions.admit(
            authority(UUID.randomUUID(), sourceIdentity()),
            streamingProperties.provisioningTimeout());

    assertThat(admitted).isEmpty();
  }

  @Test
  @DisplayName("Should leave playback unchanged when no media sources are terminalized")
  void shouldLeavePlaybackUnchangedWhenNoMediaSourcesAreTerminalized() {
    createPlaybackSession();

    var terminalized =
        lifecycleTransactions.terminalizeByMediaFiles(
            MediaStreamTermination.builder()
                .mediaFileIds(Set.of())
                .reason(StreamSessionTerminalReason.SOURCE_DELETED)
                .terminalAt(clock.instant())
                .build());

    assertThat(terminalized).isEmpty();
    assertThat(new StreamSessionStateProbe(dsl).status(streamSessionId))
        .contains(StreamSessionStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should return the next page of terminating sessions after the keyset")
  void shouldReturnNextPageOfTerminatingSessionsAfterKeyset() {
    var sessionIds =
        List.of(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("11111111-1111-1111-1111-111111111112"),
            UUID.fromString("11111111-1111-1111-1111-111111111113"));
    var identity = sourceIdentity();
    for (var sessionId : sessionIds) {
      assertThat(
              lifecycleTransactions.admit(
                  authority(sessionId, identity), streamingProperties.provisioningTimeout()))
          .isPresent();
      assertThat(
              lifecycleTransactions.terminalize(
                  StreamSessionTermination.builder()
                      .streamSessionId(sessionId)
                      .reason(StreamSessionTerminalReason.OWNER_DESTROY)
                      .terminalAt(clock.instant())
                      .build()))
          .isTrue();
    }

    var page = lifecycleTransactions.findTerminatingIdsAfter(sessionIds.getFirst(), 1);

    assertThat(page).containsExactly(sessionIds.get(1));
  }

  @Test
  @DisplayName("Should create and serve playlist and segment through durable playback gate")
  void shouldCreateAndServePlaylistAndSegmentThroughDurablePlaybackGate() throws Exception {
    var created =
        playbackSessionCreationService.create(
            CreatePlaybackSessionCommand.builder()
                .mediaFileId(mediaFileId)
                .options(defaultOptions())
                .sourceIdentity(sourceIdentity())
                .build());
    streamSessionId = created.sessionId();

    var result =
        mockMvc
            .perform(
                get("/api/stream/{sessionId}/master.m3u8", streamSessionId)
                    .param("t", created.playbackToken().value()))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentAsString()).contains("#EXTM3U");

    var segment = new byte[] {0x47, 0x01, 0x02};
    SEGMENT_STORE.addSegment(streamSessionId, "segment0.ts", segment);
    mockMvc
        .perform(
            get("/api/stream/{sessionId}/segment0.ts", streamSessionId)
                .param("t", created.playbackToken().value()))
        .andExpect(status().isOk())
        .andExpect(content().contentType("video/mp2t"))
        .andExpect(content().bytes(segment));
  }

  @Test
  @DisplayName("Should durably terminalize before owner destroy cleans runtime")
  void shouldDurablyTerminalizeBeforeOwnerDestroyCleansRuntime() throws Exception {
    createPlaybackSession();
    var requestBody =
        """
        {
          "query": "mutation { destroyStreamSession(sessionId: \\"%s\\") }"
        }
        """
            .formatted(streamSessionId);

    mockMvc
        .perform(
            post("/graphql")
                .with(AuthTestSupport.bearer(authTestSupport.profileBearer(testIdentity)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.destroyStreamSession").value(true));

    assertThat(SEGMENT_STORE.observedTerminatingAuthority()).isTrue();
    assertThat(new StreamSessionStateProbe(dsl).status(streamSessionId)).isEmpty();
    assertThat(streamingService.getActiveSessionCount()).isZero();
    assertThat(TRANSCODE_EXECUTOR.isRunning(streamSessionId)).isFalse();
  }

  @Test
  @DisplayName("Should deny playback gate when auth session is revoked after creation")
  void shouldDenyPlaybackGateWhenAuthSessionIsRevokedAfterCreation() {
    var created = createPlaybackSession();
    var playbackIdentity = playbackIdentity(created);
    var stateProbe = new StreamSessionStateProbe(dsl);
    var before = stateProbe.lastAccessedAt(streamSessionId);
    authSessionRepository.revoke(
        testIdentity.session().getId(), SessionRevocationReason.LOGOUT, clock.instant());

    var result = playbackAccessService().access(streamSessionId, playbackIdentity);

    assertThat(result).isEmpty();
    assertThat(stateProbe.lastAccessedAt(streamSessionId)).isEqualTo(before);
  }

  @Test
  @DisplayName("Should deny playback gate when account is disabled after creation")
  void shouldDenyPlaybackGateWhenAccountIsDisabledAfterCreation() {
    var created = createPlaybackSession();
    var playbackIdentity = playbackIdentity(created);
    var account = testIdentity.account();
    account.setEnabled(false);
    userAccountRepository.saveAndFlush(account);

    var result = playbackAccessService().access(streamSessionId, playbackIdentity);

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should deny playback gate when token auth session does not match durable authority")
  void shouldDenyPlaybackGateWhenTokenAuthSessionDoesNotMatchDurableAuthority() {
    var created = createPlaybackSession();
    var playbackIdentity = playbackIdentity(created);
    var stateProbe = new StreamSessionStateProbe(dsl);
    var before = stateProbe.lastAccessedAt(streamSessionId);
    var mismatchedIdentity =
        copyIdentity(playbackIdentity, UUID.randomUUID(), playbackIdentity.profileId());

    var result = playbackAccessService().access(streamSessionId, mismatchedIdentity);

    assertThat(result).isEmpty();
    assertThat(stateProbe.lastAccessedAt(streamSessionId)).isEqualTo(before);
  }

  @Test
  @DisplayName("Should deny playback gate when token profile does not match durable authority")
  void shouldDenyPlaybackGateWhenTokenProfileDoesNotMatchDurableAuthority() {
    var created = createPlaybackSession();
    var playbackIdentity = playbackIdentity(created);
    var stateProbe = new StreamSessionStateProbe(dsl);
    var before = stateProbe.lastAccessedAt(streamSessionId);
    var mismatchedIdentity =
        copyIdentity(playbackIdentity, playbackIdentity.sessionId(), UUID.randomUUID());

    var result = playbackAccessService().access(streamSessionId, mismatchedIdentity);

    assertThat(result).isEmpty();
    assertThat(stateProbe.lastAccessedAt(streamSessionId)).isEqualTo(before);
  }

  @Test
  @DisplayName("Should deny playback gate while durable authority is provisioning")
  void shouldDenyPlaybackGateWhileDurableAuthorityIsProvisioning() {
    streamSessionId = UUID.randomUUID();
    var identity = sourceIdentity();
    assertThat(
            lifecycleTransactions.admit(
                authority(streamSessionId, identity), streamingProperties.provisioningTimeout()))
        .isPresent();
    runtimeRegistry.save(
        StreamSession.builder().sessionId(streamSessionId).profileId(identity.profileId()).build());
    var stateProbe = new StreamSessionStateProbe(dsl);
    var before = stateProbe.lastAccessedAt(streamSessionId);

    var access =
        playbackAccessService()
            .access(streamSessionId, playbackIdentity(identity, streamSessionId));

    assertThat(access).isEmpty();
    assertThat(stateProbe.status(streamSessionId)).contains(StreamSessionStatus.PROVISIONING);
    assertThat(stateProbe.lastAccessedAt(streamSessionId)).isEqualTo(before);
  }

  @Test
  @DisplayName("Should preserve a later durable access timestamp when playback is touched")
  void shouldPreserveLaterDurableAccessTimestampWhenPlaybackIsTouched() {
    var created = createPlaybackSession();
    var futureAccess = Instant.parse("2030-01-02T03:04:05.123456789Z");
    dsl.update(STREAM_SESSION)
        .set(STREAM_SESSION.LAST_ACCESSED_AT, futureAccess.atOffset(ZoneOffset.UTC))
        .where(STREAM_SESSION.ID.eq(streamSessionId))
        .execute();

    var result = playbackAccessService().access(streamSessionId, playbackIdentity(created));
    var storedAccess = postgresTimestamp(futureAccess);

    assertThat(result).isPresent();
    assertThat(new StreamSessionStateProbe(dsl).lastAccessedAt(streamSessionId))
        .contains(storedAccess);
    assertThat(result.orElseThrow().getLastAccessedAt()).isEqualTo(storedAccess);
  }

  @Test
  @DisplayName("Should deny and reconcile playback when media deletion event is missing")
  void shouldDenyAndReconcilePlaybackWhenMediaDeletionEventIsMissing() {
    var created = createPlaybackSession();
    var playbackIdentity = playbackIdentity(created);
    mediaFileRepository.deleteById(mediaFileId);
    mediaFileRepository.flush();

    var access = playbackAccessService().access(streamSessionId, playbackIdentity);
    cleanupWorker.cleanupTerminating();

    assertThat(access).isEmpty();
    assertThat(new StreamSessionStateProbe(dsl).status(streamSessionId)).isEmpty();
    assertThat(streamingService.getActiveSessionCount()).isZero();
    assertThat(TRANSCODE_EXECUTOR.isRunning(streamSessionId)).isFalse();
  }

  @Test
  @DisplayName("Should spawn no transcode when auth session was revoked before admission")
  void shouldSpawnNoTranscodeWhenAuthSessionWasRevokedBeforeAdmission() {
    var identity = sourceIdentity();
    authSessionRepository.revoke(
        testIdentity.session().getId(), SessionRevocationReason.LOGOUT, clock.instant());

    assertThatThrownBy(
            () ->
                playbackSessionCreationService.create(
                    CreatePlaybackSessionCommand.builder()
                        .mediaFileId(mediaFileId)
                        .options(defaultOptions())
                        .sourceIdentity(identity)
                        .build()))
        .isInstanceOf(com.streamarr.server.exceptions.SessionNotFoundException.class);

    assertThat(TRANSCODE_EXECUTOR.getStartedRequests()).isEmpty();
    assertThat(new StreamSessionStateProbe(dsl).countForAuthSession(testIdentity.session().getId()))
        .isZero();
  }

  @Test
  @DisplayName("Should spawn no transcode when account is disabled before admission")
  void shouldSpawnNoTranscodeWhenAccountIsDisabledBeforeAdmission() {
    var account = testIdentity.account();
    account.setEnabled(false);
    userAccountRepository.saveAndFlush(account);

    assertThatThrownBy(this::createPlaybackSession)
        .isInstanceOf(com.streamarr.server.exceptions.SessionNotFoundException.class);

    assertThat(TRANSCODE_EXECUTOR.getStartedRequests()).isEmpty();
    assertThat(new StreamSessionStateProbe(dsl).countForAuthSession(testIdentity.session().getId()))
        .isZero();
  }

  @Test
  @DisplayName("Should spawn no transcode when profile grant is missing before admission")
  void shouldSpawnNoTranscodeWhenProfileGrantIsMissingBeforeAdmission() {
    var identity = sourceIdentity();
    dsl.deleteFrom(ACCOUNT_PROFILE)
        .where(ACCOUNT_PROFILE.ACCOUNT_ID.eq(testIdentity.account().getId()))
        .and(ACCOUNT_PROFILE.PROFILE_ID.eq(testIdentity.profile().getId()))
        .execute();

    assertThatThrownBy(
            () ->
                playbackSessionCreationService.create(
                    CreatePlaybackSessionCommand.builder()
                        .mediaFileId(mediaFileId)
                        .options(defaultOptions())
                        .sourceIdentity(identity)
                        .build()))
        .isInstanceOf(com.streamarr.server.exceptions.SessionNotFoundException.class);

    assertThat(TRANSCODE_EXECUTOR.getStartedRequests()).isEmpty();
    assertThat(new StreamSessionStateProbe(dsl).countForAuthSession(testIdentity.session().getId()))
        .isZero();
  }

  @Test
  @DisplayName("Should spawn no transcode when profile context mismatches before admission")
  void shouldSpawnNoTranscodeWhenProfileContextMismatchesBeforeAdmission() {
    var identity = sourceIdentity();
    var mismatchedIdentity = copyIdentity(identity, identity.sessionId(), UUID.randomUUID());

    assertThatThrownBy(
            () ->
                playbackSessionCreationService.create(
                    CreatePlaybackSessionCommand.builder()
                        .mediaFileId(mediaFileId)
                        .options(defaultOptions())
                        .sourceIdentity(mismatchedIdentity)
                        .build()))
        .isInstanceOf(com.streamarr.server.exceptions.SessionNotFoundException.class);

    assertThat(TRANSCODE_EXECUTOR.getStartedRequests()).isEmpty();
    assertThat(new StreamSessionStateProbe(dsl).countForAuthSession(testIdentity.session().getId()))
        .isZero();
  }

  @Test
  @DisplayName("Should leave no playable runtime when auth is revoked during startup")
  void shouldLeaveNoPlayableRuntimeWhenAuthIsRevokedDuringStartup() throws Exception {
    SEGMENT_STORE.failDeletion();
    TRANSCODE_EXECUTOR.blockNextStart();
    var identity = sourceIdentity();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var creation =
          executor.submit(
              () ->
                  playbackSessionCreationService.create(
                      CreatePlaybackSessionCommand.builder()
                          .mediaFileId(mediaFileId)
                          .options(defaultOptions())
                          .sourceIdentity(identity)
                          .build()));
      assertThat(TRANSCODE_EXECUTOR.awaitBlockedStart()).isTrue();
      streamSessionId = TRANSCODE_EXECUTOR.blockedSessionId();

      authSessionRepository.revoke(
          testIdentity.session().getId(), SessionRevocationReason.LOGOUT, clock.instant());
      TRANSCODE_EXECUTOR.releaseStart();

      assertThatThrownBy(() -> creation.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(com.streamarr.server.exceptions.SessionNotFoundException.class);
    }

    var stateProbe = new StreamSessionStateProbe(dsl);
    assertThat(stateProbe.status(streamSessionId)).contains(StreamSessionStatus.TERMINATING);
    assertThat(stateProbe.activeCount(streamSessionId)).isZero();
    assertThat(streamingService.getActiveSessionCount()).isZero();
    assertThat(TRANSCODE_EXECUTOR.isRunning(streamSessionId)).isFalse();
  }

  @Test
  @DisplayName("Should not activate a provisioning row with another live identity")
  void shouldNotActivateProvisioningRowWithAnotherLiveIdentity() {
    streamSessionId = UUID.randomUUID();
    var admittedAuthority = authority(streamSessionId, sourceIdentity());
    assertThat(
            lifecycleTransactions.admit(
                admittedAuthority, streamingProperties.provisioningTimeout()))
        .isPresent();
    var otherIdentity = authTestSupport.createIdentity();
    try {
      sessionScopeService.selectHousehold(
          otherIdentity.account().getId(),
          otherIdentity.session().getId(),
          otherIdentity.household().getId());
      sessionScopeService.selectProfile(
          otherIdentity.account().getId(),
          otherIdentity.session().getId(),
          otherIdentity.profile().getId());
      var mismatchedAuthority = authority(streamSessionId, sourceIdentity(otherIdentity));

      var activated =
          lifecycleTransactions.activate(
              mismatchedAuthority, streamingProperties.provisioningTimeout());

      assertThat(activated).isFalse();
      assertThat(new StreamSessionStateProbe(dsl).status(streamSessionId))
          .contains(StreamSessionStatus.PROVISIONING);
    } finally {
      authTestSupport.deleteIdentity(otherIdentity);
    }
  }

  @Test
  @DisplayName("Should refuse activation when the requested auth session does not exist")
  void shouldRefuseActivationWhenRequestedAuthSessionDoesNotExist() {
    streamSessionId = UUID.randomUUID();
    var admittedAuthority = authority(streamSessionId, sourceIdentity());
    assertThat(
            lifecycleTransactions.admit(
                admittedAuthority, streamingProperties.provisioningTimeout()))
        .isPresent();
    var missingAuthAuthority =
        StreamSessionAuthority.builder()
            .streamSessionId(streamSessionId)
            .authSessionId(UUID.randomUUID())
            .accountId(admittedAuthority.accountId())
            .householdId(admittedAuthority.householdId())
            .profileId(admittedAuthority.profileId())
            .mediaFileId(admittedAuthority.mediaFileId())
            .build();

    var activated =
        lifecycleTransactions.activate(
            missingAuthAuthority, streamingProperties.provisioningTimeout());

    assertThat(activated).isFalse();
    assertThat(new StreamSessionStateProbe(dsl).status(streamSessionId))
        .contains(StreamSessionStatus.PROVISIONING);
  }

  @Test
  @DisplayName("Should wait for in-flight revocation and then refuse activation")
  void shouldWaitForInFlightRevocationAndThenRefuseActivation() throws Exception {
    streamSessionId = UUID.randomUUID();
    var authority = authority(streamSessionId, sourceIdentity());
    assertThat(lifecycleTransactions.admit(authority, streamingProperties.provisioningTimeout()))
        .isPresent();
    var revocationUpdated = new CountDownLatch(1);
    var allowRevocationCommit = new CountDownLatch(1);
    var activationStarted = new CountDownLatch(1);
    var transactionTemplate = new TransactionTemplate(transactionManager);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var revocation =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      _ -> {
                        dsl.update(AUTH_SESSION)
                            .set(AUTH_SESSION.REVOKED_AT, clock.instant().atOffset(ZoneOffset.UTC))
                            .set(
                                AUTH_SESSION.REVOKED_REASON,
                                com.streamarr.server.jooq.generated.enums.SessionRevocationReason
                                    .LOGOUT)
                            .where(AUTH_SESSION.ID.eq(testIdentity.session().getId()))
                            .execute();
                        revocationUpdated.countDown();
                        ControllableTranscodeExecutor.await(allowRevocationCommit);
                      }));
      assertThat(revocationUpdated.await(5, TimeUnit.SECONDS)).isTrue();
      var activation =
          executor.submit(
              () -> {
                activationStarted.countDown();
                return lifecycleTransactions.activate(
                    authority, streamingProperties.provisioningTimeout());
              });
      assertThat(activationStarted.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> activation.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
      allowRevocationCommit.countDown();
      revocation.get(5, TimeUnit.SECONDS);

      assertThat(activation.get(5, TimeUnit.SECONDS)).isFalse();
    } finally {
      allowRevocationCommit.countDown();
    }

    assertThat(new StreamSessionStateProbe(dsl).status(streamSessionId))
        .contains(StreamSessionStatus.PROVISIONING);
  }

  @Test
  @DisplayName("Should preserve terminal marker when authoritative parents are deleted")
  void shouldPreserveTerminalMarkerWhenAuthoritativeParentsAreDeleted() {
    createPlaybackSession();
    var terminalAt = Instant.parse("2030-01-02T03:04:05.123456789Z");
    assertThat(
            lifecycleTransactions.terminalize(
                StreamSessionTermination.builder()
                    .streamSessionId(streamSessionId)
                    .reason(StreamSessionTerminalReason.AUTH_REVOKED)
                    .terminalAt(terminalAt)
                    .build()))
        .isTrue();

    assertThatThrownBy(
            () ->
                dsl.deleteFrom(ACCOUNT_PROFILE)
                    .where(ACCOUNT_PROFILE.ACCOUNT_ID.eq(testIdentity.account().getId()))
                    .and(ACCOUNT_PROFILE.PROFILE_ID.eq(testIdentity.profile().getId()))
                    .execute())
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                dsl.deleteFrom(AUTH_SESSION)
                    .where(AUTH_SESSION.ID.eq(testIdentity.session().getId()))
                    .execute())
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    var stateProbe = new StreamSessionStateProbe(dsl);
    assertThat(stateProbe.status(streamSessionId)).contains(StreamSessionStatus.TERMINATING);
    assertThat(stateProbe.terminalReason(streamSessionId))
        .contains(
            com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason.AUTH_REVOKED);
    assertThat(stateProbe.terminalAt(streamSessionId)).contains(postgresTimestamp(terminalAt));
  }

  @Test
  @DisplayName("Should leave no playable runtime when media source is deleted during startup")
  void shouldLeaveNoPlayableRuntimeWhenMediaSourceIsDeletedDuringStartup() throws Exception {
    SEGMENT_STORE.failDeletion();
    TRANSCODE_EXECUTOR.blockNextStart();
    var identity = sourceIdentity();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var creation =
          executor.submit(
              () ->
                  playbackSessionCreationService.create(
                      CreatePlaybackSessionCommand.builder()
                          .mediaFileId(mediaFileId)
                          .options(defaultOptions())
                          .sourceIdentity(identity)
                          .build()));
      assertThat(TRANSCODE_EXECUTOR.awaitBlockedStart()).isTrue();
      streamSessionId = TRANSCODE_EXECUTOR.blockedSessionId();

      mediaFileRepository.deleteById(mediaFileId);
      mediaFileRepository.flush();
      TRANSCODE_EXECUTOR.releaseStart();

      assertThatThrownBy(() -> creation.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(com.streamarr.server.exceptions.SessionNotFoundException.class);
    }

    var stateProbe = new StreamSessionStateProbe(dsl);
    assertThat(stateProbe.status(streamSessionId)).contains(StreamSessionStatus.TERMINATING);
    assertThat(stateProbe.activeCount(streamSessionId)).isZero();
    assertThat(streamingService.getActiveSessionCount()).isZero();
    assertThat(TRANSCODE_EXECUTOR.isRunning(streamSessionId)).isFalse();
  }

  @Test
  @DisplayName("Should stop partial ladder when a later variant fails to start")
  void shouldStopPartialLadderWhenLaterVariantFailsToStart() {
    SEGMENT_STORE.failDeletion();
    FFPROBE_SERVICE.setDefaultProbe(defaultProbe("hevc"));
    TRANSCODE_EXECUTOR.failOnStart(2, TRANSCODE_FAILURE);

    assertThatThrownBy(
            () ->
                playbackSessionCreationService.create(
                    CreatePlaybackSessionCommand.builder()
                        .mediaFileId(mediaFileId)
                        .options(defaultOptions())
                        .sourceIdentity(sourceIdentity())
                        .build()))
        .isSameAs(TRANSCODE_FAILURE);

    streamSessionId = TRANSCODE_EXECUTOR.attemptedSessionId();
    var stateProbe = new StreamSessionStateProbe(dsl);
    assertThat(TRANSCODE_EXECUTOR.startAttempts()).isEqualTo(2);
    assertThat(TRANSCODE_EXECUTOR.getStartedRequests()).hasSize(1);
    assertThat(TRANSCODE_EXECUTOR.isRunning(streamSessionId)).isFalse();
    assertThat(streamingService.getActiveSessionCount()).isZero();
    assertThat(stateProbe.status(streamSessionId)).contains(StreamSessionStatus.TERMINATING);
  }

  @Test
  @DisplayName("Should preserve the first terminal reason and timestamp")
  void shouldPreserveFirstTerminalReasonAndTimestamp() {
    createPlaybackSession();
    var firstAt = Instant.parse("2030-01-02T03:04:05.123456789Z");
    var secondAt = Instant.parse("2030-01-02T03:04:06.987654321Z");

    var firstTransition =
        lifecycleTransactions.terminalize(
            StreamSessionTermination.builder()
                .streamSessionId(streamSessionId)
                .reason(StreamSessionTerminalReason.AUTH_REVOKED)
                .terminalAt(firstAt)
                .build());
    var secondTransition =
        lifecycleTransactions.terminalize(
            StreamSessionTermination.builder()
                .streamSessionId(streamSessionId)
                .reason(StreamSessionTerminalReason.SOURCE_DELETED)
                .terminalAt(secondAt)
                .build());

    var stateProbe = new StreamSessionStateProbe(dsl);
    assertThat(firstTransition).isTrue();
    assertThat(secondTransition).isFalse();
    assertThat(stateProbe.terminalReason(streamSessionId))
        .contains(
            com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason.AUTH_REVOKED);
    assertThat(stateProbe.terminalAt(streamSessionId)).contains(postgresTimestamp(firstAt));
  }

  @Test
  @DisplayName("Should refuse activation when durable authority is already active")
  void shouldRefuseActivationWhenDurableAuthorityIsAlreadyActive() {
    createPlaybackSession();

    var activatedAgain =
        lifecycleTransactions.activate(
            authority(streamSessionId, sourceIdentity()),
            streamingProperties.provisioningTimeout());

    assertThat(activatedAgain).isFalse();
    assertThat(new StreamSessionStateProbe(dsl).status(streamSessionId))
        .contains(StreamSessionStatus.ACTIVE);
  }

  private AuthenticatedIdentity sourceIdentity() {
    return sourceIdentity(testIdentity);
  }

  private UUID prepareExpiredActiveGuard() {
    var sessionId = UUID.randomUUID();
    var sessionAuthority = authority(sessionId, sourceIdentity());
    assertThat(
            lifecycleTransactions.admit(
                sessionAuthority, streamingProperties.provisioningTimeout()))
        .isPresent();
    assertThat(
            lifecycleTransactions.activate(
                sessionAuthority, streamingProperties.provisioningTimeout()))
        .isTrue();
    dsl.update(STREAM_SESSION_TERMINATION_INTENT)
        .set(STREAM_SESSION_TERMINATION_INTENT.REPLAY_AFTER, Instant.EPOCH.atOffset(ZoneOffset.UTC))
        .where(STREAM_SESSION_TERMINATION_INTENT.STREAM_SESSION_ID.eq(sessionId))
        .execute();
    return sessionId;
  }

  private Instant replayAfter(UUID sessionId) {
    return dsl.select(STREAM_SESSION_TERMINATION_INTENT.REPLAY_AFTER)
        .from(STREAM_SESSION_TERMINATION_INTENT)
        .where(STREAM_SESSION_TERMINATION_INTENT.STREAM_SESSION_ID.eq(sessionId))
        .fetchSingle(STREAM_SESSION_TERMINATION_INTENT.REPLAY_AFTER)
        .toInstant();
  }

  private com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason
      terminationIntentReason(UUID sessionId) {
    return dsl.select(STREAM_SESSION_TERMINATION_INTENT.TERMINAL_REASON)
        .from(STREAM_SESSION_TERMINATION_INTENT)
        .where(STREAM_SESSION_TERMINATION_INTENT.STREAM_SESSION_ID.eq(sessionId))
        .fetchSingle(STREAM_SESSION_TERMINATION_INTENT.TERMINAL_REASON);
  }

  private boolean terminationIntentArmed(UUID sessionId) {
    return dsl.select(STREAM_SESSION_TERMINATION_INTENT.ARMED)
        .from(STREAM_SESSION_TERMINATION_INTENT)
        .where(STREAM_SESSION_TERMINATION_INTENT.STREAM_SESSION_ID.eq(sessionId))
        .fetchSingle(STREAM_SESSION_TERMINATION_INTENT.ARMED);
  }

  private static Instant postgresTimestamp(Instant timestamp) {
    return timestamp.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
  }

  private AuthenticatedIdentity sourceIdentity(TestIdentity identity) {
    var jwt = jwtDecoder.decode(authTestSupport.profileBearer(identity));
    return AuthenticatedIdentity.fromJwt(jwt);
  }

  private StreamSessionAuthority authority(
      UUID candidateStreamSessionId, AuthenticatedIdentity identity) {
    return StreamSessionAuthority.builder()
        .streamSessionId(candidateStreamSessionId)
        .authSessionId(identity.sessionId())
        .accountId(identity.accountId())
        .householdId(identity.householdId())
        .profileId(identity.profileId())
        .mediaFileId(mediaFileId)
        .build();
  }

  private CreatedPlaybackSession createPlaybackSession() {
    var created =
        playbackSessionCreationService.create(
            CreatePlaybackSessionCommand.builder()
                .mediaFileId(mediaFileId)
                .options(defaultOptions())
                .sourceIdentity(sourceIdentity())
                .build());
    streamSessionId = created.sessionId();
    return created;
  }

  private AuthenticatedIdentity playbackIdentity(CreatedPlaybackSession created) {
    return AuthenticatedIdentity.fromJwt(jwtDecoder.decode(created.playbackToken().value()));
  }

  private AuthenticatedIdentity playbackIdentity(
      AuthenticatedIdentity source, UUID candidateStreamSessionId) {
    return AuthenticatedIdentity.builder()
        .accountId(source.accountId())
        .role(source.role())
        .sessionId(source.sessionId())
        .sessionVersion(source.sessionVersion())
        .scope(com.streamarr.server.services.auth.TokenScope.PLAYBACK)
        .householdId(source.householdId())
        .householdRole(source.householdRole())
        .membershipVersion(source.membershipVersion())
        .profileId(source.profileId())
        .policyVersion(source.policyVersion())
        .streamSessionId(candidateStreamSessionId)
        .build();
  }

  private AuthenticatedIdentity copyIdentity(
      AuthenticatedIdentity source, UUID sessionId, UUID profileId) {
    return AuthenticatedIdentity.builder()
        .accountId(source.accountId())
        .role(source.role())
        .sessionId(sessionId)
        .sessionVersion(source.sessionVersion())
        .scope(source.scope())
        .householdId(source.householdId())
        .householdRole(source.householdRole())
        .membershipVersion(source.membershipVersion())
        .profileId(profileId)
        .policyVersion(source.policyVersion())
        .streamSessionId(source.streamSessionId())
        .build();
  }

  private PlaybackSessionAccessService playbackAccessService() {
    return new DefaultPlaybackSessionAccessService(
        runtimeRegistry, lifecycleTransactions, transactionRetry);
  }

  private MediaFile saveMediaFile(UUID owningLibraryId) {
    return mediaFileRepository.saveAndFlush(
        MediaFile.builder()
            .filepathUri("/media/movies/test-" + UUID.randomUUID() + ".mkv")
            .filename("test.mkv")
            .status(MediaFileStatus.MATCHED)
            .size(1_000_000L)
            .libraryId(owningLibraryId)
            .build());
  }

  private StreamingOptions defaultOptions() {
    return StreamingOptions.builder()
        .quality(VideoQuality.AUTO)
        .supportedCodecs(List.of("h264"))
        .build();
  }

  private static MediaProbe defaultProbe(String videoCodec) {
    return MediaProbe.builder()
        .duration(Duration.ofMinutes(120))
        .framerate(23.976)
        .width(1920)
        .height(1080)
        .videoCodec(videoCodec)
        .audioCodec("aac")
        .bitrate(5_000_000L)
        .build();
  }

  private void deleteStreamRowIfSchemaExists() {
    try {
      var authorityMatch =
          streamSessionId == null
              ? field(name("auth_session_id"), UUID.class).eq(testIdentity.session().getId())
              : field(name("id"), UUID.class).eq(streamSessionId);
      dsl.deleteFrom(table(name("stream_session"))).where(authorityMatch).execute();
    } catch (RuntimeException exception) {
      if (!hasSqlState(exception, "42P01")) {
        throw exception;
      }
    }
  }

  private static boolean hasSqlState(Throwable throwable, String expected) {
    for (var cause = throwable; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException
          && expected.equals(sqlException.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  private static final class ObservingFailingPlaybackTokenIssuer extends PlaybackTokenIssuer {

    private final StreamSessionStateProbe stateProbe;
    private UUID streamSessionId;
    private boolean observedActiveAuthority;

    private ObservingFailingPlaybackTokenIssuer(StreamSessionStateProbe stateProbe) {
      super(null, null, null);
      this.stateProbe = stateProbe;
    }

    @Override
    public AccessToken issue(
        AuthenticatedIdentity identity, StreamSession streamSession, Duration validity) {
      streamSessionId = streamSession.getSessionId();
      observedActiveAuthority =
          stateProbe.status(streamSessionId).filter(StreamSessionStatus.ACTIVE::equals).isPresent();
      throw TOKEN_FAILURE;
    }

    private UUID streamSessionId() {
      return streamSessionId;
    }

    private boolean observedActiveAuthority() {
      return observedActiveAuthority;
    }
  }

  private static final class ObservingFailingSegmentStore implements SegmentStore {

    private final FakeSegmentStore delegate = new FakeSegmentStore();
    private StreamSessionStateProbe stateProbe;
    private boolean observedTerminatingAuthority;
    private boolean failDeletion;

    private void reset(DSLContext dsl) {
      stateProbe = new StreamSessionStateProbe(dsl);
      observedTerminatingAuthority = false;
      failDeletion = false;
    }

    private void failDeletion() {
      failDeletion = true;
    }

    private void allowDeletion() {
      failDeletion = false;
    }

    private boolean observedTerminatingAuthority() {
      return observedTerminatingAuthority;
    }

    private void addSegment(UUID sessionId, String name, byte[] data) {
      delegate.addSegment(sessionId, name, data);
    }

    @Override
    public byte[] readSegment(UUID sessionId, String segmentName) {
      return delegate.readSegment(sessionId, segmentName);
    }

    @Override
    public boolean waitForSegment(UUID sessionId, String segmentName, Duration timeout) {
      return delegate.waitForSegment(sessionId, segmentName, timeout);
    }

    @Override
    public boolean segmentExists(UUID sessionId, String segmentName) {
      return delegate.segmentExists(sessionId, segmentName);
    }

    @Override
    public void deleteSession(UUID sessionId) {
      observedTerminatingAuthority =
          stateProbe.status(sessionId).filter(StreamSessionStatus.TERMINATING::equals).isPresent();
      if (failDeletion) {
        throw new IllegalStateException("simulated segment cleanup failure");
      }
      delegate.deleteSession(sessionId);
    }
  }

  private record StreamSessionStateProbe(DSLContext dsl) {

    private Optional<StreamSessionStatus> status(UUID sessionId) {
      return dsl.select(STREAM_SESSION.STATUS)
          .from(STREAM_SESSION)
          .where(STREAM_SESSION.ID.eq(sessionId))
          .fetchOptional(STREAM_SESSION.STATUS);
    }

    private Optional<com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason>
        terminalReason(UUID sessionId) {
      return dsl.select(STREAM_SESSION.TERMINAL_REASON)
          .from(STREAM_SESSION)
          .where(STREAM_SESSION.ID.eq(sessionId))
          .fetchOptional(STREAM_SESSION.TERMINAL_REASON);
    }

    private Optional<Instant> terminalAt(UUID sessionId) {
      return dsl.select(STREAM_SESSION.TERMINAL_AT)
          .from(STREAM_SESSION)
          .where(STREAM_SESSION.ID.eq(sessionId))
          .fetchOptional(STREAM_SESSION.TERMINAL_AT)
          .map(java.time.OffsetDateTime::toInstant);
    }

    private int activeCount(UUID sessionId) {
      return dsl.fetchCount(
          STREAM_SESSION,
          STREAM_SESSION
              .ID
              .eq(sessionId)
              .and(STREAM_SESSION.STATUS.eq(StreamSessionStatus.ACTIVE)));
    }

    private int countForAuthSession(UUID authSessionId) {
      return dsl.fetchCount(STREAM_SESSION, STREAM_SESSION.AUTH_SESSION_ID.eq(authSessionId));
    }

    private Optional<java.time.Instant> lastAccessedAt(UUID sessionId) {
      return dsl.select(STREAM_SESSION.LAST_ACCESSED_AT)
          .from(STREAM_SESSION)
          .where(STREAM_SESSION.ID.eq(sessionId))
          .fetchOptional(STREAM_SESSION.LAST_ACCESSED_AT)
          .map(java.time.OffsetDateTime::toInstant);
    }
  }

  private static final class ControllableTranscodeExecutor extends FakeTranscodeExecutor {

    private volatile CountDownLatch startEntered;
    private volatile CountDownLatch allowStart;
    private volatile UUID blockedSessionId;
    private volatile UUID attemptedSessionId;
    private volatile RuntimeException startFailure;
    private volatile Function<UUID, Boolean> firstStartObserver;
    private volatile boolean observedProvisioningAtFirstStart;
    private volatile int failOnAttempt;
    private int startAttempts;

    private void blockNextStart() {
      startEntered = new CountDownLatch(1);
      allowStart = new CountDownLatch(1);
      blockedSessionId = null;
    }

    private boolean awaitBlockedStart() throws InterruptedException {
      return startEntered.await(5, TimeUnit.SECONDS);
    }

    private UUID blockedSessionId() {
      return blockedSessionId;
    }

    private void releaseStart() {
      allowStart.countDown();
    }

    private void failOnStart(int attempt, RuntimeException failure) {
      failOnAttempt = attempt;
      startFailure = failure;
    }

    private int startAttempts() {
      return startAttempts;
    }

    private UUID attemptedSessionId() {
      return attemptedSessionId;
    }

    private void observeFirstStart(Function<UUID, Boolean> observer) {
      firstStartObserver = observer;
    }

    private boolean observedProvisioningAtFirstStart() {
      return observedProvisioningAtFirstStart;
    }

    @Override
    public TranscodeHandle start(TranscodeRequest request) {
      attemptedSessionId = request.sessionId();
      startAttempts++;
      if (startAttempts == 1 && firstStartObserver != null) {
        observedProvisioningAtFirstStart = firstStartObserver.apply(request.sessionId());
      }
      var entered = startEntered;
      if (entered != null && entered.getCount() > 0) {
        blockedSessionId = request.sessionId();
        entered.countDown();
        await(allowStart);
      }
      if (startAttempts == failOnAttempt) {
        throw startFailure;
      }
      return super.start(request);
    }

    @Override
    public void reset() {
      super.reset();
      startEntered = null;
      allowStart = null;
      blockedSessionId = null;
      attemptedSessionId = null;
      startFailure = null;
      firstStartObserver = null;
      observedProvisioningAtFirstStart = false;
      failOnAttempt = 0;
      startAttempts = 0;
    }

    private static void await(CountDownLatch latch) {
      try {
        latch.await();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted while controlling transcode startup", exception);
      }
    }
  }

  private static final class FailingThenDelegatingLifecycle
      implements StreamSessionLifecycleTransactions {

    private final StreamSessionLifecycleTransactions delegate;
    private final AtomicInteger activationFailuresRemaining;
    private final AtomicInteger terminalFailuresRemaining;
    private final AtomicInteger intentFailuresRemaining;
    private final AtomicInteger activationAttempts = new AtomicInteger();

    private FailingThenDelegatingLifecycle(
        StreamSessionLifecycleTransactions delegate, int activationFailures, int terminalFailures) {
      this(delegate, activationFailures, terminalFailures, 0);
    }

    private FailingThenDelegatingLifecycle(
        StreamSessionLifecycleTransactions delegate,
        int activationFailures,
        int terminalFailures,
        int intentFailures) {
      this.delegate = delegate;
      activationFailuresRemaining = new AtomicInteger(activationFailures);
      terminalFailuresRemaining = new AtomicInteger(terminalFailures);
      intentFailuresRemaining = new AtomicInteger(intentFailures);
    }

    @Override
    public Optional<Instant> admit(StreamSessionAuthority authority, Duration provisioningTimeout) {
      return delegate.admit(authority, provisioningTimeout);
    }

    @Override
    public boolean activate(StreamSessionAuthority authority, Duration provisioningTimeout) {
      activationAttempts.incrementAndGet();
      if (activationFailuresRemaining.getAndDecrement() > 0) {
        throw new IllegalStateException(
            "simulated serialization failure", new SQLException("test", "40001"));
      }
      return delegate.activate(authority, provisioningTimeout);
    }

    private int activationAttempts() {
      return activationAttempts.get();
    }

    @Override
    public Optional<Instant> touchIfPlaybackRequestMatches(
        com.streamarr.server.repositories.streaming.PlaybackRequestAuthority authority) {
      return delegate.touchIfPlaybackRequestMatches(authority);
    }

    @Override
    public List<UUID> findTerminatingIds(int limit) {
      return delegate.findTerminatingIds(limit);
    }

    @Override
    public List<UUID> findTerminatingIdsAfter(UUID afterId, int limit) {
      return delegate.findTerminatingIdsAfter(afterId, limit);
    }

    @Override
    public List<UUID> terminalizeByMediaFiles(
        com.streamarr.server.repositories.streaming.MediaStreamTermination termination) {
      return delegate.terminalizeByMediaFiles(termination);
    }

    @Override
    public List<UUID> terminalizeMissingMediaSources(Instant terminalAt) {
      return delegate.terminalizeMissingMediaSources(terminalAt);
    }

    @Override
    public boolean terminalize(StreamSessionTermination termination) {
      if (terminalFailuresRemaining.getAndDecrement() > 0) {
        throw new IllegalStateException(
            "simulated serialization failure", new SQLException("test", "40001"));
      }
      return delegate.terminalize(termination);
    }

    @Override
    public boolean recordTerminationIntent(StreamSessionTermination termination) {
      if (intentFailuresRemaining.getAndDecrement() > 0) {
        throw new IllegalStateException(
            "simulated serialization failure", new SQLException("test", "40001"));
      }
      return delegate.recordTerminationIntent(termination);
    }

    @Override
    public List<StreamSessionTermination> findTerminationIntents() {
      return delegate.findTerminationIntents();
    }

    @Override
    public boolean completeCreation(UUID streamSessionId) {
      return delegate.completeCreation(streamSessionId);
    }

    @Override
    public boolean replayTerminationIntent(UUID streamSessionId) {
      return delegate.replayTerminationIntent(streamSessionId);
    }

    @Override
    public boolean deleteTerminationIntent(UUID streamSessionId) {
      return delegate.deleteTerminationIntent(streamSessionId);
    }

    @Override
    public boolean deleteTerminating(UUID streamSessionId) {
      return delegate.deleteTerminating(streamSessionId);
    }
  }
}
