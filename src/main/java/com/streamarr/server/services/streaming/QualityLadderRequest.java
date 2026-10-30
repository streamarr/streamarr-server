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
 * the source's output frame rate, and the stream session's audio bitrate, which every variant's
 * cost includes because every variant shares the one audio rendition (ADR 0043).
 */
@Builder
public record QualityLadderRequest(
    @NonNull MediaProbe probe,
    @NonNull StreamingOptions options,
    @NonNull ClientCapabilityDeclaration capabilityDeclaration,
    @NonNull EncodeCodecProfile codecProfile,
    @NonNull Fraction frameRate,
    long audioBitrate) {}
