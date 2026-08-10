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

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Evaluates a {@code WorldSourceSpec.poll} Cron expression: whether a poll is due given when the
 * source was last polled, and how long until the next one is.
 *
 * <p>Backed by <a href="https://github.com/jmrozanec/cron-utils">cron-utils</a> rather than a
 * hand-rolled parser -- see {@code settings.gradle.kts} for why that library and version were
 * chosen. Uses the standard five-field Unix cron syntax ({@code minute hour day-of-month month
 * day-of-week}, no seconds field), matching Kubernetes' own {@code CronJob.spec.schedule} so an
 * operator-facing {@code WorldSource.spec.poll} value reads exactly like a value the same person
 * would already know from writing a {@code CronJob}.
 */
public final class CronSchedule {

    private static final CronParser PARSER =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    /**
     * Used when no poll has happened yet and there is therefore no {@code timeToNextExecution}
     * anchor point other than "now" -- a poll is due immediately in that case (see {@link
     * #isDue}), so this only matters for {@link #timeToNext}, where it is a conservative,
     * short fallback rather than leaving the reconciler unscheduled.
     */
    private static final Duration FALLBACK_RECHECK = Duration.ofMinutes(1);

    private final ExecutionTime executionTime;

    private CronSchedule(ExecutionTime executionTime) {
        this.executionTime = executionTime;
    }

    /**
     * Parses {@code expression} as a five-field Unix cron expression.
     *
     * @throws InvalidCronExpressionException if the expression is syntactically invalid
     */
    public static CronSchedule parse(String expression) {
        try {
            Cron cron = PARSER.parse(expression);
            cron.validate();
            return new CronSchedule(ExecutionTime.forCron(cron));
        } catch (IllegalArgumentException e) {
            throw new InvalidCronExpressionException(
                    "invalid poll cron expression '" + expression + "': " + e.getMessage(), e);
        }
    }

    /**
     * Whether a poll is due: {@code true} if this source has never been polled ({@code lastPoll}
     * is {@code null} -- nothing to wait for), or if this schedule's next execution after {@code
     * lastPoll} falls at or before {@code now}.
     */
    public boolean isDue(ZonedDateTime lastPoll, ZonedDateTime now) {
        if (lastPoll == null) {
            return true;
        }
        Optional<ZonedDateTime> next = executionTime.nextExecution(lastPoll);
        return next.isPresent() && !next.get().isAfter(now);
    }

    /** How long from {@code now} until this schedule's next execution. */
    public Duration timeToNext(ZonedDateTime now) {
        return executionTime.timeToNextExecution(now).orElse(FALLBACK_RECHECK);
    }

    /** Thrown by {@link #parse} when the given string is not a valid Cron expression. */
    public static final class InvalidCronExpressionException extends RuntimeException {

        InvalidCronExpressionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
