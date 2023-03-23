package com.streamarr.server.repositories;

import com.streamarr.server.domain.metadata.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;


@Repository
public interface CompanyRepository extends JpaRepository<Company, UUID> {

    Set<Company> findCompaniesBySourceIdIn(List<String> sourceIds);
}
