package com.streamarr.server.services.streaming;

import com.streamarr.server.repositories.streaming.MediaStreamTermination;
import com.streamarr.server.repositories.streaming.PlaybackRequestAuthority;
import com.streamarr.server.repositories.streaming.StreamSessionAuthority;
import com.streamarr.server.repositories.streaming.StreamSessionTermination;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StreamSessionLifecycleTransactions {

  Optional<Instant> admit(StreamSessionAuthority authority, Duration provisioningTimeout);

  boolean activate(StreamSessionAuthority authority, Duration provisioningTimeout);

  Optional<Instant> touchIfPlaybackRequestMatches(PlaybackRequestAuthority authority);

  List<UUID> findTerminatingIds(int limit);

  List<UUID> findTerminatingIdsAfter(UUID afterId, int limit);

  List<UUID> terminalizeByMediaFiles(MediaStreamTermination termination);

  List<UUID> terminalizeMissingMediaSources(Instant terminalAt);

  boolean terminalize(StreamSessionTermination termination);

  boolean recordTerminationIntent(StreamSessionTermination termination);

  List<StreamSessionTermination> findTerminationIntents();

  boolean completeCreation(UUID streamSessionId);

  boolean replayTerminationIntent(UUID streamSessionId);

  boolean deleteTerminationIntent(UUID streamSessionId);

  boolean deleteTerminating(UUID streamSessionId);
}
