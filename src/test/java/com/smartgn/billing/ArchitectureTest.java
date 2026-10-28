package com.smartgn.billing;

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
                        "com.smartgn.billing.adapter..",
                        "com.smartgn.billing.configuration..",
                        "org.postgresql..",
                        "tools.jackson..")
                .check(
                        new com.tngtech.archunit.core.importer.ClassFileImporter()
                                .importPackages("com.smartgn.billing"));
    }
}
