package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.services.streaming.ExecutionTargetId;
import lombok.NonNull;

/** A variant job that a worker connection accepted. */
public record VariantDispatch(@NonNull ExecutionTargetId target) {}
