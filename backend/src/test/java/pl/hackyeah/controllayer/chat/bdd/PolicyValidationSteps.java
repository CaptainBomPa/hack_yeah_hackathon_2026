package pl.hackyeah.controllayer.chat.bdd;

import io.cucumber.java.en.When;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import pl.hackyeah.controllayer.policy.PolicyDocument;

/**
 * Drives every flavour of invalid policy edit {@code PolicyValidator} is supposed to reject —
 * see {@code policy_validation.feature}. Each case builds a document that breaks exactly one rule
 * from {@code PolicyValidator.validate*}, applies it through the real {@code PolicyStore}, and
 * {@code PolicyEditingSteps.thePolicyEditIsRejected()} (already defined) checks the outcome.
 */
public class PolicyValidationSteps {

    private final BddWorld world;

    public PolicyValidationSteps(BddWorld world) {
        this.world = world;
    }

    @When("the admin attempts a policy edit that {string}")
    public void theAdminAttemptsAPolicyEditThat(String violation) {
        world.attemptPolicyEdit(doc -> switch (violation) {
            case "uses an invalid role name" -> withRoles(doc, roles ->
                    roles.put("Not-Lowercase", new PolicyDocument.RolePolicy(List.of(), null)));

            case "lists a model twice in the catalog" -> {
                var models = new ArrayList<>(doc.models());
                models.add(models.getFirst());
                yield new PolicyDocument(doc.roles(), models, doc.guards(), doc.limits(), doc.rateLimit());
            }

            case "references an unknown model in a role" -> withRoles(doc, roles ->
                    roles.put("chat", new PolicyDocument.RolePolicy(List.of("totally-unknown-model"), null)));

            case "references an unknown guard" -> withGuards(doc, guards ->
                    guards.put("FAKE-GUARD", new PolicyDocument.GuardPolicy(true, 100, java.util.Map.of())));

            case "sets a guard order below the minimum" -> withGuards(doc, guards ->
                    guards.computeIfPresent("PII-RECOGNIZERS", (id, g) ->
                            new PolicyDocument.GuardPolicy(g.enabled(), 0, g.params())));

            case "sets a guard order above the maximum" -> withGuards(doc, guards ->
                    guards.computeIfPresent("PII-RECOGNIZERS", (id, g) ->
                            new PolicyDocument.GuardPolicy(g.enabled(), 10_001, g.params())));

            case "sets a threshold above 1" -> withGuards(doc, guards ->
                    guards.computeIfPresent("PII-RECOGNIZERS", (id, g) -> withParam(g, "threshold", 1.5)));

            case "sets a negative daily token budget" -> withRoles(doc, roles ->
                    roles.put("chat", new PolicyDocument.RolePolicy(List.of(), 0L)));

            case "sets max input tokens to zero" -> new PolicyDocument(doc.roles(), doc.models(), doc.guards(),
                    new PolicyDocument.Limits(0, doc.limits().maxOutputTokens()), doc.rateLimit());

            case "sets max output tokens above the maximum" -> new PolicyDocument(doc.roles(), doc.models(),
                    doc.guards(), new PolicyDocument.Limits(doc.limits().maxInputTokens(), 200_000), doc.rateLimit());

            case "references an unknown recognizer in a block list" -> withGuards(doc, guards ->
                    guards.computeIfPresent("PII-RECOGNIZERS", (id, g) ->
                            withParam(g, "blockRecognizers", List.of("PII-999"))));

            case "puts the same recognizer in two different lists" -> withGuards(doc, guards ->
                    guards.computeIfPresent("PII-RECOGNIZERS", (id, g) -> {
                        var params = new LinkedHashMap<>(g.params());
                        params.put("disabledRecognizers", List.of("PII-001"));
                        params.put("blockRecognizers", List.of("PII-001"));
                        return new PolicyDocument.GuardPolicy(g.enabled(), g.order(), params);
                    }));

            case "sets contextPrefixWords above the maximum" -> withGuards(doc, guards ->
                    guards.computeIfPresent("PII-RECOGNIZERS", (id, g) -> withParam(g, "contextPrefixWords", 21)));

            case "sets contextSuffixWords to a negative value" -> withGuards(doc, guards ->
                    guards.computeIfPresent("PII-RECOGNIZERS", (id, g) -> withParam(g, "contextSuffixWords", -1)));

            case "references an unknown recognizer in the monitor list" -> withGuards(doc, guards ->
                    guards.computeIfPresent("PII-RECOGNIZERS", (id, g) ->
                            withParam(g, "monitorRecognizers", List.of("PII-999"))));

            case "references an unknown recognizer in the disabled list" -> withGuards(doc, guards ->
                    guards.computeIfPresent("PII-RECOGNIZERS", (id, g) ->
                            withParam(g, "disabledRecognizers", List.of("PII-999"))));

            default -> throw new IllegalArgumentException("unknown violation: " + violation);
        });
    }

    private static PolicyDocument withRoles(PolicyDocument doc, java.util.function.Consumer<LinkedHashMap<String,
            PolicyDocument.RolePolicy>> mutate) {
        var roles = new LinkedHashMap<>(doc.roles());
        mutate.accept(roles);
        return new PolicyDocument(roles, doc.models(), doc.guards(), doc.limits(), doc.rateLimit());
    }

    private static PolicyDocument withGuards(PolicyDocument doc, java.util.function.Consumer<LinkedHashMap<String,
            PolicyDocument.GuardPolicy>> mutate) {
        var guards = new LinkedHashMap<>(doc.guards());
        mutate.accept(guards);
        return new PolicyDocument(doc.roles(), doc.models(), guards, doc.limits(), doc.rateLimit());
    }

    private static PolicyDocument.GuardPolicy withParam(PolicyDocument.GuardPolicy guard, String name, Object value) {
        var params = new LinkedHashMap<>(guard.params());
        params.put(name, value);
        return new PolicyDocument.GuardPolicy(guard.enabled(), guard.order(), params);
    }
}
