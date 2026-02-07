package com.streamarr.server.fakes;

import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.repositories.CompanyRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.NotImplementedException;

public class FakeCompanyRepository extends FakeJpaRepository<Company> implements CompanyRepository {

  @Override
  public Optional<Company> findBySourceId(String sourceId) {
    return database.values().stream()
        .filter(company -> sourceId.equals(company.getSourceId()))
        .findFirst();
  }

  @Override
  public Set<Company> findCompaniesBySourceIdIn(List<String> sourceIds) {
    throw new NotImplementedException();
  }

  @Override
  public List<Company> findByMovieId(UUID movieId) {
    throw new NotImplementedException();
  }

  @Override
  public List<Company> findBySeriesId(UUID seriesId) {
    throw new NotImplementedException();
  }
}
