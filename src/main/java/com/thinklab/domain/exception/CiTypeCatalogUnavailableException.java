package com.thinklab.domain.exception;

/**
 * Domain Exception: the ci-type-catalog lookup failed (non-404 HTTP error, timeout, unreachable) while
 * {@code thinklab.ci-type-catalog.fail-closed} is {@code true} (ADR-027). Strict tenants prefer a refused
 * write over an unvalidated one.
 *
 * <p>RFC 7807 mapping: HTTP 503 Service Unavailable - a transient dependency failure, retryable, and not
 * a statement about the request or the Asset's state.
 */
public class CiTypeCatalogUnavailableException extends BusinessException {

    public CiTypeCatalogUnavailableException(String message, Throwable cause) {
        super("ERR-AST-00503", message, cause);
    }
}
