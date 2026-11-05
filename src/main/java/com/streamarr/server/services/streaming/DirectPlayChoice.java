package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.SourceContainer;
import java.util.List;
import lombok.NonNull;

/** Whether a format attempt direct-plays the source file (ADR 0045), and why it does not. */
public sealed interface DirectPlayChoice {

  /**
   * Each check that the file fails, in the order the decision makes them. Empty when the format
   * attempt direct-plays the file, and when it may not direct-play at all.
   */
  List<DecisionReason> refusals();

  /** The format attempt direct-plays the source file from its source container. */
  record Accepted(@NonNull SourceContainer container) implements DirectPlayChoice {

    @Override
    public List<DecisionReason> refusals() {
      return List.of();
    }
  }

  /** The format attempt plays over HLS. */
  record Refused(@NonNull List<DecisionReason> refusals) implements DirectPlayChoice {

    public Refused {
      refusals = List.copyOf(refusals);
    }
  }
}
