package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.ItemResult.ITEM_RESULT;
import static com.streamarr.server.repositories.media.MediaEnumMapping.toDomain;
import static com.streamarr.server.repositories.media.MediaEnumMapping.toJooq;

import com.streamarr.server.config.ArtworkProperties;
import com.streamarr.server.domain.media.ArtworkAttempt;
import com.streamarr.server.domain.media.ArtworkOutcomeTally;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.jooq.generated.Keys;
import com.streamarr.server.jooq.generated.enums.ItemResultFailureReason;
import com.streamarr.server.jooq.generated.enums.ItemResultOutcome;
import com.streamarr.server.jooq.generated.enums.ItemResultStep;
import com.streamarr.server.jooq.generated.tables.records.ItemResultRecord;
import com.streamarr.server.repositories.PostgresTransactionLocks;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.InsertOnDuplicateSetMoreStep;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JooqItemResultRepository implements ItemResultRepository {

  private final DSLContext dsl;
  private final ItemLocks itemLocks;
  private final PostgresTransactionLocks transactionLocks;
  private final ArtworkProperties properties;

  @Override
  @Transactional
  public boolean trySave(ItemResult result) {
    if (!itemLocks.tryLockAgainstDeletion(result.itemId(), result.itemType())) {
      return false;
    }

    return upsert(result)
            .where(ITEM_RESULT.ATTEMPTED_AT.le(DSL.excluded(ITEM_RESULT.ATTEMPTED_AT)))
            .execute()
        > 0;
  }

  @Override
  @Transactional
  public void overwrite(ItemResult result) {
    upsert(result).execute();
  }

  @Override
  @Transactional
  public boolean trySaveArtworkResults(ArtworkAttempt attempt, List<ItemResult> results) {
    return transactionLocks.withLockWaitLimit(
        properties.lockTimeout(),
        () -> {
          if (!itemLocks.tryLockArtworkGeneration(attempt.item(), attempt.generation())) {
            return false;
          }

          results.forEach(result -> upsert(result).where(replacesStoredArtworkResult()).execute());
          return true;
        });
  }

  @Override
  @Transactional(readOnly = true)
  public Map<ImageEntityType, ArtworkOutcomeTally> countArtworkOutcomesRecordedSince(
      Instant since) {
    var counts = new EnumMap<ImageEntityType, Map<ItemResultOutcome, Long>>(ImageEntityType.class);
    dsl.select(ITEM_RESULT.ITEM_TYPE, ITEM_RESULT.OUTCOME, DSL.count())
        .from(ITEM_RESULT)
        .where(ITEM_RESULT.STEP.eq(ItemResultStep.ARTWORK))
        .and(ITEM_RESULT.RECORDED_AT.ge(since.atOffset(ZoneOffset.UTC)))
        .groupBy(ITEM_RESULT.ITEM_TYPE, ITEM_RESULT.OUTCOME)
        .forEach(
            row ->
                counts
                    .computeIfAbsent(
                        toDomain(row.value1()), _ -> new EnumMap<>(ItemResultOutcome.class))
                    .put(row.value2(), row.value3().longValue()));
    var tallies = new EnumMap<ImageEntityType, ArtworkOutcomeTally>(ImageEntityType.class);
    counts.forEach(
        (itemType, byOutcome) ->
            tallies.put(
                itemType,
                new ArtworkOutcomeTally(
                    byOutcome.getOrDefault(ItemResultOutcome.SUCCEEDED, 0L),
                    byOutcome.getOrDefault(ItemResultOutcome.UNAVAILABLE, 0L),
                    byOutcome.getOrDefault(ItemResultOutcome.FAILED, 0L))));
    return tallies;
  }

  // A result replaces one of an older generation. Within one generation it replaces only an
  // earlier attempt's result, and never a saved image's result with a failure: overlapping
  // attempts can store their results in either order.
  private static Condition replacesStoredArtworkResult() {
    var ofOlderGeneration =
        ITEM_RESULT.ARTWORK_GENERATION.lt(DSL.excluded(ITEM_RESULT.ARTWORK_GENERATION));
    var ofEarlierAttempt = ITEM_RESULT.ATTEMPTED_AT.le(DSL.excluded(ITEM_RESULT.ATTEMPTED_AT));
    var replacesSavedImageWithFailure =
        ITEM_RESULT
            .OUTCOME
            .eq(ItemResultOutcome.SUCCEEDED)
            .and(DSL.excluded(ITEM_RESULT.OUTCOME).ne(ItemResultOutcome.SUCCEEDED));
    return ofOlderGeneration.or(ofEarlierAttempt.andNot(replacesSavedImageWithFailure));
  }

  private InsertOnDuplicateSetMoreStep<ItemResultRecord> upsert(ItemResult result) {
    return dsl.insertInto(ITEM_RESULT)
        .set(toRecord(result))
        .onConflictOnConstraint(Keys.ITEM_RESULT_IDENTITY)
        .doUpdate()
        .set(ITEM_RESULT.OUTCOME, DSL.excluded(ITEM_RESULT.OUTCOME))
        .set(ITEM_RESULT.FAILURE_REASON, DSL.excluded(ITEM_RESULT.FAILURE_REASON))
        .set(ITEM_RESULT.DETAIL, DSL.excluded(ITEM_RESULT.DETAIL))
        .set(ITEM_RESULT.SOURCE_KEY, DSL.excluded(ITEM_RESULT.SOURCE_KEY))
        .set(ITEM_RESULT.ARTWORK_GENERATION, DSL.excluded(ITEM_RESULT.ARTWORK_GENERATION))
        .set(ITEM_RESULT.ATTEMPTED_AT, DSL.excluded(ITEM_RESULT.ATTEMPTED_AT))
        .set(ITEM_RESULT.RECORDED_AT, DSL.currentOffsetDateTime());
  }

  @Override
  @Transactional(readOnly = true)
  public List<ItemResult> findByItem(UUID itemId, ImageEntityType itemType) {
    return dsl.selectFrom(ITEM_RESULT)
        .where(ITEM_RESULT.ITEM_ID.eq(itemId))
        .and(ITEM_RESULT.ITEM_TYPE.eq(toJooq(itemType)))
        .fetch(ItemResultRecords::toDomain);
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
    row.setArtworkGeneration(result.artworkGeneration());
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
}
