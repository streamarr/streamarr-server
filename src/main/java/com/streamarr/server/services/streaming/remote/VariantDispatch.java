package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import java.util.Optional;
import lombok.NonNull;

/**
 * A variant job that a worker connection accepted. {@code encoder} is the worker-verified encoder
 * that the worker advertises for the job's codec family; it is absent for a copy.
 */
public record VariantDispatch(
    @NonNull ExecutionTargetId target, @NonNull Optional<WorkerVerifiedEncoder> encoder) {}
