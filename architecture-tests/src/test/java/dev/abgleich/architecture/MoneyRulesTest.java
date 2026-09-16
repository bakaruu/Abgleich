package dev.abgleich.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static dev.abgleich.architecture.ProductionClasses.ALL;
import static dev.abgleich.architecture.ProductionClasses.DOMAIN;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import dev.abgleich.domain.archfixture.FloatingPointInvoice;
import org.junit.jupiter.api.Test;

class MoneyRulesTest {

    private static final String BECAUSE = "binary floating point cannot represent money exactly; use Money";

    static final ArchRule NO_FLOATING_POINT_FIELDS = noFields()
            .that().areDeclaredInClassesThat().resideInAPackage(DOMAIN)
            .should().haveRawType(double.class)
            .orShould().haveRawType(Double.class)
            .orShould().haveRawType(float.class)
            .orShould().haveRawType(Float.class)
            .because(BECAUSE);

    static final ArchRule NO_FLOATING_POINT_RETURN_TYPES = noMethods()
            .that().areDeclaredInClassesThat().resideInAPackage(DOMAIN)
            .should().haveRawReturnType(double.class)
            .orShould().haveRawReturnType(Double.class)
            .orShould().haveRawReturnType(float.class)
            .orShould().haveRawReturnType(Float.class)
            .because(BECAUSE);

    @Test
    void B01_no_floating_point_in_domain() {
        NO_FLOATING_POINT_FIELDS.check(ALL);
        NO_FLOATING_POINT_RETURN_TYPES.check(ALL);
    }

    @Test
    void B01_rule_catches_a_double_amount() {
        JavaClasses offending = new ClassFileImporter().importClasses(FloatingPointInvoice.class);

        assertThatThrownBy(() -> NO_FLOATING_POINT_FIELDS.check(offending)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> NO_FLOATING_POINT_RETURN_TYPES.check(offending)).isInstanceOf(AssertionError.class);
    }
}
