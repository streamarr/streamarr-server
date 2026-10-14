package com.streamarr.server.services;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyService {

  private final CompanyRepository companyRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Transactional
  public Set<Company> getOrCreateCompanies(
      Set<Company> companies,
      Map<String, List<ImageSource>> imageSourcesBySourceId,
      ImageRefreshMode imageRefreshMode) {
    if (companies == null) {
      return Set.of();
    }

    return CreditInsertOrder.sortedBySourceId(companies, Company::getSourceId, "Company").stream()
        .map(c -> findOrCreateCompany(c, imageSourcesBySourceId, imageRefreshMode))
        .collect(Collectors.toSet());
  }

  private Company findOrCreateCompany(
      Company company,
      Map<String, List<ImageSource>> imageSourcesBySourceId,
      ImageRefreshMode imageRefreshMode) {
    var imageSources = imageSourcesBySourceId.getOrDefault(company.getSourceId(), List.of());

    companyRepository.insertIfAbsent(company.getSourceId(), company.getName());
    var saved =
        companyRepository
            .findBySourceId(company.getSourceId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Company not found after upsert for sourceId: " + company.getSourceId()));

    renameBeforeNextInsert(saved, company.getName());

    publishImageEvent(saved, imageSources, imageRefreshMode);
    return saved;
  }

  // Hibernate would write a rename at its next flush, after this transaction has inserted higher
  // keys, so the row lock would be taken out of insert order. Flushing here takes it in order.
  private void renameBeforeNextInsert(Company saved, String name) {
    if (Objects.equals(saved.getName(), name)) {
      return;
    }

    saved.setName(name);
    companyRepository.flush();
  }

  private void publishImageEvent(
      Company company, List<ImageSource> imageSources, ImageRefreshMode imageRefreshMode) {
    if (imageSources.isEmpty()) {
      return;
    }

    eventPublisher.publishEvent(
        new MetadataEnrichedEvent(
            company.getId(), ImageEntityType.COMPANY, imageSources, imageRefreshMode));
  }
}
