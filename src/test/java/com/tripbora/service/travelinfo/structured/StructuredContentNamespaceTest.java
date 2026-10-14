package com.tripbora.service.travelinfo.structured;

import com.tripbora.service.file.FileUploadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredContentNamespaceTest {
    private static final String NAME = "aaaaaaaa-1111-4222-8333-444444444444.jpg";
    private static final String OTHER = "/uploads/example/content/";
    private final StructuredContentService service = StructuredContentTestSupport.structuredContentService();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void explicitNamespaceRoundTripsWithoutChangingTheJsonContract() {
        StructuredImagePolicy policy = new StructuredImagePolicy(OTHER);
        String json = document(OTHER + NAME);
        var prepared = service.prepareContent(json, policy);
        assertThat(prepared.json()).isEqualTo(json);
        assertThat(service.restoreContentJson(json, policy)).isEqualTo(json);
        assertThat(service.collectImageUrls(service.readStored(json, policy))).containsExactly(OTHER + NAME);
        assertThatThrownBy(() -> service.prepareContent(json))
                .isInstanceOf(StructuredContentValidationException.class);
        assertThatThrownBy(() -> service.prepareContent(document(StructuredImage.URL_PREFIX + NAME), policy))
                .isInstanceOf(StructuredContentValidationException.class);
    }

    @Test
    void namespaceDoesNotRelaxPathOrBlockValidation() {
        StructuredImagePolicy policy = new StructuredImagePolicy(OTHER);
        for (String path : List.of(OTHER + "../" + NAME, OTHER + NAME + "?x=1", OTHER + "%2e%2e/" + NAME,
                "https://example.test" + OTHER + NAME, OTHER + "nested/" + NAME, OTHER + "not-a-uuid.jpg")) {
            assertThatThrownBy(() -> service.prepareContent(document(path), policy))
                    .isInstanceOf(StructuredContentValidationException.class);
        }
        assertThatThrownBy(() -> new StructuredImagePolicy("/uploads/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredImagePolicy("/uploads/example/../"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.prepareContent(document(OTHER + NAME).replace("\"width\":10", "\"width\":0"), policy))
                .isInstanceOf(StructuredContentValidationException.class);
    }

    @Test
    void everyImageBlockAndTranslationUsesTheExplicitPolicy() {
        StructuredImagePolicy policy = new StructuredImagePolicy(OTHER);
        String json = new StructuredContentSerializer().write(StructuredContentSamples.palaceTour())
                .replace(StructuredImage.URL_PREFIX, OTHER);
        var content = service.prepareContent(json, policy).content();
        assertThat(service.collectImageUrls(content)).isNotEmpty().allMatch(url -> url.startsWith(OTHER));
        String translation = "{\"blocks\":{\"gyeongbok-overview\":{\"caption\":\"Translated caption\"}}}";
        assertThat(service.prepareTranslation(content, translation, "English", policy).json())
                .contains("Translated caption");
        var localized = service.localize(content, translation, policy);
        assertThat(((StructuredBlock.FullImage) localized.blocks().get(2)).caption()).isEqualTo("Translated caption");
        for (String url : service.collectImageUrls(content)) {
            String mixed = json.replace(url, url.replace(OTHER, StructuredImage.URL_PREFIX));
            assertThatThrownBy(() -> service.prepareContent(mixed, policy))
                    .isInstanceOf(StructuredContentValidationException.class);
        }
    }

    @Test
    void cleanupKeepsUnchangedImagesAndContinuesAfterADeletionFailure() {
        StructuredImagePolicy policy = new StructuredImagePolicy(OTHER);
        String kept = OTHER + NAME;
        String removed = OTHER + "bbbbbbbb-1111-4222-8333-444444444444.jpg";
        assertThat(StructuredContentImageCleanup.removedImages(Set.of(kept, removed), Set.of(kept), policy))
                .containsExactly(removed);
        List<String> deleted = new ArrayList<>();
        StructuredContentImageCleanup.afterCommit(List.of(kept, removed, removed), policy, url -> true, url -> {
            if (url.equals(kept)) throw new IllegalStateException("disk error");
            deleted.add(url);
        });
        assertThat(deleted).containsExactly(removed);
    }

    @Test
    void cleanupDiffFiltersForeignPathsAndDeletesOnlyAfterCommit() {
        StructuredImagePolicy policy = new StructuredImagePolicy(OTHER);
        String own = OTHER + NAME;
        String foreign = StructuredImage.URL_PREFIX + NAME;
        Set<String> removed = StructuredContentImageCleanup.removedImages(
                Set.of(own, foreign, OTHER + "../" + NAME), Set.of(), policy);
        assertThat(removed).containsExactly(own);
        List<String> deleted = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();
        StructuredContentImageCleanup.afterCommit(removed, policy, url -> true, deleted::add);
        assertThat(deleted).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(deleted).containsExactly(own);
    }

    @Test
    void rollbackAndReferencedImagesAreKept() {
        StructuredImagePolicy policy = new StructuredImagePolicy(OTHER);
        List<String> deleted = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();
        StructuredContentImageCleanup.afterCommit(Set.of(OTHER + NAME), policy, url -> true, deleted::add);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clearSynchronization();
        StructuredContentImageCleanup.afterCommit(Set.of(OTHER + NAME, StructuredImage.URL_PREFIX + NAME),
                policy, url -> false, deleted::add);
        assertThat(deleted).isEmpty();
    }

    @Test
    void malformedStoredJsonIsNotACleanupCandidate() {
        assertThat(StructuredContentImageCleanup.imageUrls(service, "{bad", new StructuredImagePolicy(OTHER))).isEmpty();
    }

    @Test
    void fileDeletionCannotCrossTheConfiguredNamespace(@TempDir Path root) throws Exception {
        Path own = root.resolve("example/content/" + NAME);
        Path foreign = root.resolve("travel-info/content/" + NAME);
        Files.createDirectories(own.getParent());
        Files.createDirectories(foreign.getParent());
        Files.writeString(own, "own");
        Files.writeString(foreign, "foreign");
        FileUploadService files = new FileUploadService(root.toString());
        StructuredImagePolicy policy = new StructuredImagePolicy(OTHER);
        assertThatThrownBy(() -> files.deleteStructuredContentImage(StructuredImage.URL_PREFIX + NAME, policy))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(files.deleteStructuredContentImage(OTHER + NAME, policy)).isTrue();
        assertThat(own).doesNotExist();
        assertThat(foreign).hasContent("foreign");
    }

    @Test
    void imageStorageUsesTheConfiguredNamespaceAndTheExistingUploadRules(@TempDir Path root) throws Exception {
        FileUploadService files = new FileUploadService(root.toString());
        StructuredImagePolicy policy = new StructuredImagePolicy(OTHER);
        byte[] webp = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEAAUAmJaQAA3AA/v89");
        var stored = files.saveStructuredContentImage(new MockMultipartFile("image", "test.webp", "image/webp", webp), policy);
        assertThat(stored.width()).isEqualTo(1);
        assertThat(stored.height()).isEqualTo(1);
        assertThat(stored.url()).startsWith(OTHER);
        assertThat(Files.readAllBytes(root.resolve("example/content/" + policy.fileName(stored.url())))).isEqualTo(webp);
        assertThatThrownBy(() -> files.deleteTravelInfoContentImage(stored.url()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(files.deleteStructuredContentImage(stored.url(), policy)).isTrue();
    }

    private String document(String url) {
        return "{\"version\":1,\"blocks\":[{\"type\":\"FULL_IMAGE\",\"id\":\"image\",\"image\":{\"url\":\""
                + url + "\",\"width\":10,\"height\":20}}]}";
    }
}
