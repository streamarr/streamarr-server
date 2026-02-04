package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.graphql.dto.StreamSessionDto;
import com.streamarr.server.graphql.dto.StreamingOptionsInput;
import com.streamarr.server.services.streaming.StreamingService;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class StreamingResolver {

  private final StreamingService streamingService;

  @DgsMutation
  public StreamSessionDto createStreamSession(
      @InputArgument String mediaFileId, @InputArgument StreamingOptionsInput options) {
    var opts = mapOptions(options);
    var session = streamingService.createSession(parseUuid(mediaFileId), opts);

    return toDto(session);
  }

  @DgsMutation
  public StreamSessionDto seekStreamSession(
      @InputArgument String sessionId, @InputArgument int positionSeconds) {
    var session = streamingService.seekSession(parseUuid(sessionId), positionSeconds);

    return toDto(session);
  }

  @DgsMutation
  public boolean destroyStreamSession(@InputArgument String sessionId) {
    streamingService.destroySession(parseUuid(sessionId));
    return true;
  }

  private StreamingOptions mapOptions(StreamingOptionsInput input) {
    return Optional.ofNullable(input).map(this::buildOptionsFromInput).orElse(defaultOptions());
  }

  private StreamingOptions defaultOptions() {
    return StreamingOptions.builder()
        .quality(VideoQuality.AUTO)
        .supportedCodecs(StreamingOptions.DEFAULT_SUPPORTED_CODECS)
        .build();
  }

  private StreamingOptions buildOptionsFromInput(StreamingOptionsInput input) {
    var quality =
        Optional.ofNullable(input.quality()).map(VideoQuality::valueOf).orElse(VideoQuality.AUTO);
    var codecs =
        Optional.ofNullable(input.supportedCodecs())
            .orElse(StreamingOptions.DEFAULT_SUPPORTED_CODECS);

    return StreamingOptions.builder()
        .quality(quality)
        .maxWidth(input.maxWidth())
        .maxHeight(input.maxHeight())
        .maxBitrate(input.maxBitrate())
        .supportedCodecs(codecs)
        .audioLanguage(input.audioLanguage())
        .subtitleLanguage(input.subtitleLanguage())
        .build();
  }

  private StreamSessionDto toDto(StreamSession session) {
    return new StreamSessionDto(
        session.getSessionId().toString(),
        "/api/stream/" + session.getSessionId() + "/master.m3u8",
        session.getTranscodeDecision().transcodeMode().name());
  }

  private UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException ex) {
      throw new InvalidIdException(id);
    }
  }
}
