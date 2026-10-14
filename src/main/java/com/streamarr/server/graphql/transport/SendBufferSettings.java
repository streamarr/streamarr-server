package com.streamarr.server.graphql.transport;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executor;
import lombok.Builder;
import lombok.NonNull;
import org.springframework.util.unit.DataSize;

/**
 * How {@link SendBufferedWebSocketHandler} writes a connection's frames.
 *
 * @param sendExecutor runs the writes, so the GraphQL handler never waits on a client's socket
 * @param capacity the unsent bytes one connection may hold before it closes with 4101
 * @param meterRegistry counts the connections closed because their client fell behind
 */
@Builder
record SendBufferSettings(
    @NonNull Executor sendExecutor,
    @NonNull DataSize capacity,
    @NonNull MeterRegistry meterRegistry) {}
