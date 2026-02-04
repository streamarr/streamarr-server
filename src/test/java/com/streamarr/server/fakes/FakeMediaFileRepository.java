package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.repositories.media.MediaFileRepository;
import java.util.Optional;

public class FakeMediaFileRepository extends FakeJpaRepository<MediaFile>
    implements MediaFileRepository {
  @Override
  public Optional<MediaFile> findFirstByFilepath(String filepath) {
    return database.values().stream()
        .filter(file -> filepath.equals(file.getFilepath()))
        .findFirst();
  }
}
