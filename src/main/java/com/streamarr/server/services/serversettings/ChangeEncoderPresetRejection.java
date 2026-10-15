package com.streamarr.server.services.serversettings;

/**
 * Every expected reason {@code changeEncoderPreset} refuses; each maps to one schema error type.
 */
public sealed interface ChangeEncoderPresetRejection {

  record UnknownPreset() implements ChangeEncoderPresetRejection {}
}
