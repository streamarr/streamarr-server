package com.streamarr.server.repositories.streaming;

import com.streamarr.server.domain.streaming.StreamSessionTerminalReason;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;

@Builder
public record MediaStreamTermination(
    Set<UUID> mediaFileIds, StreamSessionTerminalReason reason, Instant terminalAt) {}
