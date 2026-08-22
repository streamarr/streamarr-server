package com.streamarr.server.domain.auth;

import com.streamarr.server.domain.BaseAuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A Household to be offered the connected Profile afresh at acceptance (ADR 0024): its old share
 * admitted a Profile; once the Profile is a person's, the same share would admit the person.
 */
@Entity
@Table(name = "account_invitation_reoffer")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AccountInvitationReoffer extends BaseAuditableEntity<AccountInvitationReoffer> {

  private UUID invitationId;

  private UUID householdId;

  private String householdName;
}
