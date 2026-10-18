package com.streamarr.server.services.streaming;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record PlaybackSessionSnapshot(
    @NonNull UUID playbackSessionId,
    int formatAttemptCount,
    int maximumFormatAttempts,
    @NonNull PlaybackFormatAttempt currentFormatAttempt) {}
