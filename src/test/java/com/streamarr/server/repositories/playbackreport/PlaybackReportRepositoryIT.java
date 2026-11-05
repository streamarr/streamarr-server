package com.streamarr.server.repositories.playbackreport;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.audioEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static com.streamarr.server.fixtures.PlayerFiguresFixture.tvFiguresBuilder;
import static com.streamarr.server.fixtures.PlayerFiguresFixture.unknownFiguresBuilder;
import static com.streamarr.server.fixtures.PlayerFiguresFixture.webFiguresBuilder;
import static com.streamarr.server.jooq.generated.tables.PlaybackReportDecision.PLAYBACK_REPORT_DECISION;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DolbyVisionProfileCapability;
import com.streamarr.server.domain.streaming.SourceContainer;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoRange;
import com.streamarr.server.domain.streaming.VideoSampleEntry;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.playbackreport.NewPlaybackReport;
import com.streamarr.server.services.playbackreport.PlaybackReport;
import com.streamarr.server.services.streaming.AudioFormat;
import com.streamarr.server.services.streaming.PlaybackDecision;
import com.streamarr.server.services.streaming.PlaybackDecision.TrackDelivery;
import com.streamarr.server.services.streaming.PlaybackDelivery;
import com.streamarr.server.services.streaming.VideoFormat;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Playback Report Repository Integration Tests")
class PlaybackReportRepositoryIT extends AbstractIntegrationTest {

  // Recent enough that the retention sweep, which can fire during the suite, never deletes it.
  private static final Instant SHARED_AT = Instant.now().truncatedTo(ChronoUnit.MICROS);

  @Autowired private PlaybackReportRepository repository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;
  @Autowired private AuthTestSupport auth;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;

  private AuthTestSupport.TestIdentity viewer;
  private MediaFile mediaFile;

