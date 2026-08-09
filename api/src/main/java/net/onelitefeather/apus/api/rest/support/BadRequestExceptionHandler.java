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
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;

/** Maps {@link BadRequestException} (hand-rolled request validation, see its Javadoc) to HTTP
 * 400, with the exception's message surfaced so a caller can see what was wrong with the body. */
@Produces
@Singleton
@Requires(classes = BadRequestException.class)
public class BadRequestExceptionHandler implements ExceptionHandler<BadRequestException, HttpResponse<?>> {

    @Override
    public HttpResponse<?> handle(HttpRequest request, BadRequestException exception) {
        return HttpResponse.badRequest(new ErrorBody(exception.getMessage()));
    }

    /** Minimal JSON error body -- {@code {"message": "..."}} -- for a failed manual validation. */
    @Serdeable
    public record ErrorBody(String message) {}
}
