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
package net.onelitefeather.apus.operator.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class CronScheduleTest {

    private static final ZonedDateTime NOON = ZonedDateTime.of(2026, 8, 9, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void neverPolledBeforeIsAlwaysDue() {
        CronSchedule schedule = CronSchedule.parse("0 * * * *"); // hourly

        assertTrue(schedule.isDue(null, NOON), "a source that has never been polled must poll immediately");
    }

    @Test
    void notDueBeforeTheNextScheduledFireTime() {
        CronSchedule schedule = CronSchedule.parse("0 * * * *"); // hourly, fires on the hour

        ZonedDateTime lastPoll = NOON; // just polled at 12:00
        ZonedDateTime fiveMinutesLater = NOON.plusMinutes(5);

        assertFalse(schedule.isDue(lastPoll, fiveMinutesLater), "next hourly fire is at 13:00, not yet reached");
    }

    @Test
    void dueOnceTheNextScheduledFireTimeHasPassed() {
        CronSchedule schedule = CronSchedule.parse("0 * * * *"); // hourly

        ZonedDateTime lastPoll = NOON;
        ZonedDateTime oneHourLater = NOON.plusHours(1).plusSeconds(1);

        assertTrue(schedule.isDue(lastPoll, oneHourLater), "the 13:00 fire has already passed");
    }

    @Test
    void timeToNextReflectsTheRemainingWaitUntilTheNextFire() {
        CronSchedule schedule = CronSchedule.parse("0 * * * *"); // hourly, fires on the hour

        Duration remaining = schedule.timeToNext(NOON.plusMinutes(45));

        assertEquals(Duration.ofMinutes(15), remaining);
    }

    @Test
    void rejectsAnInvalidExpressionInsteadOfGuessingAMeaning() {
        assertThrows(CronSchedule.InvalidCronExpressionException.class, () -> CronSchedule.parse("not a cron"));
    }

    @Test
    void rejectsASixFieldQuartzStyleExpression() {
        // This module deliberately speaks five-field Unix cron only (matching
        // Kubernetes CronJob.spec.schedule) -- a seconds field must not be silently accepted.
        assertThrows(CronSchedule.InvalidCronExpressionException.class, () -> CronSchedule.parse("0 0 * * * *"));
    }
}
