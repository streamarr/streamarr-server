package com.streamarr.server.repositories.architecturefixture;

import org.springframework.data.jpa.repository.Query;

public interface RepositoryQueryFixture {

  @Query("select 1")
  Object findWithJpql();
}
