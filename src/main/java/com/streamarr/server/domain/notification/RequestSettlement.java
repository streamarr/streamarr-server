package com.streamarr.server.domain.notification;

import java.time.Instant;
import java.util.Optional;

/**
 * How every request of a kind about a subject that no event has resolved yet settles (ADR 0063
 * §Requests and news): the resolution, a decision's answer, and the instant the subject changed.
 */
public interface RequestSettlement {

  NotificationKind kind();

  SubjectReference subject();

  RequestResolution resolution();

  Optional<RequestAnswer> answer();

  Instant at();
}
