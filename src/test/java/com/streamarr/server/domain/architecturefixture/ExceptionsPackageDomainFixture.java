package com.streamarr.server.domain.architecturefixture;

import com.streamarr.server.exceptions.InvalidIdException;

public class ExceptionsPackageDomainFixture {

  public void rejectId(String id) {
    throw new InvalidIdException(id);
  }
}
