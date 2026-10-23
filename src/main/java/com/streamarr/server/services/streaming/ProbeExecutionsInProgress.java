package com.streamarr.server.services.streaming;

import lombok.Builder;

/**
 * The probe executions that the server started and that have not ended.
 *
 * @param executions every probe execution in progress, playback executions included
 * @param playbackExecutions the probe executions in progress that a playback request waits for
 */
@Builder
public record ProbeExecutionsInProgress(int executions, int playbackExecutions) {}
