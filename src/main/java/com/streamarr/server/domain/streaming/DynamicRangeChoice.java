package com.streamarr.server.domain.streaming;

/**
 * The viewer's HDR choice in a fixed quality (ADR 0013). A copy keeps the source's range, so SDR
 * for an HDR source needs an encode. Auto and Original take no choice: Auto keeps the source's
 * range when the client declares it, and Original always plays the source's range.
 */
public enum DynamicRangeChoice {
  HDR,
  SDR
}
