package com.thinklab.domain.exception;

/**
 * Domain Exception: Thrown when an Asset is initiated with a serial number that already exists
 * within the same Organisation scope.
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict.
 */
public class DuplicateAssetException extends BusinessException {

    private static final String ERROR_CODE = "ERR-AST-00409";

    public DuplicateAssetException(String message) {
        super(ERROR_CODE, message);
    }
}
