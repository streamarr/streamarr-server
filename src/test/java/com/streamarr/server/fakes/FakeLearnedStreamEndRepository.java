package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.LearnedStreamEnd;
import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.repositories.media.LearnedStreamEndRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores learned end times against the stored probe outcomes of the given repository. A fake
 * repository of probe outcomes also stores here each stream end that a published keyframe packet
 * scan read.
 */
public class FakeLearnedStreamEndRepository implements LearnedStreamEndRepository {

  private record StreamKey(UUID mediaFileId, int streamIndex) {}

  private final MediaFileContainerInfoRepository probeOutcomes;
  private final Map<StreamKey, LearnedStreamEnd> learned = new ConcurrentHashMap<>();

  public FakeLearnedStreamEndRepository(MediaFileContainerInfoRepository probeOutcomes) {
    this.probeOutcomes = probeOutcomes;
    if (probeOutcomes instanceof FakeMediaFileContainerInfoRepository fake) {
      fake.storeScannedStreamEndsIn(this);
    }
  }

  /** Stores the end in place of any end stored for the stream, as a keyframe packet scan does. */
  synchronized void replace(LearnedStreamEnd end) {
    learned.put(new StreamKey(end.mediaFileId(), end.streamIndex()), end);
  }

  @Override
  public synchronized boolean store(LearnedStreamEnd end) {
    var storedSnapshot =
        probeOutcomes.findByMediaFileId(end.mediaFileId()).map(MediaFileContainerInfo::getSnapshot);
    if (!storedSnapshot.equals(Optional.of(end.snapshot()))) {
      return false;
    }

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
