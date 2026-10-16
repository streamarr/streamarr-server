package com.streamarr.server.domain.media;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProbeVersion {

  // Version 3 adds the chosen video stream's keyframe positions. Version 4 adds the dynamic range
  // facts of streamarr/streamarr-server#501 and the other facts that ADR 0032 lists for version 4.
  // Each version includes the facts of every earlier version. Version 2 is unused.
  public static final int CURRENT = 3;
}
