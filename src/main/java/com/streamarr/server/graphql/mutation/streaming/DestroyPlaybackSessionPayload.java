package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.graphql.mutation.MutationError;
import java.util.List;
import java.util.Optional;

public record DestroyPlaybackSessionPayload(
    Optional<String> playbackSessionId, List<MutationError> userErrors) {}
