package com.streamarr.server.repositories.playbackreport;

import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.playbackreport.NewPlaybackReport;
import com.streamarr.server.services.playbackreport.PlaybackReport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Shared playback reports. Each write runs in the caller's read-write transaction. */
public interface PlaybackReportRepository {

  /**
   * Stores the report with its decisions and returns its id. When the playback session holds a
   * report with the same submission id already, stores nothing and returns that report's id.
   */
  UUID save(NewPlaybackReport report);

  /** Up to {@code seek.limit()} reports beyond the seek's position, ordered by share time. */
  List<Row<PlaybackReport>> rowsBeyond(Seek seek);

  Optional<PlaybackReport> findById(UUID id);

  int countUnread();

  /**
   * Marks the report read at {@code readAt} unless it is read already, and returns it. Empty when
   * no report has that id.
   */
  Optional<PlaybackReport> markRead(UUID id, Instant readAt);

  /** Deletes every report shared before {@code cutoff}, with its decisions. */
  int deleteSharedBefore(Instant cutoff);
}
