package com.streamarr.server.graphql.mutation.encoding;

import com.streamarr.server.services.serversettings.EncoderPresetSetting;
import java.util.List;
import java.util.Optional;

public record ChangeEncoderPresetPayload(
    Optional<EncoderPresetSetting> encoderPresetSetting,
    List<ChangeEncoderPresetError> userErrors) {}
