package com.streamarr.server.services.streaming;

import com.streamarr.server.services.auth.AccessToken;
import com.streamarr.transcode.engine.model.TranscodeMode;
import java.util.UUID;
import lombok.Builder;

@Builder
public record CreatedPlaybackSession(
    UUID sessionId, TranscodeMode transcodeMode, AccessToken playbackToken) {}
