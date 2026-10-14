package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.ArtworkRequest.ARTWORK_REQUEST;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fixtures.SavedMediaFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.PersonRepository;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Artwork Request Repository Contract Integration Tests")
class ArtworkRequestRepositoryContractIT extends AbstractIntegrationTest
    implements ArtworkRequestRepositoryContract {

  @Autowired private ArtworkRequestRepository repository;
  @Autowired private ItemResultRepository itemResults;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private PersonRepository personRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  // Pending-image counts cover every stored request.
  @BeforeEach
  void deleteStoredRequests() {
    dsl.deleteFrom(ARTWORK_REQUEST).execute();
  }

  @Override
  public ArtworkRequestRepository repository() {
    return repository;
  }

  @Override
  public ArtworkItem createMovie() {
    var movie = SavedMediaFixture.saveMovie(libraryRepository, movieRepository);
    return new ArtworkItem(movie.getId(), ImageEntityType.MOVIE);
  }

  @Override
  public ArtworkItem createPerson() {
    var person =
        personRepository.saveAndFlush(
            Person.builder()
                .name("Requested Person")
                .sourceId(UUID.randomUUID().toString())
                .build());
    return new ArtworkItem(person.getId(), ImageEntityType.PERSON);
  }

  @Override
  public void deleteMovie(ArtworkItem movie) {
    movieRepository.deleteById(movie.itemId());
  }

  @Override
  public void saveResult(ItemResult result) {
    itemResults.overwrite(result);
  }

  @Override
  public <T> T inTransaction(Supplier<T> work) {
    return transactionTemplate.execute(_ -> work.get());
  }
}
