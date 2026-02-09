package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeCompanyRepository;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Company Service Tests")
class CompanyServiceTest {

  private FakeCompanyRepository companyRepository;
  private CapturingEventPublisher eventPublisher;
  private CompanyService companyService;

  @BeforeEach
  void setUp() {
    companyRepository = new FakeCompanyRepository();
    eventPublisher = new CapturingEventPublisher();
    companyService =
        new CompanyService(companyRepository, new MutexFactoryProvider(), eventPublisher);
  }

  @Test
  @DisplayName("Should create new company when source ID not found")
  void shouldCreateNewCompanyWhenSourceIdNotFound() {
    var company =
        Company.builder().name("Warner Bros.").sourceId("wb-123").logoPath("/wb.png").build();

    var result = companyService.getOrCreateCompanies(Set.of(company));

    assertThat(result).hasSize(1);
    var saved = result.iterator().next();
    assertThat(saved.getName()).isEqualTo("Warner Bros.");
    assertThat(saved.getSourceId()).isEqualTo("wb-123");
    assertThat(saved.getLogoPath()).isEqualTo("/wb.png");
    assertThat(saved.getId()).isNotNull();
    assertThat(companyRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should update existing company when source ID already exists")
  void shouldUpdateExistingCompanyWhenSourceIdAlreadyExists() {
    var existing =
        companyRepository.save(
            Company.builder().name("Old Name").sourceId("studio-1").logoPath("/old.png").build());

    var updated =
        Company.builder().name("New Name").sourceId("studio-1").logoPath("/new.png").build();

    var result = companyService.getOrCreateCompanies(Set.of(updated));

    assertThat(result).hasSize(1);
    var returned = result.iterator().next();
    assertThat(returned.getId()).isEqualTo(existing.getId());
    assertThat(returned.getName()).isEqualTo("New Name");
    assertThat(returned.getLogoPath()).isEqualTo("/new.png");
    assertThat(companyRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should update existing company when batch contains duplicate source ID")
  void shouldUpdateExistingCompanyWhenBatchContainsDuplicate() {
    var existing =
        companyRepository.save(
            Company.builder()
                .name("Existing Studio")
                .sourceId("existing-1")
                .logoPath("/existing.png")
                .build());

    var updatedExisting =
        Company.builder()
            .name("Updated Studio")
            .sourceId("existing-1")
            .logoPath("/updated.png")
            .build();
    var brandNew =
        Company.builder()
            .name("Brand New Studio")
            .sourceId("new-1")
            .logoPath("/brand-new.png")
            .build();

    var result = companyService.getOrCreateCompanies(Set.of(updatedExisting, brandNew));

    var returnedExisting =
        result.stream().filter(c -> "existing-1".equals(c.getSourceId())).findFirst().orElseThrow();
    assertThat(returnedExisting.getId()).isEqualTo(existing.getId());
    assertThat(returnedExisting.getName()).isEqualTo("Updated Studio");
    assertThat(returnedExisting.getLogoPath()).isEqualTo("/updated.png");
  }

  @Test
  @DisplayName("Should create new company when batch contains unknown source ID")
  void shouldCreateNewCompanyWhenBatchContainsUnknownSourceId() {
    companyRepository.save(
        Company.builder()
            .name("Existing Studio")
            .sourceId("existing-1")
            .logoPath("/existing.png")
            .build());

    var updatedExisting =
        Company.builder()
            .name("Updated Studio")
            .sourceId("existing-1")
            .logoPath("/updated.png")
            .build();
    var brandNew =
        Company.builder()
            .name("Brand New Studio")
            .sourceId("new-1")
            .logoPath("/brand-new.png")
            .build();

    var result = companyService.getOrCreateCompanies(Set.of(updatedExisting, brandNew));

    var returnedNew =
        result.stream().filter(c -> "new-1".equals(c.getSourceId())).findFirst().orElseThrow();
    assertThat(returnedNew.getName()).isEqualTo("Brand New Studio");
    assertThat(returnedNew.getLogoPath()).isEqualTo("/brand-new.png");
    assertThat(returnedNew.getId()).isNotNull();
    assertThat(companyRepository.count()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should publish MetadataEnrichedEvent when creating new company")
  void shouldPublishMetadataEnrichedEventWhenCreatingNewCompany() {
    var company =
        Company.builder().name("Warner Bros.").sourceId("wb-123").logoPath("/wb.png").build();

    companyService.getOrCreateCompanies(Set.of(company));

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().entityType()).isEqualTo(ImageEntityType.COMPANY);
  }

  @Test
  @DisplayName("Should not publish event when company already exists")
  void shouldNotPublishEventWhenCompanyAlreadyExists() {
    companyRepository.save(
        Company.builder().name("Existing").sourceId("wb-123").logoPath("/wb.png").build());

    var updated =
        Company.builder().name("Updated").sourceId("wb-123").logoPath("/updated.png").build();

    companyService.getOrCreateCompanies(Set.of(updated));

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).isEmpty();
  }

  @Test
  @DisplayName("Should throw when company source ID is null")
  void shouldThrowWhenCompanySourceIdIsNull() {
    var company = Company.builder().name("Warner Bros.").sourceId(null).logoPath("/wb.png").build();
    var companies = Set.of(company);

    assertThatThrownBy(() -> companyService.getOrCreateCompanies(companies))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should return empty set when input is null")
  void shouldReturnEmptySetWhenInputIsNull() {
    var result = companyService.getOrCreateCompanies(null);

    assertThat(result).isEmpty();
  }
}
