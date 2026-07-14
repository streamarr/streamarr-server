package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.fixtures.StreamSessionFixture.playbackAuthorityFor;
import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.FakeAccountProfileRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.services.streaming.CreateStreamSessionCommand;
import com.streamarr.server.services.streaming.PlaybackRequest;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.watchprogress.SessionProgressService;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import com.streamarr.server.support.security.TestIdentityConstants;
import com.streamarr.server.support.security.WithProfileContext;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Tag("UnitTest")
@EnableDgsTest
@WithProfileContext
@SpringBootTest(
    classes = {
      StreamingResolver.class,
      StreamingResolverTest.TestConfig.class,
      SecurityContextAuthorizationService.class
    })
@DisplayName("Streaming Resolver Tests")
class StreamingResolverTest {

  private static final String TEST_KEY_BASE64 =
      "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQga+ZKCbAcyZIb7k2FE8rMPFtIpTdzX2dR/csZ8k6A95uhRANCAAQawOmVKMDLAOsboxKLb9khGsWyxwcIikucXDCfX18ME5X9/kqSS2vdMnFfZ6KR12U/Sy/EwOwnc82xFAyFdNbe";

  private static final StubStreamingService STUB_SERVICE = new StubStreamingService();

  @TestConfiguration
  static class TestConfig {
    @Bean
    StreamingService streamingService() {
      return STUB_SERVICE;
    }

    @Bean
    StreamingProperties streamingProperties() {
      return StreamingProperties.builder()
          .maxConcurrentTranscodes(8)
          .segmentDuration(Duration.ofSeconds(6))
          .sessionTimeout(Duration.ofSeconds(60))
          .sessionRetention(Duration.ofHours(1))
          .build();
    }

    /** The real issuer, so the resolver tests exercise its ownership refusal end to end. */
    @Bean
    PlaybackTokenIssuer playbackTokenIssuer() {
      var crypto = new TokenCryptoConfig();
      return new PlaybackTokenIssuer(
          crypto.jwtEncoder(crypto.tokenSigningKeys(tokenProperties())),
          tokenProperties(),
          java.time.Clock.systemUTC(),
          authority -> true);
    }

    @Bean
    ProfileRepository profileRepository() {
      return new FakeProfileRepository();
    }

    @Bean
    AccountProfileRepository accountProfileRepository() {
      return new FakeAccountProfileRepository();
    }

    @Bean
    SessionProgressService sessionProgressService() {
      return new NoopSessionProgressService();
    }

    @Bean
    WatchStatusService watchStatusService() {
      return new NoopWatchStatusService();
    }
  }

  @Autowired private DgsQueryExecutor dgsQueryExecutor;
  @Autowired private StreamingProperties streamingProperties;

  @BeforeEach
  void resetStubService() {
    STUB_SERVICE.reset();
  }

  private StreamSession buildSession(UUID sessionId) {
    return buildSessionOwnedBy(sessionId, TestIdentityConstants.PROFILE_ID);
  }

