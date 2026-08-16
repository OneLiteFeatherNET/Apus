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

/**
 * One platform-set option on a tenant, and whether the tenant may deviate from it.
 *
 * <p>Deliberately a free-form {@code key} with a declared {@code type} rather than a fixed set of
 * typed fields: options must be addable without changing this CRD's schema. {@code value} is
 * always a string and is parsed according to {@code type} by whoever reads it. The alternative --
 * a genuinely polymorphic value -- costs either schema validation for the whole subtree
 * ({@code x-kubernetes-preserve-unknown-fields}, which lets a typo through silently) or a union
 * of typed fields where exactly one is populated. See the design doc 2026-08-16, §2.
 *
 * <p><b>The api module enforces only the keys it has code for</b>, and reports per entry which
 * those are. An entry outside that set is stored and displayed and changes nothing. That is a
 * property of the design rather than an oversight: it lets an administrator record an intended
 * rule before the code that applies it exists — as long as nobody is misled into believing it
 * already bites, which is why the API returns an {@code enforced} flag and the console shows it.
 */
public class PolicyEntry {

    private String key;
    private String type;
    private String value;

    /** Whether the api module refuses a tenant write that deviates from {@code value}. */
    private boolean locked;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }
}
