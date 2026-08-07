package com.streamarr.server.repositories;

import com.streamarr.server.domain.metadata.Company;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CompanyRepository extends JpaRepository<Company, UUID>, CompanyRepositoryCustom {

  Set<Company> findCompaniesBySourceIdIn(List<String> sourceIds);

  Optional<Company> findBySourceId(String sourceId);
}
