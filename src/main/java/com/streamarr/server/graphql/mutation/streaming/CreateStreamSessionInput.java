package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.graphql.dto.StreamingOptionsInput;

public record CreateStreamSessionInput(String mediaFileId, StreamingOptionsInput options) {}
