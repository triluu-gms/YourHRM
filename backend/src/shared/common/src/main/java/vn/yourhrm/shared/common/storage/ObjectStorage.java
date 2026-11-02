package vn.yourhrm.shared.common.storage;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * SPI of the SaaS object storage (BR-CORE-100, PR-16, ADR-0015), implemented by the SaaS module: VN-hosted buckets,
 * tenant prefixes, KMS encryption at rest, antivirus scanning and short-lived signed URLs. Only Core's document
 * service talks to it; other modules store files through Core.
 *
 * <p>Every key starts with {@code <tenantId>/}; the store refuses keys outside the tenant's prefix. Each stored upload
 * is scanned asynchronously and the result is reported back to Core ({@code core.api.DocumentScanResults}).
 */
public interface ObjectStorage {

    /**
     * Stores {@code content} under {@code objectKey}.
     *
     * @param scan queue an antivirus scan whose result is reported back (false for files the platform generated)
     */
    void put(UUID tenantId, String objectKey, String contentType, byte[] content, boolean scan);

    /** The stored bytes (for Core's own processing, e.g. parsing an import file or rendering a template). */
    byte[] get(UUID tenantId, String objectKey);

    /** A signed download URL valid for {@code ttl} (at most 15 minutes, NFR-CORE-010); expired URLs are refused. */
    URI signedDownloadUrl(UUID tenantId, String objectKey, String fileName, String contentType, Duration ttl);
}
