package pl.hackyeah.controllayer.chat;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

/**
 * Błędne żądanie (brak pól, złamany JSON) musi wrócić w tym samym kontrakcie co każda inna
 * odpowiedź gatewaya (`GuardedChatResponse`), a nie w domyślnym formacie błędu Springa — inaczej
 * klient dostaje dwa różne kształty odpowiedzi w zależności od tego, który filtr go odrzucił.
 */
@RestControllerAdvice(assignableTypes = ChatCompletionController.class)
class ChatCompletionExceptionHandler {

    private static final String VALIDATION_POLICY = "request.validation";

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<GuardedChatResponse> handleValidation(WebExchangeBindException ex) {
        String detail = ex.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("invalid request body");
        return badRequest(detail);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<GuardedChatResponse> handleMalformedInput(ServerWebInputException ex) {
        return badRequest("malformed request body");
    }

    private ResponseEntity<GuardedChatResponse> badRequest(String detail) {
        String requestId = UUID.randomUUID().toString();
        var trace = List.of(new ControlTrace(VALIDATION_POLICY, "deterministic", "block", 0, detail));
        return ResponseEntity.status(400).body(GuardedChatResponse.block(requestId, VALIDATION_POLICY, trace));
    }
}
