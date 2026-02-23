package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.fakes.FakeLibraryMetadataRepository;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.library.events.ItemProcessedEvent;
import com.streamarr.server.services.library.events.ScanCompletedEvent;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Result;
import org.jooq.SelectConditionStep;
import org.jooq.SelectHavingStep;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSeekStep1;
import org.jooq.SelectSelectStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("UnitTest")
@DisplayName("Library Metadata Maintainer Tests")
class LibraryMetadataMaintainerTest {

  private final FakeLibraryMetadataRepository fakeMetadataRepository =
      new FakeLibraryMetadataRepository();
  private final MutexFactoryProvider mutexFactoryProvider = new MutexFactoryProvider();
  private DSLContext mockContext;
  private LibraryMetadataMaintainer maintainer;

  private final UUID libraryId = UUID.randomUUID();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    mockContext = mock(DSLContext.class);

    maintainer =
        new LibraryMetadataMaintainer(
            mockContext,
            fakeMetadataRepository,
            noOpTransactionTemplate(),
            _ -> false,
            mutexFactoryProvider);

    fakeMetadataRepository.deleteAll();
  }

  @Test
  @DisplayName("Should recalculate letter counts when scan completed")
  void shouldRecalculateLetterCountsWhenScanCompleted() {
    stubDslContextReturning(
        new Object[][] {
          {"A", 5},
          {"M", 12},
          {"HASH", 3}
        });

    maintainer.onScanCompleted(new ScanCompletedEvent(libraryId));

    var metadata = fakeMetadataRepository.findByLibraryIdOrderByLetterAsc(libraryId);
    assertThat(metadata).hasSize(3);
    assertThat(metadata.get(0).getLetter()).isEqualTo(AlphabetLetter.A);
    assertThat(metadata.get(0).getItemCount()).isEqualTo(5);
    assertThat(metadata.get(1).getLetter()).isEqualTo(AlphabetLetter.M);
    assertThat(metadata.get(1).getItemCount()).isEqualTo(12);
    assertThat(metadata.get(2).getLetter()).isEqualTo(AlphabetLetter.HASH);
    assertThat(metadata.get(2).getItemCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("Should recalculate letter counts when item processed and not scanning")
  void shouldRecalculateLetterCountsWhenItemProcessedAndNotScanning() {
    stubDslContextReturning(new Object[][] {{"B", 7}});

    maintainer.onItemProcessed(new ItemProcessedEvent(libraryId));

    var metadata = fakeMetadataRepository.findByLibraryIdOrderByLetterAsc(libraryId);
    assertThat(metadata).hasSize(1);
    assertThat(metadata.getFirst().getLetter()).isEqualTo(AlphabetLetter.B);
    assertThat(metadata.getFirst().getItemCount()).isEqualTo(7);
  }

  @Test
  @DisplayName("Should skip recalculation when item processed during active scan")
  void shouldSkipRecalculationWhenItemProcessedDuringActiveScan() {
    var scanningMaintainer =
        new LibraryMetadataMaintainer(
            mockContext,
            fakeMetadataRepository,
            noOpTransactionTemplate(),
            _ -> true,
            mutexFactoryProvider);

    scanningMaintainer.onItemProcessed(new ItemProcessedEvent(libraryId));

    var metadata = fakeMetadataRepository.findByLibraryIdOrderByLetterAsc(libraryId);
    assertThat(metadata).isEmpty();
  }

  @Test
  @DisplayName("Should replace old metadata on recalculation")
  void shouldReplaceOldMetadataOnRecalculation() {
    fakeMetadataRepository.save(
        LibraryMetadata.builder()
            .libraryId(libraryId)
            .letter(AlphabetLetter.Z)
            .itemCount(99)
            .build());

    stubDslContextReturning(new Object[][] {{"A", 1}});

    maintainer.onScanCompleted(new ScanCompletedEvent(libraryId));

    var metadata = fakeMetadataRepository.findByLibraryIdOrderByLetterAsc(libraryId);
    assertThat(metadata).hasSize(1);
    assertThat(metadata.getFirst().getLetter()).isEqualTo(AlphabetLetter.A);
    assertThat(metadata.getFirst().getItemCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should not propagate exception when recalculation fails")
  void shouldNotPropagateExceptionWhenRecalculationFails() {
    when(mockContext.select(any(), any())).thenThrow(new RuntimeException("DB error"));

    assertThatNoException()
        .isThrownBy(() -> maintainer.onScanCompleted(new ScanCompletedEvent(libraryId)));
  }

  @SuppressWarnings("unchecked")
  private void stubDslContextReturning(Object[][] letterCounts) {
    Result<Record2<String, Integer>> result = mock(Result.class);

    var records =
        java.util.Arrays.stream(letterCounts)
            .map(
                row -> {
                  Record2<String, Integer> record = mock(Record2.class);
                  when(record.value1()).thenReturn((String) row[0]);
                  when(record.value2()).thenReturn((Integer) row[1]);
                  return record;
                })
            .toList();

    when(result.iterator()).thenReturn(records.iterator());
    when(result.stream()).thenReturn(records.stream());

    SelectSelectStep selectStep = mock(SelectSelectStep.class);
    SelectJoinStep joinStep = mock(SelectJoinStep.class);
    SelectConditionStep conditionStep = mock(SelectConditionStep.class);
    SelectHavingStep havingStep = mock(SelectHavingStep.class);
    SelectSeekStep1 seekStep = mock(SelectSeekStep1.class);

    when(mockContext.select(any(), any())).thenReturn(selectStep);
    when(selectStep.from(any(org.jooq.Table.class))).thenReturn(joinStep);
    when(joinStep.where(any(org.jooq.Condition.class))).thenReturn(conditionStep);
    when(conditionStep.groupBy(any(org.jooq.GroupField.class))).thenReturn(havingStep);
    when(havingStep.orderBy(any(org.jooq.OrderField.class))).thenReturn(seekStep);
    when(seekStep.fetch()).thenReturn(result);
  }

  private static TransactionTemplate noOpTransactionTemplate() {
    return new TransactionTemplate(
        new AbstractPlatformTransactionManager() {
          @Override
          protected Object doGetTransaction() {
            return new Object();
          }

          @Override
          protected void doBegin(Object transaction, TransactionDefinition definition) {}

          @Override
          protected void doCommit(DefaultTransactionStatus status) {}

          @Override
          protected void doRollback(DefaultTransactionStatus status) {}
        });
  }
}
