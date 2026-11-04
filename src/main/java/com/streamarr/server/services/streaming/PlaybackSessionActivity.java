package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.PlaybackAuthority;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * What an open playback session plays now, for server administration: whose it is, its media file,
 * its current format attempt, and that attempt's decision. {@code decision} is empty while the
 * current format attempt has not started.
 */
@Builder
public record PlaybackSessionActivity(
    @NonNull UUID playbackSessionId,
    @NonNull PlaybackAuthority authority,
    @NonNull UUID mediaFileId,
    @NonNull PlaybackFormatAttempt currentFormatAttempt,
    @NonNull Optional<PlaybackDecision> decision) {}
