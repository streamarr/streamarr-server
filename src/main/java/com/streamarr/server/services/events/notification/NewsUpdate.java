package com.streamarr.server.services.events.notification;

import java.util.Set;
import lombok.NonNull;

/**
 * How a later event of a news kind updates the recipient's unread news of that kind about the
 * subject (ADR 0063 §One unread news per kind, subject and recipient). Either way the news keeps
 * its identity, stays unread and takes the later event's time, and an earlier event that arrives
 * late changes neither its time nor what the later event replaced.
 */
public sealed interface NewsUpdate {

  /** The later event's snapshot replaces the stored one: every kind built now. */
  NewsUpdate REPLACE = new Replace();

  /** The later event's snapshot replaces the stored one. */
  record Replace() implements NewsUpdate {}

  /**
   * The later event's snapshot replaces the stored one, except for the named figures, whole numbers
   * of the snapshot, which each event adds to the stored ones, so the news counts everything since
   * its recipient last read it, whichever event arrives first.
   */
  record AddFigures(@NonNull Set<String> figures) implements NewsUpdate {

    public AddFigures {
      figures = Set.copyOf(figures);
    }
  }
}
