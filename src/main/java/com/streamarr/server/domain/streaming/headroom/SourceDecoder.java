package com.streamarr.server.domain.streaming.headroom;

import lombok.NonNull;

/** The source's codec family and decode backend that select a job attempt's decoder constant. */
public record SourceDecoder(@NonNull String codecFamily, @NonNull DecodeBackend backend)
    implements CostConstant {}
