package com.siddheshfulawade.settlementlab;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Package rules from SPEC.md §4 (REQ-LAB-016). Only production classes are checked: tests may use
 * any test library.
 *
 * <p>Rules for packages that do not exist yet allow an empty match, so the build stays green while
 * the engine grows task by task. When a package gets its first class, remove {@code allowEmptyShould}
 * from its rule so that a rename cannot silently switch the rule off.
 */
@AnalyzeClasses(packages = ArchitectureTest.BASE, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String BASE = "com.siddheshfulawade.settlementlab";

    private static final String JAVA = "java..";
    private static final String DOMAIN = BASE + ".domain..";
    private static final String IDEMPOTENCY = BASE + ".idempotency..";
    private static final String PIPELINE = BASE + ".pipeline..";
    private static final String GL = BASE + ".gl..";
    private static final String RESILIENCE = BASE + ".resilience..";
    private static final String RESILIENCE4J = "io.github.resilience4j..";

    @ArchTest
    static final ArchRule domainImportsOnlyJava =
            onlyDependsOn(DOMAIN, JAVA, DOMAIN).because("REQ-LAB-016: the domain imports only java.*");

    @ArchTest
    static final ArchRule idempotencyImportsOnlyDomainAndJava = onlyDependsOn(IDEMPOTENCY, JAVA, DOMAIN, IDEMPOTENCY);

    @ArchTest
    static final ArchRule pipelineImportsOnlyDomainIdempotencyAndJava =
            onlyDependsOn(PIPELINE, JAVA, DOMAIN, IDEMPOTENCY, PIPELINE);

    @ArchTest
    static final ArchRule glImportsOnlyDomainAndJava = onlyDependsOn(GL, JAVA, DOMAIN, GL);

    @ArchTest
    static final ArchRule resilienceImportsOnlyDomainGlAndResilience4j = onlyDependsOn(
                    RESILIENCE, JAVA, DOMAIN, GL, RESILIENCE, RESILIENCE4J)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule noSpringAnywhere = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework..")
            .because("the engine stays framework-free so v2 can wrap it (SPEC.md section 4.7)");

    @ArchTest
    static final ArchRule packagesAreFreeOfCycles =
            slices().matching(BASE + ".(*)..").should().beFreeOfCycles();

    // TODO-ME: add a rule `moneyIsNeverFloatingPoint` (REQ-LAB-001): no field declared in a class in
    //  DOMAIN may have the raw type double, float, Double or Float. Hint: start from
    //  ArchRuleDefinition.noFields().that().areDeclaredInClassesThat().resideInAPackage(DOMAIN).
    //  Check it: run `./mvnw test -Dtest=ArchitectureTest` (green), then add a `double` field to
    //  Money, run it again and see your rule fail with Money named in the message. Remove the field.

    private static ArchRule onlyDependsOn(String subject, String... allowed) {
        return classes()
                .that()
                .resideInAPackage(subject)
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(allowed);
    }
}
