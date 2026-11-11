package vn.yourhrm.dev;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.DocumentScanResults;
import vn.yourhrm.shared.common.storage.ObjectStorage;

/**
 * LOCAL DEVELOPMENT ONLY ({@code dev} profile): stand-in for the SaaS object storage + malware scanner, which do not
 * exist yet. Objects are files under {@code yhrm.dev.storage-dir}; an upload that asks for a scan is reported CLEAN a
 * moment later (after the uploading transaction has committed), so documents become usable as they would after a real
 * scan. Download links point at {@link DevStorageController} under {@code /api/v1/dev/storage}, relative to the SPA
 * origin, so the dev BFF proxies them with the user's token; they carry an HMAC signature and an expiry.
 */
@Component
@Profile("dev")
public class DevObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(DevObjectStorage.class);
    static final String PATH = "/api/v1/dev/storage";

    private final Path root;
    private final byte[] secret;
    private final ObjectProvider<DocumentScanResults> scans;
    private final ScheduledExecutorService scanner = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("dev-scan").factory());

    public DevObjectStorage(@Value("${yhrm.dev.storage-dir:${user.home}/.yourhrm/dev-storage}") Path storageDir,
            @Value("${yhrm.dev.key-secret:yourhrm-local-dev-only-not-a-secret}") String secret,
            ObjectProvider<DocumentScanResults> scans) throws IOException {
        this.root = Files.createDirectories(storageDir.resolve("objects")).toAbsolutePath().normalize();
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.scans = scans;
        log.warn("DEV object storage active at {} - never use outside local development", root);
    }

    @Override
    public void put(UUID tenantId, String objectKey, String contentType, byte[] content, boolean scan) {
        Path file = file(tenantId, objectKey);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (scan) {
            reportClean(tenantId, objectKey, 1);
        }
    }

    @Override
    public byte[] get(UUID tenantId, String objectKey) {
        Path file = file(tenantId, objectKey);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException("No object " + objectKey, e);
        }
    }

    @Override
    public URI signedDownloadUrl(UUID tenantId, String objectKey, String fileName, String contentType, Duration ttl) {
        long expires = Instant.now().plus(ttl).getEpochSecond();
        String name = fileName == null ? "download" : fileName;
        String type = contentType == null ? "application/octet-stream" : contentType;
        String sig = sign(objectKey, expires, name, type);
        return URI.create(PATH + "?key=" + enc(objectKey) + "&expires=" + expires + "&name=" + enc(name) + "&type="
                + enc(type) + "&sig=" + sig);
    }

    /** Verifies a link produced by {@link #signedDownloadUrl}. */
    boolean valid(String objectKey, long expires, String name, String type, String sig) {
        return expires >= Instant.now().getEpochSecond() && sign(objectKey, expires, name, type).equals(sig);
    }

    // ---------------------------------------------------------------- internals

    /** The object's file; keys must stay under the tenant prefix and inside the storage root. */
    private Path file(UUID tenantId, String objectKey) {
        if (!objectKey.startsWith(tenantId + "/")) {
            throw new IllegalArgumentException("Key outside the tenant prefix: " + objectKey);
        }
        Path file = root.resolve(objectKey).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Invalid object key: " + objectKey);
        }
        return file;
    }

    /** The document row is written in the uploading transaction: report after it commits, retrying briefly. */
    private void reportClean(UUID tenantId, String objectKey, int attempt) {
        scanner.schedule(() -> {
            try {
                scans.getObject().report(tenantId, objectKey, "CLEAN");
            } catch (RuntimeException e) {
                if (attempt < 5) {
                    reportClean(tenantId, objectKey, attempt + 1);
                } else {
                    log.warn("DEV scan report for {} failed: {}", objectKey, e.getMessage());
                }
            }
        }, attempt * 1500L, TimeUnit.MILLISECONDS);
    }

    private String sign(String objectKey, long expires, String name, String type) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] h = mac.doFinal((objectKey + "\n" + expires + "\n" + name + "\n" + type).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
