package com.siddheshfulawade.settlementlab.postgres;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Package rules for the PostgreSQL adapter (SPEC.md section 16). Not tagged database: it needs no
 * container, so it runs on every OS. Only production classes are checked.
 *
 * <p>The adapter package gets its first class in V3-01 ({@code LedgerSchema}), so no rule here
 * allows an empty match: if the package is renamed, these rules fail instead of passing silently.
 */
@AnalyzeClasses(packages = "com.siddheshfulawade.settlementlab", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String POSTGRES = "com.siddheshfulawade.settlementlab.postgres..";

    @ArchTest
    static final ArchRule postgresDependsOnlyOnJdbcEngineAndFlyway = classes()
            .that()
            .resideInAPackage(POSTGRES)
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "javax.sql..", "com.siddheshfulawade.settlementlab..", "org.flywaydb..");

    @ArchTest
    static final ArchRule postgresUsesNoSpring = noClasses()
            .that()
            .resideInAPackage(POSTGRES)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework..")
            .because("the adapter stays framework-free; only the api module may use Spring");

    @ArchTest
    static final ArchRule postgresUsesNoJpaOrHibernate = noClasses()
            .that()
            .resideInAPackage(POSTGRES)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("jakarta.persistence..", "javax.persistence..", "org.hibernate..")
            .because("SPEC.md section 16: plain JDBC, so every SQL statement is visible");

    @ArchTest
    static final ArchRule engineNeverDependsOnPostgres = noClasses()
            .that()
            .resideOutsideOfPackage(POSTGRES)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(POSTGRES)
            .because("dependencies point inward: the engine knows nothing of the database");
}
