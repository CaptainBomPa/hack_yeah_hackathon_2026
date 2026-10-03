package pl.hackyeah.controllayer.guard.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Wektory sekretów są składane z fragmentów w runtime, żeby repo nie zawierało literałów
 * wyglądających na prawdziwe klucze (push protection, skanery w CI).
 */
class SecretScannerTest {

    static final SecretScanner SCANNER = SecretGuard.loadDefaultPack();

    static final String AWS_KEY = "AKIA" + "QYLPMN5HHHFPZAM2";
    static final String GITHUB_PAT = "ghp_" + "x8Kq2mN7pL4vR9tY1wE3uI6oA5sD0fG8hJ2k";
    static final String GENERIC_VALUE = "x8Kq2mN7pL4vR9tY" + "1wE3uI6oA5sD0f";
    static final String SLACK_BOT = "xoxb-" + "1234567890123-1234567890123-" + "AbCdEfGhIjKlMnOpQrStUvWx";
    static final String JWT = "eyJhbGciOiJIUzI1NiJ9" + "." + "eyJzdWIiOiIxMjM0NTY3ODkwIn0" + "."
            + "dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    static final String PRIVATE_KEY = "-----BEGIN RSA " + "PRIVATE KEY-----\n"
            + "MIIEowIBAAKCAQEAu1SU1LfVLPHCozMxH2Mo4lgOEePzNm0tRgeLezV6ffAt0gun\n"
            + "VTLw7onLRnrq0/IzW7yWR7QkrmBL7jTKEn5u+qKhbwKfBstIs+bMY2Zkp18gnTxK\n"
            + "-----END RSA " + "PRIVATE KEY-----";

    static final String BENIGN_PL = "Cześć, napisz mi proszę funkcję w Javie, która sortuje listę zamówień po dacie "
            + "i zwraca 10 najnowszych. Zamówienia mają pola id, kwota, status i datę utworzenia.";
    static final String BENIGN_EN = "How do I rotate an API key safely? Our token service issues keys and the secret "
            + "manager stores passwords; explain best practices for credential rotation in Kubernetes.";

    @Test
    void loadsTheGitleaksPackWithoutRegexFailures() {
        assertTrue(SCANNER.rules().size() >= 200, "rules=" + SCANNER.rules().size());
        var pack = SCANNER.rules().stream().map(SecretRule::id).toList();
        assertTrue(pack.contains("aws-access-token") && pack.contains("generic-api-key") && pack.contains("private-key"));
        // Kontekstowe reguły (szablon "nazwa operator wartość") muszą zostać rozpoznane, inaczej tracimy precheck.
        assertTrue(SCANNER.rules().stream().filter(SecretRule::contextual).count() >= 90);
    }

    @Test
    void skipsOnlyFilePathRulesWhenTranslatingGoRegexes() throws Exception {
        try (var in = SecretGuard.class.getResourceAsStream(SecretGuard.PACK)) {
            var pack = GitleaksRulePack.load(in);
            var unexpected = pack.skipped().stream()
                    .filter(reason -> !reason.endsWith("(reguła ścieżki pliku)") && !reason.endsWith("(brak regex)"))
                    .toList();
            assertEquals(List.of(), unexpected);
        }
    }

    @Test
    void translatesGoOnlyRegexSyntax() {
        assertEquals("^\\$\\{(?:[A-Z_]+)}$", GitleaksRulePack.translateGoRegex("^\\${(?:[A-Z_]+)}$"));
        assertEquals("[^}]{1,3}x{2}", GitleaksRulePack.translateGoRegex("[^}]{1,3}x{2}"));
        assertEquals("(?<keyops>a)", GitleaksRulePack.translateGoRegex("(?P<key_ops>a)"));
        assertEquals("pat[\\p{Alnum}]{14}", GitleaksRulePack.translateGoRegex("pat[[:alnum:]]{14}"));
    }

    @Test
    void benignPromptsHaveNoFindings() {
        assertEquals(List.of(), scan(BENIGN_PL));
        assertEquals(List.of(), scan(BENIGN_EN));
        assertEquals(List.of(), scan("Klucz do sukcesu: dobra dokumentacja, token zaufania i hasło przewodnie."));
    }

    @Test
    void detectsPrefixedTokensWithExactSpans() {
        String text = "config: aws_access_key_id=" + AWS_KEY + " i token " + GITHUB_PAT + " koniec";
        var findings = scan(text);
        assertTrue(findings.contains(new SecretFinding("aws-access-token", text.indexOf(AWS_KEY),
                text.indexOf(AWS_KEY) + AWS_KEY.length())), findings.toString());
        assertTrue(findings.contains(new SecretFinding("github-pat", text.indexOf(GITHUB_PAT),
                text.indexOf(GITHUB_PAT) + GITHUB_PAT.length())), findings.toString());
    }

    @Test
    void detectsCommonFormats() {
        assertTrue(ruleIds("SLACK_TOKEN=" + SLACK_BOT).contains("slack-bot-token"));
        assertTrue(ruleIds("Authorization: Bearer " + JWT).contains("jwt"));
        assertTrue(ruleIds(PRIVATE_KEY).contains("private-key"));
    }

    @Test
    void genericRuleNeedsAnAssignmentAndRespectsMatchAllowlist() {
        assertTrue(ruleIds("api_key = \"" + GENERIC_VALUE + "\"").contains("generic-api-key"));
        assertEquals(List.of(), scan("Is " + GENERIC_VALUE + " a good api key value for tests?"));
        // Allowlista Gitleaks typu "match" (public_key) działa mimo usunięcia prefiksu nazwy z regexu.
        assertEquals(List.of(), scan("public_key = \"" + GENERIC_VALUE + "\""));
    }

    @Test
    void appliesRuleAllowlists() {
        assertEquals(List.of(), scan("aws_access_key_id=AKIAIOSFODNN7EXAMPLE"));
    }

    @Test
    void skipsDisabledRules() {
        assertEquals(List.of(), SCANNER.scan("token " + GITHUB_PAT, Set.of("github-pat")));
    }

    @Test
    void fastPathMatchesReferenceFullScan() {
        String secrets = String.join("\n",
                "AWS_ACCESS_KEY_ID=" + AWS_KEY,
                "GITHUB_TOKEN=" + GITHUB_PAT,
                "api_key = \"" + GENERIC_VALUE + "\"",
                "password: \"" + GENERIC_VALUE + "\"",
                "public_key = \"" + GENERIC_VALUE + "\"",
                "SLACK=" + SLACK_BOT,
                "curl -H 'Authorization: Bearer " + JWT + "' https://api.example.com",
                PRIVATE_KEY);
        List<String> corpus = List.of(
                BENIGN_PL,
                BENIGN_EN,
                secrets,
                "{\"client_secret\": \"" + GENERIC_VALUE + "\", \"token\": \"" + GITHUB_PAT + "\"}",
                BENIGN_PL.repeat(12) + secrets + BENIGN_EN.repeat(12),
                BENIGN_EN.repeat(25),
                secrets.repeat(3));
        for (String text : corpus) {
            assertEquals(SCANNER.scanReference(text), scan(text), () -> "korpus: " + text.substring(0, 60));
        }
    }

    private static List<SecretFinding> scan(String text) {
        return SCANNER.scan(text, Set.of());
    }

    private static List<String> ruleIds(String text) {
        return scan(text).stream().map(SecretFinding::ruleId).toList();
    }
}
