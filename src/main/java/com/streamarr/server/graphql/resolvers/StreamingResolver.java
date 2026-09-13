package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.graphql.dto.StreamSessionDto;
import com.streamarr.server.graphql.dto.StreamingOptionsInput;
import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.streaming.CreateStreamSessionError;
import com.streamarr.server.graphql.mutation.streaming.CreateStreamSessionInput;
import com.streamarr.server.graphql.mutation.streaming.CreateStreamSessionPayload;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.streaming.CreateStreamSessionCommand;
import com.streamarr.server.services.streaming.CreateStreamSessionRejection;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.watchprogress.SessionProgressService;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class StreamingResolver {

  private final StreamingService streamingService;
  private final AuthorizationService authorizationService;
  private final PlaybackTokenIssuer playbackTokenIssuer;
  private final StreamingProperties streamingProperties;
  private final SessionProgressService sessionProgressService;
  private final WatchStatusService watchStatusService;

  private StreamSessionDto toCreatedSessionDto(StreamSession session) {
    try {
      return toDto(session);
    } catch (RuntimeException exception) {
      streamingService.destroySession(session.getSessionId());
      throw exception;
    }
  }

  @DgsMutation
  public CreateStreamSessionPayload createStreamSession(
      @InputArgument CreateStreamSessionInput input) {
    authorizationService.requireProfile();
    return MutationPayloads.withUuid(
        input.mediaFileId(),
        id -> createSession(id, input.options()),
        () ->
            MutationPayloads.inputError(
                new CreateStreamSessionError.InvalidIdError(
                    "Enter a valid media file ID.", InputPath.of("mediaFileId")),
                CreateStreamSessionPayload::new));
  }

  private CreateStreamSessionPayload createSession(
      UUID mediaFileId, StreamingOptionsInput options) {
    var outcome =
        streamingService.createSession(
            CreateStreamSessionCommand.builder()
                .mediaFileId(mediaFileId)
                .identity(authorizationService.currentIdentity())
                .options(mapOptions(options))
                .build());
    return MutationPayloads.payload(
        outcome.map(this::toCreatedSessionDto),
        this::createSessionError,
        CreateStreamSessionPayload::new);
  }

  private CreateStreamSessionError createSessionError(CreateStreamSessionRejection rejection) {
    return switch (rejection) {
      case CreateStreamSessionRejection.TranscodeCapacityUnavailable _ ->
          new CreateStreamSessionError.TranscodeCapacityUnavailableError(
              "The server is busy and can't start playback right now. Please try again later.");
      case CreateStreamSessionRejection.MediaFileNotFound _ ->
          new CreateStreamSessionError.MediaFileNotFoundError(
              "This media file no longer exists.", InputPath.of("mediaFileId"));
      case CreateStreamSessionRejection.ProbeNotReady() ->
          new CreateStreamSessionError.MediaFileProbeNotReadyError(
              "This file is being prepared for playback. Try again shortly.");
      case CreateStreamSessionRejection.ProbeFailed(var reason) -> probeFailureError(reason);
    };
  }

  private CreateStreamSessionError probeFailureError(ProbeError reason) {
    return switch (reason) {
      case INVALID_MEDIA ->
          new CreateStreamSessionError.InvalidMediaFileError(
              "This file cannot be read as supported media.");
      case NO_VIDEO_STREAM ->
          new CreateStreamSessionError.MediaFileHasNoVideoError("This file has no video stream.");
    };
  }

  @DgsMutation
  public boolean destroyStreamSession(@InputArgument String sessionId) {
    streamingService.destroySession(parseUuid(sessionId), authorizationService.requireProfile());

    return true;
  }

  @DgsMutation
  public boolean reportStreamSessionTimeline(
      @InputArgument String sessionId,
      @InputArgument int positionSeconds,
      @InputArgument PlaybackState state) {
    sessionProgressService.reportStreamSessionTimeline(
        authorizationService.requireProfile(), parseUuid(sessionId), positionSeconds, state);

    return true;
  }

  @DgsMutation
  public boolean markWatched(@InputArgument String id) {
    watchStatusService.markWatched(authorizationService.requireProfile(), parseUuid(id));

    return true;
  }

  @DgsMutation
  public boolean markUnwatched(@InputArgument String id) {
    watchStatusService.markUnwatched(authorizationService.requireProfile(), parseUuid(id));

    return true;
  }

  private StreamingOptions mapOptions(StreamingOptionsInput input) {
    return Optional.ofNullable(input).map(this::buildOptionsFromInput).orElse(defaultOptions());
  }

  private StreamingOptions defaultOptions() {
    return StreamingOptions.builder()
        .quality(VideoQuality.AUTO)
        .supportedCodecs(StreamingOptions.DEFAULT_SUPPORTED_CODECS)
        .supportedAudioCodecs(StreamingOptions.DEFAULT_SUPPORTED_AUDIO_CODECS)
        .maxAudioChannels(StreamingOptions.DEFAULT_MAX_AUDIO_CHANNELS)
        .build();
  }

  private StreamingOptions buildOptionsFromInput(StreamingOptionsInput input) {
    var quality =
        Optional.ofNullable(input.quality()).map(VideoQuality::valueOf).orElse(VideoQuality.AUTO);
    var codecs =
        Optional.ofNullable(input.supportedCodecs())
            .orElse(StreamingOptions.DEFAULT_SUPPORTED_CODECS);
    var audioCodecs =
        Optional.ofNullable(input.supportedAudioCodecs())
            .orElse(StreamingOptions.DEFAULT_SUPPORTED_AUDIO_CODECS);
    var maxAudioChannels =
        Optional.ofNullable(input.maxAudioChannels())
            .orElse(StreamingOptions.DEFAULT_MAX_AUDIO_CHANNELS);

    return StreamingOptions.builder()
        .quality(quality)
        .maxWidth(input.maxWidth())
        .maxHeight(input.maxHeight())
        .maxBitrate(input.maxBitrate())
        .supportedCodecs(codecs)
        .supportedAudioCodecs(audioCodecs)
        .maxAudioChannels(maxAudioChannels)
        .audioLanguage(input.audioLanguage())
        .subtitleLanguage(input.subtitleLanguage())
        .build();
  }

  private StreamSessionDto toDto(StreamSession session) {
    // The issuer refuses to mint for sessions the caller does not own — every DTO carries a
    // playback token, so the ownership check rides along wherever this is called from.
    return StreamSessionDto.builder()
        .id(session.getSessionId().toString())
        .streamUrl(
            "/api/stream/"
                + session.getSessionId()
                + "/multivariant.m3u8?t="
                + playbackTokenIssuer
                    .issue(
                        authorizationService.currentIdentity(),
                        session,
                        playbackTokenValidity(session))
                    .value())
        .transcodeMode(session.getTranscodeDecision().transcodeMode().name())
        .build();
  }

  private Duration playbackTokenValidity(StreamSession session) {
    return session.getMediaProbe().duration().plus(streamingProperties.sessionRetention());
  }

  private UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException _) {
      throw new InvalidIdException(id);
    }
  }
}
