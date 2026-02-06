package com.streamarr.server.services;

import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.repositories.CompanyRepository;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompanyService {

  private final CompanyRepository companyRepository;

  @Transactional
  public Set<Company> getOrCreateCompanies(Set<Company> companies) {
    return companies.stream().map(this::saveCompany).collect(Collectors.toSet());
  }

  private Company saveCompany(Company company) {
    var existing = companyRepository.findBySourceId(company.getSourceId());

    if (existing.isPresent()) {
      return existing.get();
    }

    return companyRepository.save(company);
  }
}
