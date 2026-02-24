package com.streamarr.server.services.library;

import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.left;
import static org.jooq.impl.DSL.upper;
import static org.jooq.impl.DSL.when;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.jooq.generated.Tables;
import com.streamarr.server.repositories.LibraryMetadataRepository;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.library.events.ItemProcessedEvent;
import com.streamarr.server.services.library.events.ScanCompletedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
public class LibraryMetadataMaintainer {

  private final DSLContext context;
  private final LibraryMetadataRepository metadataRepository;
  private final TransactionTemplate transactionTemplate;
  private final ActiveScanChecker activeScanChecker;
  private final MutexFactory<String> mutexFactory;

  public LibraryMetadataMaintainer(
      DSLContext context,
      LibraryMetadataRepository metadataRepository,
      TransactionTemplate transactionTemplate,
      ActiveScanChecker activeScanChecker,
      MutexFactoryProvider mutexFactoryProvider) {
    this.context = context;
    this.metadataRepository = metadataRepository;
    this.transactionTemplate = transactionTemplate;
    this.activeScanChecker = activeScanChecker;
    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
  }

  @EventListener
  public void onScanCompleted(ScanCompletedEvent event) {
    try {
      recalculateLetterCounts(event.libraryId());
    } catch (Exception e) {
      log.error("Letter index recalculation failed for library: {}", event.libraryId(), e);
    }
  }

  @EventListener
  public void onItemProcessed(ItemProcessedEvent event) {
    if (activeScanChecker.isActivelyScanning(event.libraryId())) {
      return;
    }

    try {
      recalculateLetterCounts(event.libraryId());
    } catch (Exception e) {
      log.error("Letter index recalculation failed for library: {}", event.libraryId(), e);
    }
  }

  private void recalculateLetterCounts(UUID libraryId) {
    var mutex = mutexFactory.getMutex("library-metadata-" + libraryId);
    mutex.lock();
    try {
      doRecalculate(libraryId);
    } finally {
      mutex.unlock();
    }
  }

  private void doRecalculate(UUID libraryId) {
    var entries = queryLetterCounts(libraryId);
    persistLetterCounts(libraryId, entries);

    log.info(
        "Recalculated letter index for library {}: {} distinct letters.",
        libraryId,
        entries.size());
  }

  private List<LibraryMetadata> queryLetterCounts(UUID libraryId) {
    var firstChar = left(Tables.BASE_COLLECTABLE.TITLE_SORT, inline(1));

    Field<String> letterExpression =
        when(firstChar.likeRegex(inline("[a-zA-Z]")), upper(firstChar)).otherwise(inline("HASH"));

    List<Record2<String, Integer>> results =
        context
            .select(letterExpression.as("letter"), count().as("item_count"))
            .from(Tables.BASE_COLLECTABLE)
            .where(Tables.BASE_COLLECTABLE.LIBRARY_ID.eq(libraryId))
            .groupBy(letterExpression)
            .orderBy(letterExpression)
            .fetch();

    var metadataEntries = new ArrayList<LibraryMetadata>();

    for (var row : results) {
      var letterStr = row.value1();
      var itemCount = row.value2();

      var letter = parseAlphabetLetter(letterStr);
      if (letter == null) {
        continue;
      }

      metadataEntries.add(
          LibraryMetadata.builder()
              .libraryId(libraryId)
              .letter(letter)
              .itemCount(itemCount)
              .build());
    }

    return metadataEntries;
  }

  private void persistLetterCounts(UUID libraryId, List<LibraryMetadata> entries) {
    transactionTemplate.executeWithoutResult(
        status -> {
          metadataRepository.deleteByLibraryId(libraryId);
          metadataRepository.saveAll(entries);
        });
  }

  private AlphabetLetter parseAlphabetLetter(String value) {
    try {
      return AlphabetLetter.valueOf(value);
    } catch (IllegalArgumentException _) {
      log.warn("Unexpected letter value from aggregation: {}", value);
      return null;
    }
  }
}
