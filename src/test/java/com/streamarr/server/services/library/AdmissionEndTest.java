package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Admission end")
class AdmissionEndTest {

  @Test
  @DisplayName("Should hide every later subject when the end is reached between two subjects")
  void shouldHideEveryLaterSubjectWhenTheEndIsReachedBetweenTwoSubjects() {
    var reached = new AtomicBoolean();
    AdmissionEnd end = reached::get;
    var subjects = end.untilReached(List.of("first", "second", "third").iterator());

    var first = subjects.next();
    reached.set(true);

    assertThat(first).isEqualTo("first");
    assertThat(subjects.hasNext()).isFalse();
    assertThatThrownBy(subjects::next).isInstanceOf(NoSuchElementException.class);
  }

  @Test
  @DisplayName(
      "Should return the subject that the admission found when the end is reached before the"
          + " admission takes the subject")
  void
      shouldReturnTheSubjectThatTheAdmissionFoundWhenTheEndIsReachedBeforeTheAdmissionTakesTheSubject() {
    var reached = new AtomicBoolean();
    AdmissionEnd end = reached::get;
    var subjects = end.untilReached(List.of("first", "second").iterator());

    var found = subjects.hasNext();
    reached.set(true);

    assertThat(found).isTrue();
    assertThat(subjects.next()).isEqualTo("first");
    assertThat(subjects.hasNext()).isFalse();
  }

  @Test
  @DisplayName("Should return every subject when the end is never reached")
  void shouldReturnEverySubjectWhenTheEndIsNeverReached() {
    AdmissionEnd end = () -> false;

    var subjects = end.untilReached(List.of("first", "second").iterator());

    assertThat(subjects).toIterable().containsExactly("first", "second");
  }
}
