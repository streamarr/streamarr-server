package com.streamarr.server.domain.streaming;

import java.nio.file.Path;
import lombok.Builder;

@Builder
public record TranscodeJob(TranscodeRequest request, String videoEncoder, Path outputDir) {}
