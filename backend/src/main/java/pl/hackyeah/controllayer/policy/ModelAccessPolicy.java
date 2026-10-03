package pl.hackyeah.controllayer.policy;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Decyzja „czy ta rola może wołać ten model” (CRITERIA §4.2.1: kontrola dostępu). Brak roli
 * w polityce albo brak modelu na liście oznacza odmowę (fail-closed).
 */
@Service
public class ModelAccessPolicy {

    private static final Logger log = LoggerFactory.getLogger(ModelAccessPolicy.class);
    private static final String ANY_MODEL = "*";

    private final PolicyProperties policy;

    public ModelAccessPolicy(PolicyProperties policy) {
        this.policy = policy;
        if (policy.roles().isEmpty()) {
            log.warn("policy.roles is empty: model access is denied for every role");
        }
    }

    public boolean allowsModel(String role, String model) {
        return Optional.ofNullable(policy.roles().get(role))
                .map(PolicyProperties.RolePolicy::models)
                .filter(models -> models.contains(ANY_MODEL) || models.contains(model))
                .isPresent();
    }
}
