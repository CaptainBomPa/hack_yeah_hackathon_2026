package pl.hackyeah.controllayer.policy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.hackyeah.controllayer.guard.Guard;
import pl.hackyeah.controllayer.guard.pii.PiiRecognizerGuard;
import pl.hackyeah.controllayer.model.ModelCatalogProperties;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Zarządzanie polityką z UI (`/api/**` = rola ADMIN, SecurityConfig). Zapis aktywuje nową wersję od
 * razu — następne żądanie czatu działa już według niej (docs/policy-management-plan.md §4.2).
 */
@RestController
@RequestMapping("/api/policy")
public class PolicyController {

    private final PolicyStore store;
    private final List<Guard> guards;
    private final ModelCatalogProperties catalog;

    public PolicyController(PolicyStore store, List<Guard> guards, ModelCatalogProperties catalog) {
        this.store = store;
        this.guards = guards;
        this.catalog = catalog;
    }

    /** Aktywna polityka + to, z czego UI może wybierać (modele, guardy, recognizery PII, role z kontami). */
    public record PolicyView(long version, String hash, String author, String source, String comment,
            Instant createdAt, PolicyDocument document, Catalog catalog) {}

    public record Catalog(List<CatalogModel> models, List<CatalogGuard> guards, List<CatalogRecognizer> piiRecognizers,
            Map<String, Long> roleAccounts) {}

    public record CatalogModel(String tag, String baseUrl) {}

    public record CatalogGuard(String id, String kind, List<String> stages) {}

    public record CatalogRecognizer(String id, String name, String entity, String defaultAction) {}

    public record VersionSummary(long version, String hash, String author, String source, String comment,
            Instant createdAt) {}

    public record SaveRequest(Long baseVersion, PolicyDocument document, String comment) {}

    public record ImportRequest(Long baseVersion, String yaml, String comment) {}

    public record ValidateRequest(PolicyDocument document) {}

    public record ValidationResult(boolean valid, List<PolicyValidator.Error> errors) {}

    @GetMapping
    public Mono<PolicyView> active() {
        return blocking(() -> view(store.current()));
    }

    @PostMapping("/validate")
    public Mono<ValidationResult> validate(@RequestBody ValidateRequest request) {
        return blocking(() -> {
            var errors = store.validate(request.document());
            return new ValidationResult(errors.isEmpty(), errors);
        });
    }

    @PutMapping
    public Mono<PolicyView> save(@RequestBody SaveRequest request, Authentication authentication) {
        return blocking(() -> view(store.apply(request.document(), request.baseVersion(),
                authentication.getName(), "ui", request.comment())));
    }

    @GetMapping("/versions")
    public Mono<List<VersionSummary>> versions() {
        return blocking(() -> store.history().stream()
                .map(v -> new VersionSummary(v.getVersion(), v.getHash(), v.getAuthor(), v.getSource(), v.getComment(),
                        v.getCreatedAt()))
                .toList());
    }

    @GetMapping("/versions/{version}")
    public Mono<PolicyView> version(@PathVariable long version) {
        return blocking(() -> view(store.find(version).orElseThrow(() -> new PolicyStore.NotFoundException(version))));
    }

    @PostMapping("/versions/{version}/restore")
    public Mono<PolicyView> restore(@PathVariable long version, Authentication authentication) {
        return blocking(() -> view(store.restore(version, authentication.getName())));
    }

    /** Aktywna polityka jako YAML — do podglądu i edycji poza UI (CRITERIA §6: jury zmienia pliki konfiguracji). */
    @GetMapping("/export")
    public Mono<ResponseEntity<String>> export() {
        return blocking(() -> {
            ActivePolicy policy = store.current();
            String yaml = "# AI Control Layer policy v" + policy.version() + " (" + policy.hash() + ")\n"
                    + "# Import it back from the Policies screen (Advanced > Import YAML).\n"
                    + PolicyJson.toYaml(policy.document());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename("policy-v" + policy.version() + ".yaml").build().toString())
                    .contentType(new MediaType("application", "yaml"))
                    .body(yaml);
        });
    }

    @PostMapping("/import")
    public Mono<PolicyView> importYaml(@RequestBody ImportRequest request, Authentication authentication) {
        return blocking(() -> {
            PolicyDocument document;
            try {
                document = PolicyJson.fromYaml(request.yaml() == null ? "" : request.yaml());
            } catch (RuntimeException e) {
                throw new PolicyStore.ValidationException(List.of(new PolicyValidator.Error("yaml",
                        "cannot read YAML: " + firstLine(e.getMessage()))));
            }
            return view(store.apply(document, request.baseVersion(), authentication.getName(), "import",
                    request.comment()));
        });
    }

    @ExceptionHandler(PolicyStore.ValidationException.class)
    ResponseEntity<Map<String, Object>> invalid(PolicyStore.ValidationException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("error", Map.of("code", "invalid_policy", "message", "Policy is invalid",
                        "errors", e.errors())));
    }

    @ExceptionHandler(PolicyStore.ConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(PolicyStore.ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", Map.of("code", "conflict", "message", e.getMessage(),
                        "currentVersion", e.currentVersion())));
    }

    @ExceptionHandler(PolicyStore.NotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(PolicyStore.NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", Map.of("code", "not_found", "message", e.getMessage())));
    }

    private PolicyView view(ActivePolicy policy) {
        var catalogView = new Catalog(
                catalog.models().stream().map(m -> new CatalogModel(m.tag(), m.baseUrl())).toList(),
                guards.stream()
                        .map(g -> new CatalogGuard(g.id(), g.kind(), g.stages().stream().map(Enum::name).sorted().toList()))
                        .sorted(java.util.Comparator.comparing(CatalogGuard::id))
                        .toList(),
                guards.stream()
                        .filter(PiiRecognizerGuard.class::isInstance)
                        .flatMap(g -> ((PiiRecognizerGuard) g).recognizers().stream())
                        .map(r -> new CatalogRecognizer(r.id(), r.name(), r.entity(), r.action().name().toLowerCase()))
                        .toList(),
                store.roleAccounts());
        return new PolicyView(policy.version(), policy.hash(), policy.author(), policy.source(), policy.comment(),
                policy.createdAt(), policy.document(), catalogView);
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static <T> Mono<T> blocking(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }
}
