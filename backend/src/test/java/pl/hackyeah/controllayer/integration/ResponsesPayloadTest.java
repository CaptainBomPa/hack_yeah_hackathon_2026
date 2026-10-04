package pl.hackyeah.controllayer.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Podział tekstów żądania Codexa na bieżącą turę i historię (ResponsesPayload.visitInput). */
class ResponsesPayloadTest {

    /** tekst -> czy bieżący */
    private static Map<String, Boolean> classify(String json) {
        var result = new LinkedHashMap<String, Boolean>();
        ResponsesPayload.visitInput(ResponsesPayload.parse(json), (text, current) -> {
            result.put(text, current);
            return text;
        });
        return result;
    }

    @Test
    void aBlockedPromptLeftInHistoryIsHistoryAndOnlyTheLastPromptIsCurrent() {
        // Codex po odrzuceniu zostawia zablokowany prompt w historii i dokleja nowy.
        var texts = classify("""
                {"model":"m","instructions":"You are Codex","input":[
                  {"type":"message","role":"developer","content":[{"type":"input_text","text":"sandbox rules"}]},
                  {"type":"message","role":"user","content":[{"type":"input_text","text":"PESEL 44051401359"}]},
                  {"type":"message","role":"user","content":[{"type":"input_text","text":"pesel ?"}]}]}""");
        assertEquals(Map.of("You are Codex", true, "sandbox rules", false, "PESEL 44051401359", false, "pesel ?", true), texts);
    }

    @Test
    void earlierTurnsBeforeTheLastModelItemAreHistory() {
        var texts = classify("""
                {"model":"m","input":[
                  {"role":"user","content":"first question"},
                  {"type":"message","role":"assistant","content":[{"type":"output_text","text":"first answer"}]},
                  {"role":"user","content":"second question"}]}""");
        assertEquals(Map.of("first question", false, "first answer", false, "second question", true), texts);
    }

    @Test
    void toolOutputsAfterTheModelCallAreCurrentButTheOldPromptIsNot() {
        // Pętla agenta: nowa treść to wynik narzędzia, nie wiadomość użytkownika.
        var texts = classify("""
                {"model":"m","input":[
                  {"role":"user","content":"list files"},
                  {"type":"function_call","call_id":"c1","name":"shell","arguments":"{\\"command\\":\\"ls\\"}"},
                  {"type":"function_call_output","call_id":"c1","output":"secret.txt"}]}""");
        assertEquals(Map.of("list files", false, "{\"command\":\"ls\"}", false, "secret.txt", true), texts);
    }

    @Test
    void plainStringInputIsCurrent() {
        assertEquals(Map.of("hello", true), classify("{\"model\":\"m\",\"input\":\"hello\"}"));
    }

    @Test
    void replacedTextsLandInTheSamePlaces() {
        var request = ResponsesPayload.parse("""
                {"model":"m","input":[{"role":"user","content":"old"},{"role":"user","content":"new"}]}""");
        ResponsesPayload.visitInput(request, (text, current) -> current ? text : "[removed]");
        assertEquals("[removed]", request.path("input").get(0).path("content").asText());
        assertEquals("new", request.path("input").get(1).path("content").asText());
    }
}
