package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamingOptions;
import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record TranscodeDecisionRequest(
    @NonNull MediaProbe source,
    @NonNull StreamingOptions clientOptions,
    @NonNull Set<String> encodableVideoCodecFamilies) {}