  private StreamSession buildSessionOwnedBy(UUID sessionId, UUID profileId) {
    return StreamSession.builder()
        .sessionId(sessionId)
        .authority(playbackAuthorityFor(profileId))
        .mediaFileId(UUID.randomUUID())
        .sourcePath(Path.of("/media/movie.mkv"))
        .mediaProbe(
            MediaProbe.builder()
                .duration(Duration.ofMinutes(120))
                .framerate(24.0)
                .width(1920)
                .height(1080)
                .videoCodec("h264")
                .audioCodec("aac")
                .bitrate(5_000_000L)
                .build())
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.REMUX)
                .videoCodecFamily("h264")
                .audioDecision(AudioDecision.copy("aac", 2, 0))
                .subtitleDecision(SubtitleDecision.exclude())
                .containerFormat(ContainerFormat.MPEGTS)
                .needsKeyframeAlignment(true)
                .build())
        .options(StreamingOptions.builder().supportedCodecs(List.of("h264")).build())
        .createdAt(Instant.now())
        .build();
  }

  @Test
  @DisplayName("Should return session DTO when creating stream session")
  void shouldReturnSessionDtoWhenCreatingStreamSession() {
    var sessionId = UUID.randomUUID();
    var session = buildSession(sessionId);
    STUB_SERVICE.setNextResult(session);

    var mutation =
        String.format(
            """
            mutation {
              createStreamSession(mediaFileId: "%s") {
                id
                streamUrl
                transcodeMode
              }
            }
            """,
            UUID.randomUUID());

    var context = dgsQueryExecutor.executeAndGetDocumentContext(mutation);
    String id = context.read("data.createStreamSession.id");
    String streamUrl = context.read("data.createStreamSession.streamUrl");
    String transcodeMode = context.read("data.createStreamSession.transcodeMode");

    assertThat(id).isEqualTo(sessionId.toString());
    assertThat(streamUrl).contains("/api/stream/" + sessionId + "/multivariant.m3u8");
    assertThat(transcodeMode).isEqualTo("REMUX");
    assertThat(STUB_SERVICE.getLastCreateProfileId()).isEqualTo(TestIdentityConstants.PROFILE_ID);
  }

  @Test
  @DisplayName("Should return stream URL with URL-safe playback token when creating session")
  void shouldReturnStreamUrlWithUrlSafePlaybackTokenWhenCreatingSession() {
    var sessionId = UUID.randomUUID();
    STUB_SERVICE.setNextResult(buildSession(sessionId));

    var mutation =
        String.format(
            """
            mutation {
              createStreamSession(mediaFileId: "%s") {
                streamUrl
              }
            }
            """,
            UUID.randomUUID());

    String streamUrl =
        dgsQueryExecutor.executeAndExtractJsonPath(mutation, "data.createStreamSession.streamUrl");

    assertThat(streamUrl).startsWith("/api/stream/" + sessionId + "/multivariant.m3u8?t=");
    assertThat(streamUrl.substring(streamUrl.indexOf("?t=") + 3)).matches("[A-Za-z0-9._-]+");
  }

  @Test
  @DisplayName("Should mint playback token for media duration plus session retention")
  void shouldMintPlaybackTokenForMediaDurationPlusSessionRetention() {
    var sessionId = UUID.randomUUID();
    var session = buildSession(sessionId);
    STUB_SERVICE.setNextResult(session);

    var mutation =
        String.format(
            """
            mutation {
              createStreamSession(mediaFileId: "%s") {
                streamUrl
              }
            }
            """,
            UUID.randomUUID());

    String streamUrl =
        dgsQueryExecutor.executeAndExtractJsonPath(mutation, "data.createStreamSession.streamUrl");
    var token = streamUrl.substring(streamUrl.indexOf("?t=") + 3);
    var decoded = decodeToken(token);
    var tokenLifetime = Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt());

    assertThat(tokenLifetime)
        .isEqualTo(session.getMediaProbe().duration().plus(streamingProperties.sessionRetention()));
  }

  private static org.springframework.security.oauth2.jwt.Jwt decodeToken(String token) {
    var keys = new TokenCryptoConfig().tokenSigningKeys(tokenProperties());
    var processor = new DefaultJWTProcessor<SecurityContext>();
    processor.setJWSKeySelector(
        new JWSVerificationKeySelector<>(
            JWSAlgorithm.ES256, new ImmutableJWKSet<>(keys.verificationKeys())));
    processor.setJWTClaimsSetVerifier((claims, context) -> {});
    return new NimbusJwtDecoder(processor).decode(token);
  }

  private static AuthTokenProperties tokenProperties() {
    return AuthTokenProperties.builder()
        .signingKey(TEST_KEY_BASE64)
        .verificationKeys(List.of())
        .accessTokenTtl(Duration.ofMinutes(10))
        .refreshTokenTtl(Duration.ofDays(30))
        .rotationGrace(Duration.ofSeconds(30))
        .build();
  }

  @Test
  @DisplayName("Should destroy session when playback token issuance fails")
  void shouldDestroySessionWhenPlaybackTokenIssuanceFails() {
    // Simulates a future internal path handing back a foreign session: the resolver must never
    // turn it into a playable URL, whatever the service layer does.
    var sessionId = UUID.randomUUID();
    STUB_SERVICE.setNextResult(buildSessionOwnedBy(sessionId, UUID.randomUUID()));

    var mutation =
        String.format(
            """
            mutation {
              createStreamSession(mediaFileId: "%s") {
                id
                streamUrl
              }
            }
            """,
            UUID.randomUUID());

    var result = dgsQueryExecutor.execute(mutation);

    assertThat(result.getErrors()).hasSize(1);
    assertThat(result.getErrors().getFirst().getMessage())
        .contains("Streaming session not found: " + sessionId);
    assertThat(result.toSpecification().toString()).doesNotContain("?t=");
    assertThat(STUB_SERVICE.getActiveSessionCount()).isZero();
  }

  @Test
  @DisplayName("Should map GraphQL options input to streaming options when options provided")
  void shouldMapGraphqlOptionsInputToStreamingOptionsWhenOptionsProvided() {
    var sessionId = UUID.randomUUID();
    var session = buildSession(sessionId);
    STUB_SERVICE.setNextResult(session);

    var mutation =
        String.format(
            """
            mutation {
              createStreamSession(
                mediaFileId: "%s",
                options: {
                  quality: HIGH_720P,
                  supportedCodecs: ["h264"],
                  supportedAudioCodecs: ["aac", "ac3"],
                  maxAudioChannels: 6
                }
              ) {
                id
              }
            }
            """,
            UUID.randomUUID());

    dgsQueryExecutor.executeAndExtractJsonPath(mutation, "data.createStreamSession.id");

    var receivedOptions = STUB_SERVICE.getLastReceivedOptions();
    assertThat(receivedOptions.quality()).isEqualTo(VideoQuality.HIGH_720P);
    assertThat(receivedOptions.supportedCodecs()).containsExactly("h264");
    assertThat(receivedOptions.supportedAudioCodecs()).containsExactly("aac", "ac3");
    assertThat(receivedOptions.maxAudioChannels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should use default options when options input is null")
  void shouldUseDefaultOptionsWhenOptionsInputIsNull() {
    var sessionId = UUID.randomUUID();
    var session = buildSession(sessionId);
    STUB_SERVICE.setNextResult(session);

    var mutation =
        String.format(
            """
            mutation {
              createStreamSession(mediaFileId: "%s") {
                id
              }
            }
            """,
            UUID.randomUUID());

    dgsQueryExecutor.executeAndExtractJsonPath(mutation, "data.createStreamSession.id");

    var receivedOptions = STUB_SERVICE.getLastReceivedOptions();
    assertThat(receivedOptions.quality()).isEqualTo(VideoQuality.AUTO);
    assertThat(receivedOptions.supportedCodecs())
        .isEqualTo(StreamingOptions.DEFAULT_SUPPORTED_CODECS);
    assertThat(receivedOptions.supportedAudioCodecs())
        .isEqualTo(StreamingOptions.DEFAULT_SUPPORTED_AUDIO_CODECS);
    assertThat(receivedOptions.maxAudioChannels())
        .isEqualTo(StreamingOptions.DEFAULT_MAX_AUDIO_CHANNELS);
  }

  @Test
  @DisplayName("Should return error when create session media file ID is invalid")
  void shouldReturnErrorWhenMediaFileIdIsInvalid() {
    var result =
        dgsQueryExecutor.execute(
            """
            mutation {
              createStreamSession(mediaFileId: "not-a-uuid") {
                id
              }
            }
            """);

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().getFirst().getMessage()).contains("Invalid ID format");
  }

  @Test
  @DisplayName("Should destroy as current profile when destroying session")
  void shouldDestroyAsCurrentProfileWhenDestroyingSession() {
    var mutation =
        String.format("mutation { destroyStreamSession(sessionId: \"%s\") }", UUID.randomUUID());

    dgsQueryExecutor.executeAndExtractJsonPath(mutation, "data.destroyStreamSession");

    assertThat(STUB_SERVICE.getLastDestroyProfileId()).isEqualTo(TestIdentityConstants.PROFILE_ID);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("booleanMutations")
  @DisplayName("Should return true when executing boolean mutation")
  void shouldReturnTrueWhenExecutingBooleanMutation(
      String name, String mutation, String resultPath) {
    Boolean result =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format(mutation, UUID.randomUUID()), resultPath);

    assertThat(result).isTrue();
  }

  static Stream<Arguments> booleanMutations() {
    return Stream.of(
        Arguments.of(
            "destroyStreamSession",
            "mutation { destroyStreamSession(sessionId: \"%s\") }",
            "data.destroyStreamSession"),
        Arguments.of(
            "reportStreamSessionTimeline",
            "mutation { reportStreamSessionTimeline(sessionId: \"%s\", positionSeconds: 300, state: PLAYING) }",
            "data.reportStreamSessionTimeline"),
        Arguments.of("markWatched", "mutation { markWatched(id: \"%s\") }", "data.markWatched"),
        Arguments.of(
            "markUnwatched", "mutation { markUnwatched(id: \"%s\") }", "data.markUnwatched"));
  }

  @Test
  @DisplayName("Should return error when destroy session ID is invalid")
  void shouldReturnErrorWhenDestroySessionIdIsInvalid() {
    var result =
        dgsQueryExecutor.execute(
            """
            mutation {
              destroyStreamSession(sessionId: "bad-id")
            }
            """);

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().getFirst().getMessage()).contains("Invalid ID format");
  }

  private static class StubStreamingService implements StreamingService {

    private StreamSession nextResult;
    private StreamingOptions lastReceivedOptions;
    private UUID lastCreateProfileId;
    private UUID lastDestroyProfileId;

    void reset() {
      nextResult = null;
      lastReceivedOptions = null;
      lastCreateProfileId = null;
      lastDestroyProfileId = null;
    }

    void setNextResult(StreamSession session) {
      this.nextResult = session;
    }

    StreamingOptions getLastReceivedOptions() {
      return lastReceivedOptions;
    }

    UUID getLastCreateProfileId() {
      return lastCreateProfileId;
    }

    UUID getLastDestroyProfileId() {
      return lastDestroyProfileId;
    }

    @Override
    public StreamSession createSession(CreateStreamSessionCommand command) {
      this.lastReceivedOptions = command.options();
      this.lastCreateProfileId = command.authority().profileId();
      return nextResult;
    }

    @Override
    public Optional<StreamSession> accessSession(PlaybackRequest request) {
      return Optional.ofNullable(nextResult);
    }

    @Override
    public void destroySession(UUID sessionId) {
      if (nextResult != null && nextResult.getSessionId().equals(sessionId)) {
        nextResult = null;
      }
    }

    @Override
    public void destroySession(UUID sessionId, UUID profileId) {
      this.lastDestroyProfileId = profileId;
    }

    @Override
    public Collection<StreamSession> getAllSessions() {
      return nextResult != null ? List.of(nextResult) : Collections.emptyList();
    }

    @Override
    public int getActiveSessionCount() {
      return nextResult != null ? 1 : 0;
    }

    @Override
    public void resumeSessionIfNeeded(UUID sessionId, String segmentName) {
      // no-op for test fake
    }
  }

  private static class NoopSessionProgressService extends SessionProgressService {

    NoopSessionProgressService() {
      super(null, null, null, null, null, null);
    }

    @Override
    public void reportStreamSessionTimeline(
        UUID profileId, UUID sessionId, int positionSeconds, PlaybackState state) {
      // no-op for test fake
    }
  }

  private static class NoopWatchStatusService extends WatchStatusService {

    NoopWatchStatusService() {
      super(null, null, null, null, null, null);
    }

    @Override
    public void markWatched(UUID profileId, UUID collectableId) {
      // no-op for test fake
    }

    @Override
    public void markUnwatched(UUID profileId, UUID collectableId) {
      // no-op for test fake
    }
  }

  @Test
  @DisplayName("Should return error when report timeline session ID is invalid")
  void shouldReturnErrorWhenReportTimelineSessionIdIsInvalid() {
    var result =
        dgsQueryExecutor.execute(
            """
            mutation {
              reportStreamSessionTimeline(sessionId: "bad-id", positionSeconds: 300, state: PLAYING)
            }
            """);

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().getFirst().getMessage()).contains("Invalid ID format");
  }

  @Test
  @DisplayName("Should return error when mark unwatched ID is invalid")
  void shouldReturnErrorWhenMarkUnwatchedIdIsInvalid() {
    var result =
        dgsQueryExecutor.execute(
            """
            mutation {
              markUnwatched(id: "bad-id")
            }
            """);

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().getFirst().getMessage()).contains("Invalid ID format");
  }
}
