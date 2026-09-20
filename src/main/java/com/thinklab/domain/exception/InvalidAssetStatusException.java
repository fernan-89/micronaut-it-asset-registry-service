package com.thinklab.domain.exception;

/**
 * Domain Exception: Indicates an illegal lifecycle transition or a business-rule violation on an
 * {@link com.thinklab.domain.model.Asset} (for example, deploying an asset with no location, or
 * mutating a decommissioned asset).
 *
 * <p>RFC 7807 mapping: HTTP 422 Unprocessable Entity (AST-03 State Conflict Handler). The request
 * is syntactically valid but semantically impossible in the aggregate's current state — which is
 * why this Service Domain reports 422 rather than the 409 used for identity collisions.
 */
public class InvalidAssetStatusException extends BusinessException {

    private static final String ERROR_CODE = "ERR-AST-00422";

    public InvalidAssetStatusException(String message) {
        super(ERROR_CODE, message);
    }
}
