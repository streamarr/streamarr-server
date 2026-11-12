package com.streamarr.server.domain.notification;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * Which Notifications a dismissal deletes (ADR 0063 §Dismissal): those with the given ids, or every
 * Notification about the given subjects, narrowed to one recipient, to some kinds, and to one form
 * when it says so. It always names ids or subjects, so no dismissal selects every Notification. The
 * domain that dismisses works it out from its own records and owns the reason.
 */
@Builder
public record DismissalSelector(
    @NonNull Targets targets,
    @NonNull Optional<RecipientReference> recipient,
    @NonNull Set<NotificationKind> kinds,
    @NonNull Optional<DismissedForm> form) {

  public DismissalSelector {
    kinds = Set.copyOf(kinds);
  }

  /** The Notifications with these ids. */
  public static DismissalSelectorBuilder byId(Set<UUID> notificationIds) {
    return builder().targets(new ById(notificationIds));
  }

  /** Every Notification about these subjects. */
  public static DismissalSelectorBuilder bySubject(Set<SubjectReference> subjects) {
    return builder().targets(new BySubject(subjects));
  }

  /** What a selector names: ids or subjects. */
  public sealed interface Targets permits ById, BySubject {}

  /**
   * @throws IllegalArgumentException when it names no id
   */
  public record ById(@NonNull Set<UUID> notificationIds) implements Targets {

    public ById {
      if (notificationIds.isEmpty()) {
        throw new IllegalArgumentException("A dismissal by id names at least one id");
      }

      notificationIds = Set.copyOf(notificationIds);
    }
  }

  /**
   * @throws IllegalArgumentException when it names no subject
   */
  public record BySubject(@NonNull Set<SubjectReference> subjects) implements Targets {

    public BySubject {
      if (subjects.isEmpty()) {
        throw new IllegalArgumentException("A dismissal by subject names at least one subject");
      }

      subjects = Set.copyOf(subjects);
    }
  }

  /** The one form a selector may narrow to. */
  public enum DismissedForm {
    NEWS,
    /** The requests that no event has resolved, whatever their expiry. */
    WAITING_REQUESTS
  }

  /** Narrows to nothing unless the selector says so: every recipient, kind and form. */
  public static class DismissalSelectorBuilder {
    private Optional<RecipientReference> recipient = Optional.empty();
    private Set<NotificationKind> kinds = Set.of();
    private Optional<DismissedForm> form = Optional.empty();
  }
}
