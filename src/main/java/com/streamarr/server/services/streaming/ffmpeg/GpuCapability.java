package com.streamarr.server.services.streaming.ffmpeg;

import java.util.Set;
import lombok.Builder;

@Builder
public record GpuCapability(boolean available, Set<String> encoders, String accelerator) {}
