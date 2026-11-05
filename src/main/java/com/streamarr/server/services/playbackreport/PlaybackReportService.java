package com.streamarr.server.services.playbackreport;

import com.streamarr.server.repositories.playbackreport.PlaybackReportRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Pager;
import com.streamarr.server.services.pagination.ServerScope;
import com.streamarr.server.services.playbackreport.MarkPlaybackReportReadRejection.PlaybackReportMissing;
import com.streamarr.server.services.playbackreport.SharePlaybackReportRejection.NoteTooLong;
import com.streamarr.server.services.playbackreport.SharePlaybackReportRejection.PlaybackSessionMissing;
import com.streamarr.server.services.playbackreport.SharePlaybackReportRejection.PlayerFigureInvalid;
import com.streamarr.server.services.streaming.PlaybackSessionRequest;
import com.streamarr.server.services.streaming.PlaybackSessionService;
import com.streamarr.server.services.streaming.PlaybackSessionSnapshot;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Playback reports that viewers share with the Server Admin from the Playback Data panel (#751). A
 * viewer shares a report about a playback session they own; only a ServerAdmin reads reports.
 */
@Service
@RequiredArgsConstructor
public class PlaybackReportService {

  /** The longest note, in Unicode code points. */
  static final int MAXIMUM_NOTE_LENGTH = 280;

  private final AuthorizationService authorizationService;
  private final PlaybackSessionService playbackSessions;
  private final PlaybackReportRepository playbackReportRepository;
  private final PlaybackReportList playbackReportList;
  private final Clock clock;

  /**
   * Stores the viewer's figures and note with the server's decisions for the playback session as
   * they stand now, and its capability declaration. A playback session that the viewer does not
   * own, or whose format attempts do not include the captured stream session, reads as missing. A
   * resent submission returns the report stored for it, even after the playback session closed.
   */
  @Transactional
  public Outcome<UUID, SharePlaybackReportRejection> sharePlaybackReport(
      @NonNull SharePlaybackReportCommand command) {
    authorizationService.requireAllowed(command.identity(), new Intent.SharePlaybackReport());
    var note = command.note().map(String::strip).filter(text -> !text.isEmpty());
    var rejections = new ArrayList<SharePlaybackReportRejection>();
    if (note.filter(PlaybackReportService::isTooLong).isPresent()) {
      rejections.add(new NoteTooLong(MAXIMUM_NOTE_LENGTH));
    }

    PlayerFiguresCheck.invalidFigures(command.player()).stream()
        .map(PlayerFigureInvalid::new)
        .forEach(rejections::add);
    if (!rejections.isEmpty()) {
      return Outcome.rejected(rejections);
    }

    var stored = playbackReportRepository.findIdBySubmission(submissionOf(command));
    if (stored.isPresent()) {
      return Outcome.accepted(stored.get());
    }

    var playback =
        playbackSessions.access(
            PlaybackSessionRequest.builder()
                .playbackSessionId(command.playbackSessionId())
                .identity(command.identity())
                .build());
    var playingChoice =
        playback.map(snapshot -> snapshot.attemptPlaybackChoices().get(command.streamSessionId()));
    if (playingChoice.isEmpty()) {
      return Outcome.rejected(new PlaybackSessionMissing());
    }

    return Outcome.accepted(
        playbackReportRepository.save(
            newReport(command, note, playback.get()).playbackChoice(playingChoice.get()).build()));
  }

  public ListPage<PlaybackReport> playbackReports(
      @NonNull AuthenticatedIdentity identity, @NonNull PageRequest request) {
    authorizationService.requireAllowed(identity, new Intent.ViewPlaybackReports());
    return Pager.page(playbackReportList, ServerScope.SERVER, request);
  }

  public Optional<PlaybackReport> playbackReport(
      @NonNull AuthenticatedIdentity identity, @NonNull UUID playbackReportId) {
    authorizationService.requireAllowed(identity, new Intent.ViewPlaybackReports());
    return playbackReportRepository.findById(playbackReportId);
  }

  public int unreadPlaybackReportCount(@NonNull AuthenticatedIdentity identity) {
    authorizationService.requireAllowed(identity, new Intent.ViewPlaybackReports());
    return playbackReportRepository.countUnread();
  }

  /** A report keeps the time a ServerAdmin first marked it read. */
  @Transactional
  public Outcome<PlaybackReport, MarkPlaybackReportReadRejection> markPlaybackReportRead(
      @NonNull AuthenticatedIdentity identity, @NonNull UUID playbackReportId) {
    authorizationService.requireAllowed(identity, new Intent.MarkPlaybackReportRead());
    return playbackReportRepository
        .markRead(playbackReportId, clock.instant())
        .<Outcome<PlaybackReport, MarkPlaybackReportReadRejection>>map(Outcome::accepted)
        .orElseGet(() -> Outcome.rejected(new PlaybackReportMissing()));
  }

  private NewPlaybackReport.NewPlaybackReportBuilder newReport(
      SharePlaybackReportCommand command, Optional<String> note, PlaybackSessionSnapshot playback) {
    return NewPlaybackReport.builder()
        .sharedAt(clock.instant())
        .profileId(command.identity().profileId())
        .playbackSessionId(playback.playbackSessionId())
        .streamSessionId(command.streamSessionId())
        .submissionId(command.submissionId())
        .mediaFileId(playback.mediaFileId())
        .note(note)
        .requestedChoice(playback.requestedChoice())
        .decisions(playback.decisions())
        .player(command.player())
        .capabilityDeclaration(playback.capabilityDeclaration());
  }

  private static PlaybackReportSubmission submissionOf(SharePlaybackReportCommand command) {
    return PlaybackReportSubmission.builder()
        .profileId(command.identity().profileId())
        .playbackSessionId(command.playbackSessionId())
        .submissionId(command.submissionId())
        .build();
  }

  private static boolean isTooLong(String note) {
    return note.codePointCount(0, note.length()) > MAXIMUM_NOTE_LENGTH;
  }
}
