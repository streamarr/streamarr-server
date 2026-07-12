package com.streamarr.transcode.engine.model;

import java.nio.file.Path;
import lombok.Builder;

@Builder
public record TranscodeJob(TranscodeRequest request, String videoEncoder, Path outputDir) {}
