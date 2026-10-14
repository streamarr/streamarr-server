package com.streamarr.server.services.serversettings;

import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.EncoderPresetChoice;
import lombok.NonNull;

/** The preset that Automatic resolves to for each video codec that has a software encoder. */
public record AutomaticEncoderPresets(
    @NonNull EncoderPreset h264, @NonNull EncoderPreset hevc, @NonNull EncoderPreset av1) {

  static AutomaticEncoderPresets current() {
    return new AutomaticEncoderPresets(
        EncoderPresetChoice.AUTOMATIC.resolveFor("h264"),
        EncoderPresetChoice.AUTOMATIC.resolveFor("hevc"),
        EncoderPresetChoice.AUTOMATIC.resolveFor("av1"));
  }
}
