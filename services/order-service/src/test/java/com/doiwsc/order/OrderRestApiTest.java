package com.doiwsc.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("pact")
@Import(FakeOrchestratorConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:order-rest-test;DB_CLOSE_DELAY=-1",
        "grpc.port=0"
})
class OrderRestApiTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FakeOrchestratorConfig.RecordingGateway gateway;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String VALID_BODY = """
            {"customerId":"cus_ok","contact":"buyer@example.com","currency":"USD",
             "amountCents":2500,"items":[{"sku":"WIDGET-RED","quantity":2}]}
            """;

    private ResponseEntity<JsonNode> post(String body, String idemKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idemKey != null) {
            headers.set("Idempotency-Key", idemKey);
        }
        return rest.postForEntity("/api/orders", new HttpEntity<>(body, headers), JsonNode.class);
    }

    @Test
    void createHappyPathReturns201AndPendingOrder() {
        ResponseEntity<JsonNode> resp = post(VALID_BODY, "rest-happy-1");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode order = resp.getBody();
        assertThat(order.get("orderId").asText()).startsWith("ord_");
        assertThat(order.get("status").asText()).isEqualTo("PENDING");
        assertThat(order.get("failureReason").isNull()).isTrue();
        assertThat(order.get("sagaId").asText()).isEqualTo("saga_" + order.get("orderId").asText());
        assertThat(order.get("customerId").asText()).isEqualTo("cus_ok");
        assertThat(order.get("amountCents").asLong()).isEqualTo(2500);
        assertThat(order.get("currency").asText()).isEqualTo("USD");
        assertThat(order.get("items")).hasSize(1);
        assertThat(order.get("items").get(0).get("sku").asText()).isEqualTo("WIDGET-RED");
        assertThat(order.get("createdAt").asText()).isNotBlank();

        String id = order.get("orderId").asText();
        // Saga was started asynchronously against the fake gateway.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(gateway.orderIds).contains(id));
    }

    @Test
    void idempotentReplayReturnsSameOrderWith200() {
        ResponseEntity<JsonNode> first = post(VALID_BODY, "rest-replay-1");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId = first.getBody().get("orderId").asText();

        ResponseEntity<JsonNode> replay = post(VALID_BODY, "rest-replay-1");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().get("orderId").asText()).isEqualTo(orderId);

        // No second order was created.
        ResponseEntity<JsonNode> list = rest.getForEntity("/api/orders", JsonNode.class);
        long count = 0;
        for (JsonNode o : list.getBody().get("orders")) {
            if (o.get("orderId").asText().equals(orderId)) {
                count++;
            }
        }
        assertThat(count).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodyReturns409Conflict() {
        ResponseEntity<JsonNode> first = post(VALID_BODY, "rest-conflict-1");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String different = VALID_BODY.replace("2500", "9999");
        ResponseEntity<JsonNode> conflict = post(different, "rest-conflict-1");
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("error").get("code").asText())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void validationFailureReturns400() {
        String bad = "{\"customerId\":\"cus_ok\",\"amountCents\":0,\"items\":[]}";
        ResponseEntity<JsonNode> resp = post(bad, "rest-bad-1");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("error").get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void getOrderReturns200And404ForUnknown() {
        ResponseEntity<JsonNode> created = post(VALID_BODY, "rest-get-1");
        String orderId = created.getBody().get("orderId").asText();

        ResponseEntity<JsonNode> fetched = rest.getForEntity("/api/orders/" + orderId, JsonNode.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("orderId").asText()).isEqualTo(orderId);

        ResponseEntity<JsonNode> missing = rest.getForEntity("/api/orders/ord_does_not_exist", JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void healthEndpointIsUp() {
        ResponseEntity<JsonNode> health = rest.getForEntity("/actuator/health", JsonNode.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody().get("status").asText()).isEqualTo("UP");
    }
}
