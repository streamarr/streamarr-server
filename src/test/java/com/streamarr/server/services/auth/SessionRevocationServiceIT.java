package com.streamarr.server.services.auth;

import static com.streamarr.server.jooq.generated.tables.StreamSession.STREAM_SESSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.auth.RefreshToken;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.jooq.generated.enums.StreamSessionStatus;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.StreamSessionAuthority;
import com.streamarr.server.services.streaming.StreamSessionLifecycleTransactions;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtEncodingException;

@Tag("IntegrationTest")
@DisplayName("Session Revocation Service Integration Tests")
@Import(SessionRevocationServiceIT.FailingJwtEncoderConfig.class)
class SessionRevocationServiceIT extends AbstractIntegrationTest {

  private static final Instant FIRST_REVOCATION = Instant.parse("2026-07-11T12:34:56Z");

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private RefreshTokenService refreshTokenService;
  @Autowired private SessionRevocationService sessionRevocationService;
  @Autowired private PasswordChangeService passwordChangeService;
  @Autowired private TokenRefreshService tokenRefreshService;
  @Autowired private LoginService loginService;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private StreamSessionLifecycleTransactions lifecycleTransactions;
  @Autowired private DSLContext dsl;
  @Autowired private DataSource dataSource;
  @Autowired private FailingJwtEncoder failingJwtEncoder;

  private final java.util.List<UUID> streamSessionIds = new ArrayList<>();
  private final java.util.List<UUID> movieIds = new ArrayList<>();
  private AuthTestSupport.TestIdentity identity;
  private Library library;

  @BeforeEach
  void setUp() {
    identity = authTestSupport.createIdentity();
    library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
  }

