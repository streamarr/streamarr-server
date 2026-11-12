package com.streamarr.server.domain.streaming;

import java.util.Optional;
import lombok.NonNull;

/** What a probe outcome says about the keyframes of the source's chosen video stream. */
public sealed interface KeyframeKnowledge {

  /**
   * The positions that a stream copy, a seek and a stream measurement follow: known positions, or
   * the positions kept from an earlier keyframe packet scan. Empty when the outcome has none.
   */
  default Optional<KeyframePositions> usablePositions() {
    return switch (this) {
      case Known(var positions) -> Optional.of(positions);
      case PacketScanRequiredWithKeptPositions(_, var keptPositions) -> Optional.of(keptPositions);
      case PacketScanRequired _, Unavailable _, NotReported _ -> Optional.empty();
    };
  }

  record Known(@NonNull KeyframePositions positions) implements KeyframeKnowledge {}

  /** The probe read no usable container index. Only a packet scan can find the positions. */
  record PacketScanRequired(@NonNull KeyframePacketScanReason reason)
      implements KeyframeKnowledge {}

  /**
   * The probe read no usable container index, so its own keyframe packet scan is still required.
   * Until that scan stores a result, the positions that an earlier keyframe packet scan read from
   * the same source snapshot and chosen video stream stand in for it.
   */
  record PacketScanRequiredWithKeptPositions(
      @NonNull KeyframePacketScanReason reason, @NonNull KeyframePositions keptPositions)
      implements KeyframeKnowledge {}

  /** The positions cannot be learned. The server never copies such a source. */
  record Unavailable(@NonNull KeyframesUnavailableReason reason) implements KeyframeKnowledge {}

  /** The outcome's probe version does not report keyframe positions. */
  record NotReported() implements KeyframeKnowledge {}
}
