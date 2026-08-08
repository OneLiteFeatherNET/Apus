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
package net.onelitefeather.apus.operator.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.Condition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConditionsTest {

    @Test
    void setAddsANewConditionWhenTheTypeIsNotYetPresent() {
        List<Condition> conditions = new ArrayList<>();
        Condition ready = Conditions.ready(true, "AllGood", "everything is fine");

        Conditions.set(conditions, ready);

        assertEquals(1, conditions.size());
        assertSame(ready, conditions.get(0));
    }

    @Test
    void setReplacesAnExistingConditionOfTheSameTypeInsteadOfAppending() {
        List<Condition> conditions = new ArrayList<>();
        Conditions.set(conditions, Conditions.ready(false, "NotYet", "still syncing"));

        Condition updated = Conditions.ready(true, "AllGood", "everything is fine");
        Conditions.set(conditions, updated);

        assertEquals(1, conditions.size(), "must replace, not append, a condition of the same type");
        assertEquals("True", conditions.get(0).getStatus());
        assertEquals("AllGood", conditions.get(0).getReason());
        assertEquals("everything is fine", conditions.get(0).getMessage());
    }

    @Test
    void setLeavesConditionsOfOtherTypesUntouched() {
        List<Condition> conditions = new ArrayList<>();
        Condition otherType = new Condition();
        otherType.setType("Progressing");
        otherType.setStatus("True");
        conditions.add(otherType);

        Conditions.set(conditions, Conditions.ready(true, "AllGood", "everything is fine"));

        assertEquals(2, conditions.size());
        assertTrue(conditions.contains(otherType), "the unrelated condition must still be present, unmodified");
        assertEquals(
                1L,
                conditions.stream().filter(c -> Conditions.READY.equals(c.getType())).count(),
                "exactly one Ready condition must exist");
    }

    @Test
    void readyTrueProducesTheExpectedStatusReasonAndTimestamp() {
        Instant before = Instant.now();

        Condition condition = Conditions.ready(true, "AllGood", "everything is fine");

        assertEquals(Conditions.READY, condition.getType());
        assertEquals("True", condition.getStatus());
        assertEquals("AllGood", condition.getReason());
        assertEquals("everything is fine", condition.getMessage());
        assertNotNull(condition.getLastTransitionTime());
        // Round-trips through Instant.parse to prove it's a real, recent RFC-3339 timestamp,
        // not just a non-null string.
        Instant stamped = Instant.parse(condition.getLastTransitionTime());
        assertTrue(!stamped.isBefore(before) && !stamped.isAfter(Instant.now().plusSeconds(1)));
    }

    @Test
    void readyFalseProducesTheExpectedStatusReasonAndTimestamp() {
        Condition condition = Conditions.ready(false, "StillRendering", "waiting for the render job");

        assertEquals(Conditions.READY, condition.getType());
        assertEquals("False", condition.getStatus());
        assertEquals("StillRendering", condition.getReason());
        assertEquals("waiting for the render job", condition.getMessage());
        assertNotNull(condition.getLastTransitionTime());
        Instant.parse(condition.getLastTransitionTime());
    }
}
