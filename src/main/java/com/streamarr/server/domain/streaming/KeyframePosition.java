package com.streamarr.server.domain.streaming;

/**
 * One keyframe of a source's chosen video stream. Both times count ticks of the positions' time
 * base on the zero-based timeline that the producer groups samples on.
 */
public record KeyframePosition(long presentationTime, long decodeTime) {}
