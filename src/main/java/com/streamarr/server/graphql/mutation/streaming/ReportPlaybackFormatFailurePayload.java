package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.graphql.dto.PlaybackSessionDto;
import com.streamarr.server.graphql.mutation.MutationError;
import java.util.List;
import java.util.Optional;

public record ReportPlaybackFormatFailurePayload(
    Optional<PlaybackSessionDto> playbackSession, List<MutationError> userErrors) {}
