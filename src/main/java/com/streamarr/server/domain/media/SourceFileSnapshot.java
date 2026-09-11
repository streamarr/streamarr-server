package com.streamarr.server.domain.media;

import java.time.Instant;
import lombok.NonNull;

public record SourceFileSnapshot(long size, @NonNull Instant modifiedAt) {}
