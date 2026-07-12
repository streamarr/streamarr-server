package com.streamarr.transcode.engine.model;

import java.nio.file.Path;
import lombok.Builder;

@Builder
public record RenditionJob(RenditionRequest request, String videoEncoder, Path outputDir) {}
