package com.thinklab.domain.exception;

import com.thinklab.infrastructure.adapter.out.persistence.entity.AssetDocument.AuditEntryDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessExceptionGuardsTest {

    private static class TestException extends BusinessException {
        TestException(String code, String message) {
            super(code, message);
        }

        TestException(String code, String message, Throwable cause) {
            super(code, message, cause);
        }
    }

    @Test
    @DisplayName("BusinessException validates code, message and cause")
    void guards() {
        Throwable cause = new IllegalStateException("root");
        TestException withCause = new TestException("ERR-X", "boom", cause);
        assertEquals("ERR-X", withCause.getErrorCode());
        assertEquals(cause, withCause.getCause());
        assertThrows(NullPointerException.class, () -> new TestException("ERR-X", "boom", null));
        assertThrows(IllegalArgumentException.class, () -> new TestException(" ", "boom"));
        assertThrows(IllegalArgumentException.class, () -> new TestException("ERR-X", " "));
        assertThrows(NullPointerException.class, () -> new TestException(null, "boom"));
        assertThrows(NullPointerException.class, () -> new TestException("ERR-X", null));
    }

    @Test
    @DisplayName("a persisted audit entry without statuses maps to a domain entry without statuses")
    void auditDocumentWithoutStatuses() {
        var domain = new AuditEntryDocument(Instant.now(), "NOTE", "op", null, null, "d").toDomain();

        assertNull(domain.fromStatus());
        assertNull(domain.toStatus());
    }
}
