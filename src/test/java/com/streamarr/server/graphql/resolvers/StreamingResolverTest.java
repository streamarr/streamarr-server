package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.fixtures.MediaEntityFixture.buildEpisode;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildMovie;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeason;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeries;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackAuthorityFor;
import static com.streamarr.server.support.TokenTestSupport.decode;
import static com.streamarr.server.support.TokenTestSupport.tokenProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.FakeAuthorizationDecider;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.services.auth.TokenClaims;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.CreateStreamSessionCommand;
import com.streamarr.server.services.streaming.CreateStreamSessionRejection;
import com.streamarr.server.services.streaming.PlaybackRequest;
import com.streamarr.server.services.streaming.PlaybackSessionRequest;
import com.streamarr.server.services.streaming.PlaybackSessionService;
import com.streamarr.server.services.streaming.StartPlaybackSessionCommand;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import com.streamarr.server.services.watchprogress.MarkUnwatchedRejection;
import com.streamarr.server.services.watchprogress.MarkWatchedRejection;
import com.streamarr.server.services.watchprogress.SessionProgressService;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import com.streamarr.server.support.security.TestIdentityConstants;
import com.streamarr.server.support.security.WithProfileContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@WithProfileContext
@SpringBootTest(
    classes = {
      StreamingResolver.class,
      StreamingResolverTest.TestConfig.class,
      SecurityContextAuthorizationService.class,
      FakeAuthorizationDecider.class
    })
@DisplayName("Streaming Resolver Tests")
class StreamingResolverTest {

  private static final String CREATED_PLAYBACK = "data.createPlaybackSession.playbackSession";
  private static final String REPORTED_PLAYBACK =
      "data.reportPlaybackFormatFailure.playbackSession";

