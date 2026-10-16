package com.streamarr.server.domain.streaming;

import lombok.Builder;

/**
 * The number of pixels that a container crop, such as a Matroska PixelCrop element or an MP4 clap
 * box, removes from each edge of a video stream's picture. The stream's width and height stay the
 * size before cropping.
 */
@Builder
public record ContainerCropping(int top, int bottom, int left, int right) {}
