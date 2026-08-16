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
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import java.util.List;
import net.onelitefeather.apus.api.policy.PolicyKey;
import net.onelitefeather.apus.api.policy.TenantPolicyReader;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.support.PrincipalResolver;

/**
 * {@code GET /api/tenant/policy} -- the caller's own tenant's options, read-only.
 *
 * <p><b>Why this exists.</b> Without it a locked option first becomes visible to a user as a
 * rejection <em>after</em> they filled in and submitted a form, which is the worst possible
 * moment to learn a rule. With it the tenant application can offer only what is permitted and say
 * why the rest is missing.
 *
 * <p>Scoped to the caller by construction: the tenant comes from the token, and there is no path
 * parameter for anyone to point at another tenant's policy. Unlike {@code /api/tenants} this is
 * open to any authenticated caller, because the entries it returns are the rules that already
 * govern that caller's own requests -- learning them cannot tell anyone anything they could not
 * discover by being refused.
 */
@Controller("/api/tenant/policy")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class TenantPolicyController {

    private final PrincipalResolver principalResolver;
    private final TenantPolicyReader reader;

    public TenantPolicyController(PrincipalResolver principalResolver, TenantPolicyReader reader) {
        this.principalResolver = principalResolver;
        this.reader = reader;
    }

    @Get
    public HttpResponse<List<PolicyEntryResponse>> policy(Authentication authentication) {
        ApusPrincipal principal = principalResolver.resolve(authentication);
        List<PolicyEntryResponse> entries = reader.forPrincipal(principal).stream()
                .map(view -> new PolicyEntryResponse(
                        view.key(), view.type(), view.value(), view.locked(), PolicyKey.isEnforced(view.key())))
                .toList();
        return HttpResponse.ok(entries);
    }
}
