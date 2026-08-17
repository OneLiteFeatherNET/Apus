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
package net.onelitefeather.apus.api.directory;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns Microsoft Graph's JSON into this module's own types.
 *
 * <p>Split out of {@code GraphDirectory} so it can be tested without a credential, a network or a
 * live directory. The parsing is where the interesting mistakes live -- a group whose members
 * come back under {@code value}, a user whose {@code mail} is null but whose
 * {@code userPrincipalName} is not, a role assignment shaped differently from what the docs
 * suggest -- and none of those are things worth discovering in production.
 *
 * <p>Every method tolerates a missing or null field rather than throwing. Graph omits what it has
 * nothing to say about, and a directory listing that fails wholesale because one account has no
 * display name would be worse than one that shows a blank.
 */
final class GraphResponses {

    private GraphResponses() {}

    /** Reads a {@code value} array, tolerating its absence. */
    static List<JsonNode> items(JsonNode body) {
        List<JsonNode> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        JsonNode value = body.get("value");
        if (value != null && value.isArray()) {
            value.forEach(out::add);
        }
        return out;
    }

    /**
     * One group as a team. The member count is left {@link DirectoryTeam#COUNT_UNAVAILABLE}
     * unless Graph actually returned one -- a zero meaning "not asked" is a lie somebody would
     * act on.
     */
    static DirectoryTeam team(JsonNode node) {
        String id = text(node, "id");
        String name = text(node, "displayName");
        JsonNode count = node.get("members@odata.count");
        int members = count != null && count.isInt() ? count.asInt() : DirectoryTeam.COUNT_UNAVAILABLE;
        return new DirectoryTeam(id, name, members);
    }

    /**
     * One user. {@code mail} is preferred over {@code userPrincipalName} because it is what a
     * person recognises, but an account can easily have only the latter -- a freshly invited
     * guest, for one -- so the fallback is not optional.
     */
    static DirectoryUser user(JsonNode node, Set<String> privilegedRoles) {
        String mail = text(node, "mail");
        String email = mail.isBlank() ? text(node, "userPrincipalName") : mail;
        return new DirectoryUser(text(node, "id"), text(node, "displayName"), email, privilegedRoles);
    }

    /**
     * The role names from a {@code memberOf} / {@code transitiveMemberOf} response, keeping only
     * entries that really are directory roles. A group in that list is a group, not a role, and
     * treating one as the other would either block an ordinary member from ever being helped or,
     * far worse, let an administrator through because their role arrived shaped unexpectedly.
     */
    static Set<String> directoryRoles(JsonNode body) {
        Set<String> roles = new LinkedHashSet<>();
        for (JsonNode node : items(body)) {
            String type = text(node, "@odata.type");
            if (type.endsWith("directoryRole")) {
                String name = text(node, "displayName");
                if (!name.isBlank()) {
                    roles.add(name);
                }
            }
        }
        return roles;
    }

    /** A field as text, or empty -- never null, so no caller has to guard. */
    static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }
}
