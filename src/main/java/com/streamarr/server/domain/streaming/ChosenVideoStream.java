package com.streamarr.server.domain.streaming;

import lombok.NonNull;

/**
 * The chosen video stream that a probe outcome names: the first video stream that is not an
 * attached picture, with the frame-rate rule's result for it.
 */
public record ChosenVideoStream(int streamIndex, @NonNull FrameRateRule frameRateRule) {}
