package com.streamarr.server.repositories;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Media File Container Info Repository Contract Integration Tests")
class MediaFileContainerInfoRepositoryContractIT extends AbstractIntegrationTest
    implements MediaFileContainerInfoRepositoryContract {

  @Autowired private MediaFileContainerInfoRepository repository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private final List<MediaFile> createdFiles = new ArrayList<>();
  private final List<Library> createdLibraries = new ArrayList<>();

  @AfterEach
  void removeCreatedFiles() {
    mediaFileRepository.deleteAll(createdFiles);
    libraryRepository.deleteAll(createdLibraries);
  }

  @Override
  public MediaFileContainerInfoRepository repository() {
    return repository;
  }

  @Override
  public UUID existingMediaFile() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    createdLibraries.add(library);
    var file =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename("probe.mkv")
                .filepathUri("file:///library/" + UUID.randomUUID() + ".mkv")
                .size(1234)
                .build());
    createdFiles.add(file);
    return file.getId();
  }

  @Override
  public void deleteMediaFile(UUID mediaFileId) {
    mediaFileRepository.deleteById(mediaFileId);
  }

  @Override
  public <T> T inTransaction(Supplier<T> work) {
    return new TransactionTemplate(transactionManager).execute(_ -> work.get());
  }
}
