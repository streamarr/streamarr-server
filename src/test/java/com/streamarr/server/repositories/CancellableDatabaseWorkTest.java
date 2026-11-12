package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

@Tag("UnitTest")
@DisplayName("Cancellable Database Work Tests")
class CancellableDatabaseWorkTest {

  private final CancellableDatabaseWork work = new CancellableDatabaseWork();

  @Test
  @DisplayName("Should return the work's result when no cancel came")
  void shouldReturnTheWorksResultWhenNoCancelCame() {
    assertThat(work.call(() -> 7)).contains(7);
  }

  @Test
  @DisplayName("Should propagate the work's failure when no cancel came")
  void shouldPropagateTheWorksFailureWhenNoCancelCame() {
    var failure = new DataAccessResourceFailureException("The database is unavailable.");

    assertThatThrownBy(
            () ->
                work.call(
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
  }

  @Test
  @DisplayName("Should not run work that starts after the cancel")
  void shouldNotRunWorkThatStartsAfterTheCancel() {
    var ran = new AtomicBoolean();

    var cancelledCalls = work.cancel();
    var result =
        work.call(
            () -> {
              ran.set(true);
              return 7;
            });

    assertThat(cancelledCalls).isZero();
    assertThat(result).isEmpty();
    assertThat(ran).isFalse();
  }
}
