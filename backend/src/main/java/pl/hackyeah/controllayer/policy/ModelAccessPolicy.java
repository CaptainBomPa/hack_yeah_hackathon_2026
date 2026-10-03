package pl.hackyeah.controllayer.policy;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Decyzja „czy ta rola może wołać ten model” (CRITERIA §4.2.1: kontrola dostępu) według aktywnej
 * polityki. Brak roli w polityce albo brak modelu na liście oznacza odmowę (fail-closed).
 */
@Service
public class ModelAccessPolicy {

    private final PolicySource policySource;

    @Autowired
    public ModelAccessPolicy(PolicySource policySource) {
        this.policySource = policySource;
    }

    /** Dla testów: stała polityka z `policy.yaml`. */
    public ModelAccessPolicy(PolicyProperties policy) {
        this(PolicySource.fixed(PolicyDocument.fromConfig(policy, null, null, null)));
    }

    public boolean allowsModel(String role, String model) {
        return allowsModel(policySource.current(), role, model);
    }

    public boolean allowsModel(ActivePolicy policy, String role, String model) {
        return Optional.ofNullable(policy.document().roles().get(role))
                .map(PolicyDocument.RolePolicy::models)
                .filter(models -> models.contains(PolicyDocument.ANY_MODEL) || models.contains(model))
                .isPresent();
    }
}
