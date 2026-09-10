package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.graphql.dto.StreamingOptionsInput;

public record CreateStreamSessionV2Input(String mediaFileId, StreamingOptionsInput options) {}
