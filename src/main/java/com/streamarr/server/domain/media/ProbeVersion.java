package com.streamarr.server.domain.media;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProbeVersion {

  // Version 3 adds the chosen video stream's keyframe positions. ADR 0032 defines version 2 as the
  // dynamic range facts of streamarr/streamarr-server#501, and each version includes the facts of
  // every earlier version.
  public static final int CURRENT = 3;
}
