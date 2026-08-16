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
package net.onelitefeather.apus.api.rest.tenant;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import java.util.Arrays;
import java.util.List;
import net.onelitefeather.apus.api.policy.PolicyKey;

/**
 * {@code GET /api/policy-keys} -- the catalogue of options this module can actually enforce.
 *
 * <p>Exists so a console form cannot drift from what the API enforces: the inputs, their types
 * and their explanations all come from the same registry the enforcement does. Adding a key in
 * one place and forgetting the other is the failure this removes.
 *
 * <p>Carries no tenant data and is identical for every caller, so it is open to anyone
 * authenticated. Knowing which options <em>can</em> be enforced reveals nothing about which ones
 * are set for whom.
 */
@Controller("/api/policy-keys")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class PolicyKeyController {

    @Get
    public HttpResponse<List<PolicyKeyResponse>> list() {
        return HttpResponse.ok(
                Arrays.stream(PolicyKey.values()).map(PolicyKeyResponse::from).toList());
    }
}
