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
package net.onelitefeather.apus.observability;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.status.ErrorStatus;
import ch.qos.logback.core.status.Status;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every {@code logback.xml} this repository ships must be a configuration Logback actually
 * accepts -- for the whole repository, not just for the module this test happens to live in.
 *
 * <p>The reason this exists is a defect that shipped in 0.4.0: {@code operator}'s and {@code
 * ingest}'s files carried a double hyphen inside an XML comment, which XML forbids. Logback
 * aborted the configuration, attached neither appender, and both services logged nothing at all
 * -- no console line for {@code kubectl logs}, and no record reaching the collector either.
 * Nothing failed at build time, because a Logback configuration is only read at runtime and
 * Logback's own reaction to a broken one is to complain on stderr and carry on.
 *
 * <p>So the check is deliberately not "does this parse as XML". It hands each file to the real
 * {@link JoranConfigurator} and fails on any warning or error Logback reports, which covers the
 * malformed comment as well as an appender class that does not exist, an element name nobody
 * spelled right, and a {@code <appender-ref>} pointing at nothing.
 *
 * <p>The files are discovered by walking the repository, not by listing them: a module added
 * later is covered the day it ships a {@code logback.xml}, without anyone remembering to come
 * back here. The one consequence worth knowing about is that instantiating the appenders needs
 * their classes on <em>this</em> module's test classpath -- if a future module introduces an
 * appender the operator does not depend on, this test says so instead of skipping the file.
 */
class LogbackConfigurationTest {

    /** Directories that never hold source; {@code build}/{@code bin} hold stale copies of it. */
    private static final Set<String> PRUNED = Set.of("build", "bin", ".git", ".gradle", "node_modules");

    @Test
    void everyServiceThatLogsIsRepresented() {
        // A finder that quietly matches nothing would make every assertion below vacuous, and
        // this test would go green precisely when it stops being able to see the problem.
        List<String> modules = configurations().stream()
                .map(path -> repositoryRoot().relativize(path).getName(0).toString())
                .sorted()
                .toList();

        // containsAll, not equals: a module added later should be picked up and validated
        // automatically, not fail this assertion for having done the right thing.
        assertTrue(
                modules.containsAll(List.of("api", "ingest", "operator")),
                "the three long-running services of docs/logging-and-tracing.md each ship a "
                        + "logback.xml, but only these were found: " + modules);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurations")
    void logbackLoadsItWithoutComplaining(Path configuration) {
        LoggerContext context = new LoggerContext();
        context.setName(configuration.toString());

        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            try {
                configurator.doConfigure(configuration.toFile());
            } catch (JoranException e) {
                // Reported through the status list below together with everything else Logback
                // has to say -- an exception escaping here would name the test, not the file.
                context.getStatusManager().add(new ErrorStatus(e.getMessage(), configuration, e));
            }

            List<Status> complaints = context.getStatusManager().getCopyOfStatusList().stream()
                    .filter(status -> status.getLevel() >= Status.WARN)
                    .toList();

            if (!complaints.isEmpty()) {
                fail("Logback refused to load %s cleanly:%n%s"
                        .formatted(configuration, complaints.stream()
                                .map(LogbackConfigurationTest::describe)
                                .collect(Collectors.joining(System.lineSeparator()))));
            }

            // A file can load without a single complaint and still route nothing anywhere, which
            // is the same silence from the operator's point of view. Both appenders of the
            // shared contract have to end up attached to the root logger.
            Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            assertTrue(attached(root).contains("CONSOLE"), "the console appender is attached to the root logger");
            assertTrue(attached(root).contains("OTEL"), "the OTLP appender is attached to the root logger");
        } finally {
            context.stop();
        }
    }

    private static List<String> attached(Logger logger) {
        List<String> names = new ArrayList<>();
        for (Iterator<Appender<ILoggingEvent>> appenders = logger.iteratorForAppenders(); appenders.hasNext(); ) {
            names.add(appenders.next().getName());
        }
        return names;
    }

    private static String describe(Status status) {
        String rendered = "  [%s] %s".formatted(status.getLevel() == Status.ERROR ? "ERROR" : "WARN", status);
        return status.getThrowable() == null ? rendered : rendered + " -- " + status.getThrowable();
    }

    /** Every {@code src/main/resources/logback.xml} in the repository, module order irrelevant. */
    static List<Path> configurations() {
        Path root = repositoryRoot();
        Path resources = Path.of("src", "main", "resources");
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    // Pruned rather than filtered out afterwards: ui/node_modules and .git are
                    // tens of thousands of files that nothing here could ever match.
                    return PRUNED.contains(directory.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (file.getFileName().toString().equals("logback.xml")
                            && file.getParent().endsWith(resources)) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk the repository at " + root, e);
        }
        return found.stream().sorted().toList();
    }

    /**
     * The Gradle {@code Test} task runs with the module directory as its working directory and an
     * IDE may pick something else again, so the root is found rather than assumed -- {@code
     * settings.gradle.kts} is the file that only ever exists at the top of this build.
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "no settings.gradle.kts above " + System.getProperty("user.dir") + "; cannot locate the repository");
    }
}
