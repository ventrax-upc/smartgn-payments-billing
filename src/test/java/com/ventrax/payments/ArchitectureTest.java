package com.ventrax.payments;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

class ArchitectureTest {
    @Test
    void domainIsIndependent() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "com.ventrax.payments.adapter..",
                        "com.ventrax.payments.configuration..",
                        "org.postgresql..",
                        "tools.jackson..")
                .check(
                        new com.tngtech.archunit.core.importer.ClassFileImporter()
                                .importPackages("com.ventrax.payments"));
    }
}
