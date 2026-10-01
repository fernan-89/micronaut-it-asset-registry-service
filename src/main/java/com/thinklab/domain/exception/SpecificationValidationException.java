package com.thinklab.domain.exception;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Domain Exception: thrown when an Asset's {@code specifications} payload does not conform to the
 * tenant-configured JSON Schema for its {@link com.thinklab.domain.model.Asset.AssetCategory}, as
 * fetched from {@code ci-type-catalog-service} (ADR-027).
 *
 * <p>RFC 7807 mapping: HTTP 422 Unprocessable Entity — distinct from the 409 Conflict used for Asset
 * lifecycle violations (ADR-019). This is semantically-invalid request content, independent of the
 * Asset's current state, not a state collision.
 */
public class SpecificationValidationException extends BusinessException {

    private static final String ERROR_CODE = "ERR-AST-00422";

    private final List<String> violations;

    public SpecificationValidationException(String message, List<String> violations) {
        super(ERROR_CODE, message);
        Objects.requireNonNull(violations, "Violations cannot be null.");
        this.violations = List.copyOf(violations);
    }

    public List<String> getViolations() {
        return Collections.unmodifiableList(violations);
    }
}
