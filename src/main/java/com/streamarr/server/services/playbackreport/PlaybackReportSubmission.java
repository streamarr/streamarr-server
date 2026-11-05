package com.streamarr.server.services.playbackreport;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/** One press of Share, which the client names with its submission id and resends unchanged. */
@Builder(toBuilder = true)
public record PlaybackReportSubmission(
    @NonNull UUID profileId, @NonNull UUID playbackSessionId, @NonNull UUID submissionId) {}