  @BeforeEach
  void createViewerAndMediaFile() {
    viewer = auth.createIdentity();
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    mediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename("report.mkv")
                .filepathUri("file:///library/" + UUID.randomUUID() + ".mkv")
                .size(1234)
                .build());
  }

  @AfterEach
  void deleteViewer() {
    auth.deleteIdentity(viewer);
  }

  @Test
  @DisplayName("Should read back every figure and decision when a report is saved")
  void shouldReadBackEveryFigureAndDecisionWhenReportIsSaved() {
    var report =
        reportBuilder()
            .note(Optional.of("Stutters every few seconds."))
            .requestedChoice(VideoQuality.ORIGINAL)
            .playbackChoice(VideoQuality.AUTO)
            .decisions(List.of(copyWithAudio(), encodeWithoutAudio()))
            .player(tvFiguresBuilder().build())
            .capabilityDeclaration(tvDeclaration())
            .build();

    var id = save(report);

    assertThat(repository.findById(id))
        .contains(
            PlaybackReport.builder()
                .id(id)
                .sharedAt(SHARED_AT)
                .readAt(Optional.empty())
                .profileId(viewer.profile().getId())
                .profileName(viewer.profile().getName())
                .playbackSessionId(report.playbackSessionId())
                .streamSessionId(report.streamSessionId())
                .mediaFileId(Optional.of(mediaFile.getId()))
                .note(report.note())
                .requestedChoice(VideoQuality.ORIGINAL)
                .playbackChoice(VideoQuality.AUTO)
                .decisions(report.decisions())
                .player(report.player())
                .capabilityDeclaration(report.capabilityDeclaration())
                .build());
  }

  @Test
  @DisplayName("Should read back the figures of a web player when it reports its screen")
  void shouldReadBackFiguresOfWebPlayerWhenItReportsScreen() {
    var figures = webFiguresBuilder().build();

    var id = save(reportBuilder().player(figures).build());

    assertThat(repository.findById(id)).map(PlaybackReport::player).contains(figures);
  }

  @Test
  @DisplayName("Should read back no figures when the player knew none")
  void shouldReadBackNoFiguresWhenPlayerKnewNone() {
    var id = save(reportBuilder().build());

    assertThat(repository.findById(id))
        .map(PlaybackReport::player)
        .contains(unknownFiguresBuilder().build());
  }

  @Test
  @DisplayName("Should keep the first read time when a report is marked read twice")
  void shouldKeepFirstReadTimeWhenReportIsMarkedReadTwice() {
    var id = save(reportBuilder().build());
    var unreadBefore = repository.countUnread();
    var firstRead = SHARED_AT.plusSeconds(60);

    var marked = inTransaction(() -> repository.markRead(id, firstRead));
    var markedAgain = inTransaction(() -> repository.markRead(id, firstRead.plusSeconds(60)));

    assertThat(marked).flatMap(PlaybackReport::readAt).contains(firstRead);
    assertThat(markedAgain).flatMap(PlaybackReport::readAt).contains(firstRead);
    assertThat(repository.countUnread()).isEqualTo(unreadBefore - 1);
  }

  @Test
  @DisplayName("Should mark nothing when no report has the id")
  void shouldMarkNothingWhenNoReportHasTheId() {
    assertThat(inTransaction(() -> repository.markRead(UUID.randomUUID(), SHARED_AT))).isEmpty();
  }

  @Test
  @DisplayName("Should delete a report and its decisions when it was shared before the cutoff")
  void shouldDeleteReportAndDecisionsWhenSharedBeforeCutoff() {
    var cutoff = SHARED_AT.minus(Duration.ofDays(30));
    var expired =
        save(
            reportBuilder()
                .sharedAt(cutoff.minusSeconds(1))
                .decisions(List.of(copyWithAudio()))
                .build());
    var kept = save(reportBuilder().build());

    inTransaction(() -> repository.deleteSharedBefore(cutoff));

    assertThat(repository.findById(expired)).isEmpty();
    assertThat(
            dsl.fetchCount(
                PLAYBACK_REPORT_DECISION, PLAYBACK_REPORT_DECISION.PLAYBACK_REPORT_ID.eq(expired)))
        .isZero();
    assertThat(repository.findById(kept)).isPresent();
  }

  @Test
  @DisplayName("Should keep the report without its media file when the media file is deleted")
  void shouldKeepReportWithoutMediaFileWhenMediaFileIsDeleted() {
    var id = save(reportBuilder().build());

    mediaFileRepository.deleteById(mediaFile.getId());

    assertThat(repository.findById(id)).flatMap(PlaybackReport::mediaFileId).isEmpty();
  }

  @Test
  @DisplayName("Should delete the report when its Profile is deleted")
  void shouldDeleteReportWhenItsProfileIsDeleted() {
    var otherViewer = auth.createIdentity();
    var id = save(reportBuilder().profileId(otherViewer.profile().getId()).build());

    auth.deleteIdentity(otherViewer);

    assertThat(repository.findById(id)).isEmpty();
  }

  private NewPlaybackReport.NewPlaybackReportBuilder reportBuilder() {
    return NewPlaybackReport.builder()
        .sharedAt(SHARED_AT)
        .profileId(viewer.profile().getId())
        .playbackSessionId(UUID.randomUUID())
        .streamSessionId(UUID.randomUUID())
        .mediaFileId(mediaFile.getId())
        .note(Optional.empty())
        .requestedChoice(VideoQuality.AUTO)
        .playbackChoice(VideoQuality.AUTO)
        .decisions(List.of())
        .player(unknownFiguresBuilder().build())
        .capabilityDeclaration(defaultDeclarationBuilder().build());
  }

  private UUID save(NewPlaybackReport report) {
    return inTransaction(() -> repository.save(report));
  }

  private <T> T inTransaction(Supplier<T> work) {
    return transactionTemplate.execute(_ -> work.get());
  }

  /** H.264 without a tier, HEVC with Dolby Vision and direct play, and two audio codecs. */
  private static ClientCapabilityDeclaration tvDeclaration() {
    return ClientCapabilityDeclaration.builder()
        .video(
            List.of(
                h264EntryBuilder().build(),
                hevcEntryBuilder()
                    .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ))
                    .dolbyVisionProfiles(
                        List.of(
                            DolbyVisionProfileCapability.builder()
                                .profile(DolbyVisionProfile.PROFILE_8_1)
                                .maxLevel(6)
                                .build()))
                    .directPlayContainers(List.of(SourceContainer.MP4))
                    .directPlaySampleEntries(List.of(VideoSampleEntry.HVC1))
                    .build()))
        .audio(
            List.of(
                aacEntryBuilder().build(),
                audioEntryBuilder(AudioCodec.EAC3)
                    .maxChannels(6)
                    .directPlayContainers(List.of(SourceContainer.MATROSKA))
                    .build()))
        .build();
  }

  private static PlaybackDecision copyWithAudio() {
    var source = videoBuilder().build();
    return PlaybackDecision.builder()
        .formatAttempt(1)
        .streamSessionId(UUID.randomUUID())
        .video(new TrackDelivery<>(PlaybackDelivery.COPY, source, source))
        .audio(
            Optional.of(
                new TrackDelivery<>(
                    PlaybackDelivery.ENCODE,
                    new AudioFormat("truehd", 8, OptionalLong.empty()),
                    new AudioFormat("eac3", 6, OptionalLong.of(640_000)))))
        .reasons(List.of(DecisionReason.AUDIO_CODEC_NOT_DECLARED))
        .build();
  }

  private static PlaybackDecision encodeWithoutAudio() {
    return PlaybackDecision.builder()
        .formatAttempt(2)
        .streamSessionId(UUID.randomUUID())
        .video(
            new TrackDelivery<>(
                PlaybackDelivery.ENCODE,
                videoBuilder().dolbyVision(true).build(),
                videoBuilder()
                    .codec("h264")
                    .width(1920)
                    .height(1080)
                    .frameRate(OptionalDouble.empty())
                    .videoRange(VideoRange.SDR)
                    .build()))
        .audio(Optional.empty())
        .reasons(List.of(DecisionReason.COPY_FAILED, DecisionReason.DOLBY_VISION_NOT_DECLARED))
        .build();
  }

  private static VideoFormat.VideoFormatBuilder videoBuilder() {
    return VideoFormat.builder()
        .codec("hevc")
        .width(3840)
        .height(2160)
        .frameRate(OptionalDouble.of(23.976))
        .bitrate(OptionalLong.of(61_200_000))
        .videoRange(VideoRange.PQ)
        .dolbyVision(false);
  }
}
