package com.streamarr.server.domain.streaming;

import java.util.OptionalInt;
import java.util.OptionalLong;
import lombok.Builder;

@Builder
public record StreamInfo(
    int index,
    String codecType,
    String codec,
    String language,
    OptionalInt channels,
    OptionalLong bitrate,
    boolean isDefault,
    boolean isForced) {

  public StreamInfo {
    if (channels == null) {
      channels = OptionalInt.empty();
    }
    if (bitrate == null) {
      bitrate = OptionalLong.empty();
    }
  }
}
