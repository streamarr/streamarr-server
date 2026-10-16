package com.streamarr.server.domain.streaming;

import java.util.List;
import lombok.NonNull;

/** What a probe outcome says about the chapters of the source's container. */
public sealed interface ChapterKnowledge {

  /** The container's chapters in the order that the probe reported them. Empty when it has none. */
  record Known(@NonNull List<Chapter> chapters) implements ChapterKnowledge {

    public Known {
      chapters = List.copyOf(chapters);
    }
  }

  /**
   * The container has chapters, but the probe left every chapter out because together they exceed
   * the probe's chapter limit.
   */
  record Omitted() implements ChapterKnowledge {}

  /**
   * No probe reported the chapters: no outcome is stored, the probe failed, or the outcome's probe
   * version reports no chapters.
   */
  record NotReported() implements ChapterKnowledge {}
}
