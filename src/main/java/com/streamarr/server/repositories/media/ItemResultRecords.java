package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.jooq.generated.tables.records.ItemResultRecord;
import java.util.Optional;

/** Reads stored item result rows as domain results. */
final class ItemResultRecords {

  private ItemResultRecords() {}

  static ItemResult toDomain(ItemResultRecord row) {
    var result =
        ItemResult.builder()
            .itemId(row.getItemId())
            .itemType(MediaEnumMapping.toDomain(row.getItemType()))
            .step(ItemStep.valueOf(row.getStep().getLiteral()))
            .outcome(toOutcome(row))
            .sourceKey(row.getSourceKey())
            .attemptedAt(row.getAttemptedAt().toInstant())
            .artworkGeneration(row.getArtworkGeneration())
            .libraryOperationId(Optional.ofNullable(row.getLibraryOperationId()));
    if (row.getImageType() != null) {
      result.imageType(MediaEnumMapping.toDomain(row.getImageType()));
    }

    return result.build();
  }

  private static ItemOutcome toOutcome(ItemResultRecord row) {
    return switch (row.getOutcome()) {
      case SUCCEEDED -> new ItemOutcome.Succeeded();
      case UNAVAILABLE -> new ItemOutcome.Unavailable();
      case FAILED ->
          new ItemOutcome.Failed(
              ItemFailureReason.valueOf(row.getFailureReason().getLiteral()), row.getDetail());
    };
  }
}
