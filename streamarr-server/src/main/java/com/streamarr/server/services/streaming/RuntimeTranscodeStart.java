package com.streamarr.server.services.streaming;

import com.streamarr.transcode.engine.model.TranscodeJobRef;
import java.util.UUID;

public record RuntimeTranscodeStart(UUID sessionId, UUID slotGeneration, TranscodeJobRef jobRef) {}
