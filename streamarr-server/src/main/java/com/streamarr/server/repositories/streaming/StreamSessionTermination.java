package com.streamarr.server.repositories.streaming;

import com.streamarr.server.domain.streaming.StreamSessionTerminalReason;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record StreamSessionTermination(
    UUID streamSessionId, StreamSessionTerminalReason reason, Instant terminalAt) {}
