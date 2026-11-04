package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.MediaProbe;
import lombok.Builder;
import lombok.NonNull;

/** What the direct-play decision reads: the source, the declaration and the format attempt. */
@Builder
public record DirectPlayRequest(
    @NonNull MediaProbe source,
    @NonNull ClientCapabilityDeclaration capabilityDeclaration,
    @NonNull VideoFormatConstraint videoFormatConstraint,
    @NonNull ViewerLimits viewerLimits) {}
