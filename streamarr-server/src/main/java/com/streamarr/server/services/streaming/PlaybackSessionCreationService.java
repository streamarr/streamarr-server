package com.streamarr.server.services.streaming;

public interface PlaybackSessionCreationService {

  CreatedPlaybackSession create(CreatePlaybackSessionCommand command);
}