  @Test
  @DisplayName("Should conceal playback status when another Profile owns the session")
  void shouldConcealPlaybackStatusWhenAnotherProfileOwnsTheSession() {
    var otherIdentity = identityFor(playbackAuthorityFor(UUID.randomUUID()));
    var streamId = UUID.randomUUID();
    STUB_SERVICE.setNextResult(buildSessionOwnedBy(streamId, otherIdentity.profileId()));
    var created =
        playbackSessions
            .start(
                StartPlaybackSessionCommand.builder()
                    .mediaFileId(UUID.randomUUID())
                    .identity(otherIdentity)
                    .options(StreamingOptions.builder().supportedCodecs(List.of("h264")).build())
                    .build())
            .fold(
                snapshot -> snapshot,
                rejections -> {
                  throw new AssertionError(rejections);
                });

    var foreign = playbackStatus(created.playbackSessionId().toString());
    var absent = playbackStatus(UUID.randomUUID().toString());

    assertThat(foreign.jsonString()).isEqualTo(absent.jsonString());
    assertThat(foreign.read("data.playbackSession", Object.class)).isNull();

    var foreignReport =
        reportFailure(created.playbackSessionId().toString(), streamId.toString(), "DECODE_ERROR");
    var absentReport =
        reportFailure(UUID.randomUUID().toString(), streamId.toString(), "DECODE_ERROR");
    assertThat(foreignReport.jsonString()).isEqualTo(absentReport.jsonString());

    var foreignClosure = destroyPlayback(created.playbackSessionId().toString());
    assertThat(foreignClosure.read("data.destroyPlaybackSession.playbackSessionId", String.class))
        .isEqualTo(created.playbackSessionId().toString());
    List<Object> closureErrors = foreignClosure.read("data.destroyPlaybackSession.userErrors");
    assertThat(closureErrors).isEmpty();
    assertThat(
            playbackSessions.access(
                PlaybackSessionRequest.builder()
                    .playbackSessionId(created.playbackSessionId())
                    .identity(otherIdentity)
                    .build()))
        .contains(created);
    assertThat(REPLACEMENT_TASKS).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"playbackSessionId", "streamSessionId"})
  @DisplayName("Should identify the malformed field when a format report has an invalid ID")
  void shouldIdentifyTheMalformedFieldWhenAFormatReportHasAnInvalidId(String field) {
    var playbackId = "playbackSessionId".equals(field) ? "invalid" : UUID.randomUUID().toString();
    var streamId = "streamSessionId".equals(field) ? "invalid" : UUID.randomUUID().toString();

    var reported = reportFailure(playbackId, streamId, "DECODE_ERROR");

    assertThat(reported.read(REPORTED_PLAYBACK, Object.class)).isNull();
    List<String> inputPath =
        reported.read("data.reportPlaybackFormatFailure.userErrors[0].inputPath");
    assertThat(inputPath).containsExactly(field);
    assertThat(
            reported.read(
                "data.reportPlaybackFormatFailure.userErrors[0].__typename", String.class))
        .isEqualTo("InvalidIdError");
  }

  @Test
  @DisplayName("Should return a typed ID error when closing a malformed playback owner")
  void shouldReturnATypedIdErrorWhenClosingAMalformedPlaybackOwner() {
    var result = destroyPlayback("invalid");

    assertThat(result.read("data.destroyPlaybackSession.playbackSessionId", Object.class)).isNull();
    assertThat(result.read("data.destroyPlaybackSession.userErrors[0].__typename", String.class))
        .isEqualTo("InvalidIdError");
    List<String> inputPath = result.read("data.destroyPlaybackSession.userErrors[0].inputPath");
    assertThat(inputPath).containsExactly("playbackSessionId");
  }

  @Test
  @DisplayName("Should acknowledge closure when a valid playback owner is already absent")
  void shouldAcknowledgeClosureWhenAValidPlaybackOwnerIsAlreadyAbsent() {
    var playbackId = UUID.randomUUID().toString();

    var result = destroyPlayback(playbackId);

    assertThat(result.read("data.destroyPlaybackSession.playbackSessionId", String.class))
        .isEqualTo(playbackId);
    List<Object> errors = result.read("data.destroyPlaybackSession.userErrors");
    assertThat(errors).isEmpty();
  }

  @Test
  @DisplayName("Should return a typed closed payload when playback closes during initial creation")
  void shouldReturnATypedClosedPayloadWhenPlaybackClosesDuringInitialCreation() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));
    STUB_SERVICE.duringNextCreate(
        command -> playbackSessions.closeOwningStream(command.streamSessionId()));

    var result = startPlayback(VideoQuality.AUTO);

    assertThat(result.read(CREATED_PLAYBACK, Object.class)).isNull();
    assertThat(result.read("data.createPlaybackSession.userErrors[0].__typename", String.class))
        .isEqualTo("PlaybackSessionClosedError");
    assertThat(STUB_SERVICE.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should hide infrastructure details when a replacement throws during startup")
  void shouldHideInfrastructureDetailsWhenAReplacementThrowsDuringStartup() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));
    var created = startPlayback(VideoQuality.AUTO);
    var playbackId = created.read(CREATED_PLAYBACK + ".id", String.class);
    reportFailure(
        playbackId,
        created.read(CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.id", String.class),
        "DECODE_ERROR");
    STUB_SERVICE.duringNextCreate(
        _ -> {
          throw new IllegalStateException("private infrastructure detail");
        });

    REPLACEMENT_TASKS.remove().run();

    var status = playbackStatus(playbackId);
    assertThat(
            status.read(
                "data.playbackSession.currentFormatAttempt.error['__typename']", String.class))
        .isEqualTo("PlaybackStartupFailedError");
    assertThat(status.read("data.playbackSession.currentFormatAttempt.error.message", String.class))
        .isEqualTo("The server could not start the next format attempt.");
    assertThat(status.jsonString()).doesNotContain("private infrastructure detail");
  }

  private DocumentContext destroyPlayback(String playbackId) {
    return dgsQueryExecutor.executeAndGetDocumentContext(
        """
        mutation {
          destroyPlaybackSession(input: {playbackSessionId: "%s"}) {
            playbackSessionId
            userErrors { __typename ... on InputMutationError { inputPath } }
          }
        }
        """
            .formatted(playbackId));
  }

  @Test
  @DisplayName("Should expose a pending replacement and its new URL when a format fails")
  void shouldExposeAPendingReplacementAndItsNewUrlWhenAFormatFails() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));
    var created = startPlayback(VideoQuality.AUTO);
    var playbackId = created.read(CREATED_PLAYBACK + ".id", String.class);
    var oldStreamId =
        created.read(CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.id", String.class);

    var reported = reportFailure(playbackId, oldStreamId, "DECODE_ERROR");

    assertThat(reported.read(REPORTED_PLAYBACK + ".id", String.class)).isEqualTo(playbackId);
    assertThat(reported.read(REPORTED_PLAYBACK + ".formatAttemptCount", Integer.class))
        .isEqualTo(2);
    assertThat(
            reported.read(REPORTED_PLAYBACK + ".currentFormatAttempt['__typename']", String.class))
        .isEqualTo("PendingPlaybackFormatAttempt");
    var replacementId =
        reported.read(REPORTED_PLAYBACK + ".currentFormatAttempt.streamSessionId", String.class);
    assertThat(replacementId).isNotEqualTo(oldStreamId);

    REPLACEMENT_TASKS.remove().run();
    var ready = playbackStatus(playbackId);

    assertThat(
            ready.read("data.playbackSession.currentFormatAttempt.streamSession.id", String.class))
        .isEqualTo(replacementId);
    assertThat(
            ready.read(
                "data.playbackSession.currentFormatAttempt.streamSession.streamUrl", String.class))
        .startsWith("/api/stream/" + replacementId + "/multivariant.m3u8?t=");
    assertThat(STUB_SERVICE.getAllSessions())
        .extracting(StreamSession::getSessionId)
        .containsExactly(UUID.fromString(replacementId));
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNSUPPORTED_FORMAT", "DECODE_ERROR"})
  @DisplayName("Should preserve the typed client failure when a fixed format is rejected")
  void shouldPreserveTheTypedClientFailureWhenAFixedFormatIsRejected(String reason) {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));
    var created = startPlayback(VideoQuality.HIGH_720P);

    var reported =
        reportFailure(
            created.read(CREATED_PLAYBACK + ".id", String.class),
            created.read(CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.id", String.class),
            reason);

    assertThat(reported.read(REPORTED_PLAYBACK + ".formatAttemptCount", Integer.class))
        .isEqualTo(1);
    assertThat(
            reported.read(
                REPORTED_PLAYBACK + ".currentFormatAttempt.error['__typename']", String.class))
        .isEqualTo("PlaybackFormatRejectedError");
    assertThat(
            reported.read(REPORTED_PLAYBACK + ".currentFormatAttempt.error.reason", String.class))
        .isEqualTo(reason);
  }

  @Test
  @DisplayName("Should expose the typed worker mismatch when Original cannot copy its source")
  void shouldExposeTheTypedWorkerMismatchWhenOriginalCannotCopyItsSource() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));
    var created = startPlayback(VideoQuality.ORIGINAL);
    var playbackId = created.read(CREATED_PLAYBACK + ".id", String.class);
    var streamId =
        created.read(CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.id", String.class);

    playbackSessions.onVideoCopyOutputMismatch(
        new VideoCopyOutputMismatchEvent(UUID.fromString(streamId), UUID.randomUUID(), "default"));

    assertThat(
            playbackStatus(playbackId)
                .read(
                    "data.playbackSession.currentFormatAttempt.error['__typename']", String.class))
        .isEqualTo("PlaybackOutputMismatchError");
  }

  @Test
  @DisplayName("Should expose the typed attempt limit when the replacement format also fails")
  void shouldExposeTheTypedAttemptLimitWhenTheReplacementFormatAlsoFails() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));
    var created = startPlayback(VideoQuality.AUTO);
    var playbackId = created.read(CREATED_PLAYBACK + ".id", String.class);
    reportFailure(
        playbackId,
        created.read(CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.id", String.class),
        "DECODE_ERROR");
    REPLACEMENT_TASKS.remove().run();
    var ready = playbackStatus(playbackId);

    var reported =
        reportFailure(
            playbackId,
            ready.read("data.playbackSession.currentFormatAttempt.streamSession.id", String.class),
            "UNSUPPORTED_FORMAT");

    assertThat(
            reported.read(
                REPORTED_PLAYBACK + ".currentFormatAttempt.error['__typename']", String.class))
        .isEqualTo("PlaybackFormatAttemptsExhaustedError");
    assertThat(reported.read(REPORTED_PLAYBACK + ".maximumFormatAttempts", Integer.class))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("Should expose a typed creation rejection when the replacement cannot start")
  void shouldExposeATypedCreationRejectionWhenTheReplacementCannotStart() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));
    var created = startPlayback(VideoQuality.AUTO);
    var playbackId = created.read(CREATED_PLAYBACK + ".id", String.class);
    reportFailure(
        playbackId,
        created.read(CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.id", String.class),
        "DECODE_ERROR");
    STUB_SERVICE.setRejection(new CreateStreamSessionRejection.ProbeNotReady());

    REPLACEMENT_TASKS.remove().run();

    assertThat(
            playbackStatus(playbackId)
                .read(
                    "data.playbackSession.currentFormatAttempt.error['__typename']", String.class))
        .isEqualTo("MediaFileProbeNotReadyError");
  }

  @Test
  @DisplayName("Should return closed and absent results when the playback owner no longer exists")
  void shouldReturnClosedAndAbsentResultsWhenThePlaybackOwnerNoLongerExists() {
    var playbackId = UUID.randomUUID().toString();

    var reported = reportFailure(playbackId, UUID.randomUUID().toString(), "DECODE_ERROR");

    assertThat(reported.read(REPORTED_PLAYBACK, Object.class)).isNull();
    assertThat(
            reported.read(
                "data.reportPlaybackFormatFailure.userErrors[0].__typename", String.class))
        .isEqualTo("PlaybackSessionClosedError");
    assertThat(playbackStatus(playbackId).read("data.playbackSession", Object.class)).isNull();
  }

  private DocumentContext startPlayback(VideoQuality quality) {
    return dgsQueryExecutor.executeAndGetDocumentContext(
        """
        mutation {
          createPlaybackSession(input: {mediaFileId: "%s", options: {quality: %s}}) {
            playbackSession {
              id
              currentFormatAttempt {
                ... on ReadyPlaybackFormatAttempt { streamSession { id } }
              }
            }
            userErrors { __typename }
          }
        }
        """
            .formatted(UUID.randomUUID(), quality));
  }

  private DocumentContext reportFailure(String playbackId, String streamId, String reason) {
    return dgsQueryExecutor.executeAndGetDocumentContext(
        """
        mutation {
          reportPlaybackFormatFailure(input: {playbackSessionId: "%s", streamSessionId: "%s", reason: %s}) {
            playbackSession {
              id formatAttemptCount maximumFormatAttempts
              currentFormatAttempt {
                __typename
                ... on PendingPlaybackFormatAttempt { streamSessionId }
                ... on ReadyPlaybackFormatAttempt { streamSession { id streamUrl } }
                ... on FailedPlaybackFormatAttempt {
                  streamSessionId
                  error { __typename ... on MutationError { message } ... on PlaybackFormatRejectedError { reason } }
                }
              }
            }
            userErrors { __typename ... on InputMutationError { inputPath } }
          }
        }
        """
            .formatted(playbackId, streamId, reason));
  }

  private DocumentContext playbackStatus(String playbackId) {
    return dgsQueryExecutor.executeAndGetDocumentContext(
        """
        query {
          playbackSession(id: "%s") {
            id formatAttemptCount maximumFormatAttempts
            currentFormatAttempt {
              __typename
              ... on ReadyPlaybackFormatAttempt { streamSession { id streamUrl } }
              ... on FailedPlaybackFormatAttempt { error { __typename ... on MutationError { message } } }
            }
          }
        }
        """
            .formatted(playbackId));
  }

  @Test
  @DisplayName("Should return a logical playback owner and ready stream when creation succeeds")
  void shouldReturnALogicalPlaybackOwnerAndReadyStreamWhenCreationSucceeds() {
    var session = buildSession(UUID.randomUUID());
    STUB_SERVICE.setNextResult(session);

    var result =
        dgsQueryExecutor.execute(
            """
        mutation {
          createPlaybackSession(input: {mediaFileId: "%s"}) {
            playbackSession {
              id formatAttemptCount maximumFormatAttempts
              currentFormatAttempt {
                __typename
                ... on ReadyPlaybackFormatAttempt { streamSession { id streamUrl } }
              }
            }
            userErrors { __typename }
          }
        }
        """
                .formatted(session.getMediaFileId()));

    assertThat(result.getErrors()).isEmpty();
    var context = JsonPath.parse(result.toSpecification());
    assertThat(context.read("data.createPlaybackSession.playbackSession.id", String.class))
        .isNotEqualTo(STUB_SERVICE.getAllSessions().iterator().next().getSessionId().toString());
    assertThat(
            context.read(
                "data.createPlaybackSession.playbackSession.formatAttemptCount", Integer.class))
        .isEqualTo(1);
    assertThat(
            context.read(
                "data.createPlaybackSession.playbackSession.currentFormatAttempt['__typename']",
                String.class))
        .isEqualTo("ReadyPlaybackFormatAttempt");
  }

  @Test
  @DisplayName("Should return a typed capacity error when playback has no transcode slots")
  void shouldReturnATypedCapacityErrorWhenPlaybackHasNoTranscodeSlots() {
    STUB_SERVICE.setRejection(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3));
    var context = requestSession(UUID.randomUUID().toString());

    assertThat(context.read("data.createPlaybackSession.playbackSession", Object.class)).isNull();
    assertThat(context.read("data.createPlaybackSession.userErrors[0].__typename", String.class))
        .isEqualTo("TranscodeCapacityUnavailableError");
  }

  @Test
  @DisplayName("Should return a typed file error when the media file no longer exists")
  void shouldReturnATypedFileErrorWhenTheMediaFileNoLongerExists() {
    var mediaFileId = UUID.randomUUID();
    STUB_SERVICE.setRejection(new CreateStreamSessionRejection.MediaFileNotFound(mediaFileId));
    var context = requestSession(mediaFileId.toString());

    assertThat(context.read("data.createPlaybackSession.playbackSession", Object.class)).isNull();
    assertThat(context.read("data.createPlaybackSession.userErrors[0].__typename", String.class))
        .isEqualTo("MediaFileNotFoundError");
    List<String> inputPath = context.read("data.createPlaybackSession.userErrors[0].inputPath");
    assertThat(inputPath).containsExactly("mediaFileId");
  }

  @Test
  @DisplayName("Should return an input error when the media file ID is malformed")
  void shouldReturnAnInputErrorWhenTheMediaFileIdIsMalformed() {
    var context = requestSession("not-a-uuid");

    assertThat(context.read("data.createPlaybackSession.playbackSession", Object.class)).isNull();
    assertThat(context.read("data.createPlaybackSession.userErrors[0].__typename", String.class))
        .isEqualTo("InvalidIdError");
    List<String> inputPath = context.read("data.createPlaybackSession.userErrors[0].inputPath");
    assertThat(inputPath).containsExactly("mediaFileId");
    assertThat(STUB_SERVICE.getLastCreateProfileId()).isNull();
  }

  @ParameterizedTest
  @MethodSource("terminalProbeErrors")
  @DisplayName("Should return the typed terminal failure when playback has a failed probe")
  void shouldReturnTheTypedTerminalFailureWhenPlaybackHasAFailedProbe(
      ProbeError reason, String errorType, String message) {
    STUB_SERVICE.setRejection(new CreateStreamSessionRejection.ProbeFailed(reason));
    var context = requestSession(UUID.randomUUID().toString());
    List<Map<String, String>> errors = context.read("data.createPlaybackSession.userErrors");

    assertThat(context.read("data.createPlaybackSession.playbackSession", Object.class)).isNull();
    assertThat(errors).containsExactly(Map.of("__typename", errorType, "message", message));
  }

  static Stream<Arguments> terminalProbeErrors() {
    return Stream.of(
        Arguments.of(
            ProbeError.INVALID_MEDIA,
            "InvalidMediaFileError",
            "This file cannot be read as supported media."),
        Arguments.of(
            ProbeError.NO_VIDEO_STREAM,
            "MediaFileHasNoVideoError",
            "This file has no video stream."),
        Arguments.of(
            ProbeError.RESULT_TOO_LARGE,
            "InvalidMediaFileError",
            "This file has more streams or metadata than the server can read."));
  }

  @Test
  @DisplayName("Should return a typed not-ready payload when playback has no probe outcome")
  void shouldReturnATypedNotReadyPayloadWhenPlaybackHasNoProbeOutcome() {
    STUB_SERVICE.setRejection(new CreateStreamSessionRejection.ProbeNotReady());
    var context = requestSession(UUID.randomUUID().toString());
    List<Map<String, String>> errors = context.read("data.createPlaybackSession.userErrors");

    assertThat(context.read("data.createPlaybackSession.playbackSession", Object.class)).isNull();
    assertThat(errors)
        .containsExactly(
            Map.of(
                "__typename", "MediaFileProbeNotReadyError",
                "message", "This file is being prepared for playback. Try again shortly."));
  }

  @Test
  @DisplayName(
      "Should return a typed frame-rate error when the video must be encoded without a frame rate")
  void shouldReturnATypedFrameRateErrorWhenTheVideoMustBeEncodedWithoutAFrameRate() {
    STUB_SERVICE.setRejection(new CreateStreamSessionRejection.FrameRateUnknown());
    var context = requestSession(UUID.randomUUID().toString());
    List<Map<String, String>> errors = context.read("data.createPlaybackSession.userErrors");

    assertThat(context.read("data.createPlaybackSession.playbackSession", Object.class)).isNull();
    assertThat(errors)
        .containsExactly(
            Map.of(
                "__typename", "MediaFileFrameRateUnknownError",
                "message",
                    "This file's video must be converted for playback, but its frame rate is unknown."));
  }

  @Test
  @DisplayName("Should return a typed format error when no video format is eligible")
  void shouldReturnATypedFormatErrorWhenNoVideoFormatIsEligible() {
    STUB_SERVICE.setRejection(new CreateStreamSessionRejection.NoEligibleVideoFormat());
    var context = requestSession(UUID.randomUUID().toString());
    List<Map<String, String>> errors = context.read("data.createPlaybackSession.userErrors");

    assertThat(context.read("data.createPlaybackSession.playbackSession", Object.class)).isNull();
    assertThat(errors)
        .containsExactly(
            Map.of(
                "__typename", "NoEligibleVideoFormatError",
                "message", "No supported video format is available for this playback request."));
  }

  @Test
  @DisplayName(
      "Should return a typed invalid file error when the probed media gives no media segment")
  void shouldReturnATypedInvalidFileErrorWhenTheProbedMediaGivesNoMediaSegment() {
    STUB_SERVICE.setRejection(new CreateStreamSessionRejection.NoMediaSegments());
    var context = requestSession(UUID.randomUUID().toString());
    List<Map<String, String>> errors = context.read("data.createPlaybackSession.userErrors");

    assertThat(context.read("data.createPlaybackSession.playbackSession", Object.class)).isNull();
    assertThat(errors)
        .containsExactly(
            Map.of(
                "__typename", "InvalidMediaFileError",
                "message", "This file has no playable duration."));
  }

  @Test
  @DisplayName("Should return a typed invalid file error when the displayed picture has no area")
  void shouldReturnATypedInvalidFileErrorWhenTheDisplayedPictureHasNoArea() {
    STUB_SERVICE.setRejection(new CreateStreamSessionRejection.InvalidPictureSize());
    var context = requestSession(UUID.randomUUID().toString());
    List<Map<String, String>> errors = context.read("data.createPlaybackSession.userErrors");

    assertThat(context.read("data.createPlaybackSession.playbackSession", Object.class)).isNull();
    assertThat(errors)
        .containsExactly(
            Map.of(
                "__typename", "InvalidMediaFileError",
                "message", "This file's video has no usable picture size."));
  }

  @Test
  @DisplayName("Should return a playable session payload when creation succeeds")
  void shouldReturnAPlayableSessionPayloadWhenCreationSucceeds() {
    var session = buildSession(UUID.randomUUID());
    STUB_SERVICE.setNextResult(session);
    var context =
        dgsQueryExecutor.executeAndGetDocumentContext(
            """
        mutation {
          createPlaybackSession(input: {
            mediaFileId: "%s"
            options: {quality: HIGH_720P, supportedCodecs: ["h264"],
              supportedAudioCodecs: ["aac", "ac3"], maxAudioChannels: 6}
          }) {
            playbackSession { id currentFormatAttempt { ... on ReadyPlaybackFormatAttempt { streamSession { id streamUrl transcodeMode } } } }
            userErrors { __typename }
          }
        }
        """
                .formatted(session.getMediaFileId()));

    assertThat(
            context.read(
                "data.createPlaybackSession.playbackSession.currentFormatAttempt.streamSession.id",
                String.class))
        .isEqualTo(STUB_SERVICE.getAllSessions().iterator().next().getSessionId().toString());
    assertThat(
            context.read(
                "data.createPlaybackSession.playbackSession.currentFormatAttempt.streamSession.transcodeMode",
                String.class))
        .isEqualTo("REMUX");
    List<Object> errors = context.read("data.createPlaybackSession.userErrors");
    assertThat(errors).isEmpty();
    var streamUrl =
        context.read(
            "data.createPlaybackSession.playbackSession.currentFormatAttempt.streamSession.streamUrl",
            String.class);
    assertThat(streamUrl)
        .startsWith(
            "/api/stream/"
                + STUB_SERVICE.getAllSessions().iterator().next().getSessionId()
                + "/multivariant.m3u8?t=");
    var decoded = decodeToken(streamUrl.substring(streamUrl.indexOf("?t=") + 3));
    assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()))
        .isEqualTo(session.getMediaProbe().duration().plus(streamingProperties.sessionRetention()));
    assertThat(STUB_SERVICE.getLastCreateProfileId()).isEqualTo(TestIdentityConstants.PROFILE_ID);
    var options = STUB_SERVICE.getLastReceivedOptions();
    assertThat(options.quality()).isEqualTo(VideoQuality.HIGH_720P);
    assertThat(options.supportedCodecs()).containsExactly("h264");
    assertThat(options.supportedAudioCodecs()).containsExactly("aac", "ac3");
    assertThat(options.maxAudioChannels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should destroy the accepted session when playback token issuance fails")
  void shouldDestroyTheAcceptedSessionWhenPlaybackTokenIssuanceFails() {
    var sessionId = UUID.randomUUID();
    STUB_SERVICE.setNextResult(buildSessionOwnedBy(sessionId, UUID.randomUUID()));

    var result =
        dgsQueryExecutor.execute(
            """
        mutation {
          createPlaybackSession(input: {mediaFileId: "%s"}) {
            playbackSession { id currentFormatAttempt { ... on ReadyPlaybackFormatAttempt { streamSession { id streamUrl } } } }
            userErrors { __typename }
          }
        }
        """
                .formatted(UUID.randomUUID()));

    assertThat(result.getErrors()).hasSize(1);
    assertThat(result.getErrors().getFirst().getMessage()).contains("Streaming session not found:");
    assertThat(result.toSpecification().toString()).doesNotContain("?t=");
    assertThat(STUB_SERVICE.getActiveSessionCount()).isZero();
  }

  @Test
  @DisplayName(
      "Should discard queued replacement when token issuance fails after initial child retires")
  void shouldDiscardQueuedReplacementWhenTokenIssuanceFailsAfterInitialChildRetires() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));
    beforeTokenEncoding =
        parameters -> {
          var childId =
              UUID.fromString(parameters.getClaims().getClaim(TokenClaims.STREAM_SESSION_ID));
          playbackSessions.onVideoCopyOutputMismatch(
              new VideoCopyOutputMismatchEvent(childId, UUID.randomUUID(), "default"));
          throw new JwtEncodingException("Token signing unavailable");
        };

    assertThatThrownBy(() -> startPlayback(VideoQuality.AUTO))
        .hasMessageContaining("Token signing unavailable");

    assertThat(REPLACEMENT_TASKS).hasSize(1);
    REPLACEMENT_TASKS.remove().run();
    assertThat(STUB_SERVICE.getAllSessions()).isEmpty();
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("watchStateItems")
  @DisplayName("Should return the marked item when the service accepts markWatched")
  void shouldReturnTheMarkedItemWhenTheServiceAcceptsMarkWatched(
      BaseCollectable<?> item, String typename) {
    STUB_WATCH_STATUS.setMarkWatchedOutcome(Outcome.accepted(item));

    var context = mark("markWatched", item.getId().toString());

    assertThat(context.read("data.markWatched.item['__typename']", String.class))
        .isEqualTo(typename);
    assertThat(context.read("data.markWatched.item.id", String.class))
        .isEqualTo(item.getId().toString());
    List<Object> errors = context.read("data.markWatched.userErrors");
    assertThat(errors).isEmpty();
    assertThat(STUB_WATCH_STATUS.getLastMarkedProfileId())
        .isEqualTo(TestIdentityConstants.PROFILE_ID);
    assertThat(STUB_WATCH_STATUS.getLastMarkedItemId()).isEqualTo(item.getId());
  }

  static Stream<Arguments> watchStateItems() {
    return Stream.of(
        Arguments.of(buildMovie("Movie"), "Movie"),
        Arguments.of(buildEpisode("Pilot", 1), "Episode"),
        Arguments.of(buildSeason("Season 1", 1), "Season"),
        Arguments.of(buildSeries("Series"), "Series"));
  }

  @Test
  @DisplayName("Should refuse to complete the payload when the marked item has no schema type")
  void shouldRefuseToCompleteThePayloadWhenTheMarkedItemHasNoSchemaType() {
    var item = new UnknownItem();
    item.setId(UUID.randomUUID());
    STUB_WATCH_STATUS.setMarkWatchedOutcome(Outcome.accepted(item));
    var mutation =
        """
        mutation {
          markWatched(input: {itemId: "%s"}) {
            item { __typename }
          }
        }
        """
            .formatted(item.getId());

    assertThatThrownBy(() -> dgsQueryExecutor.execute(mutation))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unknown watch state item type: UnknownItem");
  }

  @Test
  @DisplayName("Should return an item-not-found error when markWatched finds no item")
  void shouldReturnAnItemNotFoundErrorWhenMarkWatchedFindsNoItem() {
    var itemId = UUID.randomUUID();
    STUB_WATCH_STATUS.setMarkWatchedOutcome(
        Outcome.rejected(new MarkWatchedRejection.ItemNotFound(itemId)));

    var context = mark("markWatched", itemId.toString());

    assertThat(context.read("data.markWatched.item", Object.class)).isNull();
    List<Map<String, Object>> errors = context.read("data.markWatched.userErrors");
    assertThat(errors)
        .containsExactly(
            Map.of(
                "__typename", "ItemNotFoundError",
                "message", "This item no longer exists.",
                "inputPath", List.of("itemId")));
  }

  @Test
  @DisplayName("Should return the marked item when the service accepts markUnwatched")
  void shouldReturnTheMarkedItemWhenTheServiceAcceptsMarkUnwatched() {
    var movie = buildMovie("Movie");
    STUB_WATCH_STATUS.setMarkUnwatchedOutcome(Outcome.accepted(movie));

    var context = mark("markUnwatched", movie.getId().toString());

    assertThat(context.read("data.markUnwatched.item['__typename']", String.class))
        .isEqualTo("Movie");
    assertThat(context.read("data.markUnwatched.item.id", String.class))
        .isEqualTo(movie.getId().toString());
    List<Object> errors = context.read("data.markUnwatched.userErrors");
    assertThat(errors).isEmpty();
    assertThat(STUB_WATCH_STATUS.getLastMarkedProfileId())
        .isEqualTo(TestIdentityConstants.PROFILE_ID);
    assertThat(STUB_WATCH_STATUS.getLastMarkedItemId()).isEqualTo(movie.getId());
  }

  @Test
  @DisplayName("Should return an item-not-found error when markUnwatched finds no item")
  void shouldReturnAnItemNotFoundErrorWhenMarkUnwatchedFindsNoItem() {
    var itemId = UUID.randomUUID();
    STUB_WATCH_STATUS.setMarkUnwatchedOutcome(
        Outcome.rejected(new MarkUnwatchedRejection.ItemNotFound(itemId)));

    var context = mark("markUnwatched", itemId.toString());

    assertThat(context.read("data.markUnwatched.item", Object.class)).isNull();
    List<Map<String, Object>> errors = context.read("data.markUnwatched.userErrors");
    assertThat(errors)
        .containsExactly(
            Map.of(
                "__typename", "ItemNotFoundError",
                "message", "This item no longer exists.",
                "inputPath", List.of("itemId")));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"markWatched", "markUnwatched"})
  @DisplayName("Should return an invalid-ID error without marking when the item ID is malformed")
  void shouldReturnAnInvalidIdErrorWithoutMarkingWhenTheItemIdIsMalformed(String mutation) {
    var context = mark(mutation, "not-a-uuid");

    assertThat(context.read("data.%s.item".formatted(mutation), Object.class)).isNull();
    List<Map<String, Object>> errors = context.read("data.%s.userErrors".formatted(mutation));
    assertThat(errors)
        .containsExactly(
            Map.of(
                "__typename", "InvalidIdError",
                "message", "Enter a valid item ID.",
                "inputPath", List.of("itemId")));
    assertThat(STUB_WATCH_STATUS.getLastMarkedItemId()).isNull();
  }

  private DocumentContext mark(String mutation, String itemId) {
    return dgsQueryExecutor.executeAndGetDocumentContext(
        """
        mutation {
          %s(input: {itemId: "%s"}) {
            item {
              __typename
              ... on Movie { id }
              ... on Episode { id }
              ... on Season { id }
              ... on Series { id }
            }
            userErrors {
              __typename
              ... on MutationError { message }
              ... on InputMutationError { inputPath }
            }
          }
        }
        """
            .formatted(mutation, itemId));
  }

  private DocumentContext requestSession(String mediaFileId) {
    return dgsQueryExecutor.executeAndGetDocumentContext(
        """
        mutation {
          createPlaybackSession(input: {mediaFileId: "%s"}) {
            playbackSession { id }
            userErrors {
              __typename
              ... on MutationError { message }
              ... on InputMutationError { inputPath }
            }
          }
        }
        """
            .formatted(mediaFileId));
  }

  private static final StubStreamingService STUB_SERVICE = new StubStreamingService();
  private static final StubWatchStatusService STUB_WATCH_STATUS = new StubWatchStatusService();
  private static final ArrayDeque<Runnable> REPLACEMENT_TASKS = new ArrayDeque<>();
  private static Consumer<JwtEncoderParameters> beforeTokenEncoding = _ -> {};

  @MockitoBean private UserAccountRepository userAccountRepository;

  @TestConfiguration
  static class TestConfig {
    @Bean
    StreamingService streamingService() {
      return STUB_SERVICE;
    }

    @Bean
    PlaybackSessionService playbackSessionService() {
      return PlaybackSessionService.builder()
          .streamingService(STUB_SERVICE)
          .authorityGate(new FakePlaybackAuthorityGate())
          .executor(REPLACEMENT_TASKS::add)
          .maximumFormatAttempts(2)
          .sessionRetention(Duration.ofHours(1))
          .build();
    }

    @Bean
    StreamingProperties streamingProperties() {
      return StreamingProperties.builder()
          .maxConcurrentTranscodes(8)
          .targetSegmentDuration(Duration.ofSeconds(6))
          .sessionTimeout(Duration.ofSeconds(60))
          .sessionRetention(Duration.ofHours(1))
          .build();
    }

    /** The real issuer, so the resolver tests exercise its ownership refusal end to end. */
    @Bean
    PlaybackTokenIssuer playbackTokenIssuer() {
      var crypto = new TokenCryptoConfig();
      var encoder = crypto.jwtEncoder(crypto.tokenSigningKeys(tokenProperties()));
      return new PlaybackTokenIssuer(
          parameters -> {
            var action = beforeTokenEncoding;
            beforeTokenEncoding = _ -> {};
            action.accept(parameters);
            return encoder.encode(parameters);
          },
          tokenProperties(),
          Clock.systemUTC());
    }

    @Bean
    ProfileRepository profileRepository() {
      return new FakeProfileRepository();
    }

    @Bean
    SessionProgressService sessionProgressService() {
      return new NoopSessionProgressService();
    }

    @Bean
    WatchStatusService watchStatusService() {
      return STUB_WATCH_STATUS;
    }
  }

  @Autowired private DgsQueryExecutor dgsQueryExecutor;
  @Autowired private StreamingProperties streamingProperties;
  @Autowired private PlaybackSessionService playbackSessions;

  @BeforeEach
  void resetStubService() {
    playbackSessions.reapExpired(Instant.MAX);
    REPLACEMENT_TASKS.clear();
    beforeTokenEncoding = _ -> {};
    STUB_SERVICE.reset();
    STUB_WATCH_STATUS.reset();
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
                .keyframes(new KeyframeKnowledge.NotReported())
                .duration(Duration.ofMinutes(120))
                .framerate(OptionalDouble.of(24.0))
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
                .audioDecision(
                    AudioDecision.builder()
                        .mode(AudioMode.COPY)
                        .codec("aac")
                        .channels(2)
                        .bitrate(0)
                        .sourceCodecString(Optional.of("mp4a.40.2"))
                        .build())
                .subtitleDecision(SubtitleDecision.exclude())
                .videoSource(Optional.of(VideoPlaybackFactsFixture.sdrH264FactsBuilder().build()))
                .build())
        .options(StreamingOptions.builder().supportedCodecs(List.of("h264")).build())
        .createdAt(Instant.now())
        .build();
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
              createPlaybackSession(input: {mediaFileId: "%s"}) {
                playbackSession { currentFormatAttempt { ... on ReadyPlaybackFormatAttempt { streamSession { streamUrl } } } }
              }
            }
            """,
            UUID.randomUUID());

    String streamUrl =
        dgsQueryExecutor.executeAndExtractJsonPath(
            mutation,
            "data.createPlaybackSession.playbackSession.currentFormatAttempt.streamSession.streamUrl");

    assertThat(streamUrl)
        .startsWith(
            "/api/stream/"
                + STUB_SERVICE.getAllSessions().iterator().next().getSessionId()
                + "/multivariant.m3u8?t=");
    assertThat(streamUrl.substring(streamUrl.indexOf("?t=") + 3)).matches("[A-Za-z0-9._-]+");
  }

  private static Jwt decodeToken(String token) {
    return decode(token, tokenProperties());
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
              createPlaybackSession(input: {mediaFileId: "%s"}) {
                playbackSession { id }
              }
            }
            """,
            UUID.randomUUID());

    dgsQueryExecutor.executeAndExtractJsonPath(
        mutation, "data.createPlaybackSession.playbackSession.id");

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
  @DisplayName("Should close the owned stream when destroying logical playback")
  void shouldCloseTheOwnedStreamWhenDestroyingLogicalPlayback() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));
    String playbackId =
        requestSession(UUID.randomUUID().toString())
            .read("data.createPlaybackSession.playbackSession.id");

    var context =
        dgsQueryExecutor.executeAndGetDocumentContext(
            """
        mutation {
          destroyPlaybackSession(input: {playbackSessionId: "%s"}) {
            playbackSessionId userErrors { __typename }
          }
        }
        """
                .formatted(playbackId));

    assertThat(context.read("data.destroyPlaybackSession.playbackSessionId", String.class))
        .isEqualTo(playbackId);
    assertThat(STUB_SERVICE.getAllSessions()).isEmpty();
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
            "reportStreamSessionTimeline",
            "mutation { reportStreamSessionTimeline(sessionId: \"%s\", positionSeconds: 300, state: PLAYING) }",
            "data.reportStreamSessionTimeline"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidIdMutations")
  @DisplayName("Should return error when mutation ID is invalid")
  void shouldReturnErrorWhenMutationIdIsInvalid(String name, String mutation) {
    var result = dgsQueryExecutor.execute(mutation);

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().getFirst().getMessage()).contains("Invalid ID format");
  }

  static Stream<Arguments> invalidIdMutations() {
    return Stream.of(
        Arguments.of(
            "reportStreamSessionTimeline",
            """
            mutation {
              reportStreamSessionTimeline(sessionId: "bad-id", positionSeconds: 300, state: PLAYING)
            }
            """));
  }

  private static class StubStreamingService implements StreamingService {

    private StreamSession nextResult;
    private Optional<CreateStreamSessionRejection> rejection = Optional.empty();
    private StreamingOptions lastReceivedOptions;
    private UUID lastCreateProfileId;
    private final Map<UUID, StreamSession> sessions = new HashMap<>();
    private Consumer<CreateStreamSessionCommand> duringCreate = _ -> {};

    void reset() {
      nextResult = null;
      rejection = Optional.empty();
      lastReceivedOptions = null;
      lastCreateProfileId = null;
      sessions.clear();
      duringCreate = _ -> {};
    }

    void setNextResult(StreamSession session) {
      this.nextResult = session;
    }

    void setRejection(CreateStreamSessionRejection reason) {
      rejection = Optional.of(reason);
    }

    StreamingOptions getLastReceivedOptions() {
      return lastReceivedOptions;
    }

    UUID getLastCreateProfileId() {
      return lastCreateProfileId;
    }

    void duringNextCreate(Consumer<CreateStreamSessionCommand> action) {
      duringCreate = action;
    }

    @Override
    public Outcome<StreamSession, CreateStreamSessionRejection> createSession(
        CreateStreamSessionCommand command) {
      this.lastReceivedOptions = command.options();
      this.lastCreateProfileId = command.identity().profileId();
      if (rejection.isPresent()) {
        return Outcome.rejected(rejection.get());
      }

      var session =
          StreamSession.builder()
              .sessionId(command.streamSessionId())
              .mediaFileId(command.mediaFileId())
              .authority(nextResult.getAuthority())
              .sourcePath(nextResult.getSourcePath())
              .mediaProbe(nextResult.getMediaProbe())
              .transcodeDecision(nextResult.getTranscodeDecision())
              .options(command.options())
              .createdAt(Instant.now())
              .build();
      sessions.put(session.getSessionId(), session);
      var action = duringCreate;
      duringCreate = _ -> {};
      action.accept(command);
      return Outcome.accepted(session);
    }

    @Override
    public Optional<StreamSession> accessSession(PlaybackRequest request) {
      return Optional.ofNullable(sessions.get(request.streamSessionId()));
    }

    @Override
    public void destroySession(UUID sessionId) {
      sessions.remove(sessionId);
    }

    @Override
    public void destroySession(UUID sessionId, UUID profileId) {
      destroySession(sessionId);
    }

    @Override
    public Collection<StreamSession> getAllSessions() {
      return List.copyOf(sessions.values());
    }

    @Override
    public int getActiveSessionCount() {
      return sessions.size();
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

  private static class UnknownItem extends BaseCollectable<UnknownItem> {}

  private static class StubWatchStatusService extends WatchStatusService {

    private Outcome<BaseCollectable<?>, MarkWatchedRejection> markWatchedOutcome;
    private Outcome<BaseCollectable<?>, MarkUnwatchedRejection> markUnwatchedOutcome;
    private UUID lastMarkedProfileId;
    private UUID lastMarkedItemId;

    StubWatchStatusService() {
      super(null, null, null, null, null, null, null, null, null);
    }

    void reset() {
      markWatchedOutcome = null;
      markUnwatchedOutcome = null;
      lastMarkedProfileId = null;
      lastMarkedItemId = null;
    }

    void setMarkWatchedOutcome(Outcome<BaseCollectable<?>, MarkWatchedRejection> outcome) {
      markWatchedOutcome = outcome;
    }

    void setMarkUnwatchedOutcome(Outcome<BaseCollectable<?>, MarkUnwatchedRejection> outcome) {
      markUnwatchedOutcome = outcome;
    }

    UUID getLastMarkedProfileId() {
      return lastMarkedProfileId;
    }

    UUID getLastMarkedItemId() {
      return lastMarkedItemId;
    }

    @Override
    public Outcome<BaseCollectable<?>, MarkWatchedRejection> markWatched(
        UUID profileId, UUID itemId) {
      lastMarkedProfileId = profileId;
      lastMarkedItemId = itemId;
      return markWatchedOutcome;
    }

    @Override
    public Outcome<BaseCollectable<?>, MarkUnwatchedRejection> markUnwatched(
        UUID profileId, UUID itemId) {
      lastMarkedProfileId = profileId;
      lastMarkedItemId = itemId;
      return markUnwatchedOutcome;
    }
  }
}
