package com.cse444.agentgate;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Automated Architecture Verification (Requirement C6).
 * Enforces both Spring Modulith bounded contexts and ArchUnit structural rules.
 */
@AnalyzeClasses(
        packages = "com.cse444.agentgate",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureRulesTest {

    @Test
    void verifiesSpringModulithBoundaries() {
        ApplicationModules modules = ApplicationModules.of(AgentGateApplication.class);
        modules.verify();
    }

    @Test
    void generatesModulithComponentDocumentation() {
        ApplicationModules modules = ApplicationModules.of(AgentGateApplication.class);
        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml();
    }

    @ArchTest
    static final ArchRule topLevelModulesShouldBeFreeOfCycles =
            SlicesRuleDefinition.slices()
                    .matching("com.cse444.agentgate.(*)..")
                    .should().beFreeOfCycles()
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule auditModuleMustBeEventDrivenAndNeverCalledDirectly =
            noClasses()
                    .that().resideOutsideOfPackage("..audit..")
                    .should().dependOnClassesThat().resideInAPackage("..audit..")
                    .because("FR7 requires audit trail to be decoupled via Spring Application Events and @TransactionalEventListener")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersMustNotAccessRepositoriesDirectly =
            noClasses()
                    .that().areAnnotatedWith(RestController.class)
                    .should().dependOnClassesThat().areAnnotatedWith(Repository.class)
                    .because("Controllers must delegate through application/domain services rather than bypassing business rules")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainModulesShouldNotDependOnGatewayOrchestrator =
            noClasses()
                    .that().resideInAnyPackage(
                            "..registry..",
                            "..identity..",
                            "..policy..",
                            "..guard..",
                            "..approval..",
                            "..audit.."
                    )
                    .should().dependOnClassesThat().resideInAPackage("..gateway..")
                    .because("Gateway is the top-level orchestrator; core modules must not depend upward on gateway")
                    .allowEmptyShould(true);
}
