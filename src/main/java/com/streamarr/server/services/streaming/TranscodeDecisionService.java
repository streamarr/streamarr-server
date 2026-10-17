package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.StreamCopySegments;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.SubtitleMode;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TranscodeDecisionService {

  private static final List<String> CODEC_PREFERENCE = List.of("av1", "h264");
  // The plain aac declaration covers LC, HE-AAC and HE-AAC v2.
  private static final Set<String> COPYABLE_AAC_CODEC_STRINGS =
      Set.of("mp4a.40.2", "mp4a.40.5", "mp4a.40.29");
  private static final String STREAM_COPY_REFUSALS_METRIC =
      "streamarr.streaming.stream_copy_refusals";

  private final StreamingProperties properties;
  private final MeterRegistry meterRegistry;

  /**
   * An encode uses the first codec in the preference that the client declares and that a
   * worker-verified encoder encodes (ADR 0009), ending at H.264. A copy needs no encoder.
   */
  public TranscodeDecision decide(@NonNull TranscodeDecisionRequest request) {
    var source = request.source();
    var clientOptions = request.clientOptions();
    var encodableVideoCodecFamilies = request.encodableVideoCodecFamilies();
    var supportedCodecs = clientOptions.supportedCodecs();
    boolean videoCopyable = decideVideoCopy(source, supportedCodecs);

    var videoCodecFamily =
        videoCopyable
            ? source.videoCodec()
            : selectPreferredCodec(supportedCodecs, encodableVideoCodecFamilies);
    var audioDecision = decideAudio(source, clientOptions);
    var subtitleDecision = SubtitleDecision.exclude();

    var mode = resolveTranscodeMode(videoCopyable, audioDecision, subtitleDecision);

    return TranscodeDecision.builder()
        .transcodeMode(mode)
        .videoCodecFamily(videoCodecFamily)
        .audioDecision(audioDecision)
        .subtitleDecision(subtitleDecision)
        .videoSource(source.videoPlaybackFacts())
        .build();
  }

  private boolean decideVideoCopy(MediaProbe source, List<String> supportedCodecs) {
    if (!supportedCodecs.contains(source.videoCodec()) || !permitsCopyUntilHdrCopies(source)) {
      return false;
    }

    var refusal = streamCopyRefusal(source);
    refusal.ifPresent(this::countStreamCopyRefusal);
    return refusal.isEmpty();
  }

  // A source without playback facts has no codec string for a copy job to carry.
  private static boolean permitsCopyUntilHdrCopies(MediaProbe source) {
    return source
        .videoPlaybackFacts()
        .map(facts -> InterimVideoCopyRule.permitsCopy(source.videoCodec(), facts))
        .orElse(false);
  }

  // A stream copy's segments can start only at source keyframes (ADR 0037), so the video is copied
  // only when the known keyframes open every interval of the grid except possibly the last. A last
  // interval without a keyframe joins the copy segment before it, which then runs to the media end.
  private Optional<StreamCopyRefusal> streamCopyRefusal(MediaProbe source) {
    return switch (source.keyframes()) {
      case KeyframeKnowledge.Known(var positions) ->
          streamCopyRefusal(positions, MediaSegmentTimelines.of(source, properties));
      case KeyframeKnowledge.PacketScanRequired _ ->
          Optional.of(StreamCopyRefusal.PACKET_SCAN_REQUIRED);
      case KeyframeKnowledge.Unavailable _ -> Optional.of(StreamCopyRefusal.KEYFRAMES_UNAVAILABLE);
      case KeyframeKnowledge.NotReported _ -> Optional.of(StreamCopyRefusal.KEYFRAMES_NOT_REPORTED);
    };
  }

  private static Optional<StreamCopyRefusal> streamCopyRefusal(
      KeyframePositions positions, MediaSegmentTimeline.PlainGrid grid) {
    return switch (KeyframeSegmentBoundaries.forStreamCopy(
        positions, grid.targetSegmentDurationSeconds())) {
      case StreamCopySegments.Starts starts
          when !opensEveryIntervalButTheLast(grid, positions, starts) ->
          Optional.of(StreamCopyRefusal.INTERVAL_WITHOUT_KEYFRAME);
      case StreamCopySegments.Starts _ -> Optional.empty();
      case StreamCopySegments.IntervalWithoutKeyframe _ ->
          Optional.of(StreamCopyRefusal.INTERVAL_WITHOUT_KEYFRAME);
      case StreamCopySegments.PresentationTimeRegressed _ ->
          Optional.of(StreamCopyRefusal.PRESENTATION_TIME_REGRESSED);
      case StreamCopySegments.PresentationTimeOutOfRange _ ->
          Optional.of(StreamCopyRefusal.PRESENTATION_TIME_OUT_OF_RANGE);
      case StreamCopySegments.DecodeTimeOutOfRange _ ->
          Optional.of(StreamCopyRefusal.DECODE_TIME_OUT_OF_RANGE);
    };
  }

  private static boolean opensEveryIntervalButTheLast(
      MediaSegmentTimeline.PlainGrid grid,
      KeyframePositions positions,
      StreamCopySegments.Starts starts) {
    var copySegmentCount = grid.openedAt(positions, starts).mediaSegmentCount();
    return copySegmentCount > 0 && copySegmentCount >= grid.mediaSegmentCount() - 1;
  }

  private void countStreamCopyRefusal(StreamCopyRefusal refusal) {
    Counter.builder(STREAM_COPY_REFUSALS_METRIC)
        .description(
            "Stream copies of a video codec the client plays, refused because the source's"
                + " keyframes are not known or leave a grid interval before the last one"
                + " without a keyframe")
        .tag("reason", refusal.name().toLowerCase(Locale.ROOT))
        .register(meterRegistry)
        .increment();
  }

  private enum StreamCopyRefusal {
    KEYFRAMES_NOT_REPORTED,
    PACKET_SCAN_REQUIRED,
    KEYFRAMES_UNAVAILABLE,
    INTERVAL_WITHOUT_KEYFRAME,
    PRESENTATION_TIME_REGRESSED,
    PRESENTATION_TIME_OUT_OF_RANGE,
    DECODE_TIME_OUT_OF_RANGE
  }

  private TranscodeMode resolveTranscodeMode(
      boolean videoCopyable, AudioDecision audio, SubtitleDecision subtitle) {
    boolean subtitleForcesTranscode = subtitle.mode() == SubtitleMode.BURN_IN;
    boolean effectiveVideoCopyable = videoCopyable && !subtitleForcesTranscode;
    boolean audioPassthrough = audio.mode() == AudioMode.COPY || audio.mode() == AudioMode.NONE;

    if (effectiveVideoCopyable && audioPassthrough) {
      return TranscodeMode.REMUX;
    }
    if (effectiveVideoCopyable) {
      return TranscodeMode.AUDIO_TRANSCODE;
    }
    if (audioPassthrough) {
      return TranscodeMode.VIDEO_TRANSCODE;
    }
    return TranscodeMode.FULL_TRANSCODE;
  }

  private AudioDecision decideAudio(MediaProbe source, StreamingOptions clientOptions) {

    if (source.audioCodec() == null) {
      return AudioDecision.none();
    }

    var clientAudioCodecs =
        Optional.ofNullable(clientOptions.supportedAudioCodecs())
            .orElse(StreamingOptions.DEFAULT_SUPPORTED_AUDIO_CODECS);
    int maxChannels =
        Optional.ofNullable(clientOptions.maxAudioChannels())
            .orElse(StreamingOptions.DEFAULT_MAX_AUDIO_CHANNELS);

    var candidates = new HashSet<>(clientAudioCodecs);
    candidates.retainAll(AudioDecision.deliverableCodecs());

    int normalizedChannels = AudioDecision.normalizeChannels(source.audioChannels().orElse(2));
    int effectiveChannels = Math.min(normalizedChannels, maxChannels);

    var sourceCodecString =
        source.audioStreams().stream()
            .findFirst()
            .flatMap(StreamInfo::codecString)
            .filter(codecString -> isCopyableAudioCodecString(source.audioCodec(), codecString));
    if (candidates.contains(source.audioCodec())
        && normalizedChannels <= maxChannels
        && sourceCodecString.isPresent()) {
      return AudioDecision.builder()
          .mode(AudioMode.COPY)
          .codec(source.audioCodec())
          .channels(normalizedChannels)
          .bitrate(
              source.audioBitrate().orElse(AudioDecision.bitrateForChannels(normalizedChannels)))
          .sourceCodecString(sourceCodecString)
          .build();
    }

    return selectTranscodeAudio(candidates, effectiveChannels);
  }

  private static boolean isCopyableAudioCodecString(String codec, String codecString) {
    return !"aac".equals(codec) || COPYABLE_AAC_CODEC_STRINGS.contains(codecString);
  }

  private AudioDecision selectTranscodeAudio(Set<String> candidates, int effectiveChannels) {

    if (effectiveChannels == 1) {
      return transcode("aac", 1);
    }

    if (effectiveChannels <= 2) {
      return AudioDecision.stereoAac();
    }
    if (candidates.contains("eac3")) {
      return transcode("eac3", effectiveChannels);
    }
    if (candidates.contains("ac3")) {
      return transcode("ac3", Math.min(effectiveChannels, 6));
    }
    if (candidates.contains("aac")) {
      return transcode("aac", effectiveChannels);
    }
    return AudioDecision.stereoAac();
  }

  private AudioDecision transcode(String codec, int channels) {
    return AudioDecision.builder()
        .mode(AudioMode.TRANSCODE)
        .codec(codec)
        .channels(channels)
        .bitrate(AudioDecision.bitrateForChannels(channels))
        .build();
  }

  private String selectPreferredCodec(
      List<String> supportedCodecs, Set<String> encodableVideoCodecFamilies) {
    for (var codec : CODEC_PREFERENCE) {
      if (supportedCodecs.contains(codec) && encodableVideoCodecFamilies.contains(codec)) {
        return codec;
      }
    }
    return StreamingOptions.DEFAULT_SUPPORTED_CODECS.getFirst();
  }
}
