package dev.abgleich.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/** Production classes of every module, imported once and shared by all rule tests. */
final class ProductionClasses {

    static final String ROOT = "dev.abgleich";
    static final String DOMAIN = "dev.abgleich.domain..";
    static final String APPLICATION = "dev.abgleich.application..";
    static final String ADAPTERS = "dev.abgleich.adapter..";
    static final String BOOTSTRAP = "dev.abgleich.bootstrap..";

    static final JavaClasses ALL = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    private ProductionClasses() {
    }
}
