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
package net.onelitefeather.apus.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NumbersTest {

    @Test
    void dropsTrailingZeros() {
        assertEquals("0.5", Numbers.compact(0.5));
        assertEquals("0.674", Numbers.compact(0.674));
    }

    @Test
    void dropsTheDecimalPointForWholeNumbers() {
        assertEquals("1", Numbers.compact(1.0));
        assertEquals("-1", Numbers.compact(-1.0));
        assertEquals("0", Numbers.compact(0.0));
    }

    @Test
    void usesADotRegardlessOfTheHostLocale() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            // German locale would otherwise render 0.5 as "0,5", producing invalid JSON.
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            assertEquals("0.5", Numbers.compact(0.5));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }
}
