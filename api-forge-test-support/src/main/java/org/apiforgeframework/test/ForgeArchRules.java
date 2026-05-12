package org.apiforgeframework.test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.Architectures;

/**
 * Pre-built ArchUnit rules for API-Forge-based services.
 *
 * <p>Import into your architecture test class:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "com.company.orders")
 * class ArchitectureTest {
 *
 *     @ArchTest
 *     static final ArchRule layering = ForgeArchRules.layeringRule();
 *
 *     @ArchTest
 *     static final ArchRule noServiceImplInController =
 *             ForgeArchRules.controllersMustNotDependOnServiceImpl();
 *
 *     @ArchTest
 *     static final ArchRule generatedNotEdited =
 *             ForgeArchRules.generatedClassesMustBeAnnotated();
 * }
 * }</pre>
 */
public final class ForgeArchRules {

    private ForgeArchRules() {}

    /**
     * Enforces clean layering:
     * controller → service.api → (service.impl is internal)
     * client → (only called from service.impl)
     */
    public static ArchRule layeringRule() {
        return Architectures.layeredArchitecture()
                .consideringAllDependencies()
                .layer("Controller").definedBy("..controller..")
                .layer("ServiceApi").definedBy("..service.api..")
                .layer("ServiceImpl").definedBy("..service.impl..")
                .layer("Client").definedBy("..client..")
                .layer("Model").definedBy("..model..")
                .whereLayer("Controller").mayOnlyAccessLayers("ServiceApi", "Model")
                .whereLayer("ServiceImpl").mayOnlyAccessLayers("ServiceApi", "Client", "Model")
                .whereLayer("Client").mayOnlyAccessLayers("Model")
                .whereLayer("Model").mayNotAccessAnyLayer();
    }

    /**
     * Controllers must only depend on the generated service interface,
     * never on the hand-written implementation directly.
     */
    public static ArchRule controllersMustNotDependOnServiceImpl() {
        return ArchRuleDefinition.noClasses()
                .that().resideInAPackage("..controller..")
                .should().dependOnClassesThat()
                .resideInAPackage("..service.impl..")
                .because("Controllers must depend on the service interface, not the implementation. " +
                         "Spring wires the implementation via the interface.");
    }

    /**
     * Service implementations must not depend on each other directly.
     * Cross-service calls must go through the client layer.
     */
    public static ArchRule serviceImplsMustNotDependOnEachOther() {
        return ArchRuleDefinition.noClasses()
                .that().resideInAPackage("..service.impl..")
                .should().dependOnClassesThat()
                .resideInAPackage("..service.impl..")
                .because("Service implementations must not call each other directly. " +
                         "Use a generated egress client or a domain event.");
    }

    /**
     * All generated controller classes must carry @ForgeGenerated.
     */
    public static ArchRule generatedClassesMustBeAnnotated() {
        return ArchRuleDefinition.classes()
                .that().resideInAPackage("..controller..")
                .and().haveSimpleNameEndingWith("Controller")
                .should().beAnnotatedWith("org.apiforgeframework.annotations.ForgeGenerated")
                .because("All generated controllers must be annotated with @ForgeGenerated " +
                         "for coverage exclusion and tooling support.");
    }

    /**
     * Egress client classes must not be instantiated directly — only injected.
     * Prevents bypassing the ForgeEgressClientFactory configuration.
     */
    public static ArchRule clientsMustNotBeInstantiatedDirectly() {
        return ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackages("..client..", "..test..")
                .should().callConstructor(
                        org.apiforgeframework.runtime.client.ForgeEgressClientFactory.class)
                .because("Egress clients are managed by ForgeEgressClientFactory. " +
                         "Inject them as Spring beans, do not instantiate them manually.");
    }

    /**
     * DTOs in the model package must not reference domain/service classes.
     * Keeps the model layer pure and serializable.
     */
    public static ArchRule modelMustNotDependOnServices() {
        return ArchRuleDefinition.noClasses()
                .that().resideInAPackage("..model..")
                .should().dependOnClassesThat()
                .resideInAPackage("..service..")
                .because("Model/DTO classes must be pure data containers with no service dependencies.");
    }

    /**
     * Runs all forge rules against the given base package. Convenience method
     * for teams who want all rules without listing them individually.
     */
    public static void assertAllRules(String basePackage) {
        JavaClasses classes = new ClassFileImporter().importPackages(basePackage);
        layeringRule().check(classes);
        controllersMustNotDependOnServiceImpl().check(classes);
        serviceImplsMustNotDependOnEachOther().check(classes);
        generatedClassesMustBeAnnotated().check(classes);
        modelMustNotDependOnServices().check(classes);
    }
}
