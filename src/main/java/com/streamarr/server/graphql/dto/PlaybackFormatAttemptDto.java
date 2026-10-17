package com.streamarr.server.graphql.dto;

import com.streamarr.server.graphql.mutation.MutationError;

public sealed interface PlaybackFormatAttemptDto {

  record PendingPlaybackFormatAttempt(String streamSessionId) implements PlaybackFormatAttemptDto {}

  record ReadyPlaybackFormatAttempt(StreamSessionDto streamSession)
      implements PlaybackFormatAttemptDto {}

  record FailedPlaybackFormatAttempt(String streamSessionId, MutationError error)
      implements PlaybackFormatAttemptDto {}
}
