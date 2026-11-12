package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.language.Language;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.SourceContainer;
import com.streamarr.server.domain.streaming.SourceStreamEnd;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.task.KeyframePacketScanPublication;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.properties.StreamingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The early-end rule (ADR 0037) through playback with the real transcode decision. A stream session
 * delivers the chosen video stream and its own audio track, so only those two streams can make each
 * other end early; another audio track of the file never does. The files are synthetic: video and
 * audio tracks that end at 10 s or 17 s, whose ends a keyframe packet scan read, in the default 6 s
 * segments, so a stream that ends 7 s before another ends more than one segment before it.
 */
@Tag("UnitTest")
@DisplayName("Early End Beside Other Audio Tracks Tests")
class EarlyEndBesideOtherAudioTracksTest {

  private static final StreamingProperties PROPERTIES =
      StreamingProperties.builder()
          .maxConcurrentTranscodes(3)
          .targetSegmentDuration(Duration.ofSeconds(6))
          .sessionTimeout(Duration.ofSeconds(60))
          .build();
  private static final Duration EARLY_END = Duration.ofSeconds(10);
  private static final Duration LATE_END = Duration.ofSeconds(17);
  private static final AudioTrack ENGLISH_TO_THE_EARLY_END =
      AudioTrack.builder().language("eng").end(Optional.of(EARLY_END)).build();
  private static final AudioTrack FRENCH_TO_THE_LATE_END =
      AudioTrack.builder().language("fra").end(Optional.of(LATE_END)).build();

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeTranscodeExecutor workers = new FakeTranscodeExecutor();
  private final FakeMediaFileContainerInfoRepository probes =
      new FakeMediaFileContainerInfoRepository();
  private PlaybackSessionService playback;

