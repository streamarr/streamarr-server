package com.streamarr.transcode.engine.ffmpeg;

import com.streamarr.transcode.engine.model.TranscodeJobRef;

public record FfmpegProcessKey(TranscodeJobRef jobRef, String renditionLabel) {}
