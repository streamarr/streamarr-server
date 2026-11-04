package com.streamarr.server.domain.streaming;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A sample entry that names a video stream's coding format in an MP4 or MOV file (ADR 0045). A
 * video capability entry that lists MP4 or MOV for direct play lists the sample entries that its
 * player opens.
 */
@Getter
@RequiredArgsConstructor
public enum VideoSampleEntry {
  AVC1("avc1"),
  AVC3("avc3"),
  HVC1("hvc1"),
  HEV1("hev1"),
  DVA1("dva1"),
  DVAV("dvav"),
  DVH1("dvh1"),
  DVHE("dvhe"),
  AV01("av01"),
  DAV1("dav1");

  /** The four characters that the file stores, as probe version 7 reports them. */
  private final String label;
}
