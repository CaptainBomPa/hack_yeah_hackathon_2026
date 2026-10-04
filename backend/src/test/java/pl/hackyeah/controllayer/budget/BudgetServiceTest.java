package pl.hackyeah.controllayer.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Weryfikuje, że atomowy wzorzec rezerwacji/rozliczenia z
 * docs/deterministic/14-token-budget-quotas.md §9 (INSERT ... ON CONFLICT DO NOTHING + UPDATE z
 * warunkiem w WHERE + GREATEST) faktycznie działa na H2 w trybie zgodności z PostgreSQL — czyli
 * na tym samym silniku, co profil `local` backendu. Każdy test dostaje własną, odizolowaną bazę
 * in-memory, żeby testy mogły się wykonywać równolegle bez współdzielenia stanu.
 */
class BudgetServiceTest {

    private BudgetService service;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:budget-test-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE budget_counter (
                    subject      VARCHAR(200) NOT NULL,
                    period_kind  VARCHAR(10)  NOT NULL,
                    period_start DATE         NOT NULL,
                    used_tokens  BIGINT       NOT NULL DEFAULT 0,
                    reserved     BIGINT       NOT NULL DEFAULT 0,
                    PRIMARY KEY (subject, period_kind, period_start)
                )
                """);
        service = new BudgetService(jdbcTemplate);
    }

    @Test // BUDGET-T001-style: pierwsza rezerwacja w dniu, limit nie przekroczony
    void allowsReservationUnderTheLimit() {
        BudgetReservation reservation = service.reserve("chat", 1000L, 400).block();
        assertTrue(reservation.allowed());
        assertEquals(400, reservation.usedTokensAfter());
    }

    @Test // BUDGET-T005: kolejna rezerwacja po wyczerpaniu limitu jest odrzucana
    void deniesReservationThatWouldExceedTheLimit() {
        service.reserve("chat", 1000L, 900).block();
        BudgetReservation second = service.reserve("chat", 1000L, 200).block();
        assertFalse(second.allowed());
    }

    @Test // BUDGET-004: rozliczenie zwalnia rezerwację i księguje rzeczywiste zużycie
    void reconcileReleasesReservationAndBooksActualUsage() {
        service.reserve("chat", 1000L, 500).block();
        service.reconcile("chat", 1000L, 500L, 300L).block();

        // 300 zaksięgowane + 700 nowej rezerwacji == dokładnie limit 1000 -> wciąż dozwolone.
        BudgetReservation next = service.reserve("chat", 1000L, 700).block();
        assertTrue(next.allowed());
        assertEquals(1000, next.usedTokensAfter());
    }

    @Test // zwolniona rezerwacja (np. request zablokowany/błąd upstreamu) nie zjada budżetu na stałe
    void reconcileWithZeroActualUsageFullyReleasesTheReservation() {
        service.reserve("chat", 1000L, 1000).block();
        service.reconcile("chat", 1000L, 1000L, 0L).block();

        BudgetReservation next = service.reserve("chat", 1000L, 1000).block();
        assertTrue(next.allowed());
    }

    @Test // BUDGET-005: próg ostrzegawczy przy >= 80% dziennego limitu
    void flagsSoftCapAtEightyPercent() {
        BudgetReservation reservation = service.reserve("chat", 1000L, 800).block();
        assertTrue(reservation.overSoftCap());
    }

    @Test
    void doesNotTouchTheDatabaseWhenNoDailyLimitIsConfigured() {
        // dailyLimit == null (np. rola "admin" bez sekcji budget w policy.yaml) -> brak limitu,
        // reserve() w ogóle nie woła JdbcTemplate (BudgetGate.dailyLimitOf zwraca null).
        var unlimitedService = new BudgetService(null);
        BudgetReservation reservation = unlimitedService.reserve("admin", null, 999_999_999).block();
        assertTrue(reservation.allowed());
    }

    @Test
    void booksUsageOfARoleWithoutDailyLimitSoTheDashboardSeesIt() {
        assertTrue(service.reserve("admin", null, 999_999_999).block().allowed());
        assertEquals(120L, service.reconcile("admin", null, 999_999_999, 120).block());
        assertEquals(150L, service.reconcile("admin", null, 999_999_999, 30).block());

        var usage = service.todayUsageByRole().get("admin");
        assertEquals(150, usage.usedTokens());
        assertEquals(0, usage.reservedTokens());
    }

    @Test
    void twoDifferentRolesHaveIndependentBudgets() {
        service.reserve("chat", 1000L, 1000).block();
        BudgetReservation chatExhausted = service.reserve("chat", 1000L, 1).block();
        BudgetReservation agentStillFresh = service.reserve("agent", 1000L, 1).block();

        assertFalse(chatExhausted.allowed());
        assertTrue(agentStillFresh.allowed());
    }
}
