package com.streamarr.server.graphql.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record PlaybackSessionDto(
    String id,
    int formatAttemptCount,
    int maximumFormatAttempts,
    String requestedChoice,
    String playbackChoice,
    String formatChange,
    List<String> offeredChoices,
    PlaybackFormatAttemptDto currentFormatAttempt,
    List<PlaybackDecisionDto> decisions) {}
