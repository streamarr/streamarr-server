package com.streamarr.server.graphql.inputs;

import com.streamarr.server.services.metadata.ImageRefreshMode;

public record StartLibraryRefreshInput(String libraryId, ImageRefreshMode imageRefreshMode) {}
