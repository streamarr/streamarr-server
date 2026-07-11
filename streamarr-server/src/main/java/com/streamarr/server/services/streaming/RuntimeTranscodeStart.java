package com.streamarr.server.services.streaming;

import java.util.UUID;

public record RuntimeTranscodeStart(UUID sessionId, UUID generation) {}
