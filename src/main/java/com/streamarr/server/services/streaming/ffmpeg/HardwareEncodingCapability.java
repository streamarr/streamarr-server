package com.streamarr.server.services.streaming.ffmpeg;

import java.util.Set;
import lombok.Builder;

@Builder
public record HardwareEncodingCapability(
    boolean available, Set<String> encoders, String accelerator) {}
