package com.streamarr.server.services;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.List;
import java.util.Map;
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
  public Set<Company> getOrCreateCompanies(
      Set<Company> companies, Map<String, List<ImageSource>> imageSourcesBySourceId) {
    if (companies == null) {
      return Set.of();
    }

    return companies.stream()
        .map(c -> findOrCreateCompany(c, imageSourcesBySourceId))
        .collect(Collectors.toSet());
  }

  private Company findOrCreateCompany(
      Company company, Map<String, List<ImageSource>> imageSourcesBySourceId) {
    if (company.getSourceId() == null) {
      throw new IllegalArgumentException("Company sourceId must not be null");
    }

    var imageSources = imageSourcesBySourceId.getOrDefault(company.getSourceId(), List.of());
    var mutex = mutexFactory.getMutex(company.getSourceId());

    mutex.lock();
    try {
      var existing = companyRepository.findBySourceId(company.getSourceId());

      if (existing.isPresent()) {
        var target = existing.get();
        target.setName(company.getName());
        return companyRepository.save(target);
      }

      var savedCompany = companyRepository.save(company);
      publishImageEvent(savedCompany, imageSources);
      return savedCompany;
    } finally {
      mutex.unlock();
    }
  }

  private void publishImageEvent(Company company, List<ImageSource> imageSources) {
    if (imageSources.isEmpty()) {
      return;
    }

    eventPublisher.publishEvent(
        new MetadataEnrichedEvent(company.getId(), ImageEntityType.COMPANY, imageSources));
  }
}
