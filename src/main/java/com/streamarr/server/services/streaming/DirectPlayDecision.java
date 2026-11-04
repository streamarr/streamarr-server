package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.SourceContainer;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * The stream decision of a stream session that direct-plays the source file (ADR 0045). {@code
 * audioTrackPosition} names the audio track that a player that selects audio tracks selects: its
 * position, counting from 0, among the file's audio tracks in stream order. It is empty for a file
 * without audio.
 */
@Builder
public record DirectPlayDecision(
    @NonNull SourceContainer sourceContainer, @NonNull OptionalInt audioTrackPosition) {}
