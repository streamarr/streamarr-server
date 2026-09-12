package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.graphql.dto.StreamSessionDto;
import java.util.List;
import java.util.Optional;

public record CreateStreamSessionPayload(
    Optional<StreamSessionDto> session, List<CreateStreamSessionError> userErrors) {}
