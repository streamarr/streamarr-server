package com.streamarr.server.services;

import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class CompanyService {

  private final CompanyRepository companyRepository;
  private final MutexFactory<String> mutexFactory;

  public CompanyService(
      CompanyRepository companyRepository, MutexFactoryProvider mutexFactoryProvider) {
    this.companyRepository = companyRepository;
    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
  }

  @Transactional
  public Set<Company> getOrCreateCompanies(Set<Company> companies) {
    return companies.stream().map(this::findOrCreateCompany).collect(Collectors.toSet());
  }

  private Company findOrCreateCompany(Company company) {
    var mutex = mutexFactory.getMutex(company.getSourceId());

    try {
      mutex.lock();

      var existing = companyRepository.findBySourceId(company.getSourceId());

      if (existing.isPresent()) {
        var target = existing.get();
        target.setName(company.getName());
        target.setLogoPath(company.getLogoPath());
        return companyRepository.save(target);
      }

      return companyRepository.save(company);
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
      }
    }
  }
}
