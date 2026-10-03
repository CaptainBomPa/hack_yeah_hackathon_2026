package pl.hackyeah.controllayer.guard.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.hackyeah.controllayer.guard.GuardContext;
import pl.hackyeah.controllayer.guard.GuardSettings;
import pl.hackyeah.controllayer.guard.Verdict;

/** Wektory z docs/deterministic/01-pii-detection.md §14 (PII-T*), na prawdziwej paczce recognizerów. */
class PiiRecognizerGuardTest {

    private static final GuardSettings DEFAULTS = new GuardSettings(true, Map.of());
    private static PiiRecognizerGuard guard;

    @BeforeAll
    static void loadPack() throws IOException {
        try (InputStream in = PiiRecognizerGuardTest.class.getResourceAsStream("/rules/pii/recognizers.yaml")) {
            guard = new PiiRecognizerGuard(RecognizerPack.load(in));
        }
    }

    private static Verdict check(String text) {
        return check(text, DEFAULTS);
    }

    private static Verdict check(String text, GuardSettings settings) {
        return guard.check(new GuardContext("test", text, null, null), settings);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            // PII-T001
            "Mój PESEL to 44051401359, sprawdź.            | Mój PESEL to [REDACTED:PL_PESEL], sprawdź.",
            // PII-T009
            "NIP 1234563218                                | NIP [REDACTED:PL_NIP]",
            "NIP: 123-456-32-18                            | NIP: [REDACTED:PL_NIP]",
            "Faktura na PL1234563218                       | Faktura na [REDACTED:PL_NIP]",
            // PII-T012
            "REGON firmy: 123456785                        | REGON firmy: [REDACTED:PL_REGON]",
            // PII-T013
            "Seria i numer dowodu: ABA300000               | Seria i numer dowodu: [REDACTED:PL_ID_CARD]",
            // PII-T014
            "Karta 4111 1111 1111 1111 ważna do 12/29      | Karta [REDACTED:CREDIT_CARD] ważna do 12/29",
            // PII-T017
            "Przelew na PL61 1090 1014 0000 0712 1981 2874 | Przelew na [REDACTED:IBAN_CODE]",
            "Konto 61109010140000071219812874              | Konto [REDACTED:IBAN_CODE]",
            // PII-T018
            "Pisz na jan.kowalski@example.pl.              | Pisz na [REDACTED:EMAIL_ADDRESS].",
            // PII-T021
            "Dzwoń: +48 601 234 567                        | Dzwoń: [REDACTED:PHONE_NUMBER]",
            "tel. 601-234-567                              | tel. [REDACTED:PHONE_NUMBER]",
            "mój telefon 601234567                         | mój telefon [REDACTED:PHONE_NUMBER]",
    })
    void redactsDetectedPii(String input, String expected) {
        Verdict verdict = check(input.strip());
        assertThat(verdict).isInstanceOf(Verdict.Redact.class);
        assertThat(((Verdict.Redact) verdict).newText()).isEqualTo(expected.strip());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Numer zamówienia 44051401358 został wysłany.", // PII-T002: zły checksum
            "44131401359",                                    // PII-T003: miesiąc 13
            "1234563219",                                     // PII-T010: zła cyfra NIP
            "Kod produktu 123456785",                         // PII-T012: REGON bez kontekstu
            "SKU ABA300000",                                  // PII-T013: dowód bez kontekstu
            "dowód ABA300001",                                // PII-T013: zły checksum
            "4111 1111 1111 1112",                            // PII-T015: Luhn
            "Konto 61109010140000071219812875",               // PII-T017: zmieniona cyfra
            "Zamówienie 601234567 wysłane",                   // PII-T022: 9 cyfr bez kontekstu
            "Zwykłe pytanie o pogodę w Krakowie.",
    })
    void allowsNonPii(String input) {
        assertThat(check(input)).isInstanceOf(Verdict.Allow.class);
    }

    @Test
    void detailExplainsScoreWithoutRawValue() {
        var verdict = (Verdict.Redact) check("PESEL 44051401359");
        assertThat(verdict.detail())
                .contains("PII-001/PL_PESEL×1")
                .contains("pesel:valid 1.00")
                .doesNotContain("44051401359");
    }

    @Test
    void overlappingFindingsKeepTheLongerOneOnEqualScore() {
        // "1090 1014 0000 0712" wewnątrz IBAN przechodzi też Luhn; IBAN (dłuższy) wygrywa.
        var verdict = (Verdict.Redact) check("PL61 1090 1014 0000 0712 1981 2874");
        assertThat(verdict.newText()).isEqualTo("[REDACTED:IBAN_CODE]");
        assertThat(verdict.detail()).doesNotContain("CREDIT_CARD");
    }

    @Test
    void blockOverrideBlocksWithoutLeakingValue() {
        var settings = new GuardSettings(true, Map.of("blockRecognizers", "PII-007"));
        Verdict verdict = check("karta 4111111111111111", settings);
        assertThat(verdict).isInstanceOf(Verdict.Block.class);
        assertThat(((Verdict.Block) verdict).reason()).contains("PII-007").doesNotContain("4111111111111111");
    }

    @Test
    void monitorOverrideReportsButKeepsText() {
        var settings = new GuardSettings(true, Map.of("monitorRecognizers", "PII-005"));
        Verdict verdict = check("mail: jan@example.pl", settings);
        assertThat(verdict).isInstanceOf(Verdict.Allow.class);
        assertThat(((Verdict.Allow) verdict).detail()).startsWith("monitor PII-005/EMAIL_ADDRESS×1");
    }

    @Test
    void disabledRecognizerIsSkipped() {
        var settings = new GuardSettings(true, Map.of("disabledRecognizers", "PII-001, PII-005"));
        assertThat(check("PESEL 44051401359, jan@example.pl", settings)).isInstanceOf(Verdict.Allow.class);
    }

    @Test
    void thresholdIsConfigurable() {
        // Goły 9-cyfrowy numer ma score 0.2: przy progu 0.1 przechodzi bez kontekstu.
        var settings = new GuardSettings(true, Map.of("threshold", 0.1));
        assertThat(check("Zamówienie 601234567", settings)).isInstanceOf(Verdict.Redact.class);
    }

    @Test
    void maskOperatorAndAllowListFromPack() {
        var pack = pack("""
                recognizers:
                  - id: T-1
                    supported_entity: CREDIT_CARD
                    patterns: [{ name: card, regex: '\\b\\d{16}\\b', score: 0.3 }]
                    validator: luhn
                    allow_list: ["4111 1111 1111 1111"]
                    operator: { type: mask, masking_char: "#", chars_to_mask: 12, from_end: false }
                """);
        var masking = new PiiRecognizerGuard(pack);
        var ctx = new GuardContext("t", "a 5555555555554444 b 4111111111111111", null, null);
        var verdict = (Verdict.Redact) masking.check(ctx, DEFAULTS);
        assertThat(verdict.newText()).isEqualTo("a ############4444 b 4111111111111111");
    }

    @Test
    void denyListLikePresidio() {
        var pack = pack("""
                recognizers:
                  - supported_entity: TITLE
                    deny_list: [Dr., Prof.]
                """);
        var verdict = (Verdict.Redact) new PiiRecognizerGuard(pack)
                .check(new GuardContext("t", "Pisze prof. Nowak", null, null), DEFAULTS);
        assertThat(verdict.newText()).isEqualTo("Pisze [REDACTED:TITLE] Nowak");
    }

    @Test
    void invalidPackFailsFast() {
        assertThatThrownBy(() -> pack("""
                recognizers:
                  - supported_entity: X
                    patterns: [{ name: x, regex: 'x', score: 0.5 }]
                    validator: does_not_exist
                """)).hasMessageContaining("unknown validator");
        assertThatThrownBy(() -> pack("""
                recognizers:
                  - supported_entity: X
                    patterns: [{ name: x, regex: 'x', score: 1.5 }]
                """)).hasMessageContaining("score");
    }

    private static RecognizerPack pack(String yaml) {
        return RecognizerPack.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
