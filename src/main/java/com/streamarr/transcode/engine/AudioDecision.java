package com.streamarr.transcode.engine;

import lombok.Builder;

@Builder
public record AudioDecision(AudioMode mode, String codec, int channels, long bitrate) {}
