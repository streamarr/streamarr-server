package com.streamarr.server.services.watchprogress;

import static com.streamarr.server.jooq.generated.tables.StreamSession.STREAM_SESSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamSessionTerminalReason;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.jooq.generated.enums.StreamSessionStatus;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.repositories.streaming.StreamSessionTermination;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.services.streaming.StreamSessionLifecycleTransactions;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Session Progress Timeline Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionProgressTimelineIT extends AbstractIntegrationTest {

  private static final Instant INITIAL_ACCESS = Instant.parse("2020-01-01T00:00:00Z");
  private static final Instant FUTURE_ACCESS = Instant.parse("2100-01-01T00:00:00Z");

  @Autowired private SessionProgressService service;
  @Autowired private SessionProgressRepository progressRepository;
  @Autowired private WatchHistoryRepository watchHistoryRepository;
  @Autowired private RuntimeStreamSessionRegistry runtimeRegistry;
  @Autowired private StreamSessionLifecycleTransactions lifecycleTransactions;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private MovieRepository movieRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private AccountProfileRepository accountProfileRepository;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;

  private final java.util.List<UUID> streamSessionIds = new ArrayList<>();
  private final java.util.List<UUID> movieIds = new ArrayList<>();
  private AuthTestSupport.TestIdentity identity;
  private Library library;
  private UUID alternateProfileId;

  @BeforeAll
  void setUpIdentity() {
    identity = authTestSupport.createIdentity();
    library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var alternateProfile =
        profileRepository.saveAndFlush(
            ProfileFixture.defaultProfileBuilder()
                .householdId(identity.household().getId())
                .build());
    alternateProfileId = alternateProfile.getId();
    accountProfileRepository.linkProfile(
        AccountProfile.builder()
            .accountId(identity.account().getId())
            .householdId(identity.household().getId())
            .profileId(alternateProfileId)
            .build());
  }

  @AfterEach
  void cleanUpStreamSessions() {
    streamSessionIds.forEach(runtimeRegistry::removeById);
    streamSessionIds.forEach(progressRepository::deleteBySessionId);
    dsl.deleteFrom(STREAM_SESSION).where(STREAM_SESSION.ID.in(streamSessionIds)).execute();
    streamSessionIds.clear();
    movieRepository.deleteAllById(movieIds);
    movieRepository.flush();
    movieIds.clear();
  }

  @AfterAll
  void cleanUpIdentity() {
    libraryRepository.deleteById(library.getId());
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should commit progress and mirror the exact durable timeline in runtime")
  void shouldCommitProgressAndMirrorExactDurableTimelineInRuntime() {
    var fixture = activeTimelineFixture();

    service.reportStreamSessionTimeline(
        identity.profile().getId(), fixture.runtime().getSessionId(), 300, PlaybackState.PLAYING);

    var durableAccess = durableAccess(fixture.runtime().getSessionId());
    var progress =
        progressRepository.findBySessionId(fixture.runtime().getSessionId()).orElseThrow();
    var runtimeSnapshot = fixture.runtime().getPlaybackSnapshot();

    assertThat(durableAccess).isAfter(INITIAL_ACCESS);
    assertThat(progress.getPositionSeconds()).isEqualTo(300);
    assertThat(runtimeSnapshot.positionSeconds()).isEqualTo(300);
    assertThat(runtimeSnapshot.state()).isEqualTo(PlaybackState.PLAYING);
    assertThat(runtimeSnapshot.accessedAt()).isEqualTo(durableAccess);
  }

  @Test
  @DisplayName("Should roll back progress and leave runtime unchanged with the outer transaction")
  void shouldRollBackProgressAndLeaveRuntimeUnchangedWithOuterTransaction() {
    var fixture = activeTimelineFixture();
    var initialSnapshot = fixture.runtime().getPlaybackSnapshot();
    var transaction = new TransactionTemplate(transactionManager);

    transaction.executeWithoutResult(
        status -> {
          var reportStartedAfter = databaseStatementTime();
          service.reportStreamSessionTimeline(
              identity.profile().getId(),
              fixture.runtime().getSessionId(),
              600,
              PlaybackState.PAUSED);

          assertThat(fixture.runtime().getPlaybackSnapshot()).isEqualTo(initialSnapshot);
          assertThat(durableAccess(fixture.runtime().getSessionId()))
              .isAfterOrEqualTo(reportStartedAfter);
          status.setRollbackOnly();
        });

    assertThat(durableAccess(fixture.runtime().getSessionId())).isEqualTo(INITIAL_ACCESS);
    assertThat(progressRepository.findBySessionId(fixture.runtime().getSessionId())).isEmpty();
    assertThat(fixture.runtime().getPlaybackSnapshot()).isEqualTo(initialSnapshot);
  }

  @Test
  @DisplayName("Should reject runtime session when durable session is missing")
  void shouldRejectRuntimeSessionWhenDurableSessionIsMissing() {
    var fixture = activeTimelineFixture();
    var profileId = identity.profile().getId();
    var streamSessionId = fixture.runtime().getSessionId();
    var initialSnapshot = fixture.runtime().getPlaybackSnapshot();
    dsl.deleteFrom(STREAM_SESSION).where(STREAM_SESSION.ID.eq(streamSessionId)).execute();

    assertThatThrownBy(
            () ->
                service.reportStreamSessionTimeline(
                    profileId, streamSessionId, 300, PlaybackState.PLAYING))
        .isInstanceOf(SessionNotFoundException.class);

    assertThat(progressRepository.findBySessionId(streamSessionId)).isEmpty();
    assertThat(fixture.runtime().getPlaybackSnapshot()).isEqualTo(initialSnapshot);
  }

  @Test
  @DisplayName("Should reject timeline when durable session belongs to another profile")
  void shouldRejectTimelineWhenDurableSessionBelongsToAnotherProfile() {
    var fixture = timelineFixture(StreamSessionStatus.ACTIVE, alternateProfileId, INITIAL_ACCESS);
    var profileId = identity.profile().getId();
    var streamSessionId = fixture.runtime().getSessionId();
    var initialSnapshot = fixture.runtime().getPlaybackSnapshot();

    assertThatThrownBy(
            () ->
                service.reportStreamSessionTimeline(
                    profileId, streamSessionId, 300, PlaybackState.PLAYING))
        .isInstanceOf(SessionNotFoundException.class);

    assertThat(durableAccess(streamSessionId)).isEqualTo(INITIAL_ACCESS);
    assertThat(progressRepository.findBySessionId(streamSessionId)).isEmpty();
    assertThat(fixture.runtime().getPlaybackSnapshot()).isEqualTo(initialSnapshot);
  }

  @Test
  @DisplayName("Should reject timeline when durable session is not active")
  void shouldRejectTimelineWhenDurableSessionIsNotActive() {
    var profileId = identity.profile().getId();
    var fixture = timelineFixture(StreamSessionStatus.PROVISIONING, profileId, INITIAL_ACCESS);
    var streamSessionId = fixture.runtime().getSessionId();
    var initialSnapshot = fixture.runtime().getPlaybackSnapshot();

    assertThatThrownBy(
            () ->
                service.reportStreamSessionTimeline(
                    profileId, streamSessionId, 300, PlaybackState.PLAYING))
        .isInstanceOf(SessionNotFoundException.class);

    assertThat(durableAccess(streamSessionId)).isEqualTo(INITIAL_ACCESS);
    assertThat(progressRepository.findBySessionId(streamSessionId)).isEmpty();
    assertThat(fixture.runtime().getPlaybackSnapshot()).isEqualTo(initialSnapshot);
  }

  @Test
  @DisplayName("Should preserve a newer durable access timestamp when timeline reported")
  void shouldPreserveNewerDurableAccessTimestampWhenTimelineReported() {
    var fixture =
        timelineFixture(StreamSessionStatus.ACTIVE, identity.profile().getId(), FUTURE_ACCESS);

    service.reportStreamSessionTimeline(
        identity.profile().getId(), fixture.runtime().getSessionId(), 900, PlaybackState.PAUSED);

    var snapshot = fixture.runtime().getPlaybackSnapshot();
    assertThat(durableAccess(fixture.runtime().getSessionId())).isEqualTo(FUTURE_ACCESS);
    assertThat(snapshot.positionSeconds()).isEqualTo(900);
    assertThat(snapshot.state()).isEqualTo(PlaybackState.PAUSED);
    assertThat(snapshot.accessedAt()).isEqualTo(FUTURE_ACCESS);
  }

  @Test
  @DisplayName("Should roll back watched status with the durable timeline touch")
  void shouldRollBackWatchedStatusWithDurableTimelineTouch() {
    var fixture = activeTimelineFixture();
    var initialSnapshot = fixture.runtime().getPlaybackSnapshot();
    var initialWatchCount = watchHistoryRepository.count();
    var transaction = new TransactionTemplate(transactionManager);

    transaction.executeWithoutResult(
        status -> {
          service.reportStreamSessionTimeline(
              identity.profile().getId(),
              fixture.runtime().getSessionId(),
              6840,
              PlaybackState.STOPPED);
          assertThat(fixture.runtime().getPlaybackSnapshot()).isEqualTo(initialSnapshot);
          status.setRollbackOnly();
        });

    assertThat(durableAccess(fixture.runtime().getSessionId())).isEqualTo(INITIAL_ACCESS);
    assertThat(watchHistoryRepository.count()).isEqualTo(initialWatchCount);
    assertThat(progressRepository.findBySessionId(fixture.runtime().getSessionId())).isEmpty();
    assertThat(fixture.runtime().getPlaybackSnapshot()).isEqualTo(initialSnapshot);
  }

  @Test
  @DisplayName("Should require an outer transaction for owned timeline touch")
  void shouldRequireOuterTransactionForOwnedTimelineTouch() {
    var streamSessionId = UUID.randomUUID();
    var profileId = identity.profile().getId();

    assertThatThrownBy(
            () -> lifecycleTransactions.touchIfActiveAndOwnedBy(streamSessionId, profileId))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  @DisplayName("Should serialize timeline commit before concurrent terminalization")
  void shouldSerializeTimelineCommitBeforeConcurrentTerminalization() throws Exception {
    var fixture = activeTimelineFixture();
    var timelineWritten = new CountDownLatch(1);
    var allowTimelineCommit = new CountDownLatch(1);
    var terminalizationStarted = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var timeline =
          executor.submit(
              () -> {
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        _ -> {
                          service.reportStreamSessionTimeline(
                              identity.profile().getId(),
                              fixture.runtime().getSessionId(),
                              1200,
                              PlaybackState.PLAYING);
                          timelineWritten.countDown();
                          await(allowTimelineCommit);
                        });
                return true;
              });
      await(timelineWritten);

      var terminalization =
          executor.submit(
              () -> {
                terminalizationStarted.countDown();
                return lifecycleTransactions.terminalize(
                    StreamSessionTermination.builder()
                        .streamSessionId(fixture.runtime().getSessionId())
                        .terminalAt(Instant.parse("2026-07-11T03:00:00Z"))
                        .reason(StreamSessionTerminalReason.OWNER_DESTROY)
                        .build());
              });
      await(terminalizationStarted);
      try {
        assertThatThrownBy(() -> terminalization.get(300, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
      } finally {
        allowTimelineCommit.countDown();
      }

      assertThat(timeline.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(terminalization.get(10, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(durableStatus(fixture.runtime().getSessionId()))
        .isEqualTo(StreamSessionStatus.TERMINATING);
    var progress =
        progressRepository.findBySessionId(fixture.runtime().getSessionId()).orElseThrow();
    assertThat(progress.getPositionSeconds()).isEqualTo(1200);
  }

  private TimelineFixture activeTimelineFixture() {
    return timelineFixture(StreamSessionStatus.ACTIVE, identity.profile().getId(), INITIAL_ACCESS);
  }

  private TimelineFixture timelineFixture(
      StreamSessionStatus status, UUID durableProfileId, Instant lastAccessedAt) {
    var mediaFileId = createMovieWithFile();
    var runtime =
        StreamSessionFixture.defaultSessionBuilder()
            .mediaFileId(mediaFileId)
            .profileId(identity.profile().getId())
            .build();
    runtime.setLastAccessedAt(lastAccessedAt);
    streamSessionIds.add(runtime.getSessionId());
    runtimeRegistry.save(runtime);

    dsl.insertInto(STREAM_SESSION)
        .set(STREAM_SESSION.ID, runtime.getSessionId())
        .set(STREAM_SESSION.AUTH_SESSION_ID, identity.session().getId())
        .set(STREAM_SESSION.ACCOUNT_ID, identity.account().getId())
        .set(STREAM_SESSION.HOUSEHOLD_ID, identity.household().getId())
        .set(STREAM_SESSION.PROFILE_ID, durableProfileId)
        .set(STREAM_SESSION.MEDIA_FILE_ID, mediaFileId)
        .set(STREAM_SESSION.STATUS, status)
        .set(STREAM_SESSION.LAST_ACCESSED_AT, lastAccessedAt.atOffset(ZoneOffset.UTC))
        .execute();
    return new TimelineFixture(runtime);
  }

  private UUID createMovieWithFile() {
    var file =
        MediaFile.builder()
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename("timeline-" + UUID.randomUUID() + ".mkv")
            .filepathUri("/media/" + UUID.randomUUID() + ".mkv")
            .build();
    var movie =
        movieRepository.saveAndFlush(
            Movie.builder()
                .title("Timeline " + UUID.randomUUID())
                .files(Set.of(file))
                .library(library)
                .build());
    movieIds.add(movie.getId());
    return movie.getFiles().iterator().next().getId();
  }

  private Instant durableAccess(UUID streamSessionId) {
    return dsl.select(STREAM_SESSION.LAST_ACCESSED_AT)
        .from(STREAM_SESSION)
        .where(STREAM_SESSION.ID.eq(streamSessionId))
        .fetchOptional(STREAM_SESSION.LAST_ACCESSED_AT)
        .orElseThrow()
        .toInstant();
  }

  private StreamSessionStatus durableStatus(UUID streamSessionId) {
    return dsl.select(STREAM_SESSION.STATUS)
        .from(STREAM_SESSION)
        .where(STREAM_SESSION.ID.eq(streamSessionId))
        .fetchOptional(STREAM_SESSION.STATUS)
        .orElseThrow();
  }

  private Instant databaseStatementTime() {
    var statementTimestamp =
        DSL.function(DSL.name("statement_timestamp"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    return dsl.select(statementTimestamp).fetchSingle().value1().toInstant();
  }

  private record TimelineFixture(StreamSession runtime) {}

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for timeline interleaving");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for timeline interleaving", exception);
    }
  }
}
