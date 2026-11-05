package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.domain.streaming.NetworkRoute;
import com.streamarr.server.graphql.dto.StreamingOptionsInput;

public record CreatePlaybackSessionInput(
    String mediaFileId,
    StreamingOptionsInput options,
    ClientCapabilityDeclarationInput capabilities,
    Double startPositionSeconds,
    NetworkRoute networkRoute) {}
