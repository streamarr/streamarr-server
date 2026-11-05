package com.streamarr.server.services.streaming;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * The viewer's switch to another audio track during direct play. {@code audioTrackPosition} names
 * the chosen track by its position, counting from 0, among the file's audio tracks in stream order.
 */
@Builder
public record SwitchPlaybackAudioTrackCommand(
    @NonNull UUID playbackSessionId,
    @NonNull UUID streamSessionId,
    @NonNull AuthenticatedIdentity identity,
    int audioTrackPosition) {}
