package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TranscodeDecisionService {

  private static final List<String> CODEC_PREFERENCE = List.of("av1", "h264");

  public TranscodeDecision decide(MediaProbe source, StreamingOptions clientOptions) {
    var supportedCodecs = clientOptions.supportedCodecs();
    boolean videoCompatible = supportedCodecs.contains(source.videoCodec());

    var videoCodecFamily =
        videoCompatible ? source.videoCodec() : selectPreferredCodec(supportedCodecs);
    var containerFormat = containerForCodec(videoCodecFamily);
    var audioDecision = decideAudio(source, clientOptions, containerFormat);

    var mode = resolveTranscodeMode(videoCompatible, audioDecision);
    boolean needsKeyframeAlignment =
        mode == TranscodeMode.REMUX || mode == TranscodeMode.AUDIO_TRANSCODE;

    return TranscodeDecision.builder()
        .transcodeMode(mode)
        .videoCodecFamily(videoCodecFamily)
        .audioDecision(audioDecision)
        .containerFormat(containerFormat)
        .needsKeyframeAlignment(needsKeyframeAlignment)
        .build();
  }

  private TranscodeMode resolveTranscodeMode(boolean videoCompatible, AudioDecision audio) {
    boolean audioCopy = audio.mode() == AudioMode.COPY;

    if (videoCompatible && audioCopy) {
      return TranscodeMode.REMUX;
    }
    if (videoCompatible) {
      return TranscodeMode.AUDIO_TRANSCODE;
    }
    if (audioCopy) {
      return TranscodeMode.VIDEO_TRANSCODE;
    }
    return TranscodeMode.FULL_TRANSCODE;
  }

  private AudioDecision decideAudio(
      MediaProbe source, StreamingOptions clientOptions, ContainerFormat containerFormat) {

    var clientAudioCodecs =
        clientOptions.supportedAudioCodecs() != null
            ? clientOptions.supportedAudioCodecs()
            : StreamingOptions.DEFAULT_SUPPORTED_AUDIO_CODECS;
    int maxChannels =
        clientOptions.maxAudioChannels() != null
            ? clientOptions.maxAudioChannels()
            : StreamingOptions.DEFAULT_MAX_AUDIO_CHANNELS;
    var containerCodecs = containerFormat.supportedAudioCodecs();

    var candidates = new HashSet<>(clientAudioCodecs);
    candidates.retainAll(containerCodecs);

    int normalizedChannels = AudioDecision.normalizeChannels(source.audioChannels());
    int effectiveChannels = Math.min(normalizedChannels, maxChannels);

    if (canCopyAudio(source, candidates, normalizedChannels, maxChannels, containerFormat)) {
      return AudioDecision.copy(source.audioCodec(), normalizedChannels, source.audioBitrate());
    }

    return selectTranscodeAudio(candidates, effectiveChannels, containerFormat);
  }

  private boolean canCopyAudio(
      MediaProbe source,
      HashSet<String> candidates,
      int normalizedChannels,
      int maxChannels,
      ContainerFormat containerFormat) {

    if (!candidates.contains(source.audioCodec())) {
      return false;
    }
    if (normalizedChannels > maxChannels) {
      return false;
    }
    // Multichannel AAC has no channel layout metadata in MPEG-TS — block copy there
    return !("aac".equals(source.audioCodec())
        && normalizedChannels > 2
        && containerFormat == ContainerFormat.MPEGTS);
  }

  private AudioDecision selectTranscodeAudio(
      HashSet<String> candidates, int effectiveChannels, ContainerFormat containerFormat) {

    if (effectiveChannels > 2) {
      if (candidates.contains("eac3")) {
        return transcode("eac3", effectiveChannels);
      }
      if (candidates.contains("ac3")) {
        return transcode("ac3", Math.min(effectiveChannels, 6));
      }
      if (candidates.contains("aac") && containerFormat == ContainerFormat.FMP4) {
        return transcode("aac", effectiveChannels);
      }
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

  private ContainerFormat containerForCodec(String codecFamily) {
    return switch (codecFamily) {
      case "av1", "hevc" -> ContainerFormat.FMP4;
      default -> ContainerFormat.MPEGTS;
    };
  }
}
