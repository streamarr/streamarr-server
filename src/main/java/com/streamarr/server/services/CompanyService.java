package com.streamarr.server.services;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class CompanyService {

  private final CompanyRepository companyRepository;
  private final MutexFactory<String> mutexFactory;
  private final ApplicationEventPublisher eventPublisher;

  public CompanyService(
      CompanyRepository companyRepository,
      MutexFactoryProvider mutexFactoryProvider,
      ApplicationEventPublisher eventPublisher) {
    this.companyRepository = companyRepository;
    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
    this.eventPublisher = eventPublisher;
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

      var savedCompany = companyRepository.save(company);
      publishImageEvent(savedCompany);
      return savedCompany;
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
      }
    }
  }

  private void publishImageEvent(Company company) {
    if (company.getLogoPath() == null) {
      return;
    }

    eventPublisher.publishEvent(
        new MetadataEnrichedEvent(
            company.getId(),
            ImageEntityType.COMPANY,
            List.of(new TmdbImageSource(ImageType.LOGO, company.getLogoPath()))));
  }
}
