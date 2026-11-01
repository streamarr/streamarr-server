package com.streamarr.server.services.streaming;

import lombok.NonNull;

/** Why a playback session's current format attempt failed, and whether it copied the video. */
record FailedFormatAttempt(@NonNull PlaybackSessionFailure reason, boolean copiedVideo) {}
