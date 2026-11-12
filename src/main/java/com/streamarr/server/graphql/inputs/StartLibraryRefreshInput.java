package com.streamarr.server.graphql.inputs;

import com.streamarr.server.domain.media.ImageRefreshMode;

public record StartLibraryRefreshInput(String libraryId, ImageRefreshMode imageRefreshMode) {}