  @AfterEach
  void cleanUp() {
    failingJwtEncoder.reset();
    dsl.deleteFrom(STREAM_SESSION).where(STREAM_SESSION.ID.in(streamSessionIds)).execute();
    movieRepository.deleteAllById(movieIds);
    movieRepository.flush();
    libraryRepository.deleteById(library.getId());
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should terminate durable streams when logging out")
  void shouldTerminateDurableStreamsWhenLoggingOut() {
    var streamSessionId = saveStream(StreamSessionStatus.ACTIVE);

    refreshTokenService.logout(identity.session().getId());

    assertThat(streamStatus(streamSessionId)).isEqualTo(StreamSessionStatus.TERMINATING);
    assertThat(streamTerminalReason(streamSessionId))
        .isEqualTo(
            com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason.AUTH_REVOKED);
  }

  @Test
  @DisplayName(
      "Should terminate caller streams and mint an isolated replacement on password change")
  void shouldTerminateCallerStreamsAndMintIsolatedReplacementWhenPasswordChanged() {
    var streamSessionId = saveStream(StreamSessionStatus.ACTIVE);

    var result =
        passwordChangeService.changePassword(
            ChangePasswordCommand.builder()
                .accountId(identity.account().getId())
                .sessionId(identity.session().getId())
                .currentPassword(AuthTestSupport.PASSWORD)
                .newPassword(UUID.randomUUID().toString())
                .build());

    assertThat(streamStatus(streamSessionId)).isEqualTo(StreamSessionStatus.TERMINATING);
    assertThat(streamTerminalReason(streamSessionId))
        .isEqualTo(
            com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason.AUTH_REVOKED);
    assertThat(authSessionRepository.findById(identity.session().getId()).orElseThrow())
        .satisfies(
            session -> {
              assertThat(session.getRevokedAt()).isNotNull();
              assertThat(session.getRevokedReason())
                  .isEqualTo(SessionRevocationReason.PASSWORD_CHANGE);
            });
    assertThat(authSessionRepository.findByAccountId(identity.account().getId()))
        .filteredOn(session -> session.getRevokedAt() == null)
        .singleElement()
        .satisfies(
            replacement ->
                assertThat(replacement.getId()).isNotEqualTo(identity.session().getId()));
    assertThat(tokenRefreshService.refresh(result.rawRefreshToken()).accessToken()).isNotNull();
  }

  @Test
  @DisplayName("Should reconcile refresh and stream rows when session was already revoked")
  void shouldReconcileRefreshAndStreamRowsWhenSessionWasAlreadyRevoked() {
    authSessionRepository.revoke(
        identity.session().getId(), SessionRevocationReason.TOKEN_REUSE, FIRST_REVOCATION);
    refreshTokenRepository.revokeAllForSession(identity.session().getId(), FIRST_REVOCATION);
    var activeToken =
        refreshTokenRepository.save(
            RefreshToken.builder()
                .sessionId(identity.session().getId())
                .digest("deployment-anomaly-" + UUID.randomUUID())
                .status(RefreshTokenStatus.ACTIVE)
                .expiresAt(FIRST_REVOCATION.plus(Duration.ofDays(1)))
                .build());
    var streamSessionId = saveStream(StreamSessionStatus.PROVISIONING);

    sessionRevocationService.revoke(
        identity.session().getId(),
        SessionRevocationReason.LOGOUT,
        FIRST_REVOCATION.plusSeconds(1));

    assertThat(refreshTokenStatus(activeToken.getId())).isEqualTo(RefreshTokenStatus.REVOKED);
    assertThat(streamStatus(streamSessionId)).isEqualTo(StreamSessionStatus.TERMINATING);
    assertThat(authRevokedAt()).isEqualTo(FIRST_REVOCATION);
    assertThat(authRevocationReason()).isEqualTo(SessionRevocationReason.TOKEN_REUSE);
    assertThat(authSessionVersion()).isEqualTo(1L);
  }

  @Test
  @DisplayName("Should use the caller timestamp and preserve a stream's first terminal state")
  void shouldUseCallerTimestampAndPreserveStreamFirstTerminalState() {
    var activeStreamId = saveStream(StreamSessionStatus.ACTIVE);
    var provisioningStreamId = saveStream(StreamSessionStatus.PROVISIONING);
    var firstTerminalAt = FIRST_REVOCATION.minusSeconds(1);
    var terminatingStreamId = saveTerminatingStream(firstTerminalAt);

    sessionRevocationService.revoke(
        identity.session().getId(), SessionRevocationReason.LOGOUT, FIRST_REVOCATION);

    assertThat(authRevokedAt()).isEqualTo(FIRST_REVOCATION);
    assertThat(streamTerminalAt(activeStreamId)).isEqualTo(FIRST_REVOCATION);
    assertThat(streamTerminalAt(provisioningStreamId)).isEqualTo(FIRST_REVOCATION);
    assertThat(streamTerminalAt(terminatingStreamId)).isEqualTo(firstTerminalAt);
    assertThat(streamTerminalReason(terminatingStreamId))
        .isEqualTo(
            com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason.OWNER_DESTROY);
  }

  @Test
  @DisplayName("Should roll back auth and tokens when stream terminalization fails")
  void shouldRollBackAuthAndTokensWhenStreamTerminalizationFails() {
    var streamSessionId = saveStream(StreamSessionStatus.ACTIVE);
    var originalTokenId =
        dsl.select(com.streamarr.server.jooq.generated.tables.RefreshToken.REFRESH_TOKEN.ID)
            .from(com.streamarr.server.jooq.generated.tables.RefreshToken.REFRESH_TOKEN)
            .where(
                com.streamarr.server.jooq.generated.tables.RefreshToken.REFRESH_TOKEN.SESSION_ID.eq(
                    identity.session().getId()))
            .fetchSingle(com.streamarr.server.jooq.generated.tables.RefreshToken.REFRESH_TOKEN.ID);
    var authSessionId = identity.session().getId();
    installFailingStreamTerminalizationTrigger();
    try {
      assertThatThrownBy(
              () ->
                  sessionRevocationService.revoke(
                      authSessionId, SessionRevocationReason.LOGOUT, FIRST_REVOCATION))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("injected stream terminalization failure");
    } finally {
      removeFailingStreamTerminalizationTrigger();
    }

    assertThat(authRevokedAtOrNull()).isNull();
    assertThat(authSessionVersion()).isZero();
    assertThat(refreshTokenStatus(originalTokenId)).isEqualTo(RefreshTokenStatus.ACTIVE);
    assertThat(streamStatus(streamSessionId)).isEqualTo(StreamSessionStatus.ACTIVE);
  }

  @Test
  @DisplayName(
      "Should roll back password, sessions, refresh tokens, and streams when JWT encoding fails")
  void shouldRollBackPasswordSessionsRefreshTokensAndStreamsWhenJwtEncodingFails() {
    var streamSessionId = saveStream(StreamSessionStatus.ACTIVE);
    var newPassword = UUID.randomUUID().toString();
    var command =
        ChangePasswordCommand.builder()
            .accountId(identity.account().getId())
            .sessionId(identity.session().getId())
            .currentPassword(AuthTestSupport.PASSWORD)
            .newPassword(newPassword)
            .build();
    failingJwtEncoder.failNextEncoding();

    assertThatThrownBy(() -> passwordChangeService.changePassword(command))
        .isInstanceOf(JwtEncodingException.class)
        .hasMessageContaining("injected JWT encoding failure");

    assertThat(authSessionRepository.findByAccountId(identity.account().getId()))
        .singleElement()
        .satisfies(
            session -> {
              assertThat(session.getId()).isEqualTo(identity.session().getId());
              assertThat(session.getRevokedAt()).isNull();
              assertThat(session.getSessionVersion()).isZero();
            });
    assertThat(streamStatus(streamSessionId)).isEqualTo(StreamSessionStatus.ACTIVE);
    assertThat(tokenRefreshService.refresh(identity.rawRefreshToken()).accessToken()).isNotNull();
    assertThat(login(AuthTestSupport.PASSWORD, "rollback-old-password")).isNotNull();
    assertThatThrownBy(() -> login(newPassword, "rollback-new-password"))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  private LoginResult login(String password, String source) {
    return loginService.login(
        LoginCommand.builder()
            .email(identity.account().getEmail())
            .password(password)
            .deviceName("rollback-test-device")
            .source(source)
            .build());
  }

  @Test
  @DisplayName("Should reconcile revoked sessions with the stored revocation timestamp")
  void shouldReconcileRevokedSessionsWithStoredRevocationTimestamp() {
    var activeStreamId = saveStream(StreamSessionStatus.ACTIVE);
    var provisioningStreamId = saveStream(StreamSessionStatus.PROVISIONING);
    var firstTerminalAt = FIRST_REVOCATION.minusSeconds(1);
    var terminatingStreamId = saveTerminatingStream(firstTerminalAt);
    authSessionRepository.revoke(
        identity.session().getId(), SessionRevocationReason.LOGOUT, FIRST_REVOCATION);

    var reconciled = lifecycleTransactions.terminalizeRevokedAuthSessions(10);

    assertThat(reconciled).containsExactlyInAnyOrder(activeStreamId, provisioningStreamId);
    assertThat(streamTerminalAt(activeStreamId)).isEqualTo(FIRST_REVOCATION);
    assertThat(streamTerminalAt(provisioningStreamId)).isEqualTo(FIRST_REVOCATION);
    assertThat(streamTerminalAt(terminatingStreamId)).isEqualTo(firstTerminalAt);
    assertThat(streamTerminalReason(activeStreamId))
        .isEqualTo(
            com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason.AUTH_REVOKED);
  }

  @Test
  @DisplayName("Should skip locked streams while reconciling revoked sessions")
  void shouldSkipLockedStreamsWhileReconcilingRevokedSessions() throws Exception {
    var lockedStreamId = saveStream(StreamSessionStatus.ACTIVE);
    var availableStreamId = saveStream(StreamSessionStatus.ACTIVE);
    authSessionRepository.revoke(
        identity.session().getId(), SessionRevocationReason.LOGOUT, FIRST_REVOCATION);
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocker = executor.submit(() -> holdStreamLock(lockedStreamId, locked, release));
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

      var firstBatch = lifecycleTransactions.terminalizeRevokedAuthSessions(10);

      assertThat(firstBatch).containsExactly(availableStreamId);
      assertThat(streamStatus(lockedStreamId)).isEqualTo(StreamSessionStatus.ACTIVE);
      release.countDown();
      blocker.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
    }

    assertThat(lifecycleTransactions.terminalizeRevokedAuthSessions(10))
        .containsExactly(lockedStreamId);
  }

  @Test
  @DisplayName("Should wait for an uncommitted stream and revoke it after insertion commits")
  void shouldWaitForUncommittedStreamAndRevokeItAfterInsertionCommits() throws Exception {
    var mediaFileId = createMovieWithFile();
    var streamSessionId = UUID.randomUUID();
    streamSessionIds.add(streamSessionId);
    var inserted = new CountDownLatch(1);
    var commitInsert = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var insertion =
          executor.submit(
              () ->
                  insertStreamAndPauseBeforeCommit(
                      streamSessionId, mediaFileId, inserted, commitInsert));
      assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

      var revocation =
          executor.submit(
              () ->
                  sessionRevocationService.revoke(
                      identity.session().getId(),
                      SessionRevocationReason.LOGOUT,
                      FIRST_REVOCATION));
      assertThatThrownBy(() -> revocation.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      commitInsert.countDown();
      insertion.get(10, TimeUnit.SECONDS);
      revocation.get(10, TimeUnit.SECONDS);
    } finally {
      commitInsert.countDown();
    }

    assertThat(streamStatus(streamSessionId)).isEqualTo(StreamSessionStatus.TERMINATING);
    assertThat(streamTerminalAt(streamSessionId)).isEqualTo(FIRST_REVOCATION);
    var authority =
        StreamSessionAuthority.builder()
            .streamSessionId(streamSessionId)
            .authSessionId(identity.session().getId())
            .accountId(identity.account().getId())
            .householdId(identity.household().getId())
            .profileId(identity.profile().getId())
            .mediaFileId(mediaFileId)
            .build();
    assertThat(lifecycleTransactions.activate(authority, Duration.ofMinutes(1))).isFalse();
  }

  private void installFailingStreamTerminalizationTrigger() {
    dsl.execute(
        """
        CREATE OR REPLACE FUNCTION fail_auth_stream_terminalization()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
          IF NEW.terminal_reason = 'AUTH_REVOKED' THEN
            RAISE EXCEPTION 'injected stream terminalization failure';
          END IF;
          RETURN NEW;
        END;
        $$
        """);
    dsl.execute(
        """
        CREATE TRIGGER fail_auth_stream_terminalization
        BEFORE UPDATE ON stream_session
        FOR EACH ROW
        EXECUTE FUNCTION fail_auth_stream_terminalization()
        """);
  }

  private void removeFailingStreamTerminalizationTrigger() {
    dsl.execute("DROP TRIGGER IF EXISTS fail_auth_stream_terminalization ON stream_session");
    dsl.execute("DROP FUNCTION IF EXISTS fail_auth_stream_terminalization()");
  }

  private void holdStreamLock(UUID streamSessionId, CountDownLatch locked, CountDownLatch release) {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement("SELECT id FROM stream_session WHERE id = ? FOR UPDATE")) {
      connection.setAutoCommit(false);
      statement.setObject(1, streamSessionId);
      statement.executeQuery();
      locked.countDown();
      if (!release.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("stream reconciliation did not release the held row");
      }
      connection.rollback();
    } catch (Exception exception) {
      throw new AssertionError("could not coordinate the stream-session row lock", exception);
    }
  }

