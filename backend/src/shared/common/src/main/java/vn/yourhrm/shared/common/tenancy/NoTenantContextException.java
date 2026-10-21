package vn.yourhrm.shared.common.tenancy;

/** Thrown when tenant-scoped work (e.g. a database transaction) starts without a bound {@link TenantContext}. */
public class NoTenantContextException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public NoTenantContextException() {
        super("No tenant context bound: tenant-scoped work must run inside a request/job with a TenantContext");
    }
}
