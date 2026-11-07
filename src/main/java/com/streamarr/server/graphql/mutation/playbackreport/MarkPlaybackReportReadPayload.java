package com.streamarr.server.graphql.mutation.playbackreport;

import com.streamarr.server.graphql.dto.PlaybackReportDto;
import java.util.List;
import java.util.Optional;

public record MarkPlaybackReportReadPayload(
    Optional<PlaybackReportDto> playbackReport, List<MarkPlaybackReportReadError> userErrors) {}
