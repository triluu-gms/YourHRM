package vn.yourhrm.shared.common.error;

import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Base for business errors rendered as RFC 9457 ProblemDetail. {@code code} becomes the problem
 * {@code type} ({@code urn:yourhrm:problem:<code>}) and a {@code code} property, so clients can branch
 * on it without parsing messages.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;
    private final transient Map<String, Object> properties;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> properties) {
        super(message);
        this.status = status;
        this.code = code;
        this.properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    public URI type() {
        return URI.create("urn:yourhrm:problem:" + code);
    }

    public static ApiException notFound(String what, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", what + " " + id + " not found");
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    public static ApiException unprocessable(String code, String message, Map<String, Object> properties) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message, properties);
    }
}
