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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.api.directory.Directory;
import net.onelitefeather.apus.api.directory.DirectoryTeam;
import net.onelitefeather.apus.api.directory.DirectoryUnavailableException;
import net.onelitefeather.apus.api.directory.DirectoryUser;

/**
 * A directory in a map, so the cases worth testing can actually be tested.
 *
 * <p>Without it, every test of this controller would need a Graph credential and a live Entra
 * tenant -- which means the interesting ones (a tenant-owner reaching into another tenant, a
 * password reset aimed at a Global Administrator, the whole thing being down) would be exactly
 * the ones nobody could write.
 *
 * <p>{@link #unavailable} makes it throw the way Graph does when it is throttling or down, which
 * is the difference between "this tenant has no teams" and "we could not ask" -- a distinction
 * the controller is supposed to keep and this fake exists to check.
 */
class InMemoryDirectory implements Directory {

    private final Map<String, List<DirectoryTeam>> teams = new LinkedHashMap<>();
    private final Map<String, List<DirectoryUser>> members = new LinkedHashMap<>();
    private final Map<String, DirectoryUser> users = new LinkedHashMap<>();
    private final List<String> resetUserIds = new ArrayList<>();
    private boolean unavailable;

    /** Makes every operation fail the way an unreachable or throttling directory does. */
    void unavailable() {
        this.unavailable = true;
    }

    void putTeam(String groupId, DirectoryTeam team) {
        teams.computeIfAbsent(groupId, key -> new ArrayList<>()).add(team);
    }

    void putMember(String groupId, DirectoryUser user) {
        members.computeIfAbsent(groupId, key -> new ArrayList<>()).add(user);
        users.put(user.id(), user);
    }

    /** The ids whose password was actually reset -- so a test can assert one was *not*. */
    List<String> resetUserIds() {
        return List.copyOf(resetUserIds);
    }

    private void check() {
        if (unavailable) {
            throw new DirectoryUnavailableException("the directory is unavailable");
        }
    }

    @Override
    public List<DirectoryTeam> teamsIn(String groupId) {
        check();
        return List.copyOf(teams.getOrDefault(groupId, List.of()));
    }

    @Override
    public List<DirectoryUser> membersOf(String groupId) {
        check();
        return List.copyOf(members.getOrDefault(groupId, List.of()));
    }

    @Override
    public DirectoryTeam createTeam(String groupId, String displayName) {
        check();
        DirectoryTeam team = new DirectoryTeam("team-" + displayName.toLowerCase(java.util.Locale.ROOT), displayName, 0);
        putTeam(groupId, team);
        return team;
    }

    @Override
    public DirectoryUser invite(String groupId, String email, String displayName) {
        check();
        DirectoryUser invited = DirectoryUser.member("user-" + email, displayName, email);
        putMember(groupId, invited);
        return invited;
    }

    @Override
    public DirectoryUser findUser(String userId) {
        check();
        return users.get(userId);
    }

    @Override
    public String resetPassword(String userId) {
        check();
        resetUserIds.add(userId);
        return "temporary-password";
    }
}
