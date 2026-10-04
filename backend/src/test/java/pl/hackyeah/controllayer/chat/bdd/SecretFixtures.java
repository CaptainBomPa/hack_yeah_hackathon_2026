package pl.hackyeah.controllayer.chat.bdd;

import java.util.Map;

/**
 * Secret-shaped test values for SEC-GITLEAKS scenarios, assembled from fragments at runtime so
 * the repository never contains a literal that looks like a real credential — same precaution as
 * {@code SecretScannerTest} (push protection, CI secret scanners).
 */
final class SecretFixtures {

    private SecretFixtures() {}

    static final String AWS_KEY = "AKIA" + "QYLPMN5HHHFPZAM2";
    static final String GITHUB_PAT = "ghp_" + "x8Kq2mN7pL4vR9tY1wE3uI6oA5sD0fG8hJ2k";
    static final String SLACK_BOT = "xoxb-" + "1234567890123-1234567890123-" + "AbCdEfGhIjKlMnOpQrStUvWx";
    static final String JWT = "eyJhbGciOiJIUzI1NiJ9" + "." + "eyJzdWIiOiIxMjM0NTY3ODkwIn0" + "."
            + "dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    static final String PRIVATE_KEY = "-----BEGIN RSA " + "PRIVATE KEY-----\n"
            + "MIIEowIBAAKCAQEAu1SU1LfVLPHCozMxH2Mo4lgOEePzNm0tRgeLezV6ffAt0gun\n"
            + "VTLw7onLRnrq0/IzW7yWR7QkrmBL7jTKEn5u+qKhbwKfBstIs+bMY2Zkp18gnTxK\n"
            + "-----END RSA " + "PRIVATE KEY-----";

    static final String BENIGN_EN = "How do I rotate an API key safely? Our token service issues keys and the secret "
            + "manager stores passwords; explain best practices for credential rotation in Kubernetes.";

    /** Talks about secrets using code-like syntax but never contains an actual value — no finding should fire. */
    static final String BENIGN_CODE_LIKE = "Set the variable api_key = os.environ.get('API_KEY') and never hardcode "
            + "it. Same for aws_secret_access_key and github_token — always read them from the environment.";

    /** Gitleaks rule id each fixture is expected to trip — used to assert {@code blockedBy}/detail. */
    static final Map<String, String> RULE_ID = Map.of(
            "AWS access key", "aws-access-token",
            "GitHub token", "github-pat",
            "Slack bot token", "slack-bot-token",
            "JWT", "jwt",
            "private key", "private-key");

    static String byLabel(String label) {
        return switch (label) {
            case "AWS access key" -> AWS_KEY;
            case "GitHub token" -> GITHUB_PAT;
            case "Slack bot token" -> SLACK_BOT;
            case "JWT" -> JWT;
            case "private key" -> PRIVATE_KEY;
            default -> throw new IllegalArgumentException("unknown secret fixture: " + label);
        };
    }
}
