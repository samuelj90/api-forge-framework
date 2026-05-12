package com.company.orders.contract;

import org.apiforgeframework.test.ForgeArchRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit tests enforcing API-Forge architectural conventions.
 *
 * <p>These rules run at build time (no JVM startup cost — bytecode analysis only).
 * Violations cause the build to fail with a clear, actionable message.
 *
 * <p>All rules are pre-built in {@link ForgeArchRules} — you just declare them.
 * Add project-specific rules below as needed.
 */
@AnalyzeClasses(
        packages = "com.company.orders",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureTest {

    /**
     * Clean layer separation:
     * controller → service.api → (service.impl internal)
     * client → (callable only from service.impl)
     */
    @ArchTest
    static final ArchRule layeringIsRespected =
            ForgeArchRules.layeringRule();

    /**
     * Controllers must inject the service interface, not the implementation.
     * If this fires: you have a @Autowired ServiceImpl in a controller.
     */
    @ArchTest
    static final ArchRule controllersMustUseServiceInterface =
            ForgeArchRules.controllersMustNotDependOnServiceImpl();

    /**
     * Service implementations must not call each other directly.
     * Cross-service communication must go through generated egress clients.
     */
    @ArchTest
    static final ArchRule noDirectServiceToServiceCalls =
            ForgeArchRules.serviceImplsMustNotDependOnEachOther();

    /**
     * DTOs in the model package must be pure data containers.
     * They must not reference service or repository classes.
     */
    @ArchTest
    static final ArchRule modelMustBeClean =
            ForgeArchRules.modelMustNotDependOnServices();

    // ── Project-specific rules ────────────────────────────────────────────

    // Example: enforce that all domain exceptions extend ForgeException
    // @ArchTest
    // static final ArchRule customExceptionsMustExtendForgeException =
    //         ArchRuleDefinition.classes()
    //                 .that().resideInAPackage("..exception..")
    //                 .and().haveSimpleNameEndingWith("Exception")
    //                 .should().beAssignableTo(ForgeException.class)
    //                 .because("All domain exceptions must extend ForgeException " +
    //                          "to be handled by ForgeExceptionHandler.");
}
