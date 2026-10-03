package pl.hackyeah.controllayer.integration;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import pl.hackyeah.controllayer.chat.ChatCompletionController;
import pl.hackyeah.controllayer.model.ModelCatalogProperties;

class IntegrationModesTest {

    @Nested
    @SpringBootTest(properties = {"OLLAMA_BASE_URL=http://127.0.0.1:1",
            "spring.datasource.url=jdbc:h2:mem:integration-both;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"})
    @ActiveProfiles("local")
    class Default {
        @Autowired ApplicationContext context;
        @Autowired IntegrationProperties integration;
        @Autowired ModelCatalogProperties catalog;

        @Test void webAndCodexRunSideBySide() {
            assertTrue(integration.webEnabled());
            assertTrue(integration.codexEnabled());
            assertEquals(1, context.getBeansOfType(ChatCompletionController.class).size());
            assertEquals(1, context.getBeansOfType(ResponsesController.class).size());
            assertTrue(catalog.models().stream().anyMatch(model -> model.tag().equals("gpt-6.1-sol")));
            assertTrue(catalog.models().stream().anyMatch(model -> model.tag().equals("qwen2.5:0.5b")));
            assertEquals("https://chatgpt.com/backend-api/codex", integration.codexBaseUrl());
            assertThrows(IllegalArgumentException.class, () ->
                    new IntegrationProperties(true, true, "https://api.openai.com/v1", null, 0));
        }
    }

    @Nested
    @SpringBootTest(properties = {"control-layer.integration.codex-enabled=false",
            "spring.datasource.url=jdbc:h2:mem:integration-web-only;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"})
    @ActiveProfiles("local")
    class CodexDisabled {
        @Autowired ApplicationContext context;
        @Autowired ModelCatalogProperties catalog;

        @Test void onlyWebIsExposedAndBlankCodexModelIsSkipped() {
            assertEquals(1, context.getBeansOfType(ChatCompletionController.class).size());
            assertTrue(context.getBeansOfType(ResponsesController.class).isEmpty());
            assertTrue(catalog.models().stream().noneMatch(model -> model.tag().isBlank()));
        }
    }

    @Nested
    @SpringBootTest(properties = {"control-layer.integration.web-enabled=false",
            "spring.datasource.url=jdbc:h2:mem:integration-codex-only;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"})
    @ActiveProfiles("local")
    class WebDisabled {
        @Autowired ApplicationContext context;
        @Autowired ModelCatalogProperties catalog;

        @Test void onlyCodexIsExposed() {
            assertTrue(context.getBeansOfType(ChatCompletionController.class).isEmpty());
            assertEquals(1, context.getBeansOfType(ResponsesController.class).size());
        }

        @Test void codexModelsFromYamlJoinTheCatalogWithTheCodexUpstream() {
            var codex = catalog.models().stream()
                    .filter(model -> model.baseUrl().equals("https://chatgpt.com/backend-api/codex")).toList();
            assertTrue(codex.stream().anyMatch(model -> model.tag().equals("gpt-6.1-sol")));
            assertTrue(codex.stream().anyMatch(model -> model.tag().equals("gpt-5.5")));
            assertTrue(catalog.models().stream().anyMatch(model -> model.tag().equals("qwen2.5:0.5b")));
        }
    }
}