  private void insertStreamAndPauseBeforeCommit(
      UUID streamSessionId,
      UUID mediaFileId,
      CountDownLatch inserted,
      CountDownLatch commitInsert) {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                """
                INSERT INTO stream_session (
                    id, auth_session_id, account_id, household_id, profile_id, media_file_id, status)
                VALUES (?, ?, ?, ?, ?, ?, 'PROVISIONING')
                """)) {
      connection.setAutoCommit(false);
      statement.setObject(1, streamSessionId);
      statement.setObject(2, identity.session().getId());
      statement.setObject(3, identity.account().getId());
      statement.setObject(4, identity.household().getId());
      statement.setObject(5, identity.profile().getId());
      statement.setObject(6, mediaFileId);
      statement.executeUpdate();
      inserted.countDown();
      if (!commitInsert.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("revocation did not finish before the stream insert timeout");
      }
      connection.commit();
    } catch (Exception exception) {
      throw new AssertionError("could not coordinate the stream-session insert", exception);
    }
  }

  private UUID saveStream(StreamSessionStatus status) {
    var mediaFileId = createMovieWithFile();
    var streamSessionId = UUID.randomUUID();
    streamSessionIds.add(streamSessionId);
    dsl.insertInto(STREAM_SESSION)
        .set(STREAM_SESSION.ID, streamSessionId)
        .set(STREAM_SESSION.AUTH_SESSION_ID, identity.session().getId())
        .set(STREAM_SESSION.ACCOUNT_ID, identity.account().getId())
        .set(STREAM_SESSION.HOUSEHOLD_ID, identity.household().getId())
        .set(STREAM_SESSION.PROFILE_ID, identity.profile().getId())
        .set(STREAM_SESSION.MEDIA_FILE_ID, mediaFileId)
        .set(STREAM_SESSION.STATUS, status)
        .execute();
    return streamSessionId;
  }

  private UUID saveTerminatingStream(Instant terminalAt) {
    var streamSessionId = saveStream(StreamSessionStatus.ACTIVE);
    dsl.update(STREAM_SESSION)
        .set(STREAM_SESSION.STATUS, StreamSessionStatus.TERMINATING)
        .set(STREAM_SESSION.TERMINAL_AT, terminalAt.atOffset(ZoneOffset.UTC))
        .set(
            STREAM_SESSION.TERMINAL_REASON,
            com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason.OWNER_DESTROY)
        .where(STREAM_SESSION.ID.eq(streamSessionId))
        .execute();
    return streamSessionId;
  }

  private UUID createMovieWithFile() {
    var file =
        MediaFile.builder()
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename("revocation-" + UUID.randomUUID() + ".mkv")
            .filepathUri("/media/" + UUID.randomUUID() + ".mkv")
            .build();
    var movie =
        movieRepository.saveAndFlush(
            Movie.builder()
                .title("Revocation " + UUID.randomUUID())
                .files(Set.of(file))
                .library(library)
                .build());
    movieIds.add(movie.getId());
    return movie.getFiles().iterator().next().getId();
  }

  private StreamSessionStatus streamStatus(UUID streamSessionId) {
    return dsl.select(STREAM_SESSION.STATUS)
        .from(STREAM_SESSION)
        .where(STREAM_SESSION.ID.eq(streamSessionId))
        .fetchSingle(STREAM_SESSION.STATUS);
  }

  private com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason
      streamTerminalReason(UUID streamSessionId) {
    return dsl.select(STREAM_SESSION.TERMINAL_REASON)
        .from(STREAM_SESSION)
        .where(STREAM_SESSION.ID.eq(streamSessionId))
        .fetchSingle(STREAM_SESSION.TERMINAL_REASON);
  }

  private RefreshTokenStatus refreshTokenStatus(UUID refreshTokenId) {
    return RefreshTokenStatus.valueOf(
        dsl.select(com.streamarr.server.jooq.generated.tables.RefreshToken.REFRESH_TOKEN.STATUS)
            .from(com.streamarr.server.jooq.generated.tables.RefreshToken.REFRESH_TOKEN)
            .where(
                com.streamarr.server.jooq.generated.tables.RefreshToken.REFRESH_TOKEN.ID.eq(
                    refreshTokenId))
            .fetchSingle(
                com.streamarr.server.jooq.generated.tables.RefreshToken.REFRESH_TOKEN.STATUS)
            .getLiteral());
  }

  private Instant authRevokedAt() {
    return authRevokedAtOrNull();
  }

  private Instant authRevokedAtOrNull() {
    var revokedAt =
        dsl.select(com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION.REVOKED_AT)
            .from(com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION)
            .where(
                com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION.ID.eq(
                    identity.session().getId()))
            .fetchSingle(
                com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION.REVOKED_AT);
    return revokedAt == null ? null : revokedAt.toInstant();
  }

  private Instant streamTerminalAt(UUID streamSessionId) {
    return dsl.select(STREAM_SESSION.TERMINAL_AT)
        .from(STREAM_SESSION)
        .where(STREAM_SESSION.ID.eq(streamSessionId))
        .fetchSingle(STREAM_SESSION.TERMINAL_AT)
        .toInstant();
  }

  private SessionRevocationReason authRevocationReason() {
    return SessionRevocationReason.valueOf(
        dsl.select(
                com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION.REVOKED_REASON)
            .from(com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION)
            .where(
                com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION.ID.eq(
                    identity.session().getId()))
            .fetchSingle(
                com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION.REVOKED_REASON)
            .getLiteral());
  }

  private long authSessionVersion() {
    return dsl.select(
            com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION.SESSION_VERSION)
        .from(com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION)
        .where(
            com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION.ID.eq(
                identity.session().getId()))
        .fetchSingle(
            com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION.SESSION_VERSION);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FailingJwtEncoderConfig {

    @Bean
    @Primary
    FailingJwtEncoder failingJwtEncoder(@Qualifier("jwtEncoder") JwtEncoder delegate) {
      return new FailingJwtEncoder(delegate);
    }
  }

  static final class FailingJwtEncoder implements JwtEncoder {

    private final JwtEncoder delegate;
    private final AtomicBoolean failNextEncoding = new AtomicBoolean();

    private FailingJwtEncoder(JwtEncoder delegate) {
      this.delegate = delegate;
    }

    void failNextEncoding() {
      failNextEncoding.set(true);
    }

    void reset() {
      failNextEncoding.set(false);
    }

    @Override
    public Jwt encode(JwtEncoderParameters parameters) throws JwtEncodingException {
      if (failNextEncoding.getAndSet(false)) {
        throw new JwtEncodingException("injected JWT encoding failure");
      }
      return delegate.encode(parameters);
    }
  }
}
