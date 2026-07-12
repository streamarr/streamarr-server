package com.streamarr.transcode.engine.ffmpeg;

import java.util.Set;
import lombok.Builder;

@Builder
public record HardwareEncodingCapability(
    boolean available, Set<String> encoders, String accelerator) {}
