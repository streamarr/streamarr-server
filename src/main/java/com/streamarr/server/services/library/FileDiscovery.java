package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.services.ArtworkRun;
import lombok.NonNull;

/** A library whose media files are being matched, with the run that fetches their artwork. */
public record FileDiscovery(@NonNull Library library, @NonNull ArtworkRun artworkRun) {}
