package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.repositories.media.ArtworkRequestRepositoryContract;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@Tag("UnitTest")
@DisplayName("Fake Artwork Request Repository Tests")
class FakeArtworkRequestRepositoryTest implements ArtworkRequestRepositoryContract {

  private final FakeItemResultRepository itemResults = new FakeItemResultRepository();
  private final FakeArtworkRequestRepository repository =
      new FakeArtworkRequestRepository(itemResults);

  @Override
  public ArtworkRequestRepository repository() {
    return repository;
  }

  @Override
  public ArtworkItem createMovie() {
    return new ArtworkItem(UUID.randomUUID(), ImageEntityType.MOVIE);
  }

  @Override
  public ArtworkItem createPerson() {
    return new ArtworkItem(UUID.randomUUID(), ImageEntityType.PERSON);
  }

  @Override
  public void deleteMovie(ArtworkItem movie) {
    repository.deleteItem(movie);
  }

  @Override
  public void saveResult(ItemResult result) {
    itemResults.overwrite(result);
  }

  @Override
  public <T> T inTransaction(Supplier<T> work) {
    return work.get();
  }
}
