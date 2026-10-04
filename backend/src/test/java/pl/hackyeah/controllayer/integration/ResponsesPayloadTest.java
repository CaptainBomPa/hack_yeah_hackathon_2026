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
    void synthesizedStreamCarriesExactlyTheRedactedFinalResponse() {
        var response = ResponsesPayload.parse("""
                {"id":"resp_1","object":"response","status":"completed","output":[
                  {"type":"reasoning","id":"rs_1","encrypted_content":"opaque","summary":[]},
                  {"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"mail: <EMAIL_ADDRESS>"}]},
                  {"type":"function_call","id":"fc_1","call_id":"c1","name":"shell","arguments":"{}"}],
                 "usage":{"input_tokens":5,"output_tokens":3}}""");
        String sse = ResponsesPayload.synthesizeStream(response);

        assertEquals(response, ResponsesPayload.terminalResponse(sse));
        // Klient widzi tylko treść końcową: tekst wiadomości raz w delcie i w elementach done/completed.
        assertEquals(java.util.Set.of("mail: <EMAIL_ADDRESS>", "{}"),
                java.util.Set.copyOf(ResponsesPayload.streamTexts(sse, ResponsesPayload.terminalResponse(sse))));
        var types = sse.lines().filter(line -> line.startsWith("event: ")).map(line -> line.substring(7)).toList();
        assertEquals(java.util.List.of("response.created",
                "response.output_item.added", "response.output_item.done",
                "response.output_item.added", "response.output_text.delta", "response.output_item.done",
                "response.output_item.added", "response.output_item.done",
                "response.completed"), types);
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
