package com.streamarr.server.graphql.mutation.playbackreport;

import java.util.List;
import java.util.Optional;

public record SharePlaybackReportPayload(
    Optional<String> playbackReportId, List<SharePlaybackReportError> userErrors) {}
