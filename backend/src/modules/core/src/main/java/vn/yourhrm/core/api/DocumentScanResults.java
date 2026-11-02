package vn.yourhrm.core.api;

import java.util.UUID;

/**
 * Called by the SaaS object storage when the antivirus scan of an upload finished (BR-CORE-100, US-CORE-100). A file
 * is unusable until it is CLEAN; an INFECTED file is quarantined and its uploader notified.
 */
public interface DocumentScanResults {

    /** @param status CLEAN, INFECTED or ERROR; repeated reports of the same result are ignored */
    void report(UUID tenantId, String objectKey, String status);
}
