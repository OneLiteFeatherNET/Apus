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

/**
 * Thrown for a malformed request body. {@code micronaut-validation} (the usual home for
 * {@code @NotBlank}/{@code @Valid}-driven checks) is not on this module's classpath -- task 1's
 * report flagged it as deliberately left out (YAGNI, nothing until now needed it) and adding it
 * would mean editing {@code api/build.gradle.kts}, which is out of this task's file scope (see
 * task-2-brief.md) and, per the same report, a build-file conflict better reported than resolved
 * unilaterally while task 3 works in the same module. Request bodies are therefore validated by
 * hand in each controller, and this exception is the uniform result.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
