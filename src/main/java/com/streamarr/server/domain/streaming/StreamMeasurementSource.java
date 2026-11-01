package com.streamarr.server.domain.streaming;

/** The step that measured a file's streams (ADR 0054). */
public enum StreamMeasurementSource {
  SAMPLE_TABLE,
  KEYFRAME_PACKET_SCAN,
  BITRATE_SCAN
}
