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

import java.util.Locale;

/**
 * Locale-independent number formatting shared by the JSON and Prometheus writers.
 *
 * <p>Both output formats require a dot as the decimal separator; the host locale must
 * never leak into a payload.
 */
public final class Numbers {

    private Numbers() {}

    /** Formats {@code value} with up to six decimals, without trailing zeros. */
    public static String compact(double value) {
        String formatted = String.format(Locale.ROOT, "%.6f", value);
        if (formatted.indexOf('.') >= 0) {
            formatted = formatted.replaceAll("0+$", "");
            if (formatted.endsWith(".")) {
                formatted = formatted.substring(0, formatted.length() - 1);
            }
        }
        return formatted;
    }
}
