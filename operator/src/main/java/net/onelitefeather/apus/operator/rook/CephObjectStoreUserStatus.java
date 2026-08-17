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
package net.onelitefeather.apus.operator.rook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Observed state of a Rook CephObjectStoreUser.
 *
 * <p><b>Ignores everything it does not model, and that is load-bearing.</b> Rook owns this CRD and
 * extends it whenever it likes. It added {@code status.info} and {@code status.observedGeneration};
 * this class knew only {@code phase}, so reading a real user threw
 * {@code UnrecognizedPropertyException: Unrecognized field "info"} -- inside {@code
 * TenantReconciler}, which reads the existing user before touching it. Every reconciliation of
 * every tenant failed from that moment on, and because a tenant only reconciles when something
 * changes, nothing looked broken until a release needed one: the operator exhausted its retries and
 * the tenant silently never got the resources it was owed.
 *
 * <p>The answer is not to add the missing fields as they appear -- that is a race against another
 * project's roadmap. A model of somebody else's resource must not fail on a field it does not read.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CephObjectStoreUserStatus {

    private String phase;

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }
}
