package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeCompanyRepository;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    companyService = new CompanyService(companyRepository, eventPublisher);
  }

  @Test
  @DisplayName("Should create new company when source ID not found")
  void shouldCreateNewCompanyWhenSourceIdNotFound() {
    var company = Company.builder().name("Warner Bros.").sourceId("wb-123").build();

    var result = companyService.getOrCreateCompanies(Set.of(company), Map.of());

    assertThat(result).hasSize(1);
    var saved = result.iterator().next();
    assertThat(saved.getName()).isEqualTo("Warner Bros.");
    assertThat(saved.getSourceId()).isEqualTo("wb-123");
    assertThat(saved.getId()).isNotNull();
    assertThat(companyRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should update existing company when source ID already exists")
  void shouldUpdateExistingCompanyWhenSourceIdAlreadyExists() {
    var existing =
        companyRepository.save(Company.builder().name("Old Name").sourceId("studio-1").build());

    var updated = Company.builder().name("New Name").sourceId("studio-1").build();

    var result = companyService.getOrCreateCompanies(Set.of(updated), Map.of());

    assertThat(result).hasSize(1);
    var returned = result.iterator().next();
    assertThat(returned.getId()).isEqualTo(existing.getId());
    assertThat(returned.getName()).isEqualTo("New Name");
    assertThat(companyRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should update existing company when batch contains known source ID")
  void shouldUpdateExistingCompanyWhenBatchContainsKnownSourceId() {
    var existing =
        companyRepository.save(
            Company.builder().name("Existing Studio").sourceId("existing-1").build());

    var updatedExisting = Company.builder().name("Updated Studio").sourceId("existing-1").build();
    var brandNew = Company.builder().name("Brand New Studio").sourceId("new-1").build();

    var result = companyService.getOrCreateCompanies(Set.of(updatedExisting, brandNew), Map.of());

    var returnedExisting =
        result.stream().filter(c -> "existing-1".equals(c.getSourceId())).findFirst().orElseThrow();
    assertThat(returnedExisting.getId()).isEqualTo(existing.getId());
    assertThat(returnedExisting.getName()).isEqualTo("Updated Studio");
  }

  @Test
  @DisplayName("Should create new company when batch contains unknown source ID")
  void shouldCreateNewCompanyWhenBatchContainsUnknownSourceId() {
    companyRepository.save(
        Company.builder().name("Existing Studio").sourceId("existing-1").build());

    var updatedExisting = Company.builder().name("Updated Studio").sourceId("existing-1").build();
    var brandNew = Company.builder().name("Brand New Studio").sourceId("new-1").build();

    var result = companyService.getOrCreateCompanies(Set.of(updatedExisting, brandNew), Map.of());

    var returnedNew =
        result.stream().filter(c -> "new-1".equals(c.getSourceId())).findFirst().orElseThrow();
    assertThat(returnedNew.getName()).isEqualTo("Brand New Studio");
    assertThat(returnedNew.getId()).isNotNull();
    assertThat(companyRepository.count()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should publish MetadataEnrichedEvent when creating new company with image sources")
  void shouldPublishMetadataEnrichedEventWhenCreatingNewCompanyWithImageSources() {
    var company = Company.builder().name("Warner Bros.").sourceId("wb-123").build();
    var imageSources =
        Map.<String, List<ImageSource>>of(
            "wb-123", List.of(new TmdbImageSource(ImageType.LOGO, "/wb.png")));

    companyService.getOrCreateCompanies(Set.of(company), imageSources);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().entityType()).isEqualTo(ImageEntityType.COMPANY);
  }

  @Test
  @DisplayName("Should not publish event when company already exists")
  void shouldNotPublishEventWhenCompanyAlreadyExists() {
    companyRepository.save(Company.builder().name("Existing").sourceId("wb-123").build());

    var updated = Company.builder().name("Updated").sourceId("wb-123").build();
    var imageSources =
        Map.<String, List<ImageSource>>of(
            "wb-123", List.of(new TmdbImageSource(ImageType.LOGO, "/wb.png")));

    companyService.getOrCreateCompanies(Set.of(updated), imageSources);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).isEmpty();
  }

  @Test
  @DisplayName("Should throw when company source ID is null")
  void shouldThrowWhenCompanySourceIdIsNull() {
    var company = Company.builder().name("Warner Bros.").sourceId(null).build();
    var companies = Set.of(company);

    var emptyImageSources = Map.<String, List<ImageSource>>of();

    assertThatThrownBy(() -> companyService.getOrCreateCompanies(companies, emptyImageSources))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should return empty set when input is null")
  void shouldReturnEmptySetWhenInputIsNull() {
    var result = companyService.getOrCreateCompanies(null, Map.of());

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should throw when company not found after upsert")
  void shouldThrowWhenCompanyNotFoundAfterUpsert() {
    var stubRepository = mock(CompanyRepository.class);
    when(stubRepository.findBySourceId("wb-123")).thenReturn(Optional.empty());
    var service = new CompanyService(stubRepository, new CapturingEventPublisher());

    var company = Company.builder().name("Warner Bros.").sourceId("wb-123").build();

    assertThatThrownBy(() -> service.getOrCreateCompanies(Set.of(company), Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not found after upsert");
  }
}
