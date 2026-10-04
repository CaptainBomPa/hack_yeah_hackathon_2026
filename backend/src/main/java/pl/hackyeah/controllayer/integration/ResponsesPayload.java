package pl.hackyeah.controllayer.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Preserve native tool/reasoning items. Only inspect text-bearing protocol fields. */
public final class ResponsesPayload {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ResponsesPayload() {}

    public static ObjectNode parse(String body) {
        JsonNode node = JSON.readTree(body);
        if (!(node instanceof ObjectNode object)) throw new IllegalArgumentException("Expected JSON object");
        return object;
    }

    public static String encode(JsonNode node) { return JSON.writeValueAsString(node); }

    public static List<String> texts(JsonNode node) {
        var result = new ArrayList<String>();
        transform(node.deepCopy(), text -> { result.add(text); return text; });
        return result;
    }

    public static void transform(JsonNode node, UnaryOperator<String> textOperator) {
        if (node instanceof ArrayNode array) {
            for (JsonNode item : array) transform(item, textOperator);
        } else if (node instanceof ObjectNode object) {
            // These fields cover instructions, messages, tool arguments/results and schemas.
            for (String field : List.of("instructions", "text", "content", "arguments", "output",
                    "description", "input", "refusal")) {
                JsonNode value = object.get(field);
                if (value == null) continue;
                if (value.isTextual()) object.put(field, textOperator.apply(value.asText()));
                else transform(value, textOperator);
            }
            // Tool definitions contain descriptions nested in parameters/properties.
            for (String field : List.of("tools", "parameters", "properties", "items", "summary", "item", "part", "response")) {
                JsonNode value = object.get(field);
                if (value != null) {
                    if (field.equals("properties") && value instanceof ObjectNode properties) {
                        properties.properties().forEach(entry -> transform(entry.getValue(), textOperator));
                    } else transform(value, textOperator);
                }
            }
        }
    }

    /** Tekst do sprawdzenia i czy należy do bieżącej tury; zwraca tekst, który ma trafić do upstreamu. */
    @FunctionalInterface
    public interface InputText {
        String apply(String text, boolean current);
    }

    /**
     * Odwiedza teksty żądania w stałej kolejności, z podziałem na bieżące i historię
     * ({@link pl.hackyeah.controllayer.guard.ConversationGuard}):
     * <ul>
     *   <li>pola najwyższego poziomu (instructions, definicje narzędzi) — zawsze bieżące, blokada blokuje;</li>
     *   <li>elementy {@code input} po ostatnim elemencie wygenerowanym przez model (odpowiedź, reasoning,
     *       wywołanie narzędzia) są bieżące: wyniki narzędzi i inne elementy niebędące wiadomościami
     *       oraz <b>tylko ostatnia</b> wiadomość użytkownika. Wcześniejsze wiadomości — także prompt, który
     *       zablokowaliśmy, a Codex i tak zostawił w historii — to historia.</li>
     * </ul>
     */
    public static void visitInput(ObjectNode request, InputText text) {
        JsonNode input = request.remove("input");
        try {
            transform(request, value -> text.apply(value, true));
        } finally {
            if (input != null) request.set("input", input);
        }
        if (input == null) return;
        if (input.isTextual()) {
            request.put("input", text.apply(input.asText(), true));
            return;
        }
        if (!(input instanceof ArrayNode items)) {
            transform(input, value -> text.apply(value, true));
            return;
        }
        int lastModel = -1;
        for (int i = 0; i < items.size(); i++) {
            if (producedByModel(items.get(i))) lastModel = i;
        }
        int lastUser = -1;
        for (int i = lastModel + 1; i < items.size(); i++) {
            if (isMessage(items.get(i)) && "user".equals(items.get(i).path("role").asText())) lastUser = i;
        }
        for (int i = 0; i < items.size(); i++) {
            boolean current = i > lastModel && (!isMessage(items.get(i)) || i == lastUser);
            transform(items.get(i), value -> text.apply(value, current));
        }
    }

    private static boolean isMessage(JsonNode item) {
        String type = item.path("type").asText("");
        return type.equals("message") || (type.isEmpty() && item.has("role"));
    }

    private static boolean producedByModel(JsonNode item) {
        String type = item.path("type").asText("");
        return "assistant".equals(item.path("role").asText()) || type.equals("reasoning") || type.endsWith("_call");
    }

    /** Completed response is authoritative; reject truncated/error streams before releasing bytes. */
    public static ObjectNode terminalResponse(String sse) {
        ObjectNode response = null;
        for (ObjectNode event : events(sse)) {
            String type = event.path("type").asText();
            if (type.equals("error") || type.equals("response.failed")) {
                throw new IllegalArgumentException("Upstream stream failed");
            }
            if (type.equals("response.completed") || type.equals("response.incomplete")) {
                if (!(event.get("response") instanceof ObjectNode object)) {
                    throw new IllegalArgumentException("Missing terminal response");
                }
                response = object;
            }
        }
        if (response == null) throw new IllegalArgumentException("Missing terminal response");
        return response;
    }

    private static List<ObjectNode> events(String sse) {
        var events = new ArrayList<ObjectNode>();
        for (String frame : sse.replace("\r\n", "\n").split("\n\n")) {
            StringBuilder data = new StringBuilder();
            for (String line : frame.split("\n")) {
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                }
            }
            if (data.isEmpty() || data.toString().equals("[DONE]")) continue;
            events.add(parse(data.toString()));
        }
        return events;
    }

    /** Inspect deltas as well as completed items: provisional content must not escape unchecked. */
    public static List<String> streamTexts(String sse, ObjectNode response) {
        var result = new java.util.LinkedHashSet<>(texts(response));
        var deltas = new java.util.LinkedHashMap<String, StringBuilder>();
        for (ObjectNode event : events(sse)) {
            JsonNode delta = event.get("delta");
            if (delta != null && delta.isTextual()) {
                String key = event.path("type").asText() + ":" + event.path("item_id").asText()
                        + ":" + event.path("output_index").asText() + ":" + event.path("content_index").asText();
                deltas.computeIfAbsent(key, ignored -> new StringBuilder()).append(delta.asText());
            }
            // Any frame can contain provisional text absent from the terminal response.
            result.addAll(texts(event));
        }
        deltas.values().forEach(value -> result.add(value.toString()));
        return List.copyOf(result);
    }
}
