package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.services.streaming.PlaybackFormatFailure;
import lombok.Builder;

@Builder
public record ReportPlaybackFormatFailureInput(
    String playbackSessionId, String streamSessionId, PlaybackFormatFailure reason) {}
