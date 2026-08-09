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
package net.onelitefeather.apus.api.rest.upload;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micronaut.security.authentication.Authentication;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.api.rest.support.BadRequestException;
import net.onelitefeather.apus.api.rest.support.NotFoundException;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.security.TenantResolver;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import net.onelitefeather.apus.operator.api.WorldSource;
import org.junit.jupiter.api.Test;

/**
 * Covers everything {@code UploadController} decides *before* delegating to {@link
 * MultipartUploadService} -- role/tenant scoping and source lookup, exactly the boundary this
 * controller is responsible for. {@link MultipartUploadService}'s own request-shape validation is
 * {@code MultipartUploadServiceTest}'s job; a presigned URL's real behaviour against S3 is {@code
 * MultipartUploadServiceIntegrationTest}'s (Docker/MinIO, not run here). {@link
 * #createDelegatesToTheServiceOnceTheSourceCheckPasses()} proves the wiring between this
 * controller and that service without needing a real S3 client, by supplying a request the
 * service itself rejects for a *different* reason than anything the controller checks -- proof
 * that control genuinely passed through.
 */
class UploadControllerTest {

    private final InMemoryWorldSourceRepository sourceRepository = new InMemoryWorldSourceRepository();
    // No real S3Client/S3Presigner: every test here either fails before the service ever touches
    // them, or (createDelegatesToTheServiceOnceTheSourceCheckPasses) fails inside the service for
    // a reason unrelated to S3 connectivity -- see MultipartUploadServiceTest's Javadoc for why
    // that is a safe thing to construct.
    private final MultipartUploadService uploadService =
            new MultipartUploadService(null, null, "staging-bucket", "staging/", 67_108_864L, 10_737_418_240L, 900L);
    private final UploadController controller =
            new UploadController(sourceRepository, uploadService, new PrincipalResolver(), new TenantResolver());

    private static Authentication operator(String tenant) {
        return Authentication.build(
                "dave", List.of("tenant-operator"), Map.of(PrincipalResolver.TENANT_CLAIM, tenant));
    }

    private static Authentication viewer(String tenant) {
        return Authentication.build("carol", List.of("tenant-viewer"), Map.of(PrincipalResolver.TENANT_CLAIM, tenant));
    }

    private static WorldSource uploadSource(String name) {
        WorldSource source = new WorldSource();
        source.getMetadata().setName(name);
        source.getSpec().setType("upload");
        return source;
    }

    @Test
    void createRejectsAViewer() {
        sourceRepository.put("bluemap-acme", uploadSource("survival"));

        assertThrows(
                ForbiddenException.class,
                () -> controller.create(viewer("acme"), new CreateUploadRequest("survival", "world.zip", 1024)));
    }

    @Test
    void createRejectsAnUnknownSourceName() {
        assertThrows(
                NotFoundException.class,
                () -> controller.create(operator("acme"), new CreateUploadRequest("no-such-source", "world.zip", 1024)));
    }

    @Test
    void createRejectsASourceThatBelongsToAnotherTenant() {
        sourceRepository.put("bluemap-globex", uploadSource("globex-survival"));

        assertThrows(
                NotFoundException.class,
                () -> controller.create(
                        operator("acme"), new CreateUploadRequest("globex-survival", "world.zip", 1024)));
    }

    @Test
    void createRejectsASourceThatIsNotOfTypeUpload() {
        WorldSource s3Source = new WorldSource();
        s3Source.getMetadata().setName("survival");
        s3Source.getSpec().setType("s3");
        sourceRepository.put("bluemap-acme", s3Source);

        assertThrows(
                NotFoundException.class,
                () -> controller.create(operator("acme"), new CreateUploadRequest("survival", "world.zip", 1024)));
    }

    @Test
    void createRejectsAMissingSourceName() {
        assertThrows(
                BadRequestException.class,
                () -> controller.create(operator("acme"), new CreateUploadRequest(null, "world.zip", 1024)));
        assertThrows(BadRequestException.class, () -> controller.create(operator("acme"), null));
    }

    @Test
    void createDelegatesToTheServiceOnceTheSourceCheckPasses() {
        sourceRepository.put("bluemap-acme", uploadSource("survival"));

        // sizeBytes <= 0 is rejected by MultipartUploadService itself, never by the controller --
        // reaching that specific error proves the controller's own checks (role, source lookup)
        // all passed and control reached the service.
        assertThrows(
                BadRequestException.class,
                () -> controller.create(operator("acme"), new CreateUploadRequest("survival", "world.zip", 0)));
    }

    @Test
    void completeRejectsAViewer() {
        sourceRepository.put("bluemap-acme", uploadSource("survival"));

        assertThrows(
                ForbiddenException.class,
                () -> controller.complete(
                        viewer("acme"), "upload-1", new CompleteUploadRequest("survival", "v1", "world.zip")));
    }

    @Test
    void completeRejectsASourceThatBelongsToAnotherTenant() {
        sourceRepository.put("bluemap-globex", uploadSource("globex-survival"));

        assertThrows(
                NotFoundException.class,
                () -> controller.complete(
                        operator("acme"),
                        "upload-1",
                        new CompleteUploadRequest("globex-survival", "v1", "world.zip")));
    }
}
