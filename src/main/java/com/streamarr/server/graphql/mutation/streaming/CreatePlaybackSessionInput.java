package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.graphql.dto.StreamingOptionsInput;

public record CreatePlaybackSessionInput(
    String mediaFileId,
    StreamingOptionsInput options,
    ClientCapabilityDeclarationInput capabilities) {}
