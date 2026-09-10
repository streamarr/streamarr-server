package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.graphql.dto.StreamSessionDto;
import java.util.List;
import java.util.Optional;

public record CreateStreamSessionV2Payload(
    Optional<StreamSessionDto> session, List<CreateStreamSessionV2Error> userErrors) {}
