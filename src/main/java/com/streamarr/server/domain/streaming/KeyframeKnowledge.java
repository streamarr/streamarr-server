package com.streamarr.server.domain.streaming;

import lombok.NonNull;

/** What a probe outcome says about the keyframes of the source's chosen video stream. */
public sealed interface KeyframeKnowledge {

  record Known(@NonNull KeyframePositions positions) implements KeyframeKnowledge {}

  /** The probe read no usable container index. Only a packet scan can find the positions. */
  record PacketScanRequired(@NonNull KeyframePacketScanReason reason)
      implements KeyframeKnowledge {}

  /** The positions cannot be learned. The server never copies such a source. */
  record Unavailable(@NonNull KeyframesUnavailableReason reason) implements KeyframeKnowledge {}

  /** The outcome's probe version does not report keyframe positions. */
  record NotReported() implements KeyframeKnowledge {}
}
