package com.streamarr.server.domain.streaming;

public record TranscodeHandle(long processId, TranscodeStatus status) {}
