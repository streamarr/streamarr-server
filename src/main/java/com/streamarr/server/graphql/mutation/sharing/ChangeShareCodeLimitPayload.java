package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.services.serversettings.ShareCodeLimitSetting;
import java.util.List;
import java.util.Optional;

public record ChangeShareCodeLimitPayload(
    Optional<ShareCodeLimitSetting> shareCodeLimitSetting,
    List<ChangeShareCodeLimitError> userErrors) {}
