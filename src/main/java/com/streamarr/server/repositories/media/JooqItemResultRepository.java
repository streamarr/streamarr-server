package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.ItemResult.ITEM_RESULT;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.jooq.generated.Keys;
import com.streamarr.server.jooq.generated.enums.ItemResultFailureReason;
import com.streamarr.server.jooq.generated.enums.ItemResultOutcome;
import com.streamarr.server.jooq.generated.enums.ItemResultStep;
import com.streamarr.server.jooq.generated.tables.records.ItemResultRecord;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JooqItemResultRepository implements ItemResultRepository {

  private final DSLContext dsl;

  @Override
  @Transactional
  public boolean tryRecord(ItemResult result) {
    var row = toRecord(result);

    return dsl.insertInto(ITEM_RESULT)
            .set(row)
            .onConflictOnConstraint(Keys.ITEM_RESULT_IDENTITY)
            .doUpdate()
            .set(ITEM_RESULT.OUTCOME, DSL.excluded(ITEM_RESULT.OUTCOME))
            .set(ITEM_RESULT.FAILURE_REASON, DSL.excluded(ITEM_RESULT.FAILURE_REASON))
            .set(ITEM_RESULT.DETAIL, DSL.excluded(ITEM_RESULT.DETAIL))
            .set(ITEM_RESULT.SOURCE_KEY, DSL.excluded(ITEM_RESULT.SOURCE_KEY))
            .set(ITEM_RESULT.ATTEMPTED_AT, DSL.excluded(ITEM_RESULT.ATTEMPTED_AT))
            .set(ITEM_RESULT.RECORDED_AT, DSL.currentOffsetDateTime())
            .where(ITEM_RESULT.ATTEMPTED_AT.le(DSL.excluded(ITEM_RESULT.ATTEMPTED_AT)))
            .execute()
        > 0;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ItemResult> findByItem(UUID itemId, ImageEntityType itemType) {
    return dsl.selectFrom(ITEM_RESULT)
        .where(ITEM_RESULT.ITEM_ID.eq(itemId))
        .and(ITEM_RESULT.ITEM_TYPE.eq(toJooq(itemType)))
        .fetch(JooqItemResultRepository::toDomain);
  }

  private ItemResultRecord toRecord(ItemResult result) {
    var row = dsl.newRecord(ITEM_RESULT);
    row.setItemId(result.itemId());
    row.setItemType(toJooq(result.itemType()));
    row.setStep(ItemResultStep.lookupLiteral(result.step().name()));
    if (result.imageType() != null) {
      row.setImageType(toJooq(result.imageType()));
    }

    row.setSourceKey(result.sourceKey());
    row.setAttemptedAt(result.attemptedAt().atOffset(ZoneOffset.UTC));
    switch (result.outcome()) {
      case ItemOutcome.Succeeded _ -> row.setOutcome(ItemResultOutcome.SUCCEEDED);
      case ItemOutcome.Unavailable _ -> row.setOutcome(ItemResultOutcome.UNAVAILABLE);
      case ItemOutcome.Failed(var reason, var detail) -> {
        row.setOutcome(ItemResultOutcome.FAILED);
        row.setFailureReason(ItemResultFailureReason.lookupLiteral(reason.name()));
        row.setDetail(detail);
      }
    }

    return row;
  }

  // The generated enums share names with the domain enums they mirror.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.ImageEntityType toJooq(
      ImageEntityType itemType) {
    return com.streamarr.server.jooq.generated.enums.ImageEntityType.lookupLiteral(itemType.name());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.ImageType toJooq(ImageType imageType) {
    return com.streamarr.server.jooq.generated.enums.ImageType.lookupLiteral(imageType.name());
  }

  private static ItemResult toDomain(ItemResultRecord row) {
    var result =
        ItemResult.builder()
            .itemId(row.getItemId())
            .itemType(ImageEntityType.valueOf(row.getItemType().getLiteral()))
            .step(ItemStep.valueOf(row.getStep().getLiteral()))
            .outcome(toOutcome(row))
            .sourceKey(row.getSourceKey())
            .attemptedAt(row.getAttemptedAt().toInstant());
    if (row.getImageType() != null) {
      result.imageType(ImageType.valueOf(row.getImageType().getLiteral()));
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
