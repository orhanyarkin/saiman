package io.github.orhanyarkin.saiman.modelrouter;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.util.HashMap;
import java.util.Map;

/** Per-process scoped guard for tests and single-process demos; all operations run under one lock. */
public final class InMemoryScopedCostGuard implements ScopedCostGuard {

    private record Scope(long budget, long spent) {}

    private final Map<String, Scope> scopes = new HashMap<>();

    @Override
    public synchronized ScopeReservation reserve(String scopeId, Money estimate, Money budget) {
        ScopedCostGuard.requireValidScopeId(scopeId);
        Scope scope = scopes.getOrDefault(scopeId, new Scope(budget.atomicUnits(), 0));
        scopes.put(scopeId, scope);
        long next;
        try {
            next = Math.addExact(scope.spent(), estimate.atomicUnits());
        } catch (ArithmeticException e) {
            throw new ScopeBudgetExceededException("model cost budget of the run is exhausted");
        }
        if (next > scope.budget()) {
            throw new ScopeBudgetExceededException("model cost budget of the run is exhausted");
        }
        scopes.put(scopeId, new Scope(scope.budget(), next));
        return new ScopeReservation(scopeId, estimate);
    }

    @Override
    public synchronized void settle(String scopeId, ScopeReservation reservation, Money actual) {
        Scope scope = scopes.get(scopeId);
        if (scope == null) {
            return;
        }
        long delta = actual.atomicUnits() - reservation.estimate().atomicUnits();
        long next;
        try {
            next = Math.addExact(scope.spent(), delta);
        } catch (ArithmeticException e) {
            next = delta > 0 ? Long.MAX_VALUE : 0;
        }
        scopes.put(scopeId, new Scope(scope.budget(), Math.max(0, next)));
    }

    @Override
    public synchronized Money spent(String scopeId) {
        Scope scope = scopes.get(scopeId);
        return Money.usdMicros(scope == null ? 0 : scope.spent());
    }
}
