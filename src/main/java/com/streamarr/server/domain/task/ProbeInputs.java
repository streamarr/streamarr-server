package com.streamarr.server.domain.task;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import lombok.NonNull;

/** The most recently requested source snapshot and probe version for a media file. */
public record ProbeInputs(@NonNull SourceFileSnapshot snapshot, int probeVersion) {}
