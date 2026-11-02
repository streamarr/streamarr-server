package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.StreamMeasurementUnavailableReason;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.task.BitrateScanChunkPublication;
import com.streamarr.server.domain.task.BitrateScanInputs;
import com.streamarr.server.domain.task.BitrateScanKey;
import com.streamarr.server.domain.task.StoredBitrateScanChunk;
import com.streamarr.server.domain.task.StreamMeasurementPublication;
import com.streamarr.server.repositories.media.BitrateScanRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Holds what each file's bitrate scan reads, its stored chunks and its result. A file needs a scan
 * while the test has given it inputs and no measurement or unavailable mark holds their key.
 */
public class FakeBitrateScanRepository implements BitrateScanRepository {

  /** A stored chunk with the key it follows. */
  public record Chunk(BitrateScanKey key, StoredBitrateScanChunk chunk) {}

  private final Map<UUID, BitrateScanInputs> inputs = new ConcurrentHashMap<>();
  private final Map<UUID, List<Chunk>> chunks = new ConcurrentHashMap<>();
  private final Map<UUID, StreamMeasurements> measurements = new ConcurrentHashMap<>();
  private final Map<UUID, StreamMeasurementUnavailableReason> unavailable =
      new ConcurrentHashMap<>();
  // The key that the file's measurement or unavailable mark holds.
  private final Map<UUID, BitrateScanKey> finished = new ConcurrentHashMap<>();

  /**
   * Gives the file a stored outcome that needs a bitrate scan with these inputs. Like a stored
   * outcome in PostgreSQL, it deletes the chunks of another snapshot or other keyframe positions.
   */
  public void storeOutcomeNeedingScan(UUID mediaFileId, BitrateScanInputs needed) {
    inputs.put(mediaFileId, needed);
    chunks.computeIfPresent(
        mediaFileId,
        (_, stored) ->
            stored.stream().filter(chunk -> sameOutcome(chunk.key(), needed.key())).toList());
  }

  private static boolean sameOutcome(BitrateScanKey chunk, BitrateScanKey stored) {
    return chunk.snapshot().equals(stored.snapshot())
        && chunk.keyframePositionsDigest().equals(stored.keyframePositionsDigest());
  }

  public void storeChunkFollowing(
      UUID mediaFileId, BitrateScanKey key, StoredBitrateScanChunk chunk) {
    chunks.merge(
        mediaFileId,
        List.of(new Chunk(key, chunk)),
        (stored, added) -> Stream.concat(stored.stream(), added.stream()).toList());
  }

  public List<Chunk> chunks(UUID mediaFileId) {
    return chunks.getOrDefault(mediaFileId, List.of());
  }

  public Optional<StreamMeasurements> measurement(UUID mediaFileId) {
    return Optional.ofNullable(measurements.get(mediaFileId));
  }

  public Optional<StreamMeasurementUnavailableReason> unavailableReason(UUID mediaFileId) {
    return Optional.ofNullable(unavailable.get(mediaFileId));
  }

  @Override
  public Optional<BitrateScanInputs> findBitrateScanInputs(UUID mediaFileId) {
    return Optional.ofNullable(inputs.get(mediaFileId))
        .filter(needed -> !needed.key().equals(finished.get(mediaFileId)));
  }

  @Override
  public Optional<BitrateScanInputs> lockBitrateScanInputs(UUID mediaFileId) {
    return findBitrateScanInputs(mediaFileId);
  }

  @Override
  public boolean storeChunk(BitrateScanChunkPublication publication) {
    var id = publication.mediaFileId();
    var needed = inputs.get(id);
    if (needed == null
        || !sameOutcome(publication.key(), needed.key())
        || chunks(id).stream()
            .anyMatch(
                stored ->
                    stored.chunk().firstKeyframeInterval()
                        == publication.chunk().firstKeyframeInterval())) {
      return false;
    }

    storeChunkFollowing(id, publication.key(), publication.chunk());
    return true;
  }

  @Override
  public List<StoredBitrateScanChunk> findChunks(UUID mediaFileId, BitrateScanKey key) {
    return chunks(mediaFileId).stream()
        .filter(stored -> stored.key().equals(key))
        .map(Chunk::chunk)
        .sorted(Comparator.comparingInt(StoredBitrateScanChunk::firstKeyframeInterval))
        .toList();
  }

  @Override
  public void deleteChunksNotFollowing(UUID mediaFileId, BitrateScanKey key) {
    chunks.computeIfPresent(
        mediaFileId,
        (_, stored) -> stored.stream().filter(chunk -> chunk.key().equals(key)).toList());
  }

  @Override
  public void deleteChunks(UUID mediaFileId) {
    chunks.remove(mediaFileId);
  }

  @Override
  public boolean publishMeasurement(StreamMeasurementPublication publication) {
    if (!holds(publication.mediaFileId(), publication.key())) {
      return false;
    }

    unavailable.remove(publication.mediaFileId());
    measurements.put(publication.mediaFileId(), publication.measurements());
    finished.put(publication.mediaFileId(), publication.key());
    return true;
  }

  @Override
  public boolean markMeasurementUnavailable(
      UUID mediaFileId, BitrateScanKey key, StreamMeasurementUnavailableReason reason) {
    if (!holds(mediaFileId, key)) {
      return false;
    }

    measurements.remove(mediaFileId);
    unavailable.put(mediaFileId, reason);
    finished.put(mediaFileId, key);
    return true;
  }

  private boolean holds(UUID mediaFileId, BitrateScanKey key) {
    var stored = inputs.get(mediaFileId);
    return stored != null && stored.key().equals(key);
  }
}