  @BeforeEach
  void setUp() {
    playback =
        PlaybackSessionService.builder()
            .eventPublisher(_ -> {})
            .streamingService(streaming())
            .authorityGate(new FakePlaybackAuthorityGate())
            .executor(Runnable::run)
            .maximumFormatAttempts(2)
            .build();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sessionsBesideOtherAudioTracks")
  @DisplayName(
      "Should encode a delivered stream only when the other delivered stream ends at least one"
          + " segment after it")
  void shouldEncodeADeliveredStreamOnlyWhenTheOtherDeliveredStreamEndsAtLeastOneSegmentAfterIt(
      Session session) {
    var start =
        start(session.file())
            .options(
                StreamingOptions.builder()
                    .quality(VideoQuality.AUTO)
                    .audioLanguage(session.language())
                    .build())
            .build();

    accepted(playback.start(start));

    var decision = workers.getStartedRequests().getLast().transcodeDecision();
    assertThat(decision.transcodeMode().copiesVideo()).isEqualTo(session.copiesVideo());
    assertThat(decision.audioDecision().mode()).isEqualTo(session.audio());
    assertThat(decision.reasons()).containsExactlyElementsOf(session.reasons());
  }

  // The language picks the session's track; without one, the session plays the file's first track.
  static Stream<Arguments> sessionsBesideOtherAudioTracks() {
    var englishWithoutAKnownEnd =
        AudioTrack.builder().language("eng").end(Optional.empty()).build();
    return Stream.of(
        session(
            "a session on a track that ends with the video, listed before a track that runs on",
            copied()
                .file(videoToTheEarlyEnd(ENGLISH_TO_THE_EARLY_END, FRENCH_TO_THE_LATE_END))
                .language(Language.EN)),
        session(
            "a session on a track that ends with the video, listed after a track that runs on",
            copied()
                .file(videoToTheEarlyEnd(FRENCH_TO_THE_LATE_END, ENGLISH_TO_THE_EARLY_END))
                .language(Language.EN)),
        session(
            "a session on the track that runs on, listed after the other",
            videoEncodedForItsEarlyEnd()
                .file(videoToTheEarlyEnd(ENGLISH_TO_THE_EARLY_END, FRENCH_TO_THE_LATE_END))
                .language(Language.FR)),
        session(
            "a session on the track that runs on, listed before the other",
            videoEncodedForItsEarlyEnd()
                .file(videoToTheEarlyEnd(FRENCH_TO_THE_LATE_END, ENGLISH_TO_THE_EARLY_END))
                .language(Language.FR)),
        session(
            "a session on the second track, which ends more than a segment before the video",
            audioEncodedForItsEarlyEnd()
                .file(videoToTheLateEnd(FRENCH_TO_THE_LATE_END, ENGLISH_TO_THE_EARLY_END))
                .language(Language.EN)),
        session(
            "a session on the second track, which ends with the video, after one that ends more"
                + " than a segment before it",
            copied()
                .file(videoToTheLateEnd(ENGLISH_TO_THE_EARLY_END, FRENCH_TO_THE_LATE_END))
                .language(Language.FR)),
        session("a file without audio", copied().file(videoToTheEarlyEnd()).audio(AudioMode.NONE)),
        session(
            "a file whose one track ends with the video",
            copied().file(videoToTheEarlyEnd(ENGLISH_TO_THE_EARLY_END))),
        session(
            "a file whose one track runs on",
            videoEncodedForItsEarlyEnd().file(videoToTheEarlyEnd(FRENCH_TO_THE_LATE_END))),
        session(
            "a session on a track without a known end, beside a track that runs on",
            copied()
                .file(videoToTheEarlyEnd(englishWithoutAKnownEnd, FRENCH_TO_THE_LATE_END))
                .language(Language.EN)));
  }

  private static Arguments session(String name, Session.SessionBuilder session) {
    return Arguments.of(Named.of(name, session.build()));
  }

  private static Session.SessionBuilder copied() {
    return Session.builder().copiesVideo(true).audio(AudioMode.COPY).reasons(List.of());
  }

  // The encode holds the video's last picture up to the session audio's end.
  private static Session.SessionBuilder videoEncodedForItsEarlyEnd() {
    return Session.builder()
        .copiesVideo(false)
        .audio(AudioMode.COPY)
        .reasons(List.of(DecisionReason.VIDEO_ENDS_EARLY));
  }

  // The encode pads the session's audio with silence up to the video's end.
  private static Session.SessionBuilder audioEncodedForItsEarlyEnd() {
    return Session.builder()
        .copiesVideo(true)
        .audio(AudioMode.TRANSCODE)
        .reasons(List.of(DecisionReason.AUDIO_ENDS_EARLY));
  }

  // ADR 0045: a switch moves direct play to an HLS stream session for the chosen track, which
  // decides again. The new track runs 7 s past the video, so the encode holds the last picture up
  // to that track's end, and Original plays the encode in Auto (ADR 0052).
  @Test
  @DisplayName(
      "Should encode the video up to the new track's end when the viewer switches from direct play"
          + " to a track that runs past the video")
  void shouldEncodeTheVideoUpToTheNewTracksEndWhenTheViewerSwitchesToATrackThatRunsPastTheVideo() {
    var start =
        directPlayStart(videoToTheEarlyEnd(ENGLISH_TO_THE_EARLY_END, FRENCH_TO_THE_LATE_END));
    var directPlay = accepted(playback.start(start));
    assertThat(sessionOf(directPlay).getDelivery()).isEqualTo(StreamDelivery.DIRECT_PLAY);

    var switched = accepted(playback.switchAudioTrack(switchTo(directPlay, start, 1)));

    assertThat(sessionOf(switched).getDelivery()).isEqualTo(StreamDelivery.HLS);
    assertThat(switched.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(switched.formatChange()).contains(PlaybackFormatChange.AUDIO_TRACK_SWITCHED);
    var request = workers.getStartedRequests().getLast();
    assertThat(request.transcodeDecision().transcodeMode().copiesVideo()).isFalse();
    assertThat(request.transcodeDecision().reasons()).contains(DecisionReason.VIDEO_ENDS_EARLY);
    assertThat(request.advertisedEnd()).contains(LATE_END);
  }

  // The file's first track runs 7 s past the video, so direct play starts on it. The track that
  // the viewer switches to ends with the video, so the copy is no longer refused, and Original
  // keeps playing the copy (ADR 0052).
  @Test
  @DisplayName(
      "Should copy the video up to its end when the viewer switches from direct play on a track"
          + " that runs past the video to one that ends with it")
  void shouldCopyTheVideoUpToItsEndWhenTheViewerSwitchesToATrackThatEndsWithTheVideo() {
    var start =
        directPlayStart(videoToTheEarlyEnd(FRENCH_TO_THE_LATE_END, ENGLISH_TO_THE_EARLY_END));
    var directPlay = accepted(playback.start(start));
    assertThat(sessionOf(directPlay).getDelivery()).isEqualTo(StreamDelivery.DIRECT_PLAY);

    var switched = accepted(playback.switchAudioTrack(switchTo(directPlay, start, 1)));

    assertThat(sessionOf(switched).getDelivery()).isEqualTo(StreamDelivery.HLS);
    assertThat(switched.playbackChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(switched.formatChange()).isEmpty();
    var request = workers.getStartedRequests().getLast();
    assertThat(request.transcodeDecision().transcodeMode().copiesVideo()).isTrue();
    assertThat(request.advertisedEnd()).contains(EARLY_END);
  }

  // A switch moves only a direct-play stream session to a new stream session; an HLS stream session
  // keeps the one audio track that it started with (switchPlaybackAudioTrack). So a running copy
  // keeps its track and its end, and its player receives nothing of the other track's last 7 s.
  @Test
  @DisplayName(
      "Should keep a running HLS copy and its end when the viewer asks to switch to a track that"
          + " runs past the video")
  void shouldKeepARunningHlsCopyAndItsEndWhenTheViewerAsksToSwitchToATrackThatRunsPastTheVideo() {
    var start =
        start(videoToTheEarlyEnd(ENGLISH_TO_THE_EARLY_END, FRENCH_TO_THE_LATE_END))
            .options(StreamingOptions.builder().quality(VideoQuality.AUTO).build())
            .build();
    var copy = accepted(playback.start(start));

    var afterTheRequest = accepted(playback.switchAudioTrack(switchTo(copy, start, 1)));

    assertThat(afterTheRequest).isEqualTo(copy);
    assertThat(sessionOf(afterTheRequest).getMediaProbe().sessionAudioTrackPosition()).hasValue(0);
    assertThat(workers.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.transcodeDecision().transcodeMode().copiesVideo()).isTrue();
              assertThat(request.advertisedEnd()).contains(EARLY_END);
            });
  }

  private static StreamSession sessionOf(PlaybackSessionSnapshot snapshot) {
    if (snapshot.currentFormatAttempt() instanceof PlaybackFormatAttempt.Ready(var session)) {
      return session;
    }

    throw new AssertionError("Expected a ready format attempt: " + snapshot.currentFormatAttempt());
  }

  private static SwitchPlaybackAudioTrackCommand switchTo(
      PlaybackSessionSnapshot current, StartPlaybackSessionCommand start, int position) {
    return SwitchPlaybackAudioTrackCommand.builder()
        .playbackSessionId(current.playbackSessionId())
        .streamSessionId(current.currentFormatAttempt().streamSessionId())
        .identity(start.identity())
        .audioTrackPosition(position)
        .build();
  }

  // Original direct-plays a Matroska file to a player that cannot select an audio track, as long
  // as the session plays the file's first track (ADR 0045).
  private StartPlaybackSessionCommand directPlayStart(SourceFile file) {
    return start(file)
        .options(StreamingOptions.builder().quality(VideoQuality.ORIGINAL).build())
        .capabilityDeclaration(directPlayDeclaration())
        .build();
  }

  private StartPlaybackSessionCommand.StartPlaybackSessionCommandBuilder start(SourceFile file) {
    return StartPlaybackSessionCommand.builder()
        .mediaFileId(seed(file).getId())
        .identity(identityFor(defaultPlaybackAuthorityBuilder().build()))
        .capabilityDeclaration(defaultDeclarationBuilder().build());
  }

  private static ClientCapabilityDeclaration directPlayDeclaration() {
    var matroska = List.of(SourceContainer.MATROSKA);
    return defaultDeclarationBuilder()
        .video(List.of(h264EntryBuilder().directPlayContainers(matroska).build()))
        .audio(List.of(aacEntryBuilder().directPlayContainers(matroska).build()))
        .selectsAudioTrackInDirectPlay(false)
        .build();
  }

  // The probe stored the file's streams with no end in their headers, and asked for a keyframe
  // packet scan, which read the keyframes and each stream end that the file lists.
  private MediaFile seed(SourceFile file) {
    var mediaFile =
        mediaFiles.save(
            MediaFile.builder()
                .filepathUri("file:///media/movies/tracks.mkv")
                .filename("tracks.mkv")
                .status(MediaFileStatus.MATCHED)
                .size(1_000_000L)
                .build());
    var snapshot = new SourceFileSnapshot(1_000_000, Instant.EPOCH);
    probes.publish(
        ProbePublication.builder()
            .mediaFileId(mediaFile.getId())
            .snapshot(snapshot)
            .probeVersion(ProbeVersion.CURRENT)
            .outcome(
                ProbeFixture.completeProbeBuilder(file.probe())
                    .keyframes(
                        new KeyframeKnowledge.PacketScanRequired(
                            KeyframePacketScanReason.NO_CONTAINER_INDEX))
                    .build())
            .build());
    probes.publishKeyframePacketScan(
        KeyframePacketScanPublication.builder()
            .mediaFileId(mediaFile.getId())
            .snapshot(snapshot)
            .probeVersion(ProbeVersion.CURRENT)
            .keyframes(file.keyframes())
            .streamEnds(file.scannedEnds())
            .build());
    return mediaFile;
  }

  private HlsStreamingService streaming() {
    var segments = new FakeSegmentStore();
    var registry = new FakeRuntimeStreamSessionRegistry();
    var meters = new SimpleMeterRegistry();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(workers)
            .segmentStore(segments)
            .properties(PROPERTIES)
            .runtimeRegistry(registry)
            .heldRequests(workers.heldSegmentRequests())
            .build();
    return HlsStreamingService.builder()
        .languagePreferences(noLanguagePreferences())
        .mediaFileRepository(mediaFiles)
        .transcodeExecutor(workers)
        .segmentStore(segments)
        .playbackProbeService(playbackProbeServiceBuilder(probes).build())
        .transcodeDecisionService(new TranscodeDecisionService(PROPERTIES, meters))
        .qualityLadderService(new QualityLadderService())
        .properties(PROPERTIES)
        .authorityGate(new FakePlaybackAuthorityGate())
        .runtimeRegistry(registry)
        .producerLifecycle(rig.lifecycle())
        .deliveryCoordinator(rig.coordinator())
        .meterRegistry(meters)
        .build();
  }

  private static SourceFile videoToTheEarlyEnd(AudioTrack... audioTracks) {
    return SourceFile.builder().videoEnd(EARLY_END).audioTracks(List.of(audioTracks)).build();
  }

  private static SourceFile videoToTheLateEnd(AudioTrack... audioTracks) {
    return SourceFile.builder().videoEnd(LATE_END).audioTracks(List.of(audioTracks)).build();
  }

  /**
   * A stream session on {@code file}, which plays the track in {@code language}, and the decision
   * that the early-end rule leads to.
   */
  @Builder
  private record Session(
      SourceFile file,
      Language language,
      boolean copiesVideo,
      AudioMode audio,
      List<DecisionReason> reasons) {}

  /** One stereo AAC track of a source file, and the end that a keyframe packet scan read for it. */
  @Builder
  private record AudioTrack(String language, Optional<Duration> end) {}

  /**
   * A Matroska file: H.264 High video at stream 0 that ends at {@code videoEnd}, whose keyframes
   * open every 2 s, then the audio tracks in the listed order. The container's duration is the
   * latest end that the scan read.
   */
  @Builder
  private record SourceFile(Duration videoEnd, List<AudioTrack> audioTracks) {

    MediaProbe probe() {
      var streams = new ArrayList<StreamInfo>();
      var probe =
          defaultProbeBuilder()
              .duration(containerDuration())
              .containerFormat(Optional.of("matroska,webm"))
              .videoPlaybackFacts(
                  Optional.of(
                      VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                          .streamIndex(0)
                          .videoProperties(
                              Optional.of(
                                  VideoPlaybackFactsFixture.h264HighPropertiesBuilder().build()))
                          .build()));
      streams.add(ProbeFixture.videoStreamBuilder(probe.build()).build());
      for (var track : audioTracks) {
        streams.add(
            ProbeFixture.audioStreamBuilder("aac")
                .index(streams.size())
                .channels(OptionalInt.of(2))
                .language(Optional.of(track.language()))
                .build());
      }

      return probe.streams(streams).build();
    }

    KeyframeKnowledge keyframes() {
      var positions =
          LongStream.iterate(0, time -> time < videoEnd.toMillis(), time -> time + 2_000)
              .mapToObj(time -> new KeyframePosition(time, time))
              .toList();
      return new KeyframeKnowledge.Known(
          KeyframePositions.builder()
              .streamIndex(0)
              .timeBaseNumerator(1)
              .timeBaseDenominator(1_000)
              .source(KeyframePositionSource.PACKET_SCAN)
              .keyframes(positions)
              .build());
    }

    List<SourceStreamEnd> scannedEnds() {
      var ends = new ArrayList<SourceStreamEnd>();
      ends.add(new SourceStreamEnd(0, videoEnd));
      for (var position = 0; position < audioTracks.size(); position++) {
        var streamIndex = position + 1;
        audioTracks
            .get(position)
            .end()
            .ifPresent(end -> ends.add(new SourceStreamEnd(streamIndex, end)));
      }

      return ends;
    }

    private Duration containerDuration() {
      return scannedEnds().stream()
          .map(SourceStreamEnd::endTime)
          .max(Duration::compareTo)
          .orElseThrow();
    }
  }
}
