package com.streamarr.server.services.library;

import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.repositories.LibraryMetadataRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LibraryCountsService {

  private final LibraryMetadataRepository metadataRepository;

  @Transactional(readOnly = true)
  public Map<UUID, Counts> getItemCounts(Collection<UUID> libraryIds) {
    var buckets =
        metadataRepository.findByLibraryIdIn(libraryIds).stream()
            .collect(Collectors.groupingBy(LibraryMetadata::getLibraryId));
    return libraryIds.stream()
        .collect(
            Collectors.toMap(
                Function.identity(),
                id ->
                    new Counts(
                        buckets.getOrDefault(id, List.of()).stream()
                            .sorted(Comparator.comparing(LibraryMetadata::getLetter))
                            .toList())));
  }

  public record Counts(List<LibraryMetadata> alphabetIndex) {
    public Counts {
      alphabetIndex = List.copyOf(alphabetIndex);
    }

    public int total() {
      return alphabetIndex.stream().mapToInt(LibraryMetadata::getItemCount).sum();
    }
  }
}
