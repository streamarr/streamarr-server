package com.streamarr.server.services.events.notification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Resolve Requests Event Tests")
class ResolveRequestsEventTest {

  private static final Instant EVENT = Instant.parse("2026-11-12T10:00:00Z");

  private final SubjectReference share = SubjectReference.profileShare(UUID.randomUUID());

  @Test
  @DisplayName(
      "Should build a resolution when its kind is a request about its subject and it carries no"
          + " answer")
  void shouldBuildAResolutionWhenItsKindIsARequestAboutItsSubjectAndItCarriesNoAnswer() {
    assertThatCode(() -> withdrawn().build()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should build a decision when it carries an answer")
  void shouldBuildADecisionWhenItCarriesAnAnswer() {
    var decision =
        withdrawn()
            .resolution(RequestResolution.DECIDED)
            .answer(Optional.of(ProfileShareAnswer.ACCEPTED));

    assertThatCode(decision::build).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should refuse a resolution when its kind is news")
  void shouldRefuseAResolutionWhenItsKindIsNews() {
    var resolution = withdrawn().kind(SharingNotificationKinds.PROFILE_SHARE_ENDED);

    assertThatThrownBy(resolution::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should refuse a resolution when its subject is not of its kind's subject type")
  void shouldRefuseAResolutionWhenItsSubjectIsNotOfItsKindsSubjectType() {
    var resolution = withdrawn().subject(new SubjectReference("HOUSEHOLD", UUID.randomUUID()));

    assertThatThrownBy(resolution::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should refuse an answer when the resolution is not a decision")
  void shouldRefuseAnAnswerWhenTheResolutionIsNotADecision() {
    var resolution = withdrawn().answer(Optional.of(ProfileShareAnswer.ACCEPTED));

    assertThatThrownBy(resolution::build).isInstanceOf(IllegalArgumentException.class);
  }

  private ResolveRequestsEvent.ResolveRequestsEventBuilder withdrawn() {
    return ResolveRequestsEvent.builder()
        .kind(SharingNotificationKinds.PROFILE_SHARE_OFFERED)
        .subject(share)
        .resolution(RequestResolution.WITHDRAWN)
        .at(EVENT);
  }
}
