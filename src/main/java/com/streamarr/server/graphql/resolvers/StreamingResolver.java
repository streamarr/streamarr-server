package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsTypeResolver;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.exceptions.PlaybackSessionClosedException;
import com.streamarr.server.graphql.dto.PlaybackFormatAttemptDto;
import com.streamarr.server.graphql.dto.PlaybackSessionDto;
import com.streamarr.server.graphql.dto.StreamSessionDto;
import com.streamarr.server.graphql.dto.StreamingOptionsInput;
import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.graphql.mutation.MutationError;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.streaming.CapabilityDeclarationErrors;
import com.streamarr.server.graphql.mutation.streaming.CreatePlaybackSessionInput;
import com.streamarr.server.graphql.mutation.streaming.CreatePlaybackSessionPayload;
import com.streamarr.server.graphql.mutation.streaming.CreateStreamSessionError;
import com.streamarr.server.graphql.mutation.streaming.DestroyPlaybackSessionInput;
import com.streamarr.server.graphql.mutation.streaming.DestroyPlaybackSessionPayload;
import com.streamarr.server.graphql.mutation.streaming.PlaybackSessionError;
import com.streamarr.server.graphql.mutation.streaming.ReportPlaybackFormatFailureInput;
import com.streamarr.server.graphql.mutation.streaming.ReportPlaybackFormatFailurePayload;
import com.streamarr.server.graphql.mutation.watchprogress.MarkUnwatchedError;
import com.streamarr.server.graphql.mutation.watchprogress.MarkUnwatchedInput;
import com.streamarr.server.graphql.mutation.watchprogress.MarkUnwatchedPayload;
import com.streamarr.server.graphql.mutation.watchprogress.MarkWatchedError;
import com.streamarr.server.graphql.mutation.watchprogress.MarkWatchedInput;
import com.streamarr.server.graphql.mutation.watchprogress.MarkWatchedPayload;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection;
import com.streamarr.server.services.streaming.CreateStreamSessionRejection;
import com.streamarr.server.services.streaming.PlaybackFormatAttempt;
import com.streamarr.server.services.streaming.PlaybackSessionFailure;
import com.streamarr.server.services.streaming.PlaybackSessionRequest;
import com.streamarr.server.services.streaming.PlaybackSessionService;
import com.streamarr.server.services.streaming.PlaybackSessionSnapshot;
import com.streamarr.server.services.streaming.ReportPlaybackFormatFailureCommand;
import com.streamarr.server.services.streaming.StartPlaybackSessionCommand;
import com.streamarr.server.services.streaming.StartPlaybackSessionRejection;
import com.streamarr.server.services.watchprogress.MarkUnwatchedRejection;
import com.streamarr.server.services.watchprogress.MarkWatchedRejection;
import com.streamarr.server.services.watchprogress.SessionProgressService;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class StreamingResolver {

  private static final String ITEM_ID = "itemId";
  private static final String INVALID_ITEM_ID = "Enter a valid item ID.";
  private static final String ITEM_NOT_FOUND = "This item no longer exists.";

  private final PlaybackSessionService playbackSessions;
  private final AuthorizationService authorizationService;
  private final PlaybackTokenIssuer playbackTokenIssuer;
  private final StreamingProperties streamingProperties;
  private final SessionProgressService sessionProgressService;
  private final WatchStatusService watchStatusService;

  private PlaybackSessionDto toCreatedSessionDto(PlaybackSessionSnapshot session) {
    try {
      return toPlaybackDto(session);
    } catch (RuntimeException exception) {
      playbackSessions.abandonStart(session.playbackSessionId());
      throw exception;
    }
  }

  @DgsMutation
  public CreatePlaybackSessionPayload createPlaybackSession(
      @InputArgument CreatePlaybackSessionInput input) {
    authorizationService.requireProfile();
    return MutationPayloads.withUuid(
        input.mediaFileId(),
        id -> createSession(id, input),
        () ->
            MutationPayloads.inputError(
                new CreateStreamSessionError.InvalidIdError(
                    "Enter a valid media file ID.", InputPath.of("mediaFileId")),
                CreatePlaybackSessionPayload::new));
  }

  private CreatePlaybackSessionPayload createSession(
      UUID mediaFileId, CreatePlaybackSessionInput input) {
    try {
      var outcome =
          playbackSessions.start(
              StartPlaybackSessionCommand.builder()
                  .mediaFileId(mediaFileId)
                  .identity(authorizationService.currentIdentity())
                  .options(mapOptions(input.options()))
                  .capabilityDeclaration(input.capabilities().toDeclaration())
                  .build());
      return MutationPayloads.payload(
          outcome.map(this::toCreatedSessionDto),
          this::startPlaybackError,
          CreatePlaybackSessionPayload::new);
    } catch (PlaybackSessionClosedException _) {
      return MutationPayloads.inputError(closedError(), CreatePlaybackSessionPayload::new);
    }
  }

  private CreateStreamSessionError startPlaybackError(StartPlaybackSessionRejection rejection) {
    return switch (rejection) {
      case CreateStreamSessionRejection streamRejection -> createSessionError(streamRejection);
      case CapabilityDeclarationRejection declarationRejection ->
          CapabilityDeclarationErrors.toError(declarationRejection);
    };
  }

  private CreateStreamSessionError createSessionError(CreateStreamSessionRejection rejection) {
    return switch (rejection) {
      case CreateStreamSessionRejection.TranscodeCapacityUnavailable _ ->
          new CreateStreamSessionError.TranscodeCapacityUnavailableError(
              "The server can't start this video right now. Try again in a few minutes.");
      case CreateStreamSessionRejection.MediaFileNotFound _ ->
          new CreateStreamSessionError.MediaFileNotFoundError(
              "This video's file is no longer on the server. Go back to your library. If the video is still there, open it again.",
              InputPath.of("mediaFileId"));
      case CreateStreamSessionRejection.ProbeNotReady() ->
          new CreateStreamSessionError.MediaFileProbeNotReadyError(
              "The server is still preparing this video. Try again in a few minutes.");
      case CreateStreamSessionRejection.ProbeFailed(var reason) -> probeFailureError(reason);
      case CreateStreamSessionRejection.FrameRateUnknown() ->
          new CreateStreamSessionError.MediaFileFrameRateUnknownError(
              "This video needs converting, but the server can't find its frame rate. Ask your server admin to check the file.");
      case CreateStreamSessionRejection.InvalidPictureSize() ->
          new CreateStreamSessionError.InvalidMediaFileError(
              "This video file has no usable picture size. Ask your server admin to check the file.");
      case CreateStreamSessionRejection.NoEligibleVideoFormat() ->
          new CreateStreamSessionError.NoEligibleVideoFormatError(
              "The server can't find a video format this device can play. Set Quality to Auto, or try another device.");
      case CreateStreamSessionRejection.NoMediaSegments() ->
          new CreateStreamSessionError.InvalidMediaFileError(
              "This video file has no playable length. Ask your server admin to check the file.");
    };
  }

  private CreateStreamSessionError probeFailureError(ProbeError reason) {
    return switch (reason) {
      case INVALID_MEDIA ->
          new CreateStreamSessionError.InvalidMediaFileError(
              "The server can't read this video file. Ask your server admin to check the file.");
      case RESULT_TOO_LARGE ->
          new CreateStreamSessionError.InvalidMediaFileError(
              "This video file has more tracks or metadata than the server can read. Ask your server admin to check the file.");
      case NO_VIDEO_STREAM ->
          new CreateStreamSessionError.MediaFileHasNoVideoError(
              "This file has no video to play. Ask your server admin to check the file.");
    };
  }

  @DgsQuery
  public Optional<PlaybackSessionDto> playbackSession(@InputArgument String id) {
    authorizationService.requireProfile();
    return playbackSessions.access(playbackRequest(parseUuid(id))).map(this::toPlaybackDto);
  }

  @DgsMutation
  public ReportPlaybackFormatFailurePayload reportPlaybackFormatFailure(
      @InputArgument ReportPlaybackFormatFailureInput input) {
    authorizationService.requireProfile();
    return MutationPayloads.withUuid(
        input.playbackSessionId(),
        playbackId ->
            MutationPayloads.withUuid(
                input.streamSessionId(),
                streamId -> reportFailure(input, playbackId, streamId),
                () -> invalidReportId("streamSessionId")),
        () -> invalidReportId("playbackSessionId"));
  }

  private ReportPlaybackFormatFailurePayload reportFailure(
      ReportPlaybackFormatFailureInput input, UUID playbackId, UUID streamId) {
    return playbackSessions
        .reportFormatFailure(
            ReportPlaybackFormatFailureCommand.builder()
                .playbackSessionId(playbackId)
                .streamSessionId(streamId)
                .identity(authorizationService.currentIdentity())
                .reason(input.reason())
                .build())
        .map(
            snapshot ->
                new ReportPlaybackFormatFailurePayload(
                    Optional.of(toPlaybackDto(snapshot)), List.of()))
        .orElseGet(
            () ->
                MutationPayloads.inputError(
                    closedError(), ReportPlaybackFormatFailurePayload::new));
  }

  private ReportPlaybackFormatFailurePayload invalidReportId(String field) {
    return MutationPayloads.inputError(
        invalidPlaybackId(field), ReportPlaybackFormatFailurePayload::new);
  }

  @DgsMutation
  public DestroyPlaybackSessionPayload destroyPlaybackSession(
      @InputArgument DestroyPlaybackSessionInput input) {
    authorizationService.requireProfile();
    return MutationPayloads.withUuid(
        input.playbackSessionId(),
        id -> {
          playbackSessions.close(playbackRequest(id));
          return new DestroyPlaybackSessionPayload(Optional.of(id.toString()), List.of());
        },
        () ->
            MutationPayloads.inputError(
                invalidPlaybackId("playbackSessionId"), DestroyPlaybackSessionPayload::new));
  }

  private PlaybackSessionRequest playbackRequest(UUID id) {
    return PlaybackSessionRequest.builder()
        .playbackSessionId(id)
        .identity(authorizationService.currentIdentity())
        .build();
  }

  private CreateStreamSessionError.InvalidIdError invalidPlaybackId(String field) {
    return new CreateStreamSessionError.InvalidIdError(
        "Enter a valid session ID.", InputPath.of(field));
  }

  private PlaybackSessionError.PlaybackSessionClosedError closedError() {
    return new PlaybackSessionError.PlaybackSessionClosedError("This playback session has ended.");
  }

  private PlaybackSessionDto toPlaybackDto(PlaybackSessionSnapshot snapshot) {
    var attempt =
        switch (snapshot.currentFormatAttempt()) {
          case PlaybackFormatAttempt.Pending(var streamId) ->
              new PlaybackFormatAttemptDto.PendingPlaybackFormatAttempt(streamId.toString());
          case PlaybackFormatAttempt.Ready(var session) ->
              new PlaybackFormatAttemptDto.ReadyPlaybackFormatAttempt(toDto(session));
          case PlaybackFormatAttempt.Failed(var streamId, var reason) ->
              new PlaybackFormatAttemptDto.FailedPlaybackFormatAttempt(
                  streamId.toString(), playbackFailure(reason));
        };
    return PlaybackSessionDto.builder()
        .id(snapshot.playbackSessionId().toString())
        .formatAttemptCount(snapshot.formatAttemptCount())
        .maximumFormatAttempts(snapshot.maximumFormatAttempts())
        .currentFormatAttempt(attempt)
        .build();
  }

  private MutationError playbackFailure(PlaybackSessionFailure failure) {
    return switch (failure) {
      case PlaybackSessionFailure.OutputMismatch() ->
          new PlaybackSessionError.PlaybackOutputMismatchError(
              "This video can't play in this format. Set Quality to Auto and try again. If Quality is already Auto, ask your server admin to check the file.");
      case PlaybackSessionFailure.InvalidSpecification() ->
          new PlaybackSessionError.PlaybackInvalidSpecificationError(
              "This video can't be played right now. Ask your server admin to check the server logs.");
      case PlaybackSessionFailure.FixedFormatFailed(var reason) ->
          new PlaybackSessionError.PlaybackFormatRejectedError(
              "This device can't play the quality you chose. Set Quality to Auto and try again.",
              reason);
      case PlaybackSessionFailure.AttemptsExhausted() ->
          new PlaybackSessionError.PlaybackFormatAttemptsExhaustedError(
              "This video didn't start on this device. Try again later, or try another device.");
      case PlaybackSessionFailure.CreationRejected(var rejection) -> createSessionError(rejection);
      case PlaybackSessionFailure.StartupFailed() ->
          new PlaybackSessionError.PlaybackStartupFailedError(
              "The server couldn't start this video. Try again in a few minutes.");
    };
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
  public MarkWatchedPayload markWatched(@InputArgument MarkWatchedInput input) {
    var profileId = authorizationService.requireProfile();
    return MutationPayloads.withUuid(
        input.itemId(),
        itemId ->
            MutationPayloads.payload(
                watchStatusService.markWatched(profileId, itemId),
                this::markWatchedError,
                MarkWatchedPayload::new),
        () ->
            MutationPayloads.inputError(
                new MarkWatchedError.InvalidIdError(INVALID_ITEM_ID, InputPath.of(ITEM_ID)),
                MarkWatchedPayload::new));
  }

  private MarkWatchedError markWatchedError(MarkWatchedRejection rejection) {
    return switch (rejection) {
      case MarkWatchedRejection.ItemNotFound _ ->
          new MarkWatchedError.ItemNotFoundError(ITEM_NOT_FOUND, InputPath.of(ITEM_ID));
    };
  }

  @DgsMutation
  public MarkUnwatchedPayload markUnwatched(@InputArgument MarkUnwatchedInput input) {
    var profileId = authorizationService.requireProfile();
    return MutationPayloads.withUuid(
        input.itemId(),
        itemId ->
            MutationPayloads.payload(
                watchStatusService.markUnwatched(profileId, itemId),
                this::markUnwatchedError,
                MarkUnwatchedPayload::new),
        () ->
            MutationPayloads.inputError(
                new MarkUnwatchedError.InvalidIdError(INVALID_ITEM_ID, InputPath.of(ITEM_ID)),
                MarkUnwatchedPayload::new));
  }

  private MarkUnwatchedError markUnwatchedError(MarkUnwatchedRejection rejection) {
    return switch (rejection) {
      case MarkUnwatchedRejection.ItemNotFound _ ->
          new MarkUnwatchedError.ItemNotFoundError(ITEM_NOT_FOUND, InputPath.of(ITEM_ID));
    };
  }

  @DgsTypeResolver(name = "WatchStateItem")
  public String resolveWatchStateItem(Object item) {
    return switch (item) {
      case Movie _ -> "Movie";
      case Episode _ -> "Episode";
      case Season _ -> "Season";
      case Series _ -> "Series";
      default ->
          throw new IllegalArgumentException(
              "Unknown watch state item type: " + item.getClass().getSimpleName());
    };
  }

  private StreamingOptions mapOptions(StreamingOptionsInput input) {
    return Optional.ofNullable(input).map(this::buildOptionsFromInput).orElse(defaultOptions());
  }

  private StreamingOptions defaultOptions() {
    return StreamingOptions.builder().quality(VideoQuality.AUTO).build();
  }

  private StreamingOptions buildOptionsFromInput(StreamingOptionsInput input) {
    var quality =
        Optional.ofNullable(input.quality()).map(VideoQuality::valueOf).orElse(VideoQuality.AUTO);

    return StreamingOptions.builder()
        .quality(quality)
        .maxWidth(input.maxWidth())
        .maxHeight(input.maxHeight())
        .maxBitrate(input.maxBitrate())
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
