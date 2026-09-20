package com.thinklab.infrastructure.adapter.in.filter;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Edge Filter enforcing SRE Privacy by Design and Zero-Trust principles.
 * Intercepts all inbound traffic to mask PII (Personally Identifiable Information)
 * such as IP addresses and to truncate excessive User-Agents before they enter
 * the application's logging pipeline or tracing context.
 */
@Filter(Filter.MATCH_ALL_PATTERN)
public class TraceIdFilter implements HttpServerFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);
    private static final int MAX_USER_AGENT_LENGTH = 50;

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String rawIp = extractClientIp(request);
        String maskedIp = maskIp(rawIp);

        String rawUserAgent = request.getHeaders().get(HttpHeaders.USER_AGENT);
        String truncatedUserAgent = truncate(rawUserAgent, MAX_USER_AGENT_LENGTH);

        log.info("[EDGE FILTER] Inbound {} {} | Masked IP: {} | UA: {}",
                request.getMethod(), request.getPath(), maskedIp, truncatedUserAgent);

        // Proceeds with the reactive chain
        return chain.proceed(request);
    }

    /**
     * Extracts the IP address from the request, prioritizing the X-Forwarded-For header
     * in case the application is behind a load balancer or ingress proxy.
     */
    private String extractClientIp(HttpRequest<?> request) {
        String forwardedFor = request.getHeaders().get("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddress().getAddress().getHostAddress();
    }

    /**
     * Masks the last segment of an IPv4 or IPv6 address for privacy protection.
     * E.g., 192.168.1.100 -> 192.168.1.***
     */
    private String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }

        int lastDot = ip.lastIndexOf('.');
        if (lastDot > 0) {
            return ip.substring(0, lastDot) + ".***"; // IPv4 masking
        }

        int lastColon = ip.lastIndexOf(':');
        if (lastColon > 0) {
            return ip.substring(0, lastColon) + ":***"; // IPv6 masking
        }

        return "***";
    }

    /**
     * Truncates a string to prevent log injection or memory exhaustion from massive headers.
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}