package com.streamarr.server.graphql.mutation.streaming;

import lombok.Builder;

@Builder
public record SwitchPlaybackAudioTrackInput(
    String playbackSessionId, String streamSessionId, int audioTrackPosition) {}
