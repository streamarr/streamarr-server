package com.streamarr.server.services.auth;

import java.util.Objects;

public final class CapturingProfileDeletionService extends ProfileDeletionService {

  private DeleteProfileCommand deletion;

  public CapturingProfileDeletionService() {
    super(null, null, null, null, null, null, null, null);
  }

  @Override
  public PreparedProfileDeletion prepare(DeleteProfileCommand command) {
    deletion = command;
    return new PreparedProfileDeletion(command.actingAccountId(), command.profileId());
  }

  @Override
  public void delete(PreparedProfileDeletion command) {
    Objects.requireNonNull(command);
  }

  public DeleteProfileCommand deletion() {
    return deletion;
  }
}
