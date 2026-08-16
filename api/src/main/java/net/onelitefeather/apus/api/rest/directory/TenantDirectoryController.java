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
package net.onelitefeather.apus.api.rest.directory;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import java.util.List;
import java.util.Locale;
import net.onelitefeather.apus.api.directory.Directory;
import net.onelitefeather.apus.api.directory.DirectoryGuard;
import net.onelitefeather.apus.api.directory.DirectoryTeam;
import net.onelitefeather.apus.api.directory.DirectoryUnavailableException;
import net.onelitefeather.apus.api.directory.DirectoryUser;
import net.onelitefeather.apus.api.rest.directory.DirectoryRequests.CreateTeamRequest;
import net.onelitefeather.apus.api.rest.directory.DirectoryRequests.InviteUserRequest;
import net.onelitefeather.apus.api.rest.directory.DirectoryResponses.DirectoryCountsResponse;
import net.onelitefeather.apus.api.rest.directory.DirectoryResponses.PasswordResetResponse;
import net.onelitefeather.apus.api.rest.directory.DirectoryResponses.TeamResponse;
import net.onelitefeather.apus.api.rest.directory.DirectoryResponses.TenantDirectoryResponse;
import net.onelitefeather.apus.api.rest.directory.DirectoryResponses.UserResponse;
import net.onelitefeather.apus.api.rest.support.BadRequestException;
import net.onelitefeather.apus.api.rest.support.NotFoundException;
import net.onelitefeather.apus.api.rest.tenant.TenantRepository;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import net.onelitefeather.apus.operator.api.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A tenant's teams and people: how many there are, who they are, and the four things an
 * administrator can change.
 *
 * <p><b>Every method calls {@link DirectoryGuard} before it calls {@link Directory}.</b> That
 * ordering is the whole security model of this controller. The Graph permissions behind these
 * operations are directory-wide -- Entra offers no narrower variant -- so the guard is what keeps
 * them pointed at groups a {@code Tenant} actually claims, and nothing in {@link Directory} will
 * refuse on its own.
 *
 * <p><b>A tenant nobody may see is a 404, not a 403</b>, matching the rest of this module: a
 * tenant-owner probing for other tenants must not be able to tell "exists but forbidden" from
 * "does not exist".
 *
 * <p><b>The directory being down is not this page failing.</b> Reads catch {@link
 * DirectoryUnavailableException} and report the panel unavailable with a reason, so a tenant
 * whose storage and renders are fine stays readable while Microsoft is throttling. Writes do not:
 * an invitation that silently did not happen would be far worse than an error.
 */
