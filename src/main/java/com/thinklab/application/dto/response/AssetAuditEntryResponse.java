package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

/**
 * DTO projecting one immutable entry of the Asset forensic audit ledger (AST-02).
 */
@Serdeable
public record AssetAuditEntryResponse(
        Instant occurredAt,
        String action,
        String executor,
        String fromStatus,
        String toStatus,
        String detail
) {}
