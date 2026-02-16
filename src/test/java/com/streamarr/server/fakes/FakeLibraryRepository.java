package com.streamarr.server.fakes;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.repositories.LibraryRepository;
import java.util.List;

public class FakeLibraryRepository extends FakeJpaRepository<Library> implements LibraryRepository {

  @Override
  public boolean existsByFilepathUri(String filepathUri) {
    return database.values().stream().anyMatch(lib -> lib.getFilepathUri().equals(filepathUri));
  }

  @Override
  public List<Library> findAllByStatus(LibraryStatus status) {
    return database.values().stream().filter(lib -> lib.getStatus() == status).toList();
  }
}
