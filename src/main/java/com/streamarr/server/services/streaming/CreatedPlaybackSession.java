package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.services.auth.AccessToken;
import java.util.UUID;
import lombok.Builder;

@Builder
public record CreatedPlaybackSession(
    UUID sessionId, TranscodeMode transcodeMode, AccessToken playbackToken) {}
