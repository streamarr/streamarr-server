package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamingOptions;
import lombok.Builder;
import lombok.NonNull;

/**
 * What the ladder of an encode is built from: the source's probe, the viewer's options, the client
 * capability declaration whose entries each variant fits, the codec profile that the encode forces,
 * and the source's output frame rate.
 */
@Builder
public record QualityLadderRequest(
    @NonNull MediaProbe probe,
    @NonNull StreamingOptions options,
    @NonNull ClientCapabilityDeclaration capabilityDeclaration,
    @NonNull EncodeCodecProfile codecProfile,
    @NonNull Fraction frameRate) {}
