package com.streamarr.server.services.serversettings;

import com.streamarr.server.domain.streaming.EncoderPresetChoice;
import lombok.NonNull;

/** The stored preset choice, together with what Automatic uses for each codec. */
public record EncoderPresetSetting(
    @NonNull EncoderPresetChoice preset, @NonNull AutomaticEncoderPresets automatic) {

  static EncoderPresetSetting of(@NonNull EncoderPresetChoice preset) {
    return new EncoderPresetSetting(preset, AutomaticEncoderPresets.current());
  }
}
