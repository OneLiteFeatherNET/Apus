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
package net.onelitefeather.apus.api.security;

import io.micronaut.core.annotation.Order;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.SecurityFilter;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies {@link ImpersonationPolicy} to a request that asks to be served as somebody else.
 *
 * <p>Two headers, both optional:
 *
 * <ul>
 *   <li>{@code X-Apus-Act-As-Tenant} -- the tenant to act within. Required to impersonate at all.
 *   <li>{@code X-Apus-Act-As-User} -- the person to appear as. Omit to act as the tenant itself,
 *       which is the "as org admin" case.
 * </ul>
 *
 * <p><b>Done in a filter, once, rather than in each controller.</b> Every controller in this
 * module already resolves its caller through {@link PrincipalResolver}; replacing the request's
 * {@link Authentication} here means all of them -- including ones written later by someone who
 * has never heard of this feature -- see the impersonated principal and enforce their own rules
 * against it, with no chance of one forgetting.
 *
 * <p><b>A refused impersonation fails the request.</b> It does not quietly fall back to the real
 * principal: someone who asked to act as a tenant and was served as themselves would read the
 * answer as that tenant's, which is a worse outcome than an error.
 *
 * <p>Every impersonated request is logged with the real subject before it is served.
 */
@ServerFilter("/api/**")
// Explicitly after Micronaut's own security filter, because this reads the Authentication that
// filter puts on the request. Left to the default ordering it could run first, find no
// authentication, and refuse every impersonated request -- a failure that would look like a
// permission problem and send somebody looking at roles.
//
// A literal because an annotation value must be a constant expression and
// ServerFilterPhase.SECURITY.after() is a method call. ImpersonationFilterOrderTest asserts the
// two agree, so a Micronaut release that renumbers the phases fails a test rather than silently
// reordering this filter.
@Order(ImpersonationFilter.ORDER)
public class ImpersonationFilter {

    /** {@code ServerFilterPhase.SECURITY.after()}; see the {@link Order} annotation above. */
    public static final int ORDER = 39250;

    private static final Logger LOGGER = LoggerFactory.getLogger(ImpersonationFilter.class);

    /** The tenant to act within. Without it, nothing here happens at all. */
    public static final String TENANT_HEADER = "X-Apus-Act-As-Tenant";

    /** The person to appear as; optional, see the class Javadoc. */
    public static final String USER_HEADER = "X-Apus-Act-As-User";

    /**
     * Where the effective principal is published. Set as an attribute rather than only mutating
     * the authentication so an audit or telemetry consumer can tell an impersonated request from
     * an ordinary one without re-deriving it.
     */
    public static final String IMPERSONATION_ATTRIBUTE = "apus.impersonation";

    private final ImpersonationPolicy policy;
    private final PrincipalResolver principals;

    public ImpersonationFilter(ImpersonationPolicy policy, PrincipalResolver principals) {
        this.policy = policy;
        this.principals = principals;
    }

    @RequestFilter
    public void filter(HttpRequest<?> request) {
        String tenant = request.getHeaders().get(TENANT_HEADER);
        if (tenant == null || tenant.isBlank()) {
            return;
        }

        Authentication authentication =
                request.getAttribute(SecurityFilter.AUTHENTICATION, Authentication.class).orElse(null);
        if (authentication == null) {
            // Not authenticated at all: the security filter will refuse this request on its own,
            // and impersonating on behalf of nobody is not a thing to attempt.
            throw new ForbiddenException("impersonation requires an authenticated caller");
        }

        ApusPrincipal real = principals.resolve(authentication);
        String user = request.getHeaders().get(USER_HEADER);
        Impersonation impersonation = policy.resolve(real, tenant.trim(), user == null ? null : user.trim());

        // Logged before the request is served, so an attempt that then fails is on the record.
        LOGGER.info("{} -> {} {}", impersonation.describe(), request.getMethodName(), request.getPath());

        request.setAttribute(IMPERSONATION_ATTRIBUTE, impersonation);
        request.setAttribute(SecurityFilter.AUTHENTICATION, asAuthentication(impersonation));
    }

    /**
     * The effective principal as an {@link Authentication}, so {@link PrincipalResolver} resolves
     * it the same way it resolves a real one and no controller needs a second code path.
     *
     * <p>The tenant is carried as the explicit organisation claim rather than as groups: it has
     * already been decided by the policy, and re-deriving it from group membership would let the
     * group index have an opinion about a decision that was already made.
     */
    private static Authentication asAuthentication(Impersonation impersonation) {
        ApusPrincipal effective = impersonation.effective();
        List<String> roles =
                effective.roles().stream().map(Role::claimValue).toList();
        return Authentication.build(
                effective.subject(),
                roles,
                Map.of(
                        PrincipalResolver.TENANT_CLAIM, effective.tenant(),
                        "apus_act_as_by", impersonation.realSubject()));
    }
}
