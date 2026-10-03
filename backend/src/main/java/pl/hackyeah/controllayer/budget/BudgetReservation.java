package pl.hackyeah.controllayer.budget;

/**
 * Wynik próby rezerwacji budżetu (BUDGET-003). `reservedTokens` jest potrzebne później do
 * rozliczenia (BUDGET-004) — nawet przy odmowie trzymamy 0, żeby nie rozliczać czegoś, co nigdy
 * nie zostało zarezerwowane.
 */
record BudgetReservation(boolean allowed, long reservedTokens, long usedTokensAfter, long dailyLimit) {

    static BudgetReservation unlimited() {
        return new BudgetReservation(true, 0, 0, 0);
    }

    static BudgetReservation denied(long dailyLimit) {
        return new BudgetReservation(false, 0, dailyLimit, dailyLimit);
    }

    /** Czy po tej rezerwacji rola przekroczyła próg ostrzegawczy (BUDGET-005, 80%). */
    boolean overSoftCap() {
        return dailyLimit > 0 && usedTokensAfter * 100.0 / dailyLimit >= 80.0;
    }
}
