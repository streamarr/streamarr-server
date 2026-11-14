package com.streamarr.server.services.library.events;

import java.util.UUID;
import lombok.NonNull;

/**
 * The server stored what a keyframe packet scan learned about the media file. The publisher is
 * still inside the transaction that stored it.
 */
public record KeyframePacketScanPublishedEvent(
    @NonNull UUID mediaFileId, @NonNull String filepathUri) {}
