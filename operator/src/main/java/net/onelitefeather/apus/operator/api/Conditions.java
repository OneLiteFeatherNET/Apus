/**
 * Apus - render and host BlueMap maps on Kubernetes.
 * Copyright (C) 2026 OneLiteFeather and contributors
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.onelitefeather.apus.operator.api;

import io.fabric8.kubernetes.api.model.Condition;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Helpers for building and maintaining the standard {@code status.conditions} list. */
public final class Conditions {

    /** The condition type every Apus resource's readiness is reported under. */
    public static final String READY = "Ready";

    private Conditions() {}

    /**
     * Builds a {@code Ready} condition, stamped with the current time.
     *
     * @param ready whether the resource is currently ready
     * @param reason a short, machine-readable reason (CamelCase, no spaces)
     * @param message a human-readable explanation
     */
    public static Condition ready(boolean ready, String reason, String message) {
        Condition condition = new Condition();
        condition.setType(READY);
        condition.setStatus(ready ? "True" : "False");
        condition.setReason(reason);
        condition.setMessage(message);
        condition.setLastTransitionTime(Instant.now().toString());
        return condition;
    }

    /**
     * Adds {@code condition} to {@code conditions}, replacing any existing entry with the same
     * {@link Condition#getType()}. Keeps the list free of duplicate types the way the standard
     * Kubernetes condition contract expects.
     */
    public static void set(List<Condition> conditions, Condition condition) {
        conditions.removeIf(existing -> Objects.equals(existing.getType(), condition.getType()));
        conditions.add(condition);
    }
}
