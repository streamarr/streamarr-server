package com.streamarr.server.domain.streaming.headroom;

import lombok.Builder;
import lombok.NonNull;

/**
 * What a kept calibration describes: the worker's hardware, its CPU limit and its FFmpeg build. A
 * worker that registers with the same key starts from the kept calibration (ADR 0044).
 */
@Builder
public record CalibrationKey(
    @NonNull WorkerHardware hardware, @NonNull CpuLimit cpuLimit, @NonNull String ffmpegBuild) {}
