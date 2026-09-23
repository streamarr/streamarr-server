package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.repositories.media.ItemResultRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class FakeItemResultRepository implements ItemResultRepository {

  private record Key(UUID itemId, ImageEntityType itemType, ItemStep step, ImageType imageType) {}

  private final Map<Key, ItemResult> results = new ConcurrentHashMap<>();
  private final AtomicReference<RuntimeException> recordFailure = new AtomicReference<>();

  public void failRecordsWith(RuntimeException failure) {
    recordFailure.set(failure);
  }

  @Override
  public boolean tryRecord(ItemResult result) {
    var failure = recordFailure.get();
    if (failure != null) {
      throw failure;
    }

    var recorded = new AtomicBoolean();
    results.compute(
        new Key(result.itemId(), result.itemType(), result.step(), result.imageType()),
        (_, existing) -> {
          if (existing != null && existing.attemptedAt().isAfter(result.attemptedAt())) {
            return existing;
          }

          recorded.set(true);
          return result;
        });
    return recorded.get();
  }

  @Override
  public void record(ItemResult result) {
    var failure = recordFailure.get();
    if (failure != null) {
      throw failure;
    }

    results.put(
        new Key(result.itemId(), result.itemType(), result.step(), result.imageType()), result);
  }

  @Override
  public List<ItemResult> findByItem(UUID itemId, ImageEntityType itemType) {
    return results.values().stream()
        .filter(result -> result.itemId().equals(itemId) && result.itemType() == itemType)
        .toList();
  }

  public Optional<ItemResult> find(UUID itemId, ItemStep step, ImageType imageType) {
    return results.values().stream()
        .filter(result -> result.itemId().equals(itemId))
        .filter(result -> result.step() == step && result.imageType() == imageType)
        .findFirst();
  }
}
