package com.streamarr.server.fakes;

import com.streamarr.server.repositories.MediaFileContainerInfoRepositoryContract;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@Tag("UnitTest")
@DisplayName("Fake Media File Container Info Repository Tests")
class FakeMediaFileContainerInfoRepositoryTest implements MediaFileContainerInfoRepositoryContract {

  private final FakeMediaFileContainerInfoRepository repository =
      new FakeMediaFileContainerInfoRepository();

  @Override
  public MediaFileContainerInfoRepository repository() {
    return repository;
  }

  // The fake treats every media file id as existing until it is deleted.
  @Override
  public UUID existingMediaFile() {
    return UUID.randomUUID();
  }

  @Override
  public void deleteMediaFile(UUID mediaFileId) {
    repository.deleteMediaFile(mediaFileId);
  }

  @Override
  public <T> T inTransaction(Supplier<T> work) {
    return work.get();
  }
}
