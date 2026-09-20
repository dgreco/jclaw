// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Machine-checks the dependency law that the {@code package-info.java} files describe.
 *
 * <p>Until this existed, the law was a comment. Every module's javadoc claimed ArchUnit enforced
 * it in {@code jclaw-bootstrap}, and nothing did — so a violating import would have compiled, passed
 * review by looking plausible, and quietly dissolved the layering the whole design rests on.
 * IronClaw checks its seven-layer matrix in {@code ironclaw_architecture_tests} for the same
 * reason: an architecture nobody can violate accidentally is worth more than one everybody agrees
 * about.
 *
 * <p>Lives in {@code jclaw-bootstrap} because that is the only module with every other module on its
 * classpath, which is what makes the whole graph visible to a single import.
 */
class DependencyLawTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importProductionClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.jclaw");
    }

    @Test
    @DisplayName("the layer ladder holds: each layer is used only by the ones above it")
    void layerLadder() {
        ArchRule ladder = layeredArchitecture()
                .consideringOnlyDependenciesInLayers()

                .layer("contracts").definedBy("io.jclaw.ports..")
                .layer("domain").definedBy("io.jclaw.domain..")
                .layer("kernel").definedBy("io.jclaw.application.authority..")
                .layer("loop").definedBy("io.jclaw.application.usecase..")
                .layer("providers").definedBy("io.jclaw.adapter.out.model..")
                .layer("tools").definedBy("io.jclaw.adapter.out.capability..")
                .layer("storage").definedBy("io.jclaw.adapter.out.persistence..")
                .layer("app").definedBy("io.jclaw.bootstrap..")

                // Read bottom-up: contracts is used by everything; app is used by nothing.
                .whereLayer("app").mayNotBeAccessedByAnyLayer()
                .whereLayer("providers").mayOnlyBeAccessedByLayers("app")
                .whereLayer("tools").mayOnlyBeAccessedByLayers("app")
                .whereLayer("storage").mayOnlyBeAccessedByLayers("app")
                .whereLayer("loop").mayOnlyBeAccessedByLayers("app")
                .whereLayer("kernel").mayOnlyBeAccessedByLayers("loop", "tools", "storage", "app")
                .whereLayer("domain").mayOnlyBeAccessedByLayers(
                        "kernel", "loop", "tools", "storage", "app")
                .whereLayer("contracts").mayOnlyBeAccessedByLayers(
                        "domain", "kernel", "loop", "providers", "tools", "storage", "app");

        ladder.check(classes);
    }

    @Nested
    @DisplayName("the ports module stays neutral")
    class ContractsNeutrality {

        @Test
        @DisplayName("no Spring")
        void noSpring() {
            noClasses().that().resideInAPackage("io.jclaw.ports..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                    .because("contracts is the boundary vocabulary; binding it to a framework "
                            + "would force every adapter to adopt that framework too")
                    .check(classes);
        }

        @Test
        @DisplayName("no Jackson databind — annotations only")
        void noJacksonDatabind() {
            noClasses().that().resideInAPackage("io.jclaw.ports..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("tools.jackson..", "com.fasterxml.jackson.databind..")
                    .because("serialization is an adapter detail; contracts describes shapes, "
                            + "not how they are written to disk or a wire")
                    .check(classes);
        }

        @Test
        @DisplayName("no JDBC and no HTTP")
        void noJdbcOrHttp() {
            noClasses().that().resideInAPackage("io.jclaw.ports..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("java.sql..", "javax.sql..", "java.net.http..")
                    .because("a port must not name the transport that happens to implement it")
                    .check(classes);
        }
    }

    @Nested
    @DisplayName("the domain is a pure functional core")
    class DomainPurity {

        @Test
        @DisplayName("no Spring, no I/O, no JDBC, no HTTP")
        void noInfrastructure() {
            noClasses().that().resideInAPackage("io.jclaw.domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "java.sql..",
                            "java.net..",
                            "java.io..",
                            "tools.jackson..",
                            "com.fasterxml.jackson.databind..")
                    .because("the domain is the pure core: same inputs, same outputs, no world")
                    .check(classes);
        }

        @Test
        @DisplayName("never reads the clock — time arrives as a parameter")
        void noAmbientClock() {
            // This is the invariant that makes budget exhaustion and cron scheduling reproducible.
            // A single Instant.now() anywhere in here would make a whole class of behaviour
            // untestable without waiting for real time to pass.
            noClasses().that().resideInAPackage("io.jclaw.domain..")
                    .should().callMethod(java.time.Instant.class, "now")
                    .orShould().callMethod(java.time.LocalDateTime.class, "now")
                    .orShould().callMethod(java.time.ZonedDateTime.class, "now")
                    .orShould().callMethod(System.class, "currentTimeMillis")
                    .because("the domain takes 'now' as an argument so replay is exact")
                    .check(classes);
        }

        @Test
        @DisplayName("no randomness — determinism is the point")
        void noRandomness() {
            noClasses().that().resideInAPackage("io.jclaw.domain..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("java.util.Random")
                    .orShould().callMethod(Math.class, "random")
                    .because("a random draw in the core would make transcripts irreproducible")
                    .check(classes);
        }
    }

    @Nested
    @DisplayName("the application talks to ports, never to adapters")
    class LoopIsolation {

        @Test
        @DisplayName("never imports a secondary adapter")
        void noAdapters() {
            noClasses().that().resideInAPackage("io.jclaw.application.usecase..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("io.jclaw.adapter.out.model..", "io.jclaw.adapter.out.capability..", "io.jclaw.adapter.out.persistence..")
                    .because("the loop requests effects through ports; naming a concrete adapter "
                            + "is what makes an agent loop impossible to substitute or test")
                    .check(classes);
        }

        @Test
        @DisplayName("no Spring in the application layer")
        void noSpring() {
            noClasses().that().resideInAPackage("io.jclaw.application.usecase..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                    .check(classes);
        }
    }

    @Nested
    @DisplayName("vendor SDKs stay behind their secondary adapters")
    class ProviderContainment {

        @Test
        @DisplayName("only the anthropic adapter imports the Anthropic SDK")
        void anthropicSdkContained() {
            noClasses().that().resideOutsideOfPackage("io.jclaw.adapter.out.model.anthropic..")
                    .should().dependOnClassesThat().resideInAPackage("com.anthropic..")
                    .because("the point of the ModelProvider port is that swapping or upgrading a "
                            + "vendor SDK touches one adapter and nothing else")
                    .check(classes);
        }

        @Test
        @DisplayName("only the wasm lane imports the WebAssembly runtime")
        void wasmRuntimeContained() {
            // The same argument as the SDK rule above, and it has already been collected on:
            // moving from Chicory to Endive was a package rename in exactly one file, because
            // nothing else had ever reached for the runtime. That was true by habit rather than
            // by construction, which is the difference this rule closes.
            noClasses().that().resideOutsideOfPackage("io.jclaw.adapter.out.capability.wasm..")
                    .should().dependOnClassesThat().resideInAPackage("run.endive..")
                    .because("a WebAssembly runtime is a vendor engine like any other: swapping "
                            + "one should touch a single lane, not the harness")
                    .check(classes);
        }

        @Test
        @DisplayName("only the model adapter opens an HTTP client to a model")
        void modelTransportContained() {
            noClasses().that().resideInAnyPackage(
                            "io.jclaw.ports..", "io.jclaw.domain..",
                            "io.jclaw.application.authority..", "io.jclaw.application.usecase..")
                    .should().dependOnClassesThat().resideInAPackage("java.net.http..")
                    .because("outbound HTTP belongs to adapters, where the egress guard applies")
                    .check(classes);
        }
    }

    @Nested
    @DisplayName("security invariants")
    class SecurityInvariants {

        @Test
        @DisplayName("capability adapters never read the process environment")
        void toolsDoNotReadEnvironment() {
            // Tools must not hold credentials. Reading System.getenv would sidestep both the
            // secret-free HandlerContext and the environment scrub ShellTool applies to children.
            noClasses().that().resideInAPackage("io.jclaw.adapter.out.capability..")
                    .should().callMethod(System.class, "getenv")
                    .orShould().callMethod(System.class, "getenv", String.class)
                    .because("credentials are minted at the provider boundary, never in a tool")
                    .check(classes);
        }

        @Test
        @DisplayName("secondary adapters never hold the secret vault")
        void vaultStaysAboveTheLanes() {
            noClasses().that().resideInAnyPackage("io.jclaw.adapter.out.capability..", "io.jclaw.adapter.out.model..", "io.jclaw.application.usecase..")
                    .should().dependOnClassesThat().resideInAPackage("io.jclaw.ports.secret..")
                    .because("a secret is leased by the kernel host into one call's arguments; a "
                            + "lane that could look secrets up would be the thing worth attacking")
                    .check(classes);
        }

        @Test
        @DisplayName("only the authority layer and the bootstrap decide capability policy")
        void policyIsKernelOwned() {
            noClasses().that().resideInAnyPackage("io.jclaw.adapter.out.capability..", "io.jclaw.adapter.out.model..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("io.jclaw.application.authority.capability..")
                    .because("a lane that can see policy is a lane that can be tempted to "
                            + "re-implement it; authorization happens above, once")
                    .check(classes);
        }
    }

    @Nested
    @DisplayName("the wiring survives ahead-of-time compilation")
    class AotInvariants {

        @Test
        @DisplayName("no bean method returns an Optional")
        void beansAreNotOptional() {
            // On the JVM a bean method returning Optional works, so nothing here fails. Under AOT
            // — how the native image is built — the bean comes from a generated instance supplier,
            // and Spring wraps the returned object in a BeanWrapperImpl whose constructor unwraps
            // an Optional before asserting the target is non-null. An empty one therefore takes
            // the entire context down at startup, on every command; a present one is silently
            // unwrapped and registered under the wrong type. This cost a working binary once
            // (see LoginProvider), and the whole test suite was green while it did.
            methods().that().areAnnotatedWith(org.springframework.context.annotation.Bean.class)
                    .should().notHaveRawReturnType(java.util.Optional.class)
                    .because("a bean of type Optional cannot be instantiated by the AOT supplier "
                            + "path, so the failure appears only in the native image")
                    .check(classes);
        }
    }
}
