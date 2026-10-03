package pl.hackyeah.controllayer.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Metryki dla dashboardu (`/api/**` = rola ADMIN, SecurityConfig). Źródło: audyt + budżety. */
@RestController
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** @param window `1h` (słupki 5 min), `24h` (1 h) albo `7d` (6 h); inna wartość = `1h`. */
    @GetMapping("/api/dashboard")
    public Mono<DashboardView> dashboard(@RequestParam(defaultValue = "1h") String window) {
        return Mono.fromCallable(() -> dashboardService.compute(window)).subscribeOn(Schedulers.boundedElastic());
    }
}
