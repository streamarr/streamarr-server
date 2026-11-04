package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.services.serversettings.IdleSegmentRetentionSetting;
import java.util.List;
import java.util.Optional;

public record ChangeIdleSegmentRetentionPayload(
    Optional<IdleSegmentRetentionSetting> idleSegmentRetentionSetting,
    List<ChangeIdleSegmentRetentionError> userErrors) {}
