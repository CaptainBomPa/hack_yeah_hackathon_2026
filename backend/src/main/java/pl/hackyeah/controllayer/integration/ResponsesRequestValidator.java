package pl.hackyeah.controllayer.integration;

import java.util.List;

/** Validation of supported native protocol shapes. */
final class ResponsesRequestValidator {
    private ResponsesRequestValidator() {}

    static boolean supportedRequest(tools.jackson.databind.node.ObjectNode request) {
        // Hidden history and background execution would bypass INPUT/OUTPUT inspection.
        return !request.hasNonNull("previous_response_id") && !request.hasNonNull("conversation")
                && !request.path("background").asBoolean(false)
                && supportedInput(request.get("input")) && supportedTools(request.get("tools"));
    }

    private static boolean supportedTools(tools.jackson.databind.JsonNode tools) {
        return supportedTools(tools, 0);
    }

    private static boolean supportedTools(tools.jackson.databind.JsonNode tools, int depth) {
        if (tools == null) return true;
        if (!tools.isArray() || depth > 16) return false;
        for (var tool : tools) {
            String type = tool.path("type").asText();
            if (type.equals("namespace")) {
                if (tool.get("tools") == null || !supportedTools(tool.get("tools"), depth + 1)) return false;
            } else if (type.equals("tool_search")) {
                // Codex CLI >= 0.159: wyszukiwanie narzędzi wykonywane lokalnie przez klienta. Wariant hostowany
                // (wykonywany przez OpenAI poza naszą kontrolą) nadal odrzucamy, jak inne hosted tools.
                if (!"client".equals(tool.path("execution").asText())) return false;
            } else if (!List.of("function", "custom").contains(type)) return false;
        }
        return true;
    }

    private static boolean supportedInput(tools.jackson.databind.JsonNode input) {
        if (input.isTextual()) return true;
        if (!input.isArray()) return false;
        for (var item : input) {
            String type = item.path("type").asText("message");
            if (type.equals("additional_tools")) {
                if (item.get("tools") == null || !supportedTools(item.get("tools"))) return false;
                continue;
            }
            if (!List.of("message", "function_call", "function_call_output", "custom_tool_call",
                    "custom_tool_call_output", "reasoning", "compaction").contains(type)) return false;
            var content = item.get("content");
            if (content != null && content.isArray()) for (var part : content) {
                if (!List.of("input_text", "output_text", "refusal").contains(part.path("type").asText())) return false;
            }
        }
        return true;
    }

}
