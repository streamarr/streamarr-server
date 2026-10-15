package com.streamarr.server.graphql.mutation.encoding;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.serversettings.ChangeEncoderPresetRejection;

/** The exhaustive mapping from service rejection to schema error type. */
public final class ChangeEncoderPresetErrors {

  private ChangeEncoderPresetErrors() {}

  public static ChangeEncoderPresetError toError(ChangeEncoderPresetRejection rejection) {
    return switch (rejection) {
      case ChangeEncoderPresetRejection.UnknownPreset _ ->
          new ChangeEncoderPresetError.UnknownEncoderPresetError(
              "Choose Automatic or a preset from the list.", InputPath.of("preset"));
    };
  }
}
