package com.streamarr.server.domain.streaming;

import java.util.List;
import lombok.Builder;

@Builder
public record StreamingOptions(
    VideoQuality quality,
    Integer maxWidth,
    Integer maxHeight,
    Integer maxBitrate,
    List<String> supportedCodecs,
    List<String> supportedAudioCodecs,
    Integer maxAudioChannels,
    String audioLanguage,
    String subtitleLanguage) {

  public static final List<String> DEFAULT_SUPPORTED_CODECS = List.of("h264");
  public static final List<String> DEFAULT_SUPPORTED_AUDIO_CODECS = List.of("aac");
  public static final int DEFAULT_MAX_AUDIO_CHANNELS = 2;
}
