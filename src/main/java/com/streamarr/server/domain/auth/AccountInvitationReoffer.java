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

/** A Household that may receive a new Profile share offer after a LINK invitation is accepted. */
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
