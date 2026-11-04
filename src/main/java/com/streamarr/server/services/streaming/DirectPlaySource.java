package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.SourceContainer;
import java.nio.file.Path;
import lombok.NonNull;

/** The source file that a direct-play stream session serves, and its container. */
public record DirectPlaySource(@NonNull Path path, @NonNull SourceContainer container) {}
