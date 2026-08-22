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

/** managerOf: durable, direct ProfileManager authority of an Account over a Profile. */
@Entity
@Table(name = "profile_manager")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ProfileManager extends BaseAuditableEntity<ProfileManager> {

  private UUID accountId;

  private UUID profileId;
}
