package org.apiforgeframework.test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.*;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.*;

/**
 * Composite test annotation for testing generated egress clients against a WireMock server.
 *
 * <p>Automatically:
 * <ul>
 *   <li>Starts a WireMock server on a random port</li>
 *   <li>Sets {@code forge.egress.<clientName>.base-url} to point at it</li>
 *   <li>Loads stub mappings from {@code src/test/resources/wiremock/mappings/}</li>
 *   <li>Injects both the client under test and the WireMock server into the test class</li>
 * </ul>
 *
 * <pre>{@code
 * @ForgeEgressTest(clientName = "inventory")
 * class InventoryApiClientTest {
 *
 *     @Autowired InventoryApiClient client;
 *     @Autowired WireMockServer wireMock;
 *
 *     @Test
 *     void reserveStock_returnsReservation() {
 *         wireMock.stubFor(post("/inventory/reserve")
 *             .willReturn(okJson("""{"reservationId":"res_123","status":"CONFIRMED"}""")));
 *
 *         var result = client.reserveStock(List.of(new StockReservationRequest("SKU-1", 2)));
 *
 *         assertThat(result.getReservationId()).isEqualTo("res_123");
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ExtendWith(ForgeEgressTest.WireMockExtension.class)
public @interface ForgeEgressTest {

    /**
     * The logical client name — must match {@code forge.egress.<clientName>} in config.
     * The extension sets {@code forge.egress.<clientName>.base-url=http://localhost:<port>}.
     */
    String clientName();

    /**
     * WireMock extension — starts the server before tests and tears it down after.
     * Injects the server into the Spring context so tests can stub it.
     */
    @Slf4j
    class WireMockExtension implements BeforeAllCallback, AfterAllCallback,
            BeforeEachCallback, ParameterResolver {

        private static final String WIREMOCK_KEY = "forge.wiremock.server";

        @Override
        public void beforeAll(ExtensionContext context) {
            WireMockServer server = new WireMockServer(
                    WireMockConfiguration.wireMockConfig()
                            .dynamicPort()
                            .usingFilesUnderClasspath("wiremock"));
            server.start();
            context.getStore(ExtensionContext.Namespace.GLOBAL).put(WIREMOCK_KEY, server);

            ForgeEgressTest annotation = context.getRequiredTestClass()
                    .getAnnotation(ForgeEgressTest.class);
            String clientName = annotation.clientName();
            String baseUrl = "http://localhost:" + server.port();

            // Override the base-url property for this test run
            System.setProperty("forge.egress." + clientName + ".base-url", baseUrl);
            log.info("ForgeEgressTest: WireMock started for client '{}' at {}", clientName, baseUrl);
        }

        @Override
        public void beforeEach(ExtensionContext context) {
            getServer(context).resetAll();
        }

        @Override
        public void afterAll(ExtensionContext context) {
            WireMockServer server = getServer(context);
            if (server != null && server.isRunning()) {
                server.stop();
                log.info("ForgeEgressTest: WireMock stopped");
            }
        }

        @Override
        public boolean supportsParameter(ParameterContext paramCtx, ExtensionContext extCtx) {
            return paramCtx.getParameter().getType().equals(WireMockServer.class);
        }

        @Override
        public Object resolveParameter(ParameterContext paramCtx, ExtensionContext extCtx) {
            return getServer(extCtx);
        }

        private WireMockServer getServer(ExtensionContext context) {
            return (WireMockServer) context.getStore(ExtensionContext.Namespace.GLOBAL)
                    .get(WIREMOCK_KEY);
        }
    }
}
