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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GraphResponsesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void readsAValueArray() {
        assertEquals(2, GraphResponses.items(json("{\"value\":[{\"id\":\"a\"},{\"id\":\"b\"}]}"))
                .size());
    }

    @Test
    void toleratesAResponseWithNoValueArrayAtAll() {
        // Graph omits `value` on some error and metadata shapes. A listing that threw here would
        // take down a page that has plenty else to show.
        assertTrue(GraphResponses.items(json("{}")).isEmpty());
        assertTrue(GraphResponses.items(null).isEmpty());
    }

    @Test
    void aTeamWithoutACountSaysSoRatherThanClaimingZero() {
        // A zero that means "we did not ask" is a lie an administrator would act on.
        DirectoryTeam team = GraphResponses.team(json("{\"id\":\"g1\",\"displayName\":\"Builders\"}"));

        assertFalse(team.hasMemberCount());
        assertEquals(DirectoryTeam.COUNT_UNAVAILABLE, team.memberCount());
    }

    @Test
    void aTeamWithACountKeepsIt() {
        DirectoryTeam team =
                GraphResponses.team(json("{\"id\":\"g1\",\"displayName\":\"Builders\",\"members@odata.count\":7}"));

        assertTrue(team.hasMemberCount());
        assertEquals(7, team.memberCount());
    }

    @Test
    void prefersMailButFallsBackToTheUserPrincipalName() {
        DirectoryUser withMail = GraphResponses.user(
                json("{\"id\":\"u1\",\"displayName\":\"Alice\",\"mail\":\"alice@example.net\","
                        + "\"userPrincipalName\":\"alice_ext#EXT#@example.net\"}"),
                Set.of());
        assertEquals("alice@example.net", withMail.email());

        // A freshly invited guest has no `mail` at all -- the fallback is not optional.
        DirectoryUser guest = GraphResponses.user(
                json("{\"id\":\"u2\",\"displayName\":\"Bob\",\"mail\":null,"
                        + "\"userPrincipalName\":\"bob_ext#EXT#@example.net\"}"),
                Set.of());
        assertEquals("bob_ext#EXT#@example.net", guest.email());
    }

    @Test
    void keepsDirectoryRolesAndDropsGroups() {
        // The list a user's memberOf returns mixes both. Treating a group as a role would block
        // ordinary members from ever being helped; treating a role as a group would let an
        // administrator's password be reset, which is the failure that matters.
        Set<String> roles = GraphResponses.directoryRoles(json("{\"value\":["
                + "{\"@odata.type\":\"#microsoft.graph.directoryRole\",\"displayName\":\"Global Administrator\"},"
                + "{\"@odata.type\":\"#microsoft.graph.group\",\"displayName\":\"Builders\"}"
                + "]}"));

        assertEquals(Set.of("Global Administrator"), roles);
    }

    @Test
    void aRoleWithNoNameIsNotARole() {
        assertTrue(GraphResponses.directoryRoles(
                        json("{\"value\":[{\"@odata.type\":\"#microsoft.graph.directoryRole\"}]}"))
                .isEmpty());
    }

    @Test
    void aMissingFieldReadsAsEmptyRatherThanNull() {
        assertEquals("", GraphResponses.text(json("{}"), "displayName"));
        assertEquals("", GraphResponses.text(json("{\"displayName\":null}"), "displayName"));
        assertEquals("", GraphResponses.text(null, "displayName"));
    }
}
