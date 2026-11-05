package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.services.serversettings.UploadBudgetSetting;
import java.util.List;
import java.util.Optional;

public record ChangeUploadBudgetPayload(
    Optional<UploadBudgetSetting> uploadBudgetSetting, List<ChangeUploadBudgetError> userErrors) {}
