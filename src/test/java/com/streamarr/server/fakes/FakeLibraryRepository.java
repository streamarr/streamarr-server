package com.streamarr.server.fakes;

import com.streamarr.server.domain.Library;
import com.streamarr.server.repositories.LibraryRepository;

public class FakeLibraryRepository extends FakeJpaRepository<Library>
    implements LibraryRepository {

  @Override
  public boolean existsByFilepath(String filepath) {
    return database.values().stream().anyMatch(lib -> lib.getFilepath().equals(filepath));
  }
}
