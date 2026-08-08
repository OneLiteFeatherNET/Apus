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
package net.onelitefeather.apus.operator.crdgen;

import io.fabric8.crd.generator.collector.CustomResourceCollector;
import io.fabric8.crdv2.generator.CRDGenerationInfo;
import io.fabric8.crdv2.generator.CRDGenerator;
import io.fabric8.kubernetes.api.model.HasMetadata;
import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Generates CRD YAML manifests from the {@link io.fabric8.kubernetes.client.CustomResource}
 * subclasses found in this module.
 *
 * <p>There is no supported CLI artifact for the fabric8 crd-generator on the 7.x line: the
 * {@code crd-generator-apt} annotation processor and the {@code io.fabric8.crd.generator.CRDGenerator}
 * (v1) class are deprecated since 7.0.0, and {@code crd-generator-api-v2}/{@code
 * crd-generator-collector} 7.8.0 ship no {@code Main}/CLI class -- only the programmatic {@link
 * CRDGenerator} and {@link CustomResourceCollector} APIs. This class is the small, always-working
 * fallback: a dedicated entry point run via a Gradle {@code JavaExec} task (see
 * operator/build.gradle.kts), invoked in the same JVM/toolchain used to compile the module so
 * there is no cross-JDK class file version mismatch.
 */
public final class CrdGeneratorMain {

    private CrdGeneratorMain() {}

    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: CrdGeneratorMain <output-dir> <classes-dir>");
        }

        File outputDir = new File(args[0]);
        File classesDir = new File(args[1]);

        // withClasspathElements only feeds the class *loader* used to load classes that were
        // already found -- it plays no part in discovering them. Discovery is a separate step
        // (withFileToScan) that builds a Jandex index over the given class files/directories/
        // jars and looks for implementors of HasMetadata annotated with @Group/@Version. Point
        // it at this module's own compiled output only, so unrelated classes on the classpath
        // (Kubernetes' own HasMetadata implementors, e.g.) are never considered.
        List<String> classpathElements =
                Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator));

        CustomResourceCollector collector = new CustomResourceCollector()
                .withParentClassLoader(Thread.currentThread().getContextClassLoader())
                .withClasspathElements(classpathElements)
                .withFileToScan(classesDir);

        List<Class<? extends HasMetadata>> customResourceClasses = collector.findCustomResourceClasses();
        if (customResourceClasses.isEmpty()) {
            throw new IllegalStateException(
                    "No CustomResource classes found on classpath elements: " + classpathElements);
        }

        CRDGenerationInfo info = new CRDGenerator()
                // Default output quotes every scalar (kind: "Tenant", scope: "Cluster", ...).
                // Minimal quoting keeps the manifest close to what kubectl/helm users expect
                // and is what consumers (kubectl apply -f, this module's own CrdGenerationTest)
                // match against.
                .withMinQuotes(true)
                .customResourceClasses(customResourceClasses)
                .inOutputDir(outputDir)
                .detailedGenerate();

        System.out.println("Generated " + info.numberOfGeneratedCRDs() + " CRD(s) into "
                + outputDir.getAbsolutePath() + ": " + info.getCRDDetailsPerNameAndVersion().keySet());
    }
}
