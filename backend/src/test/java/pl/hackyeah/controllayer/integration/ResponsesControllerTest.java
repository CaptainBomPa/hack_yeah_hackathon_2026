package pl.hackyeah.controllayer.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import pl.hackyeah.controllayer.audit.*;
import pl.hackyeah.controllayer.budget.*;
import pl.hackyeah.controllayer.guard.*;
import pl.hackyeah.controllayer.model.*;
import pl.hackyeah.controllayer.policy.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ResponsesControllerTest {
    @Test
    void nativeProtocolSecurityAndAuditCases() throws Exception {
        try (var fixtures = getClass().getResourceAsStream("/integration/responses-cases.json")) {
            var cases = JsonMapper.builder().build().readTree(fixtures);
            for (JsonNode scenario : cases) verify(scenario);
        }
    }

    private void verify(JsonNode scenario) throws Exception {
        String label = scenario.path("name").asText();
        var calls = new AtomicInteger();
        var received = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var upstreamHeaders = new AtomicReference<com.sun.net.httpserver.Headers>();
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String output = scenario.path("output").asText("hello");
        String json = "{\"id\":\"resp_test\",\"object\":\"response\",\"status\":\"completed\","
                + "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"" + output + "\"}]}],"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":" + scenario.path("outputTokens").asInt(2) + "}}";
        var nativeResponse = ResponsesPayload.parse(json);
        var nativeOutput = (tools.jackson.databind.node.ArrayNode) nativeResponse.get("output");
        nativeOutput.addObject().put("type", "function_call").put("call_id", "call_test")
                .put("name", "shell").put("arguments", "{\"command\":\"pwd\"}");
        nativeOutput.addObject().put("type", "reasoning").put("encrypted_content", "opaque-state").putArray("summary");
        json = ResponsesPayload.encode(nativeResponse);
        boolean streaming = scenario.path("request").path("stream").asBoolean(false);
        String sse = "event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_test\"}}\n\n";
        if (scenario.path("addedSecret").asBoolean(false)) {
            sse += "data: {\"type\":\"response.content_part.added\",\"part\":{\"type\":\"output_text\",\"text\":\"PRIVATE-VALUE\"}}\n\n";
        }
        if (scenario.path("splitSecret").asBoolean(false)) {
            sse += "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"m1\",\"delta\":\"PRIVATE-\"}\n\n"
                    + "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"m1\",\"delta\":\"VALUE\"}\n\n";
        }
        if (!scenario.path("truncated").asBoolean(false)) {
            sse += "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":" + json + "}\n\n";
        }
        final String responseWire = streaming ? sse : json;
        server.createContext("/backend-api/codex/" + scenario.path("operation").asText("responses"), exchange -> {
            calls.incrementAndGet();
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            upstreamHeaders.set(exchange.getRequestHeaders());
            exchange.getResponseHeaders().set("x-codex-turn-state", "opaque-routing-state");
            exchange.getResponseHeaders().set("x-request-id", "provider-request-id");
            exchange.getResponseHeaders().set("Retry-After", "7");
            exchange.getResponseHeaders().set("Set-Cookie", "do-not-forward=secret");
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            var bytes = (scenario.path("upstreamStatus").asInt(200) == 200 ? responseWire : "PRIVATE-VALUE provider-secret")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(scenario.path("upstreamStatus").asInt(200), bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort() + "/backend-api/codex";
            var integration = new IntegrationProperties(true, true, base,
                    Duration.ofSeconds(3), 65536);
            var models = new ModelCatalogProperties(List.of(new ModelCatalogProperties.ModelEntry("test-model", base, true)), Duration.ofSeconds(3));
            var roles = new PolicyProperties(Map.of("agent", new PolicyProperties.RolePolicy(List.of("test-model"), null)));
            var rules = new GuardProperties(true, Map.of("TEST-GUARD", new GuardProperties.Rule(true, 100, null)));
            var limits = new BudgetLimitsProperties(10000, 100);
            var policy = PolicySource.fixed(PolicyDocument.fromConfig(roles, models, rules, limits));
            var guarded = new Guard() {
                public String id() { return "TEST-GUARD"; }
                public Set<Stage> stages() { return Set.of(Stage.INPUT, Stage.OUTPUT); }
                public Verdict check(GuardContext context, GuardSettings settings) {
                    if (context.text().contains("BLOCK-ME")) return new Verdict.Block("test rejection");
                    if (context.text().contains("PRIVATE-VALUE")) return new Verdict.Redact(context.text().replace("PRIVATE-VALUE", "[REDACTED]"), "test redaction");
                    return Verdict.allow();
                }
            };
            var entries = new ArrayList<AuditEntry>();
            AuditLog audit = entry -> {
                if (scenario.path("auditDown").asBoolean(false)) throw new IllegalStateException("audit down");
                entries.add(entry);
            };
            var controller = new ResponsesController(new ResponsesService(new ResponsesUpstream(WebClient.builder(), integration), integration,
                    policy, new ModelCatalog(models, policy), new ModelAccessPolicy(policy),
                    new BudgetGate(policy, new BudgetService(budgetDatabase())), new GuardChain(List.of(guarded), policy), audit,
                    new AuditProperties(null, true, null)));
            var auth = new UsernamePasswordAuthenticationToken("agent-login", "unused", List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
            var client = WebTestClient.bindToController(controller).webFilter((exchange, chain) -> chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))).build();
            var result = client.post().uri("/v1/" + scenario.path("operation").asText("responses")).contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", scenario.path("oauth").asText("Bearer subscription-token"))
                    .header("ChatGPT-Account-ID", scenario.path("account").asText("account-test"))
                    .header("X-Control-Layer-Authorization", "Basic gateway-secret")
                    .header("Cookie", "SESSION=private")
                    .header("OpenAI-Beta", "responses=experimental")
                    .header("x-codex-turn-state", "incoming-routing-state")
                    .bodyValue(ResponsesPayload.encode(scenario.get("request"))).exchange()
                    .expectStatus().isEqualTo(scenario.path("status").asInt()).expectBody().returnResult();
            String body = new String(result.getResponseBody(), StandardCharsets.UTF_8);
            assertEquals(1, result.getResponseHeaders().get("X-Request-Id").size(), label);
            assertDoesNotThrow(() -> java.util.UUID.fromString(result.getResponseHeaders().getFirst("X-Request-Id")), label);
            assertNull(result.getResponseHeaders().getFirst("Set-Cookie"), label);
            assertFalse(body.contains("subscription-token"), label);
            assertEquals(scenario.path("calls").asInt(), calls.get(), label);
            assertFalse(body.contains("PRIVATE-VALUE"), label);
            assertFalse(body.contains("provider-secret"), label);
            if (scenario.has("code")) assertEquals(scenario.path("code").asText(), ResponsesPayload.parse(body).path("error").path("code").asText(), label);
            if (scenario.has("stage")) {
                var error = ResponsesPayload.parse(body).path("error");
                assertEquals(scenario.path("stage").asText(), error.path("stage").asText(), label);
                assertEquals(scenario.path("guard").asText(), error.path("guard").asText(), label);
                assertEquals(result.getResponseHeaders().getFirst("X-Request-Id"), error.path("request_id").asText(), label);
                assertEquals(policy.current().version(), error.path("policy_version").asLong(), label);
                assertTrue(error.path("message").asText().contains(scenario.path("guard").asText()), label);
                assertEquals(scenario.path("guard").asText(), entries.getFirst().blockedBy(), label);
            }
            if (calls.get() > 0) {
                assertEquals(scenario.path("oauth").asText("Bearer subscription-token"), authorization.get(), label);
                assertEquals("account-test", upstreamHeaders.get().getFirst("ChatGPT-Account-ID"), label);
                assertEquals("incoming-routing-state", upstreamHeaders.get().getFirst("x-codex-turn-state"), label);
                assertEquals("responses=experimental", upstreamHeaders.get().getFirst("OpenAI-Beta"), label);
                assertNull(upstreamHeaders.get().getFirst("X-Control-Layer-Authorization"), label);
                assertNull(upstreamHeaders.get().getFirst("Cookie"), label);
                var request = ResponsesPayload.parse(received.get());
                assertFalse(request.has("max_output_tokens"), label);
                assertEquals(scenario.path("request").get("store"), request.get("store"), label);
                assertFalse(received.get().contains("PRIVATE-VALUE"), label);
                for (JsonNode text : scenario.path("upstreamContains")) assertTrue(received.get().contains(text.asText()), label);
                for (JsonNode text : scenario.path("upstreamNotContains")) assertFalse(received.get().contains(text.asText()), label);
                if (scenario.path("request").has("tools")) assertEquals(scenario.path("request").get("tools"), request.get("tools"), label);
                if (scenario.path("request").has("reasoning")) assertEquals(scenario.path("request").get("reasoning"), request.get("reasoning"), label);
            }
            if (scenario.path("status").asInt() == 200) assertEquals("opaque-routing-state", result.getResponseHeaders().getFirst("x-codex-turn-state"), label);
            if (scenario.path("upstreamStatus").asInt() == 429) assertEquals("7", result.getResponseHeaders().getFirst("Retry-After"), label);
            if (scenario.path("status").asInt() == 200 && streaming && !scenario.path("redacted").asBoolean(false)) {
                assertEquals(responseWire, body, label);
            }
            if (scenario.path("status").asInt() == 200 && streaming && scenario.path("redacted").asBoolean(false)) {
                // Zredagowany stream jest składany od nowa z odpowiedzi końcowej; elementy narzędzi i reasoning zostają.
                var terminal = ResponsesPayload.terminalResponse(body).path("output");
                assertEquals(nativeOutput.get(1), terminal.get(1), label);
                assertEquals(nativeOutput.get(2), terminal.get(2), label);
                for (JsonNode text : scenario.path("clientContains")) assertTrue(body.contains(text.asText()), label);
            }
            if (scenario.path("status").asInt() == 200 && !streaming) {
                var returned = ResponsesPayload.parse(body).path("output");
                assertEquals(nativeOutput.get(1), returned.get(1), label);
                assertEquals(nativeOutput.get(2), returned.get(2), label);
            }
            if (scenario.path("redacted").asBoolean(false)) assertEquals("redact", result.getResponseHeaders().getFirst("X-Control-Layer-Action"), label);
            String expectedAction = scenario.path("status").asInt() != 200 ? "block"
                    : scenario.path("redacted").asBoolean(false) ? "redact" : "allow";
            assertEquals(expectedAction, result.getResponseHeaders().getFirst("X-Control-Layer-Action"), label);
            if (!scenario.path("auditDown").asBoolean(false)) {
                assertEquals(1, entries.size(), label);
                assertEquals(expectedAction, entries.getFirst().action(), label);
                if (expectedAction.equals("allow")) assertNull(entries.getFirst().blockedBy(), label);
                assertFalse(entries.toString().contains("PRIVATE-VALUE"), label);
                assertFalse(entries.toString().contains("subscription-token"), label);
                assertFalse(entries.toString().contains("gateway-secret"), label);
            }
        } finally { server.stop(0); }
    }

    /** Role bez limitu też księgują zużycie (dashboard), więc BudgetService potrzebuje tabeli. */
    private static org.springframework.jdbc.core.JdbcTemplate budgetDatabase() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:responses-budget-" + java.util.UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        jdbc.execute("CREATE TABLE budget_counter (subject VARCHAR(200) NOT NULL, period_kind VARCHAR(10) NOT NULL, "
                + "period_start DATE NOT NULL, used_tokens BIGINT NOT NULL DEFAULT 0, reserved BIGINT NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (subject, period_kind, period_start))");
        return jdbc;
    }
}
