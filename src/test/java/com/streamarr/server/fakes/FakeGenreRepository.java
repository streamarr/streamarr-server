package com.streamarr.server.fakes;

import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.repositories.GenreRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Setter;
import org.apache.commons.lang3.NotImplementedException;

public class FakeGenreRepository extends FakeJpaRepository<Genre> implements GenreRepository {

  @Setter private boolean simulateConflict;

  @Override
  public boolean insertOnConflictDoNothing(String sourceId, String name) {
    if (simulateConflict) {
      save(Genre.builder().sourceId(sourceId).name(name).build());
      return false;
    }
    boolean exists =
        database.values().stream().anyMatch(g -> sourceId.equals(g.getSourceId()));
    if (exists) {
      return false;
    }
    save(Genre.builder().sourceId(sourceId).name(name).build());
    return true;
  }

  @Override
  public Optional<Genre> findBySourceId(String sourceId) {
    return database.values().stream()
        .filter(genre -> sourceId.equals(genre.getSourceId()))
        .findFirst();
  }

  @Override
  public List<Genre> findByMovieId(UUID movieId) {
    throw new NotImplementedException();
  }

  @Override
  public List<Genre> findBySeriesId(UUID seriesId) {
    throw new NotImplementedException();
  }
}
