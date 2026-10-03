package pl.hackyeah.controllayer.chat;

/**
 * Zużycie dziennego budżetu roli wywołującego, dołączane do {@link GuardedChatResponse}
 * (docs/deterministic/14-token-budget-quotas.md, BUDGET-003/005) — żeby użytkownik widział,
 * ile mu zostało, bez osobnego endpointu (`/api/**` wymaga roli ADMIN, więc zwykły `chat` i tak
 * by się tam nie dostał). `cap == null` oznacza rolę bez limitu (np. admin).
 */
public record BudgetUsage(long used, Long cap) {}
