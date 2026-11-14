package com.streamarr.server.domain.notification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Dismissal Selector Tests")
class DismissalSelectorTest {

  static Stream<Named<ThrowingCallable>> selectorsNamingNothing() {
    return Stream.of(
        Named.of("no id", () -> DismissalSelector.byId(Set.of())),
        Named.of("no subject", () -> DismissalSelector.bySubject(Set.of())));
  }

  static Stream<Named<ThrowingCallable>> selectorsNamingSomething() {
    return Stream.of(
        Named.of("an id", () -> DismissalSelector.byId(Set.of(UUID.randomUUID())).build()),
        Named.of(
            "a subject",
            () ->
                DismissalSelector.bySubject(
                        Set.of(SubjectReference.profileShare(UUID.randomUUID())))
                    .build()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("selectorsNamingSomething")
  @DisplayName("Should build a selector when it names an id or a subject")
  void shouldBuildASelectorWhenItNamesAnIdOrASubject(ThrowingCallable selector) {
    assertThatCode(selector).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("selectorsNamingNothing")
  @DisplayName("Should refuse a selector when it names no id and no subject")
  void shouldRefuseASelectorWhenItNamesNoIdAndNoSubject(ThrowingCallable selector) {
    assertThatThrownBy(selector).isInstanceOf(IllegalArgumentException.class);
  }
}
