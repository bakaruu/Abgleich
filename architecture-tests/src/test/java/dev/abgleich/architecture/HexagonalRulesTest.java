package dev.abgleich.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static dev.abgleich.architecture.ProductionClasses.ADAPTERS;
import static dev.abgleich.architecture.ProductionClasses.ALL;
import static dev.abgleich.architecture.ProductionClasses.APPLICATION;
import static dev.abgleich.architecture.ProductionClasses.BOOTSTRAP;
import static dev.abgleich.architecture.ProductionClasses.DOMAIN;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class HexagonalRulesTest {

    @Test
    void domain_depends_on_nothing_else_in_the_system() {
        noClasses().that().resideInAPackage(DOMAIN)
                .should().dependOnClassesThat().resideInAnyPackage(APPLICATION, ADAPTERS, BOOTSTRAP)
                .check(ALL);
    }

    @Test
    void application_depends_only_on_the_domain() {
        noClasses().that().resideInAPackage(APPLICATION)
                .should().dependOnClassesThat().resideInAnyPackage(ADAPTERS, BOOTSTRAP)
                .allowEmptyShould(true)
                .check(ALL);
    }

    @Test
    void B36_core_has_no_framework_dependencies() {
        noClasses().that().resideInAnyPackage(DOMAIN, APPLICATION)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "com.fasterxml.jackson..",
                        "tools.jackson..",
                        "org.apache.kafka..")
                .check(ALL);
    }

    @Test
    void B37_domain_has_no_public_setters() {
        methods().that().areDeclaredInClassesThat().resideInAPackage(DOMAIN)
                .and().haveNameMatching("set[A-Z].*")
                .should().notBePublic()
                .because("domain objects change through intention-revealing methods that protect invariants")
                .allowEmptyShould(true)
                .check(ALL);
    }

    @Test
    void B38_core_never_reads_the_system_clock() {
        noClasses().that().resideInAnyPackage(DOMAIN, APPLICATION)
                .should().callMethod(LocalDate.class, "now")
                .orShould().callMethod(LocalDateTime.class, "now")
                .orShould().callMethod(Instant.class, "now")
                .orShould().callMethod(ZonedDateTime.class, "now")
                .orShould().callMethod(OffsetDateTime.class, "now")
                .orShould().callMethod(Clock.class, "systemUTC")
                .orShould().callMethod(Clock.class, "systemDefaultZone")
                .orShould().callMethod(System.class, "currentTimeMillis")
                .because("time must come from an injected Clock so rules are testable")
                .check(ALL);
    }

    @Test
    void B39_core_never_handles_technical_exceptions() {
        noClasses().that().resideInAnyPackage(DOMAIN, APPLICATION)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.sql..",
                        "javax.sql..",
                        "javax.xml.stream..",
                        "org.postgresql..",
                        "org.xml.sax..")
                .because("adapters translate technical errors into InvalidStatementException, "
                        + "DuplicateImportException or StorageException")
                .check(ALL);
    }

    @Test
    void B40_adapters_do_not_depend_on_other_adapters() {
        slices().matching("dev.abgleich.adapter.(*).(*)..")
                .should().notDependOnEachOther()
                .allowEmptyShould(true)
                .check(ALL);
    }

    @Test
    void B40_adapters_never_depend_on_bootstrap() {
        noClasses().that().resideInAPackage(ADAPTERS)
                .should().dependOnClassesThat().resideInAPackage(BOOTSTRAP)
                .allowEmptyShould(true)
                .check(ALL);
    }
}
