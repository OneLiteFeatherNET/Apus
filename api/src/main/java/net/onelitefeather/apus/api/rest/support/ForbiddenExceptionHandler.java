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

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import net.onelitefeather.apus.api.security.ForbiddenException;

/**
 * Maps {@link ForbiddenException} to HTTP 403. Task 1 built {@code ForbiddenException} (thrown
 * by {@code TenantResolver} when a principal carries no tenant claim) but explicitly left this
 * mapping undone -- see its report's "Concerns for Task 2 / Task 3": "nothing currently maps it
 * ... that mapping logic doesn't exist yet and needs to land wherever the first controller
 * does." Controllers in {@code rest/} also throw this exception directly for their own
 * insufficient-role checks (see {@code TenantAccess}), so every 403 in this module -- whether
 * "no tenant" or "wrong role" -- funnels through here.
 */
@Produces
@Singleton
@Requires(classes = ForbiddenException.class)
public class ForbiddenExceptionHandler implements ExceptionHandler<ForbiddenException, HttpResponse<?>> {

    @Override
    public HttpResponse<?> handle(HttpRequest request, ForbiddenException exception) {
        return HttpResponse.status(HttpStatus.FORBIDDEN);
    }
}
