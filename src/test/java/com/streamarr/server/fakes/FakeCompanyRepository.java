package com.streamarr.server.fakes;

import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.repositories.CompanyRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.Setter;
import org.apache.commons.lang3.NotImplementedException;

public class FakeCompanyRepository extends FakeJpaRepository<Company> implements CompanyRepository {

  @Setter private boolean simulateConflict;

  @Override
  public boolean insertIfAbsent(String sourceId, String name) {
    if (simulateConflict) {
      save(Company.builder().sourceId(sourceId).name(name).build());
      return false;
    }
    boolean exists =
        database.values().stream().anyMatch(c -> sourceId.equals(c.getSourceId()));
    if (exists) {
      return false;
    }
    save(Company.builder().sourceId(sourceId).name(name).build());
    return true;
  }

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
