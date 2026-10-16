package com.streamarr.server.repositories.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.AuthTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

// Each write runs in a transaction of its own, so each completion gets its own database time.
@Tag("IntegrationTest")
@DisplayName("Watch History Repository Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WatchHistoryRepositoryIT extends AbstractIntegrationTest {

  private static final int DURATION_SECONDS = 7200;

  @Autowired private WatchHistoryRepository watchHistoryRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;

  private AuthTestSupport.TestIdentity identity;
  private UUID profileId;

  @BeforeAll
  void setup() {
    identity = authTestSupport.createIdentity();
    profileId = identity.profile().getId();
  }

  @AfterAll
  void deleteIdentitySeed() {
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should save one completion when a stream session completes its title twice")
  void shouldSaveOneCompletionWhenAStreamSessionCompletesItsTitleTwice() {
    var collectableId = UUID.randomUUID();
    var completion = completionBuilder(collectableId).streamSessionId(UUID.randomUUID()).build();

    var firstSaved = saveInTransaction(completion);
    var secondSaved = saveInTransaction(completion);

    assertThat(List.of(firstSaved, secondSaved)).containsExactly(1, 0);
    assertThat(completionsOf(collectableId)).isEqualTo(1);
  }

  @Test
  @DisplayName("Should save a completion for each stream session when two complete the same title")
  void shouldSaveACompletionForEachStreamSessionWhenTwoCompleteTheSameTitle() {
    var collectableId = UUID.randomUUID();

    saveInTransaction(completionBuilder(collectableId).streamSessionId(UUID.randomUUID()).build());
    saveInTransaction(completionBuilder(collectableId).streamSessionId(UUID.randomUUID()).build());

    assertThat(completionsOf(collectableId)).isEqualTo(2);
  }

  @Test
  @DisplayName("Should save a manual mark when a stream session already completed the title")
  void shouldSaveAManualMarkWhenAStreamSessionAlreadyCompletedTheTitle() {
    var collectableId = UUID.randomUUID();
    saveInTransaction(completionBuilder(collectableId).streamSessionId(UUID.randomUUID()).build());

    var marked =
        transactionTemplate.execute(
            _ -> watchHistoryRepository.batchInsert(profileId, List.of(collectableId), 0));

    assertThat(marked).isEqualTo(1);
    assertThat(completionsOf(collectableId)).isEqualTo(2);
  }

  @Test
  @DisplayName("Should save one manual mark when one batch names the same title twice")
  void shouldSaveOneManualMarkWhenOneBatchNamesTheSameTitleTwice() {
    var collectableId = UUID.randomUUID();

    var marked =
        transactionTemplate.execute(
            _ ->
                watchHistoryRepository.batchInsert(
                    profileId, List.of(collectableId, collectableId), 0));

    assertThat(marked).isEqualTo(1);
    assertThat(completionsOf(collectableId)).isEqualTo(1);
  }

  private StreamSessionCompletion.StreamSessionCompletionBuilder completionBuilder(
      UUID collectableId) {
    return StreamSessionCompletion.builder()
        .profileId(profileId)
        .collectableId(collectableId)
        .durationSeconds(DURATION_SECONDS);
  }

  private int saveInTransaction(StreamSessionCompletion completion) {
    return transactionTemplate.execute(
        _ -> watchHistoryRepository.saveStreamSessionCompletion(completion));
  }

  private int completionsOf(UUID collectableId) {
    return watchHistoryRepository
        .findByProfileIdAndCollectableIdIn(profileId, List.of(collectableId))
        .size();
  }
}
