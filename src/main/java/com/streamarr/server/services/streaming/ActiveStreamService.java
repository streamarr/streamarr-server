package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The active streams of this server instance, for a ServerAdmin (#751): every playback session that
 * plays or prepares a stream, with no history of ended ones.
 */
@Service
@Builder
@RequiredArgsConstructor
public class ActiveStreamService {

  private final AuthorizationService authorizationService;
  private final PlaybackSessionService playbackSessions;
  private final TranscodeExecutor transcodeExecutor;
  private final ProfileRepository profileRepository;
  private final AuthSessionRepository authSessionRepository;
  private final MediaFileRepository mediaFileRepository;
  private final MovieRepository movieRepository;
  private final EpisodeRepository episodeRepository;

  /** Requires a ServerAdmin on a session that is not device-bound. */
  public void requireViewActiveStreams(@NonNull AuthenticatedIdentity identity) {
    authorizationService.requireAllowed(identity, new Intent.ViewActiveStreams());
  }

  /** Every active stream, for a ServerAdmin on a session that is not device-bound. */
  public List<ActiveStream> activeStreams(@NonNull AuthenticatedIdentity identity) {
    requireViewActiveStreams(identity);
    return describe(
        playbackSessions.activity().stream().filter(ActiveStreamService::isActive).toList());
  }

  /**
   * The playback session as an active stream, or empty once it ended or failed. The caller has
   * already required a ServerAdmin.
   */
  public Optional<ActiveStream> activeStream(@NonNull UUID playbackSessionId) {
    return playbackSessions
        .activity(playbackSessionId)
        .filter(ActiveStreamService::isActive)
        .map(activity -> describe(List.of(activity)).getFirst());
  }

  // A failed playback session plays nothing, though its client has not closed it yet.
  private static boolean isActive(PlaybackSessionActivity activity) {
    return !(activity.currentFormatAttempt() instanceof PlaybackFormatAttempt.Failed);
  }

  private List<ActiveStream> describe(List<PlaybackSessionActivity> activities) {
    var profiles =
        byId(
            profileRepository.findAllById(
                ids(activities, activity -> activity.authority().profileId())));
    var authSessions =
        byId(
            authSessionRepository.findAllById(
                ids(activities, activity -> activity.authority().authSessionId())));
    var media = mediaByMediaFileId(ids(activities, PlaybackSessionActivity::mediaFileId));
    return activities.stream()
        .map(activity -> describe(activity, profiles, authSessions, media))
        .toList();
  }

  private ActiveStream describe(
      PlaybackSessionActivity activity,
      Map<UUID, Profile> profiles,
      Map<UUID, AuthSession> authSessions,
      Map<UUID, BaseCollectable<?>> media) {
    var authority = activity.authority();
    var ready = readySession(activity);
    return ActiveStream.builder()
        .playbackSessionId(activity.playbackSessionId())
        .profileId(authority.profileId())
        .profileName(Optional.ofNullable(profiles.get(authority.profileId())).map(Profile::getName))
        .deviceName(
            Optional.ofNullable(authSessions.get(authority.authSessionId()))
                .map(AuthSession::getDeviceName))
        .mediaFileId(activity.mediaFileId())
        .media(Optional.ofNullable(media.get(activity.mediaFileId())))
        .timeline(ready.map(StreamSession::getPlaybackSnapshot))
        .decision(activity.decision())
        .workers(ready.map(this::workersOf).orElseGet(List::of))
        .build();
  }

  // One read of the media files and one each of their movies and episodes, however many streams.
  private Map<UUID, BaseCollectable<?>> mediaByMediaFileId(List<UUID> mediaFileIds) {
    var mediaIdByMediaFileId =
        mediaFileRepository.findAllById(mediaFileIds).stream()
            .filter(file -> file.getMediaId() != null)
            .collect(Collectors.toMap(MediaFile::getId, MediaFile::getMediaId));
    var mediaIds = mediaIdByMediaFileId.values().stream().distinct().toList();
    Map<UUID, BaseCollectable<?>> mediaById = new HashMap<>();
    movieRepository.findAllById(mediaIds).forEach(movie -> mediaById.put(movie.getId(), movie));
    episodeRepository
        .findAllById(mediaIds)
        .forEach(episode -> mediaById.put(episode.getId(), episode));
    return mediaIdByMediaFileId.entrySet().stream()
        .filter(entry -> mediaById.containsKey(entry.getValue()))
        .collect(Collectors.toMap(Map.Entry::getKey, entry -> mediaById.get(entry.getValue())));
  }

  private static Optional<StreamSession> readySession(PlaybackSessionActivity activity) {
    if (activity.currentFormatAttempt() instanceof PlaybackFormatAttempt.Ready(var session)) {
      return Optional.of(session);
    }

    return Optional.empty();
  }

  private List<String> workersOf(StreamSession session) {
    return session.getVariantHandles().keySet().stream()
        .sorted()
        .map(label -> transcodeExecutor.runningExecutionTarget(session.getSessionId(), label))
        .flatMap(Optional::stream)
        .map(ExecutionTargetId::value)
        .distinct()
        .toList();
  }

  private static List<UUID> ids(
      List<PlaybackSessionActivity> activities, Function<PlaybackSessionActivity, UUID> id) {
    return activities.stream().map(id).distinct().toList();
  }

  private static <T extends BaseAuditableEntity<T>> Map<UUID, T> byId(Collection<T> entities) {
    return entities.stream()
        .collect(Collectors.toMap(BaseAuditableEntity::getId, Function.identity()));
  }
}
