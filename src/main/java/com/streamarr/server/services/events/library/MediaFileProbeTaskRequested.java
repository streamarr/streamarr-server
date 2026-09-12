package com.streamarr.server.services.events.library;

import java.util.UUID;
import lombok.NonNull;

public record MediaFileProbeTaskRequested(@NonNull UUID mediaFileId) {}
