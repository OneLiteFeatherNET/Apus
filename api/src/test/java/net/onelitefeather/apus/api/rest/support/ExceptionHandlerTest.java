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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micronaut.http.HttpStatus;
import net.onelitefeather.apus.api.security.ForbiddenException;
import org.junit.jupiter.api.Test;

/**
 * These handlers are plain classes -- no {@code @Requires}/{@code @Produces} processing needed
 * to call {@code handle} directly -- so this closes the loop the controller tests can't: proof
 * that {@link ForbiddenException} and {@link NotFoundException}, once thrown, actually resolve
 * to the HTTP status task-2-brief.md requires (403 and 404 respectively).
 */
class ExceptionHandlerTest {

    @Test
    void forbiddenExceptionMapsTo403() {
        var handler = new ForbiddenExceptionHandler();
        var response = handler.handle(null, new ForbiddenException("no tenant"));
        assertEquals(HttpStatus.FORBIDDEN, response.status());
    }

    @Test
    void notFoundExceptionMapsTo404() {
        var handler = new NotFoundExceptionHandler();
        var response = handler.handle(null, new NotFoundException("no such resource"));
        assertEquals(HttpStatus.NOT_FOUND, response.status());
    }

    @Test
    void badRequestExceptionMapsTo400WithMessage() {
        var handler = new BadRequestExceptionHandler();
        var response = handler.handle(null, new BadRequestException("name must not be blank"));
        assertEquals(HttpStatus.BAD_REQUEST, response.status());
        assertEquals(
                "name must not be blank",
                ((BadRequestExceptionHandler.ErrorBody) response.body()).message());
    }
}
