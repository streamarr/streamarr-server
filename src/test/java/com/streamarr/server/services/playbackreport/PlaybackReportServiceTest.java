package com.streamarr.server.services.playbackreport;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.declarationBuilderFor;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.acceptedPlayback;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackRequest;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackServiceBuilder;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.startPlaybackBuilder;
import static com.streamarr.server.fixtures.PlayerFiguresFixture.tvFiguresBuilder;
import static com.streamarr.server.fixtures.PlayerFiguresFixture.unknownDisplayBuilder;
import static com.streamarr.server.fixtures.PlayerFiguresFixture.unknownFiguresBuilder;
import static com.streamarr.server.fixtures.PlayerFiguresFixture.webFiguresBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.fullTranscodeDecision;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static com.streamarr.server.support.OutcomeTestSupport.rejectionOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeCreatingStreamingService;
import com.streamarr.server.fakes.FakePlaybackReportRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.playbackreport.MarkPlaybackReportReadRejection.PlaybackReportMissing;
import com.streamarr.server.services.playbackreport.PlayerFigures.PictureSize;
import com.streamarr.server.services.playbackreport.PlayerFigures.PlayerAudio;
import com.streamarr.server.services.playbackreport.PlayerFigures.PlayerAudioOutput;
import com.streamarr.server.services.playbackreport.PlayerFigures.PlayerBrowser;
import com.streamarr.server.services.playbackreport.PlayerFigures.PlayerDisplay;
import com.streamarr.server.services.playbackreport.PlayerFigures.PlayerScreen;
import com.streamarr.server.services.playbackreport.PlayerFigures.QualityStep;
import com.streamarr.server.services.playbackreport.SharePlaybackReportRejection.NoteTooLong;
import com.streamarr.server.services.playbackreport.SharePlaybackReportRejection.PlaybackSessionMissing;
import com.streamarr.server.services.playbackreport.SharePlaybackReportRejection.PlayerFigureInvalid;
import com.streamarr.server.services.streaming.PlaybackDecision;
import com.streamarr.server.services.streaming.PlaybackFormatAttempt;
import com.streamarr.server.services.streaming.PlaybackFormatFailure;
import com.streamarr.server.services.streaming.PlaybackSessionService;
import com.streamarr.server.services.streaming.PlaybackSessionSnapshot;
import com.streamarr.server.services.streaming.ReportPlaybackFormatFailureCommand;
import com.streamarr.server.services.streaming.StartPlaybackSessionCommand;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
@DisplayName("Playback Report Service Tests")
class PlaybackReportServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-04T20:00:00Z");

  private final AtomicReference<Instant> currentTime = new AtomicReference<>(NOW);
  private final MutableClock clock = new MutableClock(currentTime);
  private final AuthenticatedIdentity serverAdmin = accountScopedBuilder().build();
  private final FakeAuthorizationService authorizationService =
      new FakeAuthorizationService(serverAdmin);
  private final FakeCreatingStreamingService streams = new FakeCreatingStreamingService();
  private final PlaybackSessionService playbackSessions = playbackServiceBuilder(streams).build();
  private final FakePlaybackReportRepository repository = new FakePlaybackReportRepository();
  private final PlaybackReportService service =
      new PlaybackReportService(
          authorizationService,
          playbackSessions,
          repository,
          new PlaybackReportList(repository),
          clock);

  @Nested
  @DisplayName("Sharing a report")
  class Sharing {

    @Test
    @DisplayName(
        "Should store the server's decisions, the player's figures and the note when a viewer"
            + " shares their own playback session")
    void shouldStoreDecisionsFiguresAndNoteWhenViewerSharesOwnPlaybackSession() {
      streams.decideWith(
          fullTranscodeDecision("h264").toBuilder()
              .reasons(List.of(DecisionReason.DOLBY_VISION_NOT_DECLARED))
              .build());
      var start =
          startPlaybackBuilder()
              .options(StreamingOptions.builder().quality(VideoQuality.ORIGINAL).build())
              .build();
      var playback = acceptedPlayback(playbackSessions.start(start));
      var figures = tvFiguresBuilder().build();

      var reportId =
          accepted(
              service.sharePlaybackReport(
                  shareBuilder(playback, start)
                      .note(Optional.of("Stutters every few seconds."))
                      .player(figures)
                      .build()));

      var serverSide = playbackSessions.access(playbackRequest(playback, start)).orElseThrow();
      assertThat(repository.storedReports())
          .singleElement()
          .satisfies(
              report -> {
                assertThat(report.id()).isEqualTo(reportId);
                assertThat(report.sharedAt()).isEqualTo(NOW);
                assertThat(report.readAt()).isEmpty();
                assertThat(report.profileId()).isEqualTo(start.identity().profileId());
                assertThat(report.playbackSessionId()).isEqualTo(playback.playbackSessionId());
                assertThat(report.streamSessionId())
                    .isEqualTo(playback.currentFormatAttempt().streamSessionId());
                assertThat(report.mediaFileId()).contains(start.mediaFileId());
                assertThat(report.note()).contains("Stutters every few seconds.");
                assertThat(report.requestedChoice()).isEqualTo(VideoQuality.ORIGINAL);
                assertThat(report.playbackChoice()).isEqualTo(serverSide.playbackChoice());
                assertThat(report.decisions()).isEqualTo(serverSide.decisions());
                assertThat(report.decisions().getFirst().reasons())
                    .containsExactly(DecisionReason.DOLBY_VISION_NOT_DECLARED);
                assertThat(report.player()).isEqualTo(figures);
                assertThat(report.capabilityDeclaration()).isEqualTo(start.capabilityDeclaration());
              });
      assertThat(authorizationService.recordedIntents())
          .containsExactly(new Intent.SharePlaybackReport());
    }

    @Test
    @DisplayName("Should store the report without a note when the viewer sends a blank note")
    void shouldStoreReportWithoutNoteWhenViewerSendsBlankNote() {
      var start = startPlaybackBuilder().build();
      var playback = acceptedPlayback(playbackSessions.start(start));

      accepted(
          service.sharePlaybackReport(
              shareBuilder(playback, start).note(Optional.of(" \n ")).build()));
      accepted(service.sharePlaybackReport(shareBuilder(playback, start).build()));

      assertThat(repository.storedReports())
          .hasSize(2)
          .allSatisfy(report -> assertThat(report.note()).isEmpty());
    }

    @Test
    @DisplayName("Should accept the note when it has 280 characters outside the basic plane")
    void shouldAcceptNoteWhenItHas280CharactersOutsideTheBasicPlane() {
      var start = startPlaybackBuilder().build();
      var playback = acceptedPlayback(playbackSessions.start(start));
      var note = "🎬".repeat(280);

      accepted(
          service.sharePlaybackReport(
              shareBuilder(playback, start).note(Optional.of(note)).build()));

      assertThat(repository.storedReports())
          .singleElement()
          .satisfies(report -> assertThat(report.note()).contains(note));
    }

    @Test
    @DisplayName("Should refuse the report when the note has more than 280 characters")
    void shouldRefuseReportWhenNoteHasMoreThan280Characters() {
      var start = startPlaybackBuilder().build();
      var playback = acceptedPlayback(playbackSessions.start(start));

      var outcome =
          service.sharePlaybackReport(
              shareBuilder(playback, start).note(Optional.of("a".repeat(281))).build());

      assertThat(rejectionOf(outcome)).isEqualTo(new NoteTooLong(280));
      assertThat(repository.storedReports()).isEmpty();
    }

    @Test
    @DisplayName("Should read the session as missing when another viewer owns it")
    void shouldReadSessionAsMissingWhenAnotherViewerOwnsIt() {
      var start = startPlaybackBuilder().build();
      var playback = acceptedPlayback(playbackSessions.start(start));
      var otherViewer = identityFor(defaultPlaybackAuthorityBuilder().build());

      var outcome =
          service.sharePlaybackReport(shareBuilder(playback, start).identity(otherViewer).build());

      assertThat(rejectionOf(outcome)).isEqualTo(new PlaybackSessionMissing());
      assertThat(repository.storedReports()).isEmpty();
    }

    @Test
    @DisplayName("Should read the session as missing when no playback session has the id")
    void shouldReadSessionAsMissingWhenNoPlaybackSessionHasTheId() {
      var start = startPlaybackBuilder().build();
      var playback = acceptedPlayback(playbackSessions.start(start));

      var outcome =
          service.sharePlaybackReport(
              shareBuilder(playback, start).playbackSessionId(UUID.randomUUID()).build());

      assertThat(rejectionOf(outcome)).isEqualTo(new PlaybackSessionMissing());
      assertThat(repository.storedReports()).isEmpty();
    }

    @Test
    @DisplayName(
        "Should keep the captured stream session when playback falls back before the report"
            + " arrives")
    void shouldKeepCapturedStreamSessionWhenPlaybackFallsBackBeforeReportArrives() {
      var start = startPlaybackBuilder().build();
      var playback = acceptedPlayback(playbackSessions.start(start));
      var captured = playback.currentFormatAttempt().streamSessionId();
      playbackSessions.reportFormatFailure(
          ReportPlaybackFormatFailureCommand.builder()
              .playbackSessionId(playback.playbackSessionId())
              .streamSessionId(captured)
              .identity(start.identity())
              .reason(PlaybackFormatFailure.UNSUPPORTED_FORMAT)
              .build());

      accepted(service.sharePlaybackReport(shareBuilder(playback, start).build()));

      var serverSide = playbackSessions.access(playbackRequest(playback, start)).orElseThrow();
      assertThat(serverSide.currentFormatAttempt().streamSessionId()).isNotEqualTo(captured);
      assertThat(repository.storedReports())
          .singleElement()
          .satisfies(
              report -> {
                assertThat(report.streamSessionId()).isEqualTo(captured);
                assertThat(report.decisions())
                    .extracting(PlaybackDecision::streamSessionId)
                    .contains(captured)
                    .hasSize(2);
              });
    }

    @Test
    @DisplayName(
        "Should keep the captured attempt's playback choice when Original falls back to an encode"
            + " before the report arrives")
    void shouldKeepCapturedAttemptsPlaybackChoiceWhenOriginalFallsBackBeforeReportArrives() {
      var pendingWork = new ArrayDeque<Runnable>();
      var deferredPlayback = playbackServiceBuilder(streams).executor(pendingWork::add).build();
      var deferredService =
          new PlaybackReportService(
              authorizationService,
              deferredPlayback,
              repository,
              new PlaybackReportList(repository),
              clock);
      var start =
          startPlaybackBuilder()
              .options(StreamingOptions.builder().quality(VideoQuality.ORIGINAL).build())
              .build();
      var copy = acceptedPlayback(deferredPlayback.start(start));
      var copyStream = copy.currentFormatAttempt().streamSessionId();
      deferredPlayback.reportFormatFailure(
          ReportPlaybackFormatFailureCommand.builder()
              .playbackSessionId(copy.playbackSessionId())
              .streamSessionId(copyStream)
              .identity(start.identity())
              .reason(PlaybackFormatFailure.UNSUPPORTED_FORMAT)
              .build());

      var whilePending =
          accepted(deferredService.sharePlaybackReport(shareBuilder(copy, start).build()));
      assertThat(deferredPlayback.access(playbackRequest(copy, start)).orElseThrow())
          .satisfies(
              pending -> {
                assertThat(pending.currentFormatAttempt())
                    .isInstanceOf(PlaybackFormatAttempt.Pending.class);
                assertThat(pending.playbackChoice()).isEqualTo(VideoQuality.AUTO);
              });
      while (!pendingWork.isEmpty()) {
        pendingWork.poll().run();
      }

      var encode = deferredPlayback.access(playbackRequest(copy, start)).orElseThrow();
      var encodeStream = encode.currentFormatAttempt().streamSessionId();
      var whileReady =
          accepted(deferredService.sharePlaybackReport(shareBuilder(copy, start).build()));
      var ofEncode =
          accepted(
              deferredService.sharePlaybackReport(
                  shareBuilder(copy, start).streamSessionId(encodeStream).build()));

      assertThat(encode.playbackChoice()).isEqualTo(VideoQuality.AUTO);
      assertThat(List.of(whilePending, whileReady, ofEncode))
          .extracting(id -> repository.findById(id).orElseThrow().playbackChoice())
          .containsExactly(VideoQuality.ORIGINAL, VideoQuality.ORIGINAL, VideoQuality.AUTO);
    }

    @Test
    @DisplayName(
        "Should read the session as missing when the captured stream session is not one of its"
            + " format attempts")
    void shouldReadSessionAsMissingWhenCapturedStreamSessionIsNotOneOfItsFormatAttempts() {
      var start = startPlaybackBuilder().build();
      var playback = acceptedPlayback(playbackSessions.start(start));

      var outcome =
          service.sharePlaybackReport(
              shareBuilder(playback, start).streamSessionId(UUID.randomUUID()).build());

      assertThat(rejectionOf(outcome)).isEqualTo(new PlaybackSessionMissing());
      assertThat(repository.storedReports()).isEmpty();
    }

    @Test
    @DisplayName("Should store nothing when authorization denies sharing the report")
    void shouldStoreNothingWhenAuthorizationDeniesSharingTheReport() {
      var start = startPlaybackBuilder().build();
      var playback = acceptedPlayback(playbackSessions.start(start));
      authorizationService.denyAll();

      var command = shareBuilder(playback, start).build();

      assertThatThrownBy(() -> service.sharePlaybackReport(command))
          .isInstanceOf(AccessDeniedException.class);
      assertThat(repository.storedReports()).isEmpty();
    }

    @Test
    @DisplayName("Should store the figures of a web player when it reports its screen and browser")
    void shouldStoreFiguresOfWebPlayerWhenItReportsScreenAndBrowser() {
      var start = startPlaybackBuilder().build();
      var playback = acceptedPlayback(playbackSessions.start(start));
      var figures = webFiguresBuilder().build();

      accepted(service.sharePlaybackReport(shareBuilder(playback, start).player(figures).build()));

      assertThat(repository.storedReports())
          .singleElement()
          .satisfies(report -> assertThat(report.player()).isEqualTo(figures));
    }

    @Test
    @DisplayName("Should accept audio without a channel count when the player did not know it")
    void shouldAcceptAudioWithoutChannelCountWhenPlayerDidNotKnowIt() {
      var start = startPlaybackBuilder().build();
      var playback = acceptedPlayback(playbackSessions.start(start));
      var figures =
          tvFiguresBuilder()
              .audio(
                  Optional.of(
                      PlayerAudio.builder()
                          .codec("aac")
                          .channels(OptionalInt.empty())
                          .dolbyAtmos(Optional.of(false))
                          .build()))
              .build();

      accepted(service.sharePlaybackReport(shareBuilder(playback, start).player(figures).build()));

      assertThat(repository.storedReports())
          .singleElement()
          .satisfies(report -> assertThat(report.player()).isEqualTo(figures));
    }

    @Test
    @DisplayName(
        "Should store the figures that the player could not name when the TV shows HDR without"
            + " naming its format and is switching display modes")
    void shouldStoreFiguresPlayerCouldNotNameWhenTvShowsUnnamedHdrAndSwitchesDisplayModes() {
      var start = startPlaybackBuilder().build();
      var playback = acceptedPlayback(playbackSessions.start(start));
      var figures =
          tvFiguresBuilder()
              .hdrMode(Optional.of(PlayerHdrMode.HDR))
              .audio(
                  Optional.of(
                      PlayerAudio.builder()
                          .codec("eac3")
                          .channels(OptionalInt.of(6))
                          .dolbyAtmos(Optional.empty())
                          .build()))
              .display(
                  Optional.of(
                      unknownDisplayBuilder().hdrMode(Optional.of(PlayerHdrMode.HDR)).build()))
              .build();

      accepted(service.sharePlaybackReport(shareBuilder(playback, start).player(figures).build()));

      assertThat(repository.storedReports())
          .singleElement()
          .satisfies(report -> assertThat(report.player()).isEqualTo(figures));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("invalidFigures")
    @DisplayName("Should refuse the report when a player figure is out of range")
    void shouldRefuseReportWhenPlayerFigureIsOutOfRange(
        PlayerFigures figures, PlayerFigure invalidFigure) {
      var start = startPlaybackBuilder().build();
      var playback = acceptedPlayback(playbackSessions.start(start));

      var outcome =
          service.sharePlaybackReport(shareBuilder(playback, start).player(figures).build());

      assertThat(rejectionOf(outcome)).isEqualTo(new PlayerFigureInvalid(invalidFigure));
      assertThat(repository.storedReports()).isEmpty();
    }

    @Test
    @DisplayName("Should return every refusal together when the note and a figure are invalid")
    void shouldReturnEveryRefusalTogetherWhenNoteAndFigureAreInvalid() {
      var start = startPlaybackBuilder().build();
      var playback = acceptedPlayback(playbackSessions.start(start));

      var outcome =
          service.sharePlaybackReport(
              shareBuilder(playback, start)
                  .note(Optional.of("a".repeat(281)))
                  .player(unknownFiguresBuilder().stalls(OptionalInt.of(-1)).build())
                  .build());

      List<SharePlaybackReportRejection> rejections = outcome.fold(_ -> List.of(), List::copyOf);
      assertThat(rejections)
          .containsExactly(new NoteTooLong(280), new PlayerFigureInvalid(PlayerFigure.STALLS));
    }

    static Stream<Arguments> invalidFigures() {
      return Stream.of(
          invalid(
              "quality step above its count",
              tvFiguresBuilder()
                  .qualityStep(
                      Optional.of(
                          QualityStep.builder()
                              .number(4)
                              .count(3)
                              .height(2160)
                              .bitrate(1)
                              .build())),
              PlayerFigure.QUALITY_STEP),
          invalid(
              "quality step zero",
              tvFiguresBuilder()
                  .qualityStep(
                      Optional.of(
                          QualityStep.builder()
                              .number(0)
                              .count(3)
                              .height(2160)
                              .bitrate(1)
                              .build())),
              PlayerFigure.QUALITY_STEP),
          invalid(
              "quality step without a picture",
              tvFiguresBuilder()
                  .qualityStep(
                      Optional.of(
                          QualityStep.builder().number(1).count(3).height(0).bitrate(1).build())),
              PlayerFigure.QUALITY_STEP),
          invalid(
              "quality step without a bitrate",
              tvFiguresBuilder()
                  .qualityStep(
                      Optional.of(
                          QualityStep.builder().number(1).count(3).height(720).bitrate(0).build())),
              PlayerFigure.QUALITY_STEP),
          invalid(
              "negative observed bitrate",
              tvFiguresBuilder().observedBitrate(OptionalDouble.of(-1)),
              PlayerFigure.OBSERVED_BITRATE),
          invalid(
              "infinite observed bitrate",
              tvFiguresBuilder().observedBitrate(OptionalDouble.of(Double.POSITIVE_INFINITY)),
              PlayerFigure.OBSERVED_BITRATE),
          invalid(
              "negative buffer",
              tvFiguresBuilder().bufferSeconds(OptionalDouble.of(-0.5)),
              PlayerFigure.BUFFER_SECONDS),
          invalid(
              "buffer that is not a number",
              tvFiguresBuilder().bufferSeconds(OptionalDouble.of(Double.NaN)),
              PlayerFigure.BUFFER_SECONDS),
          invalid(
              "negative dropped frames",
              tvFiguresBuilder().droppedFrames(OptionalInt.of(-1)),
              PlayerFigure.DROPPED_FRAMES),
          invalid(
              "negative stalls",
              tvFiguresBuilder().stalls(OptionalInt.of(-1)),
              PlayerFigure.STALLS),
          invalid(
              "resolution without a width",
              tvFiguresBuilder().resolution(Optional.of(new PictureSize(0, 2160))),
              PlayerFigure.RESOLUTION),
          invalid(
              "resolution without a height",
              tvFiguresBuilder().resolution(Optional.of(new PictureSize(3840, 0))),
              PlayerFigure.RESOLUTION),
          invalid(
              "blank video codec",
              tvFiguresBuilder().videoCodec(Optional.of(" ")),
              PlayerFigure.VIDEO_CODEC),
          invalid(
              "video codec longer than 64 characters",
              tvFiguresBuilder().videoCodec(Optional.of("h".repeat(65))),
              PlayerFigure.VIDEO_CODEC),
          invalid(
              "audio without a codec",
              tvFiguresBuilder()
                  .audio(
                      Optional.of(
                          PlayerAudio.builder()
                              .codec("")
                              .channels(OptionalInt.of(2))
                              .dolbyAtmos(Optional.empty())
                              .build())),
              PlayerFigure.AUDIO),
          invalid(
              "audio without channels",
              tvFiguresBuilder()
                  .audio(
                      Optional.of(
                          PlayerAudio.builder()
                              .codec("aac")
                              .channels(OptionalInt.of(0))
                              .dolbyAtmos(Optional.empty())
                              .build())),
              PlayerFigure.AUDIO),
          invalid(
              "display without a refresh rate",
              tvFiguresBuilder()
                  .display(Optional.of(displayBuilder().refreshRate(OptionalDouble.of(0)).build())),
              PlayerFigure.DISPLAY),
          invalid(
              "display without a width",
              tvFiguresBuilder()
                  .display(Optional.of(displayBuilder().width(OptionalInt.of(0)).build())),
              PlayerFigure.DISPLAY),
          invalid(
              "display without a height",
              tvFiguresBuilder()
                  .display(Optional.of(displayBuilder().height(OptionalInt.of(0)).build())),
              PlayerFigure.DISPLAY),
          invalid(
              "display with a width but no height",
              tvFiguresBuilder()
                  .display(Optional.of(displayBuilder().height(OptionalInt.empty()).build())),
              PlayerFigure.DISPLAY),
          invalid(
              "display with a height but no width",
              tvFiguresBuilder()
                  .display(Optional.of(displayBuilder().width(OptionalInt.empty()).build())),
              PlayerFigure.DISPLAY),
          invalid(
              "audio output without channels",
              tvFiguresBuilder().audioOutput(Optional.of(new PlayerAudioOutput("HDMI", 0))),
              PlayerFigure.AUDIO_OUTPUT),
          invalid(
              "audio output without a route",
              tvFiguresBuilder().audioOutput(Optional.of(new PlayerAudioOutput("", 2))),
              PlayerFigure.AUDIO_OUTPUT),
          invalid(
              "screen without a pixel ratio",
              webFiguresBuilder().screen(Optional.of(screenBuilder().pixelRatio(0).build())),
              PlayerFigure.SCREEN),
          invalid(
              "screen without a width",
              webFiguresBuilder().screen(Optional.of(screenBuilder().width(0).build())),
              PlayerFigure.SCREEN),
          invalid(
              "screen without a height",
              webFiguresBuilder().screen(Optional.of(screenBuilder().height(0).build())),
              PlayerFigure.SCREEN),
          invalid(
              "browser without a version",
              webFiguresBuilder().browser(Optional.of(new PlayerBrowser("Firefox", " "))),
              PlayerFigure.BROWSER),
          invalid(
              "browser without a name",
              webFiguresBuilder().browser(Optional.of(new PlayerBrowser("", "140"))),
              PlayerFigure.BROWSER));
    }

    private static Arguments invalid(
        String name, PlayerFigures.PlayerFiguresBuilder figures, PlayerFigure invalidFigure) {
      return Arguments.argumentSet(name, figures.build(), invalidFigure);
    }

    private static PlayerDisplay.PlayerDisplayBuilder displayBuilder() {
      return tvFiguresBuilder().build().display().orElseThrow().toBuilder();
    }

    private static PlayerScreen.PlayerScreenBuilder screenBuilder() {
      return webFiguresBuilder().build().screen().orElseThrow().toBuilder();
    }
  }

  @Nested
  @DisplayName("Reading reports")
  class Reading {

    @Test
    @DisplayName("Should list the reports newest first when a ServerAdmin reads them")
    void shouldListReportsNewestFirstWhenServerAdminReadsThem() {
      var oldest = shareReportAt(NOW.minus(Duration.ofHours(2)));
      var middle = shareReportAt(NOW.minus(Duration.ofHours(1)));
      var newest = shareReportAt(NOW);

      var firstPage = service.playbackReports(serverAdmin, firstPage(2));

      assertThat(firstPage.rows())
          .extracting(Row::item)
          .extracting(PlaybackReport::id)
          .containsExactly(newest, middle);
      assertThat(firstPage.hasNextPage()).isTrue();
      var secondPage =
          service.playbackReports(
              serverAdmin,
              PageRequest.builder()
                  .position(Optional.of(firstPage.rows().getLast().position()))
                  .direction(PaginationDirection.FORWARD)
                  .limit(2)
                  .build());
      assertThat(secondPage.rows())
          .extracting(Row::item)
          .extracting(PlaybackReport::id)
          .containsExactly(oldest);
      assertThat(authorizationService.recordedIntents()).endsWith(new Intent.ViewPlaybackReports());
    }

    @Test
    @DisplayName("Should return one report when a ServerAdmin opens it")
    void shouldReturnOneReportWhenServerAdminOpensIt() {
      var reportId = shareReportAt(NOW);

      assertThat(service.playbackReport(serverAdmin, reportId))
          .map(PlaybackReport::id)
          .contains(reportId);
      assertThat(service.playbackReport(serverAdmin, UUID.randomUUID())).isEmpty();
      assertThat(authorizationService.recordedIntents()).endsWith(new Intent.ViewPlaybackReports());
    }

    @Test
    @DisplayName("Should keep the capability declaration when the playback session has closed")
    void shouldKeepCapabilityDeclarationWhenPlaybackSessionHasClosed() {
      var start =
          startPlaybackBuilder()
              .capabilityDeclaration(declarationBuilderFor(ProbedCodecFamily.HEVC).build())
              .build();
      var playback = acceptedPlayback(playbackSessions.start(start));
      var reportId = accepted(service.sharePlaybackReport(shareBuilder(playback, start).build()));

      playbackSessions.close(playbackRequest(playback, start));

      assertThat(playbackSessions.access(playbackRequest(playback, start))).isEmpty();
      assertThat(service.playbackReport(serverAdmin, reportId))
          .map(PlaybackReport::capabilityDeclaration)
          .contains(start.capabilityDeclaration());
    }

    @Test
    @DisplayName("Should count only unread reports when one report is marked read")
    void shouldCountOnlyUnreadReportsWhenOneReportIsMarkedRead() {
      var read = shareReportAt(NOW.minusSeconds(60));
      shareReportAt(NOW);

      assertThat(service.unreadPlaybackReportCount(serverAdmin)).isEqualTo(2);
      accepted(service.markPlaybackReportRead(serverAdmin, read));

      assertThat(service.unreadPlaybackReportCount(serverAdmin)).isEqualTo(1);
      assertThat(authorizationService.recordedIntents()).endsWith(new Intent.ViewPlaybackReports());
    }

    @Test
    @DisplayName("Should keep the first read time when a report is marked read again")
    void shouldKeepFirstReadTimeWhenReportIsMarkedReadAgain() {
      var reportId = shareReportAt(NOW);

      var firstRead = accepted(service.markPlaybackReportRead(serverAdmin, reportId));
      clock.advance(Duration.ofMinutes(5));
      var secondRead = accepted(service.markPlaybackReportRead(serverAdmin, reportId));

      assertThat(firstRead.readAt()).contains(NOW);
      assertThat(secondRead.readAt()).contains(NOW);
      assertThat(authorizationService.recordedIntents())
          .endsWith(new Intent.MarkPlaybackReportRead());
    }

    @Test
    @DisplayName("Should report the report missing when no report has the id")
    void shouldReportReportMissingWhenNoReportHasTheId() {
      var outcome = service.markPlaybackReportRead(serverAdmin, UUID.randomUUID());

      assertThat(rejectionOf(outcome)).isEqualTo(new PlaybackReportMissing());
    }

    @Test
    @DisplayName("Should refuse every read when the caller is not a ServerAdmin")
    void shouldRefuseEveryReadWhenCallerIsNotServerAdmin() {
      var reportId = shareReportAt(NOW);
      var page = firstPage(10);
      authorizationService.denyAll();

      assertThatThrownBy(() -> service.playbackReports(serverAdmin, page))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> service.playbackReport(serverAdmin, reportId))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> service.unreadPlaybackReportCount(serverAdmin))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> service.markPlaybackReportRead(serverAdmin, reportId))
          .isInstanceOf(AccessDeniedException.class);
      assertThat(repository.storedReports())
          .singleElement()
          .satisfies(report -> assertThat(report.readAt()).isEmpty());
    }
  }

  private static SharePlaybackReportCommand.SharePlaybackReportCommandBuilder shareBuilder(
      PlaybackSessionSnapshot playback, StartPlaybackSessionCommand start) {
    return SharePlaybackReportCommand.builder()
        .identity(start.identity())
        .playbackSessionId(playback.playbackSessionId())
        .streamSessionId(playback.currentFormatAttempt().streamSessionId())
        .note(Optional.empty())
        .player(unknownFiguresBuilder().build());
  }

  private UUID shareReportAt(Instant sharedAt) {
    currentTime.set(sharedAt);
    var start = startPlaybackBuilder().build();
    var playback = acceptedPlayback(playbackSessions.start(start));
    return accepted(service.sharePlaybackReport(shareBuilder(playback, start).build()));
  }

  private static PageRequest firstPage(int limit) {
    return PageRequest.builder()
        .position(Optional.empty())
        .direction(PaginationDirection.FORWARD)
        .limit(limit)
        .build();
  }
}
