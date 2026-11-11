package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.audioEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildEpisode;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildMovie;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeason;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeries;
import static com.streamarr.server.fixtures.ProbeFixture.audioStreamBuilder;
import static com.streamarr.server.fixtures.ServerSettingsFixture.defaultServerSettings;
import static com.streamarr.server.fixtures.StreamSessionFixture.fullTranscodeDecision;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackAuthorityFor;
import static com.streamarr.server.fixtures.StreamSessionFixture.remuxDecisionBuilder;
import static com.streamarr.server.support.TokenTestSupport.decode;
import static com.streamarr.server.support.TokenTestSupport.tokenProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.config.security.TokenCryptoConfig;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.CapabilityEntryDelivery;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionOutput;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DolbyVisionProfileCapability;
import com.streamarr.server.domain.streaming.DynamicRangeChoice;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.ShortCopy;
import com.streamarr.server.domain.streaming.SourceContainer;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeFailure;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.domain.streaming.VideoCapabilityEntryRefusal;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecProfileCapability;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoSampleEntry;
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
import com.streamarr.server.services.serversettings.ServerSettingsService;
import com.streamarr.server.services.streaming.CreateStreamSessionCommand;
import com.streamarr.server.services.streaming.CreateStreamSessionRejection;
import com.streamarr.server.services.streaming.PlaybackRequest;
import com.streamarr.server.services.streaming.PlaybackSessionRequest;
import com.streamarr.server.services.streaming.PlaybackSessionService;
import com.streamarr.server.services.streaming.StartPlaybackSessionCommand;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.events.TerminalTranscodeFailureEvent;
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
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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

  // One H.264 entry and one stereo AAC entry, both for HLS, as a client declares them.
  private static final String CAPABILITIES =
      """
      {video: [{codecFamily: H264, codecProfiles: [{profile: H264_HIGH, maxLevel: 41}], \
      maxBitDepth: 8, maxWidth: 1920, maxHeight: 1080, maxFrameRate: 60, compatibleBases: [SDR], \
      dolbyVisionProfiles: [], hdr10Plus: false, hls: true}], \
      audio: [{codec: AAC, maxChannels: 2, hls: true}], selectsAudioTrackInDirectPlay: false}""";
  private static final String REPORTED_PLAYBACK =
      "data.reportPlaybackFormatFailure.playbackSession";
  private static final String SWITCHED_PLAYBACK = "data.switchPlaybackAudioTrack.playbackSession";

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
                    .options(StreamingOptions.builder().build())
                    .capabilityDeclaration(defaultDeclarationBuilder().build())
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
        .isEqualTo("The server couldn't start this video. Try again in a few minutes.");
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
  @DisplayName("Should preserve the typed client failure when a fixed quality's encode is rejected")
  void shouldPreserveTheTypedClientFailureWhenAFixedQualitysEncodeIsRejected(String reason) {
    STUB_SERVICE.setNextResult(
        buildSessionDeciding(UUID.randomUUID(), fullTranscodeDecision("h264")));
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
    assertThat(
            reported.read(REPORTED_PLAYBACK + ".currentFormatAttempt.error.message", String.class))
        .isEqualTo(
            "This device can't play the quality you chose. Set Quality to Auto and try again.");
  }

  // ADR 0052: the server names what the viewer asked for, what plays and why they differ.
  @Test
  @DisplayName("Should expose the change to Auto when Original starts with an encode")
  void shouldExposeTheChangeToAutoWhenOriginalStartsWithAnEncode() {
    STUB_SERVICE.setNextResult(
        buildSessionDeciding(UUID.randomUUID(), fullTranscodeDecision("h264")));

    var created = startPlaybackReadingChoices(VideoQuality.ORIGINAL);

    assertThat(created.read(CREATED_PLAYBACK + ".requestedChoice", String.class))
        .isEqualTo("ORIGINAL");
    assertThat(created.read(CREATED_PLAYBACK + ".playbackChoice", String.class)).isEqualTo("AUTO");
    assertThat(created.read(CREATED_PLAYBACK + ".formatChange", String.class))
        .isEqualTo("STARTED_WITH_ENCODE");
    assertThat(created.read(CREATED_PLAYBACK + ".offeredChoices", List.class))
        .containsExactly(
            "ORIGINAL", "AUTO", "FULL_HD_1080P", "HIGH_720P", "MEDIUM_480P", "LOW_360P");
    assertThat(
            created.read(
                CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.videoRange", String.class))
        .isEqualTo("SDR");
    assertThat(
            created.read(
                CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.mediaEndSeconds",
                Object.class))
        .isNull();
  }

  @Test
  @DisplayName("Should expose the change to Auto when Original's copy fails")
  void shouldExposeTheChangeToAutoWhenOriginalsCopyFails() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));
    var created = startPlaybackReadingChoices(VideoQuality.ORIGINAL);

    var reported =
        reportFailure(
            created.read(CREATED_PLAYBACK + ".id", String.class),
            created.read(CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.id", String.class),
            "DECODE_ERROR");

    assertThat(created.read(CREATED_PLAYBACK + ".playbackChoice", String.class))
        .isEqualTo("ORIGINAL");
    assertThat(created.read(CREATED_PLAYBACK + ".formatChange", Object.class)).isNull();
    assertThat(reported.read(REPORTED_PLAYBACK + ".currentFormatAttempt.__typename", String.class))
        .isEqualTo("PendingPlaybackFormatAttempt");
    assertThat(reported.read(REPORTED_PLAYBACK + ".requestedChoice", String.class))
        .isEqualTo("ORIGINAL");
    assertThat(reported.read(REPORTED_PLAYBACK + ".playbackChoice", String.class))
        .isEqualTo("AUTO");
    assertThat(reported.read(REPORTED_PLAYBACK + ".formatChange", String.class))
        .isEqualTo("COPY_FAILED");
  }

  // T13: a direct-play stream session's range is the source's compatible base, and Dolby Vision
  // without one is PQ, as a copy's is.
  @ParameterizedTest(name = "{0}")
  @MethodSource("sourceRanges")
  @DisplayName("Should expose the source URL, container and range when the session direct-plays")
  void shouldExposeTheSourceUrlContainerAndRangeWhenTheSessionDirectPlays(
      String scenario, VideoDynamicRange range, String expectedRange) {
    var session = buildSession(UUID.randomUUID());
    var probe = session.getMediaProbe();
    STUB_SERVICE.setNextResult(
        sessionBuilderLike(session)
            .transcodeDecision(null)
            .delivery(StreamDelivery.DIRECT_PLAY)
            .sourceContainer(SourceContainer.MATROSKA)
            .mediaProbe(
                MediaProbe.builder()
                    .keyframes(probe.keyframes())
                    .duration(probe.duration())
                    .width(probe.width())
                    .height(probe.height())
                    .videoCodec(probe.videoCodec())
                    .videoPlaybackFacts(
                        Optional.of(
                            VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                                .dynamicRange(range)
                                .build()))
                    .build())
            .build());

    var context =
        dgsQueryExecutor.executeAndGetDocumentContext(
            """
            mutation {
              createPlaybackSession(
                input: {mediaFileId: "%s", options: {quality: ORIGINAL}, capabilities: %s}
              ) {
                playbackSession {
                  currentFormatAttempt {
                    ... on ReadyPlaybackFormatAttempt {
                      streamSession {
                        __typename id streamUrl
                        ... on DirectPlayStreamSession { sourceContainer videoRange }
                      }
                    }
                  }
                }
              }
            }
            """
                .formatted(UUID.randomUUID(), CAPABILITIES));

    var streamSession = CREATED_PLAYBACK + ".currentFormatAttempt.streamSession";
    assertThat(context.read(streamSession + ".__typename", String.class))
        .isEqualTo("DirectPlayStreamSession");
    assertThat(context.read(streamSession + ".sourceContainer", String.class))
        .isEqualTo("MATROSKA");
    assertThat(context.read(streamSession + ".videoRange", String.class)).isEqualTo(expectedRange);
    var streamUrl = context.read(streamSession + ".streamUrl", String.class);
    var sessionId = context.read(streamSession + ".id", String.class);
    assertThat(streamUrl).startsWith("/api/stream/" + sessionId + "/source.mkv?t=");
    var decoded = decodeToken(streamUrl.substring(streamUrl.indexOf("?t=") + 3));
    assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()))
        .isEqualTo(probe.duration().plus(Duration.ofHours(1)));
  }

  // ADR 0045: a player that selects audio tracks selects the track that the stream decision names.
  @ParameterizedTest(name = "{0}")
  @MethodSource("directPlayAudioTrackPositions")
  @DisplayName(
      "Should expose the audio track that the decision names when the session direct-plays")
  void shouldExposeTheAudioTrackThatTheDecisionNamesWhenTheSessionDirectPlays(
      String scenario, OptionalInt position, Integer expected) {
    STUB_SERVICE.setNextResult(
        sessionBuilderLike(buildSession(UUID.randomUUID()))
            .transcodeDecision(null)
            .delivery(StreamDelivery.DIRECT_PLAY)
            .sourceContainer(SourceContainer.MATROSKA)
            .directPlayAudioTrackPosition(position)
            .build());

    var context =
        dgsQueryExecutor.executeAndGetDocumentContext(
            """
            mutation {
              createPlaybackSession(
                input: {mediaFileId: "%s", options: {quality: ORIGINAL}, capabilities: %s}
              ) {
                playbackSession {
                  currentFormatAttempt {
                    ... on ReadyPlaybackFormatAttempt {
                      streamSession {
                        __typename
                        ... on DirectPlayStreamSession { audioTrackPosition }
                      }
                    }
                  }
                }
              }
            }
            """
                .formatted(UUID.randomUUID(), CAPABILITIES));

    var streamSession = CREATED_PLAYBACK + ".currentFormatAttempt.streamSession";
    assertThat(context.read(streamSession + ".__typename", String.class))
        .isEqualTo("DirectPlayStreamSession");
    assertThat(context.read(streamSession + ".audioTrackPosition", Integer.class))
        .isEqualTo(expected);
  }

  // ADR 0045: a player that cannot list a file's audio tracks offers the ones the server lists, and
  // names the chosen one by its position in stream order. The session's probe puts the session's
  // track first, as a stream session for the third track holds it.
  @Test
  @DisplayName("Should list the file's audio tracks in stream order when the session direct-plays")
  void shouldListTheFilesAudioTracksInStreamOrderWhenTheSessionDirectPlays() {
    var session = buildDirectPlaySession(UUID.randomUUID());
    var english =
        audioStreamBuilder("aac")
            .index(1)
            .language(Optional.of("eng"))
            .title(Optional.of("Stereo"))
            .isDefault(true)
            .build();
    var french = audioStreamBuilder("ac3").index(2).language(Optional.of("fre")).build();
    var untagged = audioStreamBuilder("aac").index(4).build();
    STUB_SERVICE.setNextResult(
        sessionBuilderLike(session)
            .delivery(StreamDelivery.DIRECT_PLAY)
            .sourceContainer(SourceContainer.MATROSKA)
            .mediaProbe(
                session.getMediaProbe().toBuilder()
                    .streams(List.of(untagged, english, french))
                    .build())
            .directPlayAudioTrackPosition(OptionalInt.of(2))
            .build());

    var context =
        dgsQueryExecutor.executeAndGetDocumentContext(
            """
            mutation {
              createPlaybackSession(
                input: {mediaFileId: "%s", options: {quality: ORIGINAL}, capabilities: %s}
              ) {
                playbackSession {
                  currentFormatAttempt {
                    ... on ReadyPlaybackFormatAttempt {
                      streamSession {
                        ... on DirectPlayStreamSession {
                          audioTracks { position language title }
                        }
                      }
                    }
                  }
                }
              }
            }
            """
                .formatted(UUID.randomUUID(), CAPABILITIES));

    List<Map<String, Object>> tracks =
        context.read(CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.audioTracks");
    assertThat(tracks)
        .containsExactly(
            audioTrack(0, "eng", "Stereo"), audioTrack(1, "fre", null), audioTrack(2, null, null));
  }

  private static Map<String, Object> audioTrack(int position, String language, String title) {
    var track = new HashMap<String, Object>();
    track.put("position", position);
    track.put("language", language);
    track.put("title", title);
    return track;
  }

  static Stream<Arguments> directPlayAudioTrackPositions() {
    return Stream.of(
        Arguments.of("the second audio track", OptionalInt.of(1), 1),
        Arguments.of("a file without audio", OptionalInt.empty(), null));
  }

  static Stream<Arguments> sourceRanges() {
    return Stream.of(
        Arguments.of(
            "SDR", VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.SDR), "SDR"),
        Arguments.of(
            "PQ", VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ), "PQ"),
        Arguments.of(
            "HLG", VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.HLG), "HLG"),
        Arguments.of(
            "Dolby Vision profile 5", VideoPlaybackFactsFixture.dolbyVisionProfile5(), "PQ"));
  }

  // ADR 0045: the HLS format attempt after a failed direct play keeps Original while it is pending,
  // and its encode plays in Auto with a notice.
  @Test
  @DisplayName("Should expose the change to Auto when direct play fails and no copy is eligible")
  void shouldExposeTheChangeToAutoWhenDirectPlayFailsAndNoCopyIsEligible() {
    STUB_SERVICE.setNextResult(buildDirectPlaySession(UUID.randomUUID()));
    var created = startPlaybackReadingChoices(VideoQuality.ORIGINAL);
    var playbackId = created.read(CREATED_PLAYBACK + ".id", String.class);

    var reported =
        reportFailure(
            playbackId,
            created.read(CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.id", String.class),
            "UNSUPPORTED_FORMAT");
    STUB_SERVICE.setNextResult(
        buildSessionDeciding(UUID.randomUUID(), fullTranscodeDecision("h264")));
    REPLACEMENT_TASKS.remove().run();

    assertThat(reported.read(REPORTED_PLAYBACK + ".currentFormatAttempt.__typename", String.class))
        .isEqualTo("PendingPlaybackFormatAttempt");
    assertThat(reported.read(REPORTED_PLAYBACK + ".playbackChoice", String.class))
        .isEqualTo("ORIGINAL");
    assertThat(reported.read(REPORTED_PLAYBACK + ".formatChange", Object.class)).isNull();
    var ready = playbackStatus(playbackId);
    assertThat(ready.read("data.playbackSession.formatAttemptCount", Integer.class)).isEqualTo(2);
    assertThat(ready.read("data.playbackSession.playbackChoice", String.class)).isEqualTo("AUTO");
    assertThat(ready.read("data.playbackSession.formatChange", String.class))
        .isEqualTo("DIRECT_PLAY_FAILED");
  }

  // ADR 0052: Original that reaches an encode after an audio switch plays it in Auto, with a
  // notice that names the switch.
  @Test
  @DisplayName(
      "Should expose the change to Auto when the viewer switches audio tracks and no copy is"
          + " eligible")
  void shouldExposeTheChangeToAutoWhenTheViewerSwitchesAudioTracksAndNoCopyIsEligible() {
    STUB_SERVICE.setNextResult(buildDirectPlaySessionOfTwoAudioTracks());
    var created = startPlaybackReadingChoices(VideoQuality.ORIGINAL);
    var playbackId = created.read(CREATED_PLAYBACK + ".id", String.class);
    var streamId =
        created.read(CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.id", String.class);
    STUB_SERVICE.setNextResult(
        buildSessionDeciding(UUID.randomUUID(), fullTranscodeDecision("h264")));

    var switched = switchAudioTrack(playbackId, streamId, 1);

    assertThat(switched.read(SWITCHED_PLAYBACK + ".currentFormatAttempt.__typename", String.class))
        .isEqualTo("ReadyPlaybackFormatAttempt");
    assertThat(switched.read(SWITCHED_PLAYBACK + ".playbackChoice", String.class))
        .isEqualTo("AUTO");
    assertThat(switched.read(SWITCHED_PLAYBACK + ".formatChange", String.class))
        .isEqualTo("AUDIO_TRACK_SWITCHED");
  }

  // The switch's stream session starts at the playback session's start position until playback
  // moves from it, so a re-probe that shortens the file refuses that position too.
  @ParameterizedTest(name = "{1}")
  @MethodSource("audioSwitchRejections")
  @DisplayName(
      "Should return the typed creation error when the stream session for an audio switch is"
          + " refused")
  void shouldReturnTheTypedCreationErrorWhenTheStreamSessionForAnAudioSwitchIsRefused(
      CreateStreamSessionRejection rejection, String errorType) {
    STUB_SERVICE.setNextResult(buildDirectPlaySessionOfTwoAudioTracks());
    var created = startPlaybackReadingChoices(VideoQuality.ORIGINAL);
    STUB_SERVICE.setRejection(rejection);

    var switched =
        switchAudioTrack(
            created.read(CREATED_PLAYBACK + ".id", String.class),
            created.read(CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.id", String.class),
            1);

    assertThat(switched.read(SWITCHED_PLAYBACK, Object.class)).isNull();
    assertThat(
            switched.read("data.switchPlaybackAudioTrack.userErrors[0].__typename", String.class))
        .isEqualTo(errorType);
  }

  static Stream<Arguments> audioSwitchRejections() {
    return Stream.of(
        Arguments.of(
            new CreateStreamSessionRejection.TranscodeCapacityUnavailable(8),
            "TranscodeCapacityUnavailableError"),
        Arguments.of(
            new CreateStreamSessionRejection.StartPositionOutOfRange(),
            "StartPositionOutOfRangeError"));
  }

  @Test
  @DisplayName("Should return a typed input error when the file has no audio track at the position")
  void shouldReturnATypedInputErrorWhenTheFileHasNoAudioTrackAtThePosition() {
    STUB_SERVICE.setNextResult(buildDirectPlaySessionOfTwoAudioTracks());
    var created = startPlaybackReadingChoices(VideoQuality.ORIGINAL);

    var switched =
        switchAudioTrack(
            created.read(CREATED_PLAYBACK + ".id", String.class),
            created.read(CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.id", String.class),
            2);

    assertThat(switched.read(SWITCHED_PLAYBACK, Object.class)).isNull();
    assertThat(
            switched.read("data.switchPlaybackAudioTrack.userErrors[0].__typename", String.class))
        .isEqualTo("AudioTrackNotInFileError");
    List<String> inputPath = switched.read("data.switchPlaybackAudioTrack.userErrors[0].inputPath");
    assertThat(inputPath).containsExactly("audioTrackPosition");
  }

  @ParameterizedTest
  @ValueSource(strings = {"playbackSessionId", "streamSessionId"})
  @DisplayName("Should identify the malformed field when an audio track switch has an invalid ID")
  void shouldIdentifyTheMalformedFieldWhenAnAudioTrackSwitchHasAnInvalidId(String field) {
    var playbackId = "playbackSessionId".equals(field) ? "invalid" : UUID.randomUUID().toString();
    var streamId = "streamSessionId".equals(field) ? "invalid" : UUID.randomUUID().toString();

    var switched = switchAudioTrack(playbackId, streamId, 1);

    assertThat(switched.read(SWITCHED_PLAYBACK, Object.class)).isNull();
    assertThat(
            switched.read("data.switchPlaybackAudioTrack.userErrors[0].__typename", String.class))
        .isEqualTo("InvalidIdError");
    List<String> inputPath = switched.read("data.switchPlaybackAudioTrack.userErrors[0].inputPath");
    assertThat(inputPath).containsExactly(field);
  }

  @Test
  @DisplayName("Should return a closed error when the viewer switches audio tracks after playback")
  void shouldReturnAClosedErrorWhenTheViewerSwitchesAudioTracksAfterPlayback() {
    var switched = switchAudioTrack(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1);

    assertThat(switched.read(SWITCHED_PLAYBACK, Object.class)).isNull();
    assertThat(
            switched.read("data.switchPlaybackAudioTrack.userErrors[0].__typename", String.class))
        .isEqualTo("PlaybackSessionClosedError");
  }

  private StreamSession buildDirectPlaySessionOfTwoAudioTracks() {
    var session = buildDirectPlaySession(UUID.randomUUID());
    var probe =
        session.getMediaProbe().toBuilder()
            .streams(
                List.of(
                    audioStreamBuilder("aac").index(1).build(),
                    audioStreamBuilder("aac").index(2).build()))
            .build();
    return sessionBuilderLike(session)
        .delivery(StreamDelivery.DIRECT_PLAY)
        .sourceContainer(SourceContainer.MATROSKA)
        .mediaProbe(probe)
        .directPlayAudioTrackPosition(OptionalInt.of(0))
        .build();
  }

  private DocumentContext switchAudioTrack(String playbackId, String streamId, int position) {
    return dgsQueryExecutor.executeAndGetDocumentContext(
        """
        mutation {
          switchPlaybackAudioTrack(
            input: {playbackSessionId: "%s", streamSessionId: "%s", audioTrackPosition: %d}
          ) {
            playbackSession {
              id playbackChoice formatChange
              currentFormatAttempt { __typename }
            }
            userErrors { __typename ... on InputMutationError { inputPath } }
          }
        }
        """
            .formatted(playbackId, streamId, position));
  }

  // ADR 0052: the client ends playback at the real end of a copy that ran short after every source
  // stream ended.
  @ParameterizedTest
  @CsvSource({"true, 1234.5", "false, "})
  @DisplayName("Should expose the media end in seconds only when the copy ends normally")
  void shouldExposeTheMediaEndInSecondsOnlyWhenTheCopyEndsNormally(
      boolean everySourceStreamEnded, Double expected) {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));
    STUB_SERVICE.duringNextCreate(
        command ->
            STUB_SERVICE
                .sessions
                .get(command.streamSessionId())
                .tryMarkShortCopy(
                    new ShortCopy(205, Duration.ofMillis(1_234_500), everySourceStreamEnded)));

    var created = startPlaybackReadingChoices(VideoQuality.ORIGINAL);

    assertThat(
            created.read(
                CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.mediaEndSeconds",
                Double.class))
        .isEqualTo(expected);
  }

  // Every encode is SDR until the server encodes HDR (#397); a copy keeps the source's base.
  @ParameterizedTest
  @CsvSource({"SDR, SDR", "PQ, PQ", "HLG, HLG"})
  @DisplayName("Should expose the video range of the stream session that plays")
  void shouldExposeTheVideoRangeOfTheStreamSessionThatPlays(CompatibleBase base, String expected) {
    var copy =
        remuxDecisionBuilder()
            .videoSource(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(base))
                        .build()))
            .build();
    STUB_SERVICE.setNextResult(buildSessionDeciding(UUID.randomUUID(), copy));

    var created = startPlaybackReadingChoices(VideoQuality.AUTO);

    assertThat(
            created.read(
                CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.videoRange", String.class))
        .isEqualTo(expected);
    assertThat(created.read(CREATED_PLAYBACK + ".formatChange", Object.class)).isNull();
  }

  // ADR 0013: a stream session request carries an optional HDR choice, HDR or SDR.
  @ParameterizedTest
  @EnumSource(DynamicRangeChoice.class)
  @DisplayName(
      "Should pass the viewer's HDR choice to the playback service when the options name one")
  void shouldPassTheViewersHdrChoiceToThePlaybackServiceWhenTheOptionsNameOne(
      DynamicRangeChoice choice) {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));

    dgsQueryExecutor.executeAndGetDocumentContext(
        """
        mutation {
          createPlaybackSession(
            input: {
              mediaFileId: "%s", options: {quality: HIGH_720P, dynamicRange: %s}, capabilities: %s
            }
          ) {
            playbackSession { id }
            userErrors { __typename }
          }
        }
        """
            .formatted(UUID.randomUUID(), choice, CAPABILITIES));

    assertThat(STUB_SERVICE.getLastReceivedOptions().dynamicRange()).isEqualTo(choice);
  }

  @Test
  @DisplayName("Should pass no HDR choice to the playback service when the options name none")
  void shouldPassNoHdrChoiceToThePlaybackServiceWhenTheOptionsNameNone() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));

    startPlaybackReadingChoices(VideoQuality.HIGH_720P);

    assertThat(STUB_SERVICE.getLastReceivedOptions().dynamicRange()).isNull();
  }

  private DocumentContext startPlaybackReadingChoices(VideoQuality quality) {
    return dgsQueryExecutor.executeAndGetDocumentContext(
        """
        mutation {
          createPlaybackSession(
            input: {mediaFileId: "%s", options: {quality: %s}, capabilities: %s}
          ) {
            playbackSession {
              id requestedChoice playbackChoice formatChange offeredChoices
              currentFormatAttempt {
                ... on ReadyPlaybackFormatAttempt {
                  streamSession { id ... on HlsStreamSession { videoRange mediaEndSeconds } }
                }
              }
            }
            userErrors { __typename }
          }
        }
        """
            .formatted(UUID.randomUUID(), quality, CAPABILITIES));
  }

  // ADR 0052: a fixed quality's failed copy plays the fixed quality's encode, which is SDR until
  // the server encodes HDR (#397), even when the copy kept a PQ base.
  @Test
  @DisplayName("Should report SDR when the fixed quality's encode replaces a copy")
  void shouldReportSdrWhenTheFixedQualitysEncodeReplacesACopy() {
    var pqCopy =
        remuxDecisionBuilder()
            .videoSource(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .dynamicRange(
                            VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ))
                        .build()))
            .build();
    STUB_SERVICE.setNextResult(buildSessionDeciding(UUID.randomUUID(), pqCopy));
    var created = startPlaybackReadingChoices(VideoQuality.HIGH_720P);
    var playbackId = created.read(CREATED_PLAYBACK + ".id", String.class);

    var reported =
        reportFailure(
            playbackId,
            created.read(CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.id", String.class),
            "UNSUPPORTED_FORMAT");
    STUB_SERVICE.setNextResult(
        buildSessionDeciding(UUID.randomUUID(), fullTranscodeDecision("h264")));
    REPLACEMENT_TASKS.remove().run();

    assertThat(
            created.read(
                CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.videoRange", String.class))
        .isEqualTo("PQ");
    assertThat(reported.read(REPORTED_PLAYBACK + ".currentFormatAttempt.__typename", String.class))
        .isEqualTo("PendingPlaybackFormatAttempt");
    assertThat(reported.read(REPORTED_PLAYBACK + ".playbackChoice", String.class))
        .isEqualTo("HIGH_720P");
    assertThat(reported.read(REPORTED_PLAYBACK + ".formatChange", String.class))
        .isEqualTo("COPY_FAILED");
    var ready = playbackStatus(playbackId);
    assertThat(ready.read("data.playbackSession.formatAttemptCount", Integer.class)).isEqualTo(2);
    assertThat(
            ready.read(
                "data.playbackSession.currentFormatAttempt.streamSession.videoRange", String.class))
        .isEqualTo("SDR");
  }

  // ADR 0052: Original stays selected after the fallback to the copy with Dolby Vision stripped,
  // and the answer names the change so the client shows the Dolby Vision notice and marks Original.
  @Test
  @DisplayName(
      "Should keep Original and name the stripped Dolby Vision when Original's Dolby Vision copy"
          + " fails")
  void shouldKeepOriginalAndNameTheStrippedDolbyVisionWhenOriginalsDolbyVisionCopyFails() {
    var profile81 =
        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
            .dynamicRange(
                new VideoDynamicRange(
                    CompatibleBase.PQ,
                    Presence.ABSENT,
                    new DolbyVision.Present(Optional.empty(), Presence.PRESENT)))
            .build();
    STUB_SERVICE.setNextResult(
        buildSessionDeciding(
            UUID.randomUUID(),
            remuxDecisionBuilder()
                .videoSource(Optional.of(profile81))
                .videoBitstreamRewrites(List.of(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD))
                .videoDolbyVisionOutput(
                    Optional.of(new DolbyVisionOutput(DolbyVisionProfile.PROFILE_8_1, 6)))
                .build()));
    var created = startPlaybackReadingChoices(VideoQuality.ORIGINAL);
    var playbackId = created.read(CREATED_PLAYBACK + ".id", String.class);

    var reported =
        reportFailure(
            playbackId,
            created.read(CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.id", String.class),
            "UNSUPPORTED_FORMAT");
    STUB_SERVICE.setNextResult(
        buildSessionDeciding(
            UUID.randomUUID(),
            remuxDecisionBuilder()
                .videoSource(Optional.of(profile81))
                .videoBitstreamRewrites(List.of(BitstreamRewrite.STRIP_DOLBY_VISION))
                .build()));
    REPLACEMENT_TASKS.remove().run();

    assertThat(reported.read(REPORTED_PLAYBACK + ".playbackChoice", String.class))
        .isEqualTo("ORIGINAL");
    assertThat(reported.read(REPORTED_PLAYBACK + ".formatChange", String.class))
        .isEqualTo("DOLBY_VISION_STRIPPED");
    var ready = playbackStatus(playbackId);
    assertThat(ready.read("data.playbackSession.formatAttemptCount", Integer.class)).isEqualTo(2);
    assertThat(ready.read("data.playbackSession.playbackChoice", String.class))
        .isEqualTo("ORIGINAL");
    assertThat(ready.read("data.playbackSession.formatChange", String.class))
        .isEqualTo("DOLBY_VISION_STRIPPED");
    assertThat(
            ready.read(
                "data.playbackSession.currentFormatAttempt.streamSession.videoRange", String.class))
        .isEqualTo("PQ");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      value = {
        "OUTPUT_MISMATCH|PlaybackOutputMismatchError|This video can't play in this format. Set"
            + " Quality to Auto and try again. If Quality is already Auto, ask your server admin"
            + " to check the file.",
        "INVALID_SPECIFICATION|PlaybackInvalidSpecificationError|This video can't be played right"
            + " now. Ask your server admin to check the server logs.",
        "DOLBY_VISION_CONVERSION_FAILED|PlaybackOutputMismatchError|This video's Dolby Vision"
            + " can't be converted for this device. Set Quality to Auto and try again. If Quality"
            + " is already Auto, ask your server admin to check the file."
      })
  @DisplayName("Should expose the typed worker failure when a job attempt fails finally")
  void shouldExposeTheTypedWorkerFailureWhenAJobAttemptFailsFinally(
      TranscodeFailure failure, String typename, String message) {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));
    var created = startPlayback(VideoQuality.AUTO);
    var playbackId = created.read(CREATED_PLAYBACK + ".id", String.class);
    var streamId =
        created.read(CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.id", String.class);

    playbackSessions.onTerminalTranscodeFailure(
        TerminalTranscodeFailureEvent.builder()
            .streamSessionId(UUID.fromString(streamId))
            .jobAttemptId(UUID.randomUUID())
            .variantLabel("default")
            .failure(failure)
            .build());

    var status = playbackStatus(playbackId);
    assertThat(status.read("data.playbackSession.formatAttemptCount", Integer.class)).isEqualTo(1);
    assertThat(
            status.read(
                "data.playbackSession.currentFormatAttempt.error['__typename']", String.class))
        .isEqualTo(typename);
    assertThat(status.read("data.playbackSession.currentFormatAttempt.error.message", String.class))
        .isEqualTo(message);
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
    assertThat(
            reported.read(REPORTED_PLAYBACK + ".currentFormatAttempt.error.message", String.class))
        .isEqualTo(
            "This video didn't start on this device. Try again later, or try another device.");
    assertThat(reported.read(REPORTED_PLAYBACK + ".maximumFormatAttempts", Integer.class))
        .isEqualTo(2);
  }

  // A replacement can meet a start position that the media no longer holds once the file is
  // probed again shorter.
  @ParameterizedTest
  @MethodSource("replacementRejections")
  @DisplayName("Should expose a typed creation rejection when the replacement cannot start")
  void shouldExposeATypedCreationRejectionWhenTheReplacementCannotStart(
      CreateStreamSessionRejection rejection, String errorType) {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));
    var created = startPlayback(VideoQuality.AUTO);
    var playbackId = created.read(CREATED_PLAYBACK + ".id", String.class);
    reportFailure(
        playbackId,
        created.read(CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.id", String.class),
        "DECODE_ERROR");
    STUB_SERVICE.setRejection(rejection);

    REPLACEMENT_TASKS.remove().run();

    assertThat(
            playbackStatus(playbackId)
                .read(
                    "data.playbackSession.currentFormatAttempt.error['__typename']", String.class))
        .isEqualTo(errorType);
  }

  static Stream<Arguments> replacementRejections() {
    return Stream.of(
        Arguments.of(
            new CreateStreamSessionRejection.ProbeNotReady(), "MediaFileProbeNotReadyError"),
        Arguments.of(
            new CreateStreamSessionRejection.StartPositionOutOfRange(),
            "StartPositionOutOfRangeError"));
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

  @Test
  @DisplayName(
      "Should expose each format attempt's delivery, source, output and reasons when the owner"
          + " reads the playback session")
  void
      shouldExposeEachFormatAttemptsDeliverySourceOutputAndReasonsWhenTheOwnerReadsThePlaybackSession() {
    var sessionId = UUID.randomUUID();
    STUB_SERVICE.setNextResult(
        buildSessionDeciding(
            sessionId,
            remuxDecisionBuilder()
                .transcodeMode(TranscodeMode.AUDIO_TRANSCODE)
                .audioDecision(AudioDecision.stereoAac())
                .reasons(List.of(DecisionReason.AUDIO_CODEC_NOT_DECLARED))
                .build()));
    var playbackId = startPlayback(VideoQuality.AUTO).read(CREATED_PLAYBACK + ".id", String.class);

    var result =
        dgsQueryExecutor.executeAndGetDocumentContext(
            """
            query {
              playbackSession(id: "%s") {
                decisions {
                  formatAttempt streamSessionId reasons
                  video {
                    delivery
                    source {
                      codec width height frameRate bitrate videoRange dolbyVision
                      details { bitDepth compatibleBase hdr10Plus dolbyVision { presence profile } }
                    }
                    output { codec width height videoRange dolbyVision }
                  }
                  audio { delivery source { codec channels bitrate } output { codec channels bitrate } }
                }
              }
            }
            """
                .formatted(playbackId));

    var decision = "data.playbackSession.decisions[0]";
    assertThat(result.read("data.playbackSession.decisions.length()", Integer.class)).isEqualTo(1);
    assertThat(result.read(decision + ".formatAttempt", Integer.class)).isEqualTo(1);
    assertThat(result.read(decision + ".reasons", List.class))
        .containsExactly("AUDIO_CODEC_NOT_DECLARED");
    assertThat(result.read(decision + ".video.delivery", String.class)).isEqualTo("COPY");
    assertThat(result.read(decision + ".video.source.details.bitDepth", Integer.class))
        .isEqualTo(8);
    assertThat(result.read(decision + ".video.source.details.hdr10Plus", String.class))
        .isEqualTo("UNSPECIFIED");
    assertThat(result.read(decision + ".video.source", Map.class))
        .containsEntry("codec", "h264")
        .containsEntry("width", 1920)
        .containsEntry("height", 1080)
        .containsEntry("videoRange", "SDR")
        .containsEntry("dolbyVision", false)
        .containsEntry("bitrate", null);
    assertThat(result.read(decision + ".audio.delivery", String.class)).isEqualTo("ENCODE");
    assertThat(result.read(decision + ".audio.source", Map.class))
        .containsEntry("codec", "aac")
        .containsEntry("channels", null)
        .containsEntry("bitrate", null);
    assertThat(result.read(decision + ".audio.output", Map.class))
        .containsEntry("codec", "aac")
        .containsEntry("channels", 2)
        .containsEntry("bitrate", 128_000);
  }

  @Test
  @DisplayName(
      "Should expose what each video capability entry refused when a format attempt's decision"
          + " lists it")
  void shouldExposeWhatEachVideoCapabilityEntryRefusedWhenAFormatAttemptsDecisionListsIt() {
    STUB_SERVICE.setNextResult(
        buildSessionDeciding(
            UUID.randomUUID(),
            remuxDecisionBuilder()
                .videoCapabilityEntryRefusals(
                    List.of(
                        new VideoCapabilityEntryRefusal(
                            CapabilityEntryDelivery.DIRECT_PLAY,
                            0,
                            List.of(
                                DecisionReason.VIDEO_CODEC_LEVEL_NOT_DECLARED,
                                DecisionReason.VIDEO_PICTURE_SIZE_NOT_DECLARED)),
                        new VideoCapabilityEntryRefusal(CapabilityEntryDelivery.HLS, 2, List.of())))
                .build()));
    var playbackId = startPlayback(VideoQuality.AUTO).read(CREATED_PLAYBACK + ".id", String.class);

    var result =
        dgsQueryExecutor.executeAndGetDocumentContext(
            """
            query {
              playbackSession(id: "%s") {
                decisions { videoCapabilityEntryRefusals { delivery entryPosition reasons } }
              }
            }
            """
                .formatted(playbackId));

    assertThat(
            result.read(
                "data.playbackSession.decisions[0].videoCapabilityEntryRefusals", List.class))
        .containsExactly(
            Map.of(
                "delivery",
                "DIRECT_PLAY",
                "entryPosition",
                0,
                "reasons",
                List.of("VIDEO_CODEC_LEVEL_NOT_DECLARED", "VIDEO_PICTURE_SIZE_NOT_DECLARED")),
            Map.of("delivery", "HLS", "entryPosition", 2, "reasons", List.of()));
  }

  @Test
  @DisplayName("Should expose every decision reason when a format attempt's decision names it")
  void shouldExposeEveryDecisionReasonWhenAFormatAttemptsDecisionNamesIt() {
    STUB_SERVICE.setNextResult(
        buildSessionDeciding(
            UUID.randomUUID(),
            remuxDecisionBuilder().reasons(List.of(DecisionReason.values())).build()));
    var playbackId = startPlayback(VideoQuality.AUTO).read(CREATED_PLAYBACK + ".id", String.class);

    var result =
        dgsQueryExecutor.executeAndGetDocumentContext(
            """
            query { playbackSession(id: "%s") { decisions { reasons } } }
            """
                .formatted(playbackId));

    assertThat(result.read("data.playbackSession.decisions[0].reasons", List.class))
        .containsExactlyElementsOf(Stream.of(DecisionReason.values()).map(Enum::name).toList());
  }

  private DocumentContext startPlayback(VideoQuality quality) {
    return dgsQueryExecutor.executeAndGetDocumentContext(
        """
        mutation {
          createPlaybackSession(
            input: {mediaFileId: "%s", options: {quality: %s}, capabilities: %s}
          ) {
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
            .formatted(UUID.randomUUID(), quality, CAPABILITIES));
  }

  private DocumentContext reportFailure(String playbackId, String streamId, String reason) {
    return dgsQueryExecutor.executeAndGetDocumentContext(
        """
        mutation {
          reportPlaybackFormatFailure(input: {playbackSessionId: "%s", streamSessionId: "%s", reason: %s}) {
            playbackSession {
              id formatAttemptCount maximumFormatAttempts requestedChoice playbackChoice formatChange
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
            id formatAttemptCount maximumFormatAttempts playbackChoice formatChange
            currentFormatAttempt {
              __typename
              ... on ReadyPlaybackFormatAttempt {
                streamSession { id streamUrl ... on HlsStreamSession { videoRange } }
              }
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
          createPlaybackSession(input: {mediaFileId: "%s", capabilities: %s}) {
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
                .formatted(session.getMediaFileId(), CAPABILITIES));

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
    assertThat(context.read("data.createPlaybackSession.userErrors[0].message", String.class))
        .isEqualTo("The server can't start this video right now. Try again in a few minutes.");
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
    assertThat(context.read("data.createPlaybackSession.userErrors[0].message", String.class))
        .isEqualTo(
            "This video's file is no longer on the server. Go back to your library. If the video is still there, open it again.");
    List<String> inputPath = context.read("data.createPlaybackSession.userErrors[0].inputPath");
    assertThat(inputPath).containsExactly("mediaFileId");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName(
      "Should pass the start position to the service and return it with the stream session")
  void shouldPassTheStartPositionToTheServiceAndReturnItWithTheStreamSession(boolean directPlay) {
    var session =
        directPlay ? buildDirectPlaySession(UUID.randomUUID()) : buildSession(UUID.randomUUID());
    STUB_SERVICE.setNextResult(session);

    var context = startPlaybackAt(session.getMediaFileId().toString(), "5381.5");

    assertThat(STUB_SERVICE.getLastReceivedStartPosition()).contains(Duration.ofMillis(5_381_500));
    assertThat(
            context.read(
                CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.startPositionSeconds",
                Double.class))
        .isEqualTo(5381.5);
  }

  @Test
  @DisplayName("Should return no start position when the input has none")
  void shouldReturnNoStartPositionWhenTheInputHasNone() {
    var session = buildSession(UUID.randomUUID());
    STUB_SERVICE.setNextResult(session);

    var context = startPlaybackAt(session.getMediaFileId().toString(), "null");

    assertThat(STUB_SERVICE.getLastReceivedStartPosition()).isEmpty();
    assertThat(
            context.read(
                CREATED_PLAYBACK + ".currentFormatAttempt.streamSession.startPositionSeconds",
                Object.class))
        .isNull();
  }

  // A fraction of a nanosecond below zero still counts as before zero.
  @Test
  @DisplayName("Should keep a start position just below zero negative when the service reads it")
  void shouldKeepAStartPositionJustBelowZeroNegativeWhenTheServiceReadsIt() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));

    startPlaybackAt(UUID.randomUUID().toString(), "-1e-10");

    assertThat(STUB_SERVICE.getLastReceivedStartPosition()).contains(Duration.ofNanos(-1));
  }

  @Test
  @DisplayName("Should return a typed input error when the start position lies outside the media")
  void shouldReturnATypedInputErrorWhenTheStartPositionLiesOutsideTheMedia() {
    STUB_SERVICE.setRejection(new CreateStreamSessionRejection.StartPositionOutOfRange());

    var context = startPlaybackAt(UUID.randomUUID().toString(), "9000");

    assertThat(context.read(CREATED_PLAYBACK, Object.class)).isNull();
    assertThat(context.read("data.createPlaybackSession.userErrors[0].__typename", String.class))
        .isEqualTo("StartPositionOutOfRangeError");
    assertThat(context.read("data.createPlaybackSession.userErrors[0].message", String.class))
        .isEqualTo("This video can't start at that point. Play it from the beginning instead.");
    List<String> inputPath = context.read("data.createPlaybackSession.userErrors[0].inputPath");
    assertThat(inputPath).containsExactly("startPositionSeconds");
  }

  private DocumentContext startPlaybackAt(String mediaFileId, String startPositionSeconds) {
    return dgsQueryExecutor.executeAndGetDocumentContext(
        """
        mutation {
          createPlaybackSession(input: {
            mediaFileId: "%s", startPositionSeconds: %s, capabilities: %s
          }) {
            playbackSession {
              currentFormatAttempt {
                ... on ReadyPlaybackFormatAttempt { streamSession { startPositionSeconds } }
              }
            }
            userErrors {
              __typename
              ... on MutationError { message }
              ... on InputMutationError { inputPath }
            }
          }
        }
        """
            .formatted(mediaFileId, startPositionSeconds, CAPABILITIES));
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
            "The server can't read this video file. Ask your server admin to check the file."),
        Arguments.of(
            ProbeError.NO_VIDEO_STREAM,
            "MediaFileHasNoVideoError",
            "This file has no video to play. Ask your server admin to check the file."),
        Arguments.of(
            ProbeError.RESULT_TOO_LARGE,
            "InvalidMediaFileError",
            "This video file has more tracks or metadata than the server can read. Ask your server admin to check the file."));
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
                "message",
                    "The server is still preparing this video. Try again in a few minutes."));
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
                    "This video needs converting, but the server can't find its frame rate. Ask your server admin to check the file."));
  }

  // Original encodes on Auto's ladder when it cannot copy (ADR 0052), so switching to Auto cannot
  // find a format that the device plays either.
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
                "message",
                    "The server can't find a video format this device can play. Try another device."));
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
                "message",
                    "This video file has no playable length. Ask your server admin to check the file."));
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
                "message",
                    "This video file has no usable picture size. Ask your server admin to check the file."));
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
            options: {quality: HIGH_720P}
            capabilities: {
              video: [{
                codecFamily: HEVC
                codecProfiles: [{profile: HEVC_MAIN_10, maxLevel: 51}]
                maxBitDepth: 10, maxWidth: 3840, maxHeight: 2160, maxFrameRate: 59.94
                compatibleBases: [SDR, PQ, HLG]
                dolbyVisionProfiles: [{profile: PROFILE_8_1, maxLevel: 6}]
                hdr10Plus: true, hls: true, directPlayContainers: [MATROSKA, MP4]
                directPlaySampleEntries: [HVC1, DVH1]
              }]
              audio: [
                {codec: AAC, maxChannels: 2, hls: true}
                {codec: EAC3, maxChannels: 6, hls: false, directPlayContainers: [MOV]}
              ]
              selectsAudioTrackInDirectPlay: true
            }
          }) {
            playbackSession {
              id
              currentFormatAttempt {
                ... on ReadyPlaybackFormatAttempt {
                  streamSession { __typename id streamUrl ... on HlsStreamSession { transcodeMode } }
                }
              }
            }
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
                CREATED_PLAYBACK + ".currentFormatAttempt.streamSession['__typename']",
                String.class))
        .isEqualTo("HlsStreamSession");
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
        .isEqualTo(session.getMediaProbe().duration().plus(Duration.ofHours(1)));
    assertThat(STUB_SERVICE.getLastCreateProfileId()).isEqualTo(TestIdentityConstants.PROFILE_ID);
    var options = STUB_SERVICE.getLastReceivedOptions();
    assertThat(options.quality()).isEqualTo(VideoQuality.HIGH_720P);
    assertThat(STUB_SERVICE.getLastReceivedDeclaration())
        .isEqualTo(
            ClientCapabilityDeclaration.builder()
                .video(
                    List.of(
                        VideoCapabilityEntry.builder()
                            .codecFamily(ProbedCodecFamily.HEVC)
                            .codecProfiles(
                                List.of(
                                    VideoCodecProfileCapability.builder()
                                        .profile(VideoCodecProfile.HEVC_MAIN_10)
                                        .maxLevel(51)
                                        .build()))
                            .maxBitDepth(10)
                            .maxWidth(3840)
                            .maxHeight(2160)
                            .maxFrameRate(59.94)
                            .compatibleBases(
                                List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG))
                            .dolbyVisionProfiles(
                                List.of(
                                    DolbyVisionProfileCapability.builder()
                                        .profile(DolbyVisionProfile.PROFILE_8_1)
                                        .maxLevel(6)
                                        .build()))
                            .hdr10Plus(true)
                            .hls(true)
                            .directPlayContainers(
                                List.of(SourceContainer.MATROSKA, SourceContainer.MP4))
                            .directPlaySampleEntries(
                                List.of(VideoSampleEntry.HVC1, VideoSampleEntry.DVH1))
                            .build()))
                .audio(
                    List.of(
                        aacEntryBuilder().build(),
                        audioEntryBuilder(AudioCodec.EAC3)
                            .maxChannels(6)
                            .hls(false)
                            .directPlayContainers(List.of(SourceContainer.MOV))
                            .build()))
                .selectsAudioTrackInDirectPlay(true)
                .build());
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
          createPlaybackSession(input: {mediaFileId: "%s", capabilities: %s}) {
            playbackSession { id currentFormatAttempt { ... on ReadyPlaybackFormatAttempt { streamSession { id streamUrl } } } }
            userErrors { __typename }
          }
        }
        """
                .formatted(UUID.randomUUID(), CAPABILITIES));

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
          createPlaybackSession(input: {mediaFileId: "%s", capabilities: %s}) {
            playbackSession { id }
            userErrors {
              __typename
              ... on MutationError { message }
              ... on InputMutationError { inputPath }
            }
          }
        }
        """
            .formatted(mediaFileId, CAPABILITIES));
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
          .eventPublisher(_ -> {})
          .streamingService(STUB_SERVICE)
          .authorityGate(new FakePlaybackAuthorityGate())
          .executor(REPLACEMENT_TASKS::add)
          .maximumFormatAttempts(2)
          .build();
    }

    @Bean
    ServerSettingsService serverSettingsService() {
      return defaultServerSettings();
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
  @Autowired private PlaybackSessionService playbackSessions;

  @BeforeEach
  void resetStubService() {
    playbackSessions.reapExpired(Instant.MAX, Duration.ZERO);
    REPLACEMENT_TASKS.clear();
    beforeTokenEncoding = _ -> {};
    STUB_SERVICE.reset();
    STUB_WATCH_STATUS.reset();
  }

  private StreamSession buildSession(UUID sessionId) {
    return buildSessionOwnedBy(sessionId, TestIdentityConstants.PROFILE_ID);
  }

  private StreamSession buildSessionDeciding(UUID sessionId, TranscodeDecision decision) {
    return sessionBuilderLike(buildSession(sessionId)).transcodeDecision(decision).build();
  }

  // A direct-play stream session carries no transcode decision, because nothing transcodes.
  private StreamSession buildDirectPlaySession(UUID sessionId) {
    return sessionBuilderLike(buildSession(sessionId))
        .transcodeDecision(null)
        .delivery(StreamDelivery.DIRECT_PLAY)
        .sourceContainer(SourceContainer.MATROSKA)
        .build();
  }

  private static StreamSession.StreamSessionBuilder sessionBuilderLike(StreamSession session) {
    return StreamSession.builder()
        .sessionId(session.getSessionId())
        .authority(session.getAuthority())
        .mediaFileId(session.getMediaFileId())
        .sourcePath(session.getSourcePath())
        .mediaProbe(session.getMediaProbe())
        .transcodeDecision(session.getTranscodeDecision())
        .options(session.getOptions())
        .createdAt(session.getCreatedAt());
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
                .videoPlaybackFacts(
                    Optional.of(VideoPlaybackFactsFixture.sdrH264FactsBuilder().build()))
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
        .options(StreamingOptions.builder().build())
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
              createPlaybackSession(input: {mediaFileId: "%s", capabilities: %s}) {
                playbackSession { currentFormatAttempt { ... on ReadyPlaybackFormatAttempt { streamSession { streamUrl } } } }
              }
            }
            """,
            UUID.randomUUID(), CAPABILITIES);

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
  @DisplayName("Should play in Auto quality when the request names no options")
  void shouldPlayInAutoQualityWhenTheRequestNamesNoOptions() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));

    var mutation =
        String.format(
            """
            mutation {
              createPlaybackSession(input: {mediaFileId: "%s", capabilities: %s}) {
                playbackSession { id }
              }
            }
            """,
            UUID.randomUUID(), CAPABILITIES);

    dgsQueryExecutor.executeAndExtractJsonPath(
        mutation, "data.createPlaybackSession.playbackSession.id");

    assertThat(STUB_SERVICE.getLastReceivedOptions())
        .isEqualTo(StreamingOptions.builder().quality(VideoQuality.AUTO).build());
    assertThat(STUB_SERVICE.getLastReceivedDeclaration())
        .isEqualTo(
            defaultDeclarationBuilder()
                .video(
                    List.of(
                        h264EntryBuilder()
                            .codecProfiles(
                                List.of(
                                    VideoCodecProfileCapability.builder()
                                        .profile(VideoCodecProfile.H264_HIGH)
                                        .maxLevel(41)
                                        .build()))
                            .maxWidth(1920)
                            .maxHeight(1080)
                            .build()))
                .build());
  }

  @Test
  @DisplayName(
      "Should carry a FLAC entry's minimum block size when the declaration gives one for FLAC")
  void shouldCarryAFlacEntrysMinimumBlockSizeWhenTheDeclarationGivesOneForFlac() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));

    var context =
        dgsQueryExecutor.executeAndGetDocumentContext(
            """
            mutation {
              createPlaybackSession(input: {
                mediaFileId: "%s"
                capabilities: {
                  video: [{
                    codecFamily: H264, codecProfiles: [{profile: H264_HIGH, maxLevel: 41}]
                    maxBitDepth: 8, maxWidth: 1920, maxHeight: 1080, maxFrameRate: 60
                    compatibleBases: [SDR], dolbyVisionProfiles: [], hdr10Plus: false, hls: true
                  }]
                  audio: [
                    {codec: AAC, maxChannels: 2, hls: true}
                    {codec: FLAC, maxChannels: 8, hls: true, minBlockSize: 192}
                  ]
                  selectsAudioTrackInDirectPlay: false
                }
              }) {
                playbackSession { id }
                userErrors { __typename }
              }
            }
            """
                .formatted(UUID.randomUUID()));

    List<Object> errors = context.read("data.createPlaybackSession.userErrors");
    assertThat(errors).isEmpty();
    assertThat(STUB_SERVICE.getLastReceivedDeclaration().audio())
        .containsExactly(
            aacEntryBuilder().build(),
            audioEntryBuilder(AudioCodec.FLAC)
                .maxChannels(8)
                .minBlockSize(OptionalInt.of(192))
                .build());
  }

  @Test
  @DisplayName("Should refuse a minimum block size when the audio entry's codec is not FLAC")
  void shouldRefuseAMinimumBlockSizeWhenTheAudioEntrysCodecIsNotFlac() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));

    var context =
        dgsQueryExecutor.executeAndGetDocumentContext(
            """
            mutation {
              createPlaybackSession(input: {
                mediaFileId: "%s"
                capabilities: {
                  video: [{
                    codecFamily: H264, codecProfiles: [{profile: H264_HIGH, maxLevel: 41}]
                    maxBitDepth: 8, maxWidth: 1920, maxHeight: 1080, maxFrameRate: 60
                    compatibleBases: [SDR], dolbyVisionProfiles: [], hdr10Plus: false, hls: true
                  }]
                  audio: [{codec: AAC, maxChannels: 2, hls: true, minBlockSize: 192}]
                  selectsAudioTrackInDirectPlay: false
                }
              }) {
                playbackSession { id }
                userErrors {
                  __typename
                  ... on InputMutationError { inputPath }
                }
              }
            }
            """
                .formatted(UUID.randomUUID()));

    assertThat(context.read(CREATED_PLAYBACK, Object.class)).isNull();
    List<Map<String, Object>> errors = context.read("data.createPlaybackSession.userErrors");
    assertThat(errors)
        .containsExactly(
            Map.of(
                "__typename",
                "AudioMinBlockSizeInvalidError",
                "inputPath",
                List.of("capabilities", "audio", "0", "minBlockSize")));
    assertThat(STUB_SERVICE.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should return every refused capability field when the declaration is invalid")
  void shouldReturnEveryRefusedCapabilityFieldWhenTheDeclarationIsInvalid() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));

    var context =
        dgsQueryExecutor.executeAndGetDocumentContext(
            """
            mutation {
              createPlaybackSession(input: {
                mediaFileId: "%s"
                capabilities: {
                  video: [{
                    codecFamily: AV1
                    codecProfiles: [{profile: HEVC_MAIN, maxLevel: 0}]
                    maxBitDepth: 10, maxWidth: 3840, maxHeight: 2160, maxFrameRate: 60
                    compatibleBases: [SDR], dolbyVisionProfiles: [], hdr10Plus: false, hls: true
                  }]
                  audio: [{codec: OPUS, maxChannels: 2, hls: true}]
                  selectsAudioTrackInDirectPlay: false
                }
              }) {
                playbackSession { id }
                userErrors {
                  __typename
                  ... on InputMutationError { inputPath }
                }
              }
            }
            """
                .formatted(UUID.randomUUID()));

    assertThat(context.read(CREATED_PLAYBACK, Object.class)).isNull();
    List<Map<String, Object>> errors = context.read("data.createPlaybackSession.userErrors");
    assertThat(errors)
        .containsExactly(
            Map.of(
                "__typename",
                "CapabilityProfileMismatchError",
                "inputPath",
                List.of("capabilities", "video", "0", "codecProfiles", "0", "profile")),
            Map.of(
                "__typename",
                "CapabilityMaximumInvalidError",
                "inputPath",
                List.of("capabilities", "video", "0", "codecProfiles", "0", "maxLevel")),
            Map.of(
                "__typename",
                "HlsAacCapabilityRequiredError",
                "inputPath",
                List.of("capabilities", "audio")));
    assertThat(STUB_SERVICE.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse the frame-rate maximum when its literal overflows to infinity")
  void shouldRefuseTheFrameRateMaximumWhenItsLiteralOverflowsToInfinity() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));

    var context =
        dgsQueryExecutor.executeAndGetDocumentContext(
            """
            mutation {
              createPlaybackSession(input: {
                mediaFileId: "%s"
                capabilities: {
                  video: [{
                    codecFamily: H264
                    codecProfiles: [{profile: H264_HIGH, maxLevel: 41}]
                    maxBitDepth: 8, maxWidth: 1920, maxHeight: 1080, maxFrameRate: 1e309
                    compatibleBases: [SDR], dolbyVisionProfiles: [], hdr10Plus: false, hls: true
                  }]
                  audio: [{codec: AAC, maxChannels: 2, hls: true}]
                  selectsAudioTrackInDirectPlay: false
                }
              }) {
                playbackSession { id }
                userErrors {
                  __typename
                  ... on InputMutationError { inputPath }
                }
              }
            }
            """
                .formatted(UUID.randomUUID()));

    assertThat(context.read(CREATED_PLAYBACK, Object.class)).isNull();
    List<Map<String, Object>> errors = context.read("data.createPlaybackSession.userErrors");
    assertThat(errors)
        .containsExactly(
            Map.of(
                "__typename",
                "CapabilityMaximumInvalidError",
                "inputPath",
                List.of("capabilities", "video", "0", "maxFrameRate")));
    assertThat(STUB_SERVICE.getAllSessions()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("capabilitiesWithValuesTheSchemaDoesNotName")
  @DisplayName("Should refuse the request when its declaration names a value the schema does not")
  void shouldRefuseTheRequestWhenItsDeclarationNamesAValueTheSchemaDoesNot(
      String scenario, String capabilities, String undefinedValue) {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));

    var result =
        dgsQueryExecutor.execute(
            """
            mutation {
              createPlaybackSession(input: {mediaFileId: "%s", capabilities: %s}) {
                playbackSession { id }
              }
            }
            """
                .formatted(UUID.randomUUID(), capabilities));

    assertThat(result.getErrors())
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.getErrorType()).hasToString("INVALID_ARGUMENT");
              assertThat(error.getMessage()).contains(undefinedValue);
            });
    assertThat(STUB_SERVICE.getAllSessions()).isEmpty();
  }

  // ADR 0049 declares no Dolby Vision profile 7 and no NONE or UNSPECIFIED compatible base, and
  // issue 395 declares no VP9 codec family.
  static Stream<Arguments> capabilitiesWithValuesTheSchemaDoesNotName() {
    return Stream.of(
        Arguments.of(
            "Dolby Vision profile 7",
            CAPABILITIES.replace(
                "dolbyVisionProfiles: []",
                "dolbyVisionProfiles: [{profile: PROFILE_7, maxLevel: 6}]"),
            "PROFILE_7"),
        Arguments.of(
            "compatible base NONE",
            CAPABILITIES.replace("compatibleBases: [SDR]", "compatibleBases: [NONE]"),
            "NONE"),
        Arguments.of(
            "compatible base UNSPECIFIED",
            CAPABILITIES.replace("compatibleBases: [SDR]", "compatibleBases: [UNSPECIFIED]"),
            "UNSPECIFIED"),
        Arguments.of(
            "codec family VP9",
            CAPABILITIES.replace("codecFamily: H264", "codecFamily: VP9"),
            "VP9"));
  }

  // A client states whether its direct-play player selects an audio track (ADR 0045), so the
  // schema gives the fact no default.
  @Test
  @DisplayName(
      "Should refuse the request when its declaration omits whether the player selects an audio"
          + " track")
  void shouldRefuseTheRequestWhenItsDeclarationOmitsWhetherThePlayerSelectsAnAudioTrack() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));
    var capabilities = CAPABILITIES.replace(", selectsAudioTrackInDirectPlay: false", "");
    assertThat(capabilities).doesNotContain("selectsAudioTrackInDirectPlay");

    var result =
        dgsQueryExecutor.execute(
            """
            mutation {
              createPlaybackSession(input: {mediaFileId: "%s", capabilities: %s}) {
                playbackSession { id }
              }
            }
            """
                .formatted(UUID.randomUUID(), capabilities));

    assertThat(result.getErrors())
        .singleElement()
        .satisfies(
            error -> assertThat(error.getMessage()).contains("selectsAudioTrackInDirectPlay"));
    assertThat(STUB_SERVICE.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should refuse the request when a codec profile in its declaration carries a maximum tier")
  void shouldRefuseTheRequestWhenACodecProfileInItsDeclarationCarriesAMaximumTier() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));
    var capabilities =
        CAPABILITIES.replace(
            "codecFamily: H264, codecProfiles: [{profile: H264_HIGH, maxLevel: 41}]",
            "codecFamily: HEVC, codecProfiles: [{profile: HEVC_MAIN, maxLevel: 51, maxTier: MAIN}]");
    assertThat(capabilities).contains("maxTier: MAIN");

    var result =
        dgsQueryExecutor.execute(
            """
            mutation {
              createPlaybackSession(input: {mediaFileId: "%s", capabilities: %s}) {
                playbackSession { id }
              }
            }
            """
                .formatted(UUID.randomUUID(), capabilities));

    assertThat(result.getErrors())
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.getErrorType()).hasToString("INVALID_ARGUMENT");
              assertThat(error.getMessage()).contains("maxTier");
            });
    assertThat(STUB_SERVICE.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse the request when it carries no capability declaration")
  void shouldRefuseTheRequestWhenItCarriesNoCapabilityDeclaration() {
    STUB_SERVICE.setNextResult(buildSession(UUID.randomUUID()));

    var result =
        dgsQueryExecutor.execute(
            """
            mutation {
              createPlaybackSession(input: {mediaFileId: "%s"}) {
                playbackSession { id }
              }
            }
            """
                .formatted(UUID.randomUUID()));

    assertThat(result.getErrors())
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.getErrorType()).hasToString("INVALID_ARGUMENT");
              assertThat(error.getMessage()).contains("capabilities");
            });
    assertThat(STUB_SERVICE.getAllSessions()).isEmpty();
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
    private ClientCapabilityDeclaration lastReceivedDeclaration;
    private UUID lastCreateProfileId;
    private Optional<Duration> lastReceivedStartPosition = Optional.empty();
    private final Map<UUID, StreamSession> sessions = new HashMap<>();
    private Consumer<CreateStreamSessionCommand> duringCreate = _ -> {};

    void reset() {
      nextResult = null;
      rejection = Optional.empty();
      lastReceivedOptions = null;
      lastReceivedDeclaration = null;
      lastCreateProfileId = null;
      lastReceivedStartPosition = Optional.empty();
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

    ClientCapabilityDeclaration getLastReceivedDeclaration() {
      return lastReceivedDeclaration;
    }

    UUID getLastCreateProfileId() {
      return lastCreateProfileId;
    }

    Optional<Duration> getLastReceivedStartPosition() {
      return lastReceivedStartPosition;
    }

    void duringNextCreate(Consumer<CreateStreamSessionCommand> action) {
      duringCreate = action;
    }

    @Override
    public Outcome<StreamSession, CreateStreamSessionRejection> createSession(
        CreateStreamSessionCommand command) {
      this.lastReceivedOptions = command.options();
      this.lastReceivedDeclaration = command.capabilityDeclaration();
      this.lastCreateProfileId = command.identity().profileId();
      this.lastReceivedStartPosition = command.startPosition();
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
              .delivery(nextResult.getDelivery())
              .sourceContainer(nextResult.getSourceContainer().orElse(null))
              .directPlayAudioTrackPosition(nextResult.getDirectPlayAudioTrackPosition())
              .options(command.options())
              .startPosition(command.startPosition().orElse(null))
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
    public boolean destroySessionIfLastAccessedBefore(UUID sessionId, Instant cutoff) {
      throw new UnsupportedOperationException();
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
