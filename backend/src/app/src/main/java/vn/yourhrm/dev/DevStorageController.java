package vn.yourhrm.dev;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.shared.auth.api.PublicEndpoint;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * LOCAL DEVELOPMENT ONLY ({@code dev} profile): serves the signed download links of {@link DevObjectStorage}. The
 * caller must be authenticated (bearer token added by the dev BFF), the link signature and expiry must be valid and the
 * object must belong to the caller's tenant. Authorisation of the document itself happened when the link was issued.
 */
@RestController
@Profile("dev")
public class DevStorageController {

    private final DevObjectStorage storage;

    public DevStorageController(DevObjectStorage storage) {
        this.storage = storage;
    }

    @GetMapping(DevObjectStorage.PATH)
    @PublicEndpoint(reason = "dev-only signed download link; the document was authorised when the link was issued")
    public ResponseEntity<byte[]> download(@RequestParam String key, @RequestParam long expires,
            @RequestParam String name, @RequestParam String type, @RequestParam String sig) {
        UUID tenant = TenantContext.current().tenantId();
        if (!key.startsWith(tenant + "/") || !storage.valid(key, expires, name, type, sig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(name, StandardCharsets.UTF_8).build().toString())
                .body(storage.get(tenant, key));
    }
}
