package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.streaming.CollectableScope;
import java.util.UUID;
import lombok.NonNull;

/** One Profile's watch state of one media title. */
public record WatchState(
    @NonNull UUID profileId, @NonNull UUID titleId, @NonNull CollectableScope scope) {}
