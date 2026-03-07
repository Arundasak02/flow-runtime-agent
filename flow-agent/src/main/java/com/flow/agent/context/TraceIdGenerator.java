package com.flow.agent.context;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates trace IDs and span IDs.
 */
public class TraceIdGenerator {

    /** Full UUID for trace IDs — ensures global uniqueness. */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /** 16-hex-character span ID — shorter, still low-collision probability. */
    public static String generateSpanId() {
        long value = ThreadLocalRandom.current().nextLong();
        // Ensure positive to avoid leading minus sign in hex
        String hex = Long.toHexString(value < 0 ? -value : value);
        // Pad or truncate to exactly 16 characters
        if (hex.length() < 16) {
            return String.format("%16s", hex).replace(' ', '0');
        }
        return hex.substring(0, 16);
    }
}

