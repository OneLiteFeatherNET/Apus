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
package net.onelitefeather.apus.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.javaoperatorsdk.operator.Operator;
import java.util.Set;
import java.util.stream.Collectors;
import net.onelitefeather.apus.operator.ingest.WorldIngestReconciler;
import net.onelitefeather.apus.operator.ingest.WorldSourceReconciler;
import net.onelitefeather.apus.operator.map.BlueMapMapReconciler;
import net.onelitefeather.apus.operator.render.BlueMapRenderReconciler;
import net.onelitefeather.apus.operator.tenant.TenantReconciler;
import org.junit.jupiter.api.Test;

/**
 * {@link OperatorConfig#fromEnvironment} itself is already covered by {@code
 * OperatorConfigTest}; this class instead proves that {@link ApusOperator}'s wiring is correct --
 * that all three reconcilers this operator ships actually end up registered.
 *
 * <p>{@link ApusOperator#main} is not exercised directly: it builds its own {@link
 * KubernetesClient} via {@code KubernetesClientBuilder} and calls {@link Operator#start()}, both
 * of which need a real (or at least reachable) cluster. {@link ApusOperator#registerReconcilers}
 * exists precisely to make the registration step reachable without one -- it is exercised here
 * against the fabric8 mock client the other reconciler tests already use, with the {@link
 * Operator} itself never started.
 */
@EnableKubernetesMockClient(crud = true)
class ApusOperatorTest {

    KubernetesClient client;

    @Test
    void registersAllFiveReconcilers() {
        Operator operator = new Operator(o -> o.withKubernetesClient(client));

        ApusOperator.registerReconcilers(operator, client, OperatorConfig.defaults());

        assertEquals(5, operator.getRegisteredControllersNumber());
        Set<String> reconcilerClassNames = operator.getRegisteredControllers().stream()
                .map(controller -> controller.getConfiguration().getAssociatedReconcilerClassName())
                .collect(Collectors.toSet());
        assertTrue(reconcilerClassNames.contains(TenantReconciler.class.getName()));
        assertTrue(reconcilerClassNames.contains(BlueMapMapReconciler.class.getName()));
        assertTrue(reconcilerClassNames.contains(BlueMapRenderReconciler.class.getName()));
        assertTrue(reconcilerClassNames.contains(WorldSourceReconciler.class.getName()));
        assertTrue(reconcilerClassNames.contains(WorldIngestReconciler.class.getName()));
    }
}
