package pl.hackyeah.controllayer.policy;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import pl.hackyeah.controllayer.auth.AppUserRepository;
import pl.hackyeah.controllayer.budget.BudgetLimitsProperties;
import pl.hackyeah.controllayer.guard.Guard;
import pl.hackyeah.controllayer.guard.GuardProperties;
import pl.hackyeah.controllayer.guard.pii.PiiRecognizerGuard;
import pl.hackyeah.controllayer.model.ModelCatalogProperties;

/**
 * Źródło prawdy polityki: tabela `policy_version` (append-only), aktywna = najnowsza wersja.
 * Aktywna polityka jest w pamięci ({@link AtomicReference}) i podmieniana dopiero po udanym zapisie
 * nowej wersji — następne żądanie widzi ją od razu, bez restartu, a błędna wersja nigdy nie staje się
 * aktywna (VISION.md §4). Przy pierwszym starcie wersja 1 powstaje z dotychczasowych plików
 * (`policy.yaml`, `application.yml`).
 *
 * Zapis jest serializowany (`synchronized`) — wystarcza dla jednej instancji gatewaya (Pi); przy wielu
 * instancjach kolizję numeru wersji złapie klucz główny tabeli, a pozostałe instancje trzeba by
 * powiadamiać o zmianie (poza zakresem MVP).
 */
@Service
public class PolicyStore implements PolicySource {

    private static final Logger log = LoggerFactory.getLogger(PolicyStore.class);
    private static final int HISTORY_LIMIT = 200;

    private final PolicyVersionRepository repository;
    private final AppUserRepository users;
    private final List<Guard> guards;
    private final ModelCatalogProperties catalog;
    private final PolicyProperties seedPolicy;
    private final GuardProperties seedGuards;
    private final BudgetLimitsProperties seedLimits;
    private final AtomicReference<ActivePolicy> active = new AtomicReference<>();

    public PolicyStore(PolicyVersionRepository repository, AppUserRepository users, List<Guard> guards,
            ModelCatalogProperties catalog, PolicyProperties seedPolicy, GuardProperties seedGuards,
            BudgetLimitsProperties seedLimits) {
        this.repository = repository;
        this.users = users;
        this.guards = guards;
        this.catalog = catalog;
        this.seedPolicy = seedPolicy;
        this.seedGuards = seedGuards;
        this.seedLimits = seedLimits;
    }

    @PostConstruct
    void load() {
        var latest = repository.findTopByOrderByVersionDesc();
        if (latest.isPresent()) {
            active.set(latest.get().toActive());
            log.info("policy loaded version={} hash={}", active.get().version(), active.get().hash());
            return;
        }
        var seed = PolicyDocument.fromConfig(seedPolicy, catalog, seedGuards, seedLimits);
        var errors = PolicyValidator.validate(seed, validationContext());
        if (!errors.isEmpty()) {
            throw new IllegalStateException("seed policy from config files is invalid: " + errors);
        }
        active.set(save(1, seed, "seed", "seed", "initial policy from policy.yaml and application.yml"));
        log.info("policy seeded version=1 hash={}", active.get().hash());
    }

    @Override
    public ActivePolicy current() {
        return active.get();
    }

    public List<PolicyValidator.Error> validate(PolicyDocument document) {
        return PolicyValidator.validate(document, validationContext());
    }

    /**
     * Waliduje i aktywuje nową wersję. {@code baseVersion} to wersja, którą edytował admin — jeśli ktoś
     * w międzyczasie zapisał inną, zapis jest odrzucany ({@link ConflictException}) zamiast cicho nadpisać.
     * Identyczna treść nie tworzy nowej wersji.
     */
    public synchronized ActivePolicy apply(PolicyDocument document, Long baseVersion, String author, String source,
            String comment) {
        ActivePolicy current = active.get();
        if (baseVersion != null && baseVersion != current.version()) {
            throw new ConflictException(current.version());
        }
        var errors = validate(document);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        if (PolicyJson.hash(document).equals(current.hash())) {
            return current;
        }
        ActivePolicy saved = save(current.version() + 1, document, author, source, comment);
        active.set(saved);
        log.info("policy activated version={} hash={} author={} source={}", saved.version(), saved.hash(), author, source);
        return saved;
    }

    /** Przywrócenie starej wersji = nowa wersja z jej treścią (historia nie jest przepisywana). */
    public ActivePolicy restore(long version, String author) {
        ActivePolicy old = find(version).orElseThrow(() -> new NotFoundException(version));
        return apply(old.document(), null, author, "restore", "restored from version " + version);
    }

    public Optional<ActivePolicy> find(long version) {
        return repository.findById(version).map(PolicyVersion::toActive);
    }

    public List<PolicyVersion> history() {
        return repository.findAllByOrderByVersionDesc(PageRequest.of(0, HISTORY_LIMIT));
    }

    /** Rola → liczba kont; do walidacji (nie usuwamy ról z kontami) i do UI. */
    public Map<String, Long> roleAccounts() {
        return users.countByRole().stream()
                .collect(Collectors.toMap(AppUserRepository.RoleCount::getRole, AppUserRepository.RoleCount::getCount));
    }

    public Set<String> recognizerIds() {
        return guards.stream()
                .filter(PiiRecognizerGuard.class::isInstance)
                .map(PiiRecognizerGuard.class::cast)
                .flatMap(g -> g.recognizers().stream().map(r -> r.id()))
                .collect(Collectors.toSet());
    }

    private PolicyValidator.Context validationContext() {
        return new PolicyValidator.Context(
                guards.stream().map(Guard::id).collect(Collectors.toSet()),
                catalog.models().stream().map(ModelCatalogProperties.ModelEntry::tag).collect(Collectors.toSet()),
                recognizerIds(),
                roleAccounts());
    }

    private ActivePolicy save(long version, PolicyDocument document, String author, String source, String comment) {
        var row = new PolicyVersion(version, PolicyJson.toJson(document), PolicyJson.hash(document), author, source,
                comment == null || comment.isBlank() ? null : comment.strip(), Instant.now().truncatedTo(ChronoUnit.MILLIS));
        return repository.saveAndFlush(row).toActive();
    }

    public static class ValidationException extends RuntimeException {
        private final transient List<PolicyValidator.Error> errors;

        ValidationException(List<PolicyValidator.Error> errors) {
            super("policy is invalid: " + errors);
            this.errors = errors;
        }

        public List<PolicyValidator.Error> errors() {
            return errors;
        }
    }

    public static class ConflictException extends RuntimeException {
        private final long currentVersion;

        ConflictException(long currentVersion) {
            super("policy was changed in the meantime, current version is " + currentVersion);
            this.currentVersion = currentVersion;
        }

        public long currentVersion() {
            return currentVersion;
        }
    }

    public static class NotFoundException extends RuntimeException {
        NotFoundException(long version) {
            super("policy version " + version + " does not exist");
        }
    }
}
