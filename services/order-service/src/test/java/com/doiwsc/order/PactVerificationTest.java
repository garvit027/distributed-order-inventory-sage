package com.doiwsc.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import au.com.dius.pact.provider.ConsumersGroup;
import au.com.dius.pact.provider.ProviderInfo;
import au.com.dius.pact.provider.ProviderVerifier;
import au.com.dius.pact.provider.IConsumerInfo;
import au.com.dius.pact.provider.VerificationResult;
import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Pact provider verification (docs/CONTRACT.md §3.8) using the pact-jvm
 * programmatic verifier against a live {@code @SpringBootTest(RANDOM_PORT)}
 * instance. Provider states are served by the {@code @Profile("pact")}
 * {@link com.doiwsc.order.pact.PactStateController}.
 *
 * <p>Pact files are read from the directory given by the {@code pact.dir} system
 * property or {@code PACT_DIR} env var (default {@code <repo>/pacts}). When no
 * pact files are present the test skips cleanly via {@code assumeTrue}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("pact")
@Import(FakeOrchestratorConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:order-pact-test;DB_CLOSE_DELAY=-1",
        "grpc.port=0"
})
class PactVerificationTest {

    @LocalServerPort
    private int port;

    private static File pactDirectory() {
        String prop = System.getProperty("pact.dir");
        if (prop == null || prop.isBlank()) {
            prop = System.getenv("PACT_DIR");
        }
        if (prop == null || prop.isBlank()) {
            prop = "../../pacts";
        }
        return Path.of(prop).toAbsolutePath().normalize().toFile();
    }

    @Test
    void verifyPactContracts() throws Exception {
        File dir = pactDirectory();
        File[] pacts = dir.isDirectory()
                ? dir.listFiles((d, name) -> name.endsWith(".json"))
                : new File[0];
        assumeTrue(pacts != null && pacts.length > 0,
                "No pact files in " + dir.getPath() + " - skipping provider verification");

        ProviderInfo provider = new ProviderInfo("order-service");
        provider.setProtocol("http");
        provider.setHost("localhost");
        provider.setPort(port);
        provider.setPath("/");
        URL stateChangeUrl = new URL("http://localhost:" + port + "/pact/state");
        provider.setStateChangeUrl(stateChangeUrl);
        provider.setStateChangeUsesBody(true);

        // The "include" parameter is a Kotlin non-null Regex, so Java null trips
        // an Intrinsics.checkNotNullParameter NPE. It is matched against every
        // file name in the directory (including .gitkeep), so restrict it to
        // pact JSON files - otherwise a zero-byte placeholder is parsed as JSON.
        ConsumersGroup group = new ConsumersGroup("order-service", dir,
                stateChangeUrl, true, false, new kotlin.text.Regex(".*\\.json"));
        List<IConsumerInfo> consumers = provider.setupConsumerListFromPactFiles(group);
        assumeTrue(consumers != null && !consumers.isEmpty(),
                "No consumers loaded from " + dir.getPath());

        ProviderVerifier verifier = new ProviderVerifier();
        List<VerificationResult> results = verifier.verifyProvider(provider);

        List<VerificationResult.Failed> failures = results.stream()
                .filter(r -> r instanceof VerificationResult.Failed)
                .map(r -> (VerificationResult.Failed) r)
                .toList();
        if (!failures.isEmpty()) {
            verifier.displayFailures(failures);
        }
        assertThat(failures)
                .as("pact verification failures")
                .isEmpty();
    }
}
