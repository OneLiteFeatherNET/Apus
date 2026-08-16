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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.security.authentication.Authentication;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.onelitefeather.apus.api.directory.DirectoryGuard;
import net.onelitefeather.apus.api.directory.DirectoryTeam;
import net.onelitefeather.apus.api.directory.DirectoryUser;
import net.onelitefeather.apus.api.directory.TenantGroupIndex;
import net.onelitefeather.apus.api.rest.directory.DirectoryRequests.CreateTeamRequest;
import net.onelitefeather.apus.api.rest.directory.DirectoryRequests.InviteUserRequest;
import net.onelitefeather.apus.api.rest.support.BadRequestException;
import net.onelitefeather.apus.api.rest.support.NotFoundException;
import net.onelitefeather.apus.api.rest.tenant.InMemoryTenantRepository;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import net.onelitefeather.apus.operator.api.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TenantDirectoryControllerTest {

    private static final String ACME_GROUP = "g-acme";
    private static final String GLOBEX_GROUP = "g-globex";

    private final InMemoryTenantRepository tenants = new InMemoryTenantRepository();
    private final InMemoryDirectory directory = new InMemoryDirectory();
    private final DirectoryGuard guard = new DirectoryGuard();
    private final PrincipalResolver principals = new PrincipalResolver();
    private final TenantDirectoryController controller =
            new TenantDirectoryController(tenants, directory, guard, principals);

    @BeforeEach
    void setUp() {
        tenants.put(tenant("acme", ACME_GROUP));
        tenants.put(tenant("globex", GLOBEX_GROUP));
        tenants.put(tenant("unconfigured", null));
        TenantGroupIndex index = TenantGroupIndex.of(tenants.list());
        principals.setGroupIndex(index);
        guard.setManagedGroups(index.managedGroups());
    }

    private static Tenant tenant(String name, String groupId) {
        Tenant tenant = new Tenant();
        tenant.getMetadata().setName(name);
        tenant.getSpec().getIdentity().setGroupId(groupId);
        return tenant;
    }

    private static Authentication platformAdmin() {
        return Authentication.build("root", List.of("platform-admin"), Map.of());
    }

    /** A tenant-owner whose tenant comes from the groups claim, exactly as in production. */
    private static Authentication owner(String subject, String group) {
        return Authentication.build(subject, List.of("tenant-owner"), Map.of("groups", List.of(group)));
    }

    private static Authentication viewer(String group) {
        return Authentication.build("bob", List.of("tenant-viewer"), Map.of("groups", List.of(group)));
    }

    // --- reading -------------------------------------------------------------------------------

    @Test
    void showsTheTeamsAndMembersOfATenant() {
        directory.putTeam(ACME_GROUP, new DirectoryTeam("t1", "Builders", 3));
        directory.putMember(ACME_GROUP, DirectoryUser.member("u1", "Alice", "alice@acme.example"));

        var body = controller.read(owner("alice", ACME_GROUP), "acme").body();

        assertEquals(1, body.teams().size());
        assertEquals("Builders", body.teams().get(0).displayName());
        assertEquals(1, body.users().size());
        assertNull(body.unavailableReason());
    }

    @Test
    void countsTeamsAndUsers() {
        directory.putTeam(ACME_GROUP, new DirectoryTeam("t1", "Builders", 3));
        directory.putMember(ACME_GROUP, DirectoryUser.member("u1", "Alice", "alice@acme.example"));
        directory.putMember(ACME_GROUP, DirectoryUser.member("u2", "Carol", "carol@acme.example"));

        var counts = controller.counts(platformAdmin(), "acme").body();

        assertEquals(1, counts.teams());
        assertEquals(2, counts.users());
        assertNull(counts.unavailableReason());
    }

    @Test
    void reportsCountsAsUnavailableRatherThanZeroWhenTheDirectoryIsDown() {
        // A zero here would read as "this tenant has nobody in it", which is something an
        // administrator would act on. The page around it must keep working either way.
        directory.unavailable();

        var counts = controller.counts(platformAdmin(), "acme").body();

        assertNull(counts.teams());
        assertNull(counts.users());
        assertNotNull(counts.unavailableReason());
    }

    @Test
    void aDownDirectoryDoesNotFailTheTenantPage() {
        directory.unavailable();

        var body = controller.read(platformAdmin(), "acme").body();

        assertTrue(body.teams().isEmpty());
        assertNotNull(body.unavailableReason());
    }

    // --- who may look at what -------------------------------------------------------------------

    @Test
    void refusesATenantOwnerLookingIntoAnotherTenant() {
        assertThrows(ForbiddenException.class, () -> controller.read(owner("alice", ACME_GROUP), "globex"));
    }

    @Test
    void refusesEveryoneOnATenantWithNoIdentityGroup() {
        // Not "anything goes": an unconfigured tenant is the narrowest, not the widest.
        assertThrows(ForbiddenException.class, () -> controller.read(platformAdmin(), "unconfigured"));
    }

    @Test
    void reportsAnUnknownTenantAsNotFound() {
        assertThrows(NotFoundException.class, () -> controller.read(platformAdmin(), "does-not-exist"));
    }

    // --- creating and inviting -------------------------------------------------------------------

    @Test
    void createsATeam() {
        var response = controller.createTeam(owner("alice", ACME_GROUP), "acme", new CreateTeamRequest("Builders"));

        assertEquals(201, response.getStatus().getCode());
        assertEquals("Builders", response.body().displayName());
        assertEquals(1, directory.teamsIn(ACME_GROUP).size());
    }

    @Test
    void refusesAViewerCreatingATeam() {
        assertThrows(
                ForbiddenException.class,
                () -> controller.createTeam(viewer(ACME_GROUP), "acme", new CreateTeamRequest("Builders")));
    }

    @Test
    void refusesATeamWithNoName() {
        assertThrows(
                BadRequestException.class,
                () -> controller.createTeam(platformAdmin(), "acme", new CreateTeamRequest("   ")));
    }

    @Test
    void invitesSomebody() {
        var response = controller.invite(
                platformAdmin(), "acme", new InviteUserRequest("carol@acme.example", "Carol"));

        assertEquals(201, response.getStatus().getCode());
        assertEquals("carol@acme.example", response.body().email());
    }

    @Test
    void namesAnInviteeAfterTheirAddressWhenNoNameIsGiven() {
        // Better than a blank row in every list from then on.
        var response = controller.invite(platformAdmin(), "acme", new InviteUserRequest("carol@acme.example", null));

        assertEquals("carol", response.body().displayName());
    }

    @Test
    void refusesAnInvitationWithoutAUsableAddress() {
        assertThrows(
                BadRequestException.class,
                () -> controller.invite(platformAdmin(), "acme", new InviteUserRequest("not-an-address", null)));
        assertThrows(
                BadRequestException.class,
                () -> controller.invite(platformAdmin(), "acme", new InviteUserRequest("", null)));
    }

    // --- password reset, where the damage is worst -----------------------------------------------

    @Test
    void resetsAnOrdinaryMembersPassword() {
        directory.putMember(ACME_GROUP, DirectoryUser.member("u1", "Alice", "alice@acme.example"));

        var response = controller.resetPassword(platformAdmin(), "acme", "u1");

        assertEquals("u1", response.body().userId());
        assertNotNull(response.body().temporaryPassword());
        assertEquals(List.of("u1"), directory.resetUserIds());
    }

    @Test
    void refusesToResetTheePasswordOfAPrivilegedAccount() {
        // The escalation this whole subsystem is shaped around: a tenant-owner taking over a
        // directory administrator through a console button.
        directory.putMember(
                ACME_GROUP,
                new DirectoryUser("u-admin", "Root", "root@acme.example", Set.of("Global Administrator")));

        assertThrows(
                ForbiddenException.class,
                () -> controller.resetPassword(owner("alice", ACME_GROUP), "acme", "u-admin"));
        assertTrue(directory.resetUserIds().isEmpty(), "nothing may have been reset");
    }

    @Test
    void refusesToResetSomebodyWhoIsNotAMemberOfThisTenant() {
        // 404 rather than 403: whether an account exists elsewhere in the directory is not
        // something this endpoint should confirm.
        directory.putMember(GLOBEX_GROUP, DirectoryUser.member("u-globex", "Dana", "dana@globex.example"));

        assertThrows(
                NotFoundException.class, () -> controller.resetPassword(platformAdmin(), "acme", "u-globex"));
        assertTrue(directory.resetUserIds().isEmpty());
    }

    @Test
    void refusesAViewerResettingAnything() {
        directory.putMember(ACME_GROUP, DirectoryUser.member("u1", "Alice", "alice@acme.example"));

        assertThrows(ForbiddenException.class, () -> controller.resetPassword(viewer(ACME_GROUP), "acme", "u1"));
        assertTrue(directory.resetUserIds().isEmpty());
    }

    @Test
    void refusesResettingYourOwnPasswordHere() {
        directory.putMember(ACME_GROUP, DirectoryUser.member("alice", "Alice", "alice@acme.example"));

        assertThrows(
                ForbiddenException.class,
                () -> controller.resetPassword(owner("alice", ACME_GROUP), "acme", "alice"));
        assertTrue(directory.resetUserIds().isEmpty());
    }
}
