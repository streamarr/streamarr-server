package com.streamarr.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Worker Package Independence Tests")
class WorkerPackageIndependenceReviewTest {

  @Test
  @DisplayName("Should remain independent of server classes when importing production worker code")
  void shouldRemainIndependentOfServerClassesWhenImportingProductionWorkerCode() {
    var classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.streamarr.transcode");

    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.streamarr.server..")
        .check(classes);
  }
}
