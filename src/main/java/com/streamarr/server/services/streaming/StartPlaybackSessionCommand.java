package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record StartPlaybackSessionCommand(
    @NonNull UUID mediaFileId,
    @NonNull AuthenticatedIdentity identity,
    @NonNull StreamingOptions options) {
  public StartPlaybackSessionCommand {
    options =
        StreamingOptions.builder()
            .quality(options.quality())
            .maxWidth(options.maxWidth())
            .maxHeight(options.maxHeight())
            .maxBitrate(options.maxBitrate())
            .maxAudioChannels(options.maxAudioChannels())
            .audioLanguage(options.audioLanguage())
            .subtitleLanguage(options.subtitleLanguage())
            .supportedCodecs(
                options.supportedCodecs() == null ? null : List.copyOf(options.supportedCodecs()))
            .supportedAudioCodecs(
                options.supportedAudioCodecs() == null
                    ? null
                    : List.copyOf(options.supportedAudioCodecs()))
            .build();
  }
}
