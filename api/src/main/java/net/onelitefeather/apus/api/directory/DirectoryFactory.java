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

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses the {@link Directory} the rest of the module gets: the real one when a credential is
 * configured, and one that refuses everything when it is not.
 *
 * <p>Refusing is deliberately not the same as failing to start. Directory management is an
 * optional capability behind a permission grant a platform may reasonably decline to make, and an
 * API that would not boot without it would make that grant mandatory in practice. It is also not
 * the same as pretending the directory is empty: an empty team list means "this tenant has no
 * teams", which is a fact somebody would act on, whereas {@link DirectoryUnavailableException}
 * makes the console show the panel as unavailable and leave the rest of the page alone.
 */
@Factory
public class DirectoryFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirectoryFactory.class);

    @Singleton
    public Directory directory(GraphDirectoryConfiguration config) {
        if (!config.isConfigured()) {
            LOGGER.info(
                    "no directory credential configured (apus.directory.*): teams, invitations and password"
                            + " resets are unavailable, everything else is unaffected");
            return new UnconfiguredDirectory();
        }
        LOGGER.info(
                "directory management enabled against {} as client {}",
                config.getGraphEndpoint(),
                config.getClientId());
        return new GraphDirectory(config);
    }

    /**
     * The directory when nobody has granted Apus access to one. Every method says the same thing,
     * in words an administrator can act on -- naming the configuration that is missing rather
     * than reporting a bare failure.
     */
    static final class UnconfiguredDirectory implements Directory {

        private static final String MESSAGE =
                "directory management is not configured on this platform (apus.directory.tenant-id,"
                        + " .client-id and .client-secret)";

        @Override
        public List<DirectoryTeam> teamsIn(String groupId) {
            throw new DirectoryUnavailableException(MESSAGE);
        }

        @Override
        public List<DirectoryUser> membersOf(String groupId) {
            throw new DirectoryUnavailableException(MESSAGE);
        }

        @Override
        public DirectoryTeam createTeam(String groupId, String displayName) {
            throw new DirectoryUnavailableException(MESSAGE);
        }

        @Override
        public DirectoryUser invite(String groupId, String email, String displayName) {
            throw new DirectoryUnavailableException(MESSAGE);
        }

        @Override
        public DirectoryUser findUser(String userId) {
            throw new DirectoryUnavailableException(MESSAGE);
        }

        @Override
        public String resetPassword(String userId) {
            throw new DirectoryUnavailableException(MESSAGE);
        }
    }
}
