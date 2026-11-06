package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.LearnedStreamEnd;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.repositories.media.LearnedStreamEndRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FakeLearnedStreamEndRepository implements LearnedStreamEndRepository {

  private record StreamKey(UUID mediaFileId, int streamIndex) {}

  private final Map<StreamKey, LearnedStreamEnd> learned = new ConcurrentHashMap<>();

  @Override
  public synchronized boolean store(LearnedStreamEnd end) {
    var key = new StreamKey(end.mediaFileId(), end.streamIndex());
    var existing = learned.get(key);
    if (existing != null && existing.snapshot().equals(end.snapshot())) {
      return false;
    }

    learned.put(key, end);
    return true;
  }

  @Override
  public List<LearnedStreamEnd> findFor(UUID mediaFileId, SourceFileSnapshot snapshot) {
    return learned.values().stream()
        .filter(end -> end.mediaFileId().equals(mediaFileId))
        .filter(end -> end.snapshot().equals(snapshot))
        .toList();
  }
}
