package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.repositories.media.MediaFileRepository;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class FakeMediaFileRepository extends FakeJpaRepository<MediaFile>
    implements MediaFileRepository {
  @Override
  public Optional<MediaFile> findFirstByFilepathUri(String filepathUri) {
    return database.values().stream()
        .filter(file -> filepathUri.equals(file.getFilepathUri()))
        .findFirst();
  }

  @Override
  public List<MediaFile> findByMediaId(UUID mediaId) {
    return database.values().stream().filter(file -> mediaId.equals(file.getMediaId())).toList();
  }

  @Override
  public List<MediaFile> findByLibraryId(UUID libraryId) {
    return database.values().stream()
        .filter(file -> libraryId.equals(file.getLibraryId()))
        .toList();
  }

  @Override
  public Set<UUID> findDistinctMediaIdsByMediaIdIn(Collection<UUID> mediaIds) {
    return database.values().stream()
        .map(MediaFile::getMediaId)
        .filter(Objects::nonNull)
        .filter(mediaIds::contains)
        .collect(Collectors.toSet());
  }
}
