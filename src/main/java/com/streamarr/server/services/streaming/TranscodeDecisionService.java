package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamCopySegments;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.SubtitleMode;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TranscodeDecisionService {

  private static final List<String> CODEC_PREFERENCE = List.of("av1", "h264");

  private final StreamingProperties properties;

  public TranscodeDecision decide(MediaProbe source, StreamingOptions clientOptions) {
    var supportedCodecs = clientOptions.supportedCodecs();
    boolean videoCopyable =
        supportedCodecs.contains(source.videoCodec()) && keyframesOpenEverySegment(source);

    var videoCodecFamily =
        videoCopyable ? source.videoCodec() : selectPreferredCodec(supportedCodecs);
    var audioDecision = decideAudio(source, clientOptions);
    var subtitleDecision = SubtitleDecision.exclude();

    var mode = resolveTranscodeMode(videoCopyable, audioDecision, subtitleDecision);

    return TranscodeDecision.builder()
        .transcodeMode(mode)
        .videoCodecFamily(videoCodecFamily)
        .audioDecision(audioDecision)
        .subtitleDecision(subtitleDecision)
        .build();
  }

  // A stream copy's segments can start only at source keyframes (ADR 0037), so the video is copied
  // only when the known keyframes open every segment.
  private boolean keyframesOpenEverySegment(MediaProbe source) {
    return switch (source.keyframes()) {
      case KeyframeKnowledge.Known(var positions) ->
          KeyframeSegmentBoundaries.forStreamCopy(positions, periodSeconds(source))
              instanceof StreamCopySegments.Starts;
      case KeyframeKnowledge.PacketScanRequired _,
          KeyframeKnowledge.Unavailable _,
          KeyframeKnowledge.NotReported _ ->
          false;
    };
  }

  private int periodSeconds(MediaProbe source) {
    return MediaSegmentTimelines.of(source, properties).targetSegmentDurationSeconds();
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

    if (candidates.contains(source.audioCodec()) && normalizedChannels <= maxChannels) {
      return AudioDecision.copy(
          source.audioCodec(),
          normalizedChannels,
          source.audioBitrate().orElse(AudioDecision.bitrateForChannels(normalizedChannels)));
    }

    return selectTranscodeAudio(candidates, effectiveChannels);
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
    return new AudioDecision(
        AudioMode.TRANSCODE, codec, channels, AudioDecision.bitrateForChannels(channels));
  }

  private String selectPreferredCodec(List<String> supportedCodecs) {
    for (var codec : CODEC_PREFERENCE) {
      if (supportedCodecs.contains(codec)) {
        return codec;
      }
    }
    return StreamingOptions.DEFAULT_SUPPORTED_CODECS.getFirst();
  }
}
