package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.fakes.FakeCompanyRepository;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Company Service Tests")
class CompanyServiceTest {

  private FakeCompanyRepository companyRepository;
  private CompanyService companyService;

  @BeforeEach
  void setUp() {
    companyRepository = new FakeCompanyRepository();
    companyService = new CompanyService(companyRepository, new MutexFactoryProvider());
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
    assertThat(companyRepository.database).hasSize(1);
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
    assertThat(companyRepository.database).hasSize(1);
  }

  @Test
  @DisplayName("Should handle multiple companies with mix of new and existing")
  void shouldHandleMultipleCompaniesWithMixOfNewAndExisting() {
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

    assertThat(result).hasSize(2);
    assertThat(companyRepository.database).hasSize(2);

    var returnedExisting =
        result.stream().filter(c -> "existing-1".equals(c.getSourceId())).findFirst().orElseThrow();
    assertThat(returnedExisting.getId()).isEqualTo(existing.getId());
    assertThat(returnedExisting.getName()).isEqualTo("Updated Studio");
    assertThat(returnedExisting.getLogoPath()).isEqualTo("/updated.png");

    var returnedNew =
        result.stream().filter(c -> "new-1".equals(c.getSourceId())).findFirst().orElseThrow();
    assertThat(returnedNew.getName()).isEqualTo("Brand New Studio");
    assertThat(returnedNew.getLogoPath()).isEqualTo("/brand-new.png");
    assertThat(returnedNew.getId()).isNotNull();
  }
}
