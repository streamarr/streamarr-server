package com.streamarr.server.graphql.inputs;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.media.MediaType;

public record AddLibraryInput(
    String name,
    String filepath,
    MediaType type,
    LibraryBackend backend,
    ExternalAgentStrategy externalAgentStrategy) {}
