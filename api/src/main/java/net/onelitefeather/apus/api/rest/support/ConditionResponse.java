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
package net.onelitefeather.apus.api.rest.support;

import io.fabric8.kubernetes.api.model.Condition;
import io.micronaut.serde.annotation.Serdeable;

/**
 * A simplified view of a Kubernetes {@link Condition}, shared by every response type in {@code
 * rest/} that surfaces a resource's conditions. Deliberately not {@link Condition} itself --
 * that type carries {@code observedGeneration} and other reconciler bookkeeping nobody outside
 * the cluster needs (see task-2-brief.md on response models being their own types).
 */
@Serdeable
public record ConditionResponse(String type, String status, String reason, String message) {

    public static ConditionResponse from(Condition condition) {
        return new ConditionResponse(
                condition.getType(), condition.getStatus(), condition.getReason(), condition.getMessage());
    }
}