@Controller("/api/tenants/{tenant}/directory")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class TenantDirectoryController {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantDirectoryController.class);

    private final TenantRepository tenants;
    private final Directory directory;
    private final DirectoryGuard guard;
    private final PrincipalResolver principals;

    public TenantDirectoryController(
            TenantRepository tenants, Directory directory, DirectoryGuard guard, PrincipalResolver principals) {
        this.tenants = tenants;
        this.directory = directory;
        this.guard = guard;
        this.principals = principals;
    }

    /** Counts for the tenant list. Never fails the page -- see the class Javadoc. */
    @Get("/counts")
    public HttpResponse<DirectoryCountsResponse> counts(Authentication authentication, @PathVariable String tenant) {
        ApusPrincipal principal = principals.resolve(authentication);
        String group = groupOf(principal, tenant);
        guard.requireTenantAccess(principal, tenant, group);
        try {
            return HttpResponse.ok(DirectoryCountsResponse.of(
                    directory.teamsIn(group).size(), directory.membersOf(group).size()));
        } catch (DirectoryUnavailableException e) {
            LOGGER.warn("directory counts unavailable for tenant '{}': {}", tenant, e.getMessage());
            return HttpResponse.ok(DirectoryCountsResponse.unavailable(e.getMessage()));
        }
    }

    /** The teams and members of one tenant. */
    @Get
    public HttpResponse<TenantDirectoryResponse> read(Authentication authentication, @PathVariable String tenant) {
        ApusPrincipal principal = principals.resolve(authentication);
        String group = groupOf(principal, tenant);
        guard.requireTenantAccess(principal, tenant, group);
        try {
            List<TeamResponse> teams =
                    directory.teamsIn(group).stream().map(TeamResponse::from).toList();
            List<UserResponse> users =
                    directory.membersOf(group).stream().map(UserResponse::from).toList();
            return HttpResponse.ok(new TenantDirectoryResponse(teams, users, null));
        } catch (DirectoryUnavailableException e) {
            LOGGER.warn("directory unavailable for tenant '{}': {}", tenant, e.getMessage());
            return HttpResponse.ok(new TenantDirectoryResponse(List.of(), List.of(), e.getMessage()));
        }
    }

    @Post("/teams")
    public HttpResponse<TeamResponse> createTeam(
            Authentication authentication, @PathVariable String tenant, @Body CreateTeamRequest request) {
        ApusPrincipal principal = principals.resolve(authentication);
        String group = groupOf(principal, tenant);
        guard.requireTenantWrite(principal, tenant, group);

        String displayName = request == null || request.displayName() == null
                ? ""
                : request.displayName().trim();
        if (displayName.isEmpty()) {
            throw new BadRequestException("a team needs a name");
        }
        // Logged before the call, so an attempt that fails is on the record too.
        LOGGER.info("'{}' is creating team '{}' in tenant '{}'", principal.subject(), displayName, tenant);
        DirectoryTeam team = directory.createTeam(group, displayName);
        return HttpResponse.created(TeamResponse.from(team));
    }

    @Post("/invitations")
    public HttpResponse<UserResponse> invite(
            Authentication authentication, @PathVariable String tenant, @Body InviteUserRequest request) {
        ApusPrincipal principal = principals.resolve(authentication);
        String group = groupOf(principal, tenant);
        guard.requireTenantWrite(principal, tenant, group);

        String email =
                request == null || request.email() == null ? "" : request.email().trim();
        if (!looksLikeAnAddress(email)) {
            throw new BadRequestException("an invitation needs an e-mail address");
        }
        String displayName = request.displayName() == null
                        || request.displayName().isBlank()
                ? email.substring(0, email.indexOf('@'))
                : request.displayName().trim();

        LOGGER.info("'{}' is inviting '{}' into tenant '{}'", principal.subject(), email, tenant);
        return HttpResponse.created(UserResponse.from(directory.invite(group, email, displayName)));
    }

    /**
     * Resets a member's password and returns the temporary one.
     *
     * <p>Four checks stand between a request and a changed password, and the order matters: the
     * group must be one Apus manages, the caller must be able to write in this tenant, the target
     * must actually be a member of it, and only then may the guard weigh in on who the target
     * <em>is</em>. Doing the membership check before fetching roles also means a caller cannot
     * use this endpoint to probe which accounts hold privileged roles.
     */
    @Post("/users/{userId}/password-reset")
    public HttpResponse<PasswordResetResponse> resetPassword(
            Authentication authentication, @PathVariable String tenant, @PathVariable String userId) {
        ApusPrincipal principal = principals.resolve(authentication);
        String group = groupOf(principal, tenant);
        guard.requireTenantWrite(principal, tenant, group);

        boolean isMember =
                directory.membersOf(group).stream().anyMatch(member -> member.id().equals(userId));
        if (!isMember) {
            // 404, not 403: whether an account exists elsewhere in the directory is not something
            // this endpoint should confirm.
            throw new NotFoundException("no such member of tenant '" + tenant + "'");
        }

        DirectoryUser target = directory.findUser(userId);
        if (target == null) {
            throw new NotFoundException("no such member of tenant '" + tenant + "'");
        }
        guard.requirePasswordResetAllowed(principal, target);

        LOGGER.info("'{}' is resetting the password of '{}' in tenant '{}'", principal.subject(), userId, tenant);
        return HttpResponse.ok(new PasswordResetResponse(userId, directory.resetPassword(userId)));
    }

    /**
     * The tenant's identity group, or a {@code 404} if there is no such tenant.
     *
     * <p>A tenant that exists but has no group configured is deliberately <em>not</em> a 404 --
     * it is a real tenant, and the guard's message about a missing identity group is the useful
     * answer. Reporting "no such tenant" there would send an administrator looking for the wrong
     * problem entirely.
     */
    private String groupOf(ApusPrincipal principal, String tenantName) {
        Tenant tenant = tenants.findByName(tenantName).orElseThrow(() -> {
            if (!principal.isPlatformAdmin()) {
                return new NotFoundException("no such tenant");
            }
            return new NotFoundException("no such tenant: " + tenantName);
        });
        return tenant.getSpec().getIdentity().getGroupId();
    }

    /**
     * Enough of an address check to catch a mistyped field, and no more. The identity provider
     * does the real validation, and a stricter pattern here would reject addresses that are
     * perfectly valid before the invitation ever reaches it.
     */
    private static boolean looksLikeAnAddress(String email) {
        int at = email.indexOf('@');
        return at > 0
                && at < email.length() - 1
                && email.indexOf('@', at + 1) < 0
                && email.lastIndexOf('.') > at
                && !email.contains(" ")
                && email.equals(email.toLowerCase(Locale.ROOT).trim());
    }
}
