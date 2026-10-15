package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.repositories.media.ArtworkResultsContract;
import com.streamarr.server.repositories.media.ItemResultRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@Tag("UnitTest")
@DisplayName("Fake Item Result Repository Tests")
class FakeItemResultRepositoryTest implements ArtworkResultsContract {

  private final FakeItemResultRepository itemResults = new FakeItemResultRepository();
  private final AtomicLong lastGeneration = new AtomicLong();

  @Override
  public ItemResultRepository itemResults() {
    return itemResults;
  }

  @Override
  public ArtworkItem createMovie() {
    return new ArtworkItem(UUID.randomUUID(), ImageEntityType.MOVIE);
  }

  @Override
  public long requestArtwork(ArtworkItem movie) {
    return lastGeneration.incrementAndGet();
  }
}
