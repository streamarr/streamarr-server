package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.RequestAnswer;

/** How the invited Account answered an invitation to manage a Profile. */
public enum ProfileManagerInvitationAnswer implements RequestAnswer {
  ACCEPTED,
  DECLINED
}
