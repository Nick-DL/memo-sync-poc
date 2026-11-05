package com.example.memosync.core;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hybrid Logical Clock (HLC), as fixed by {@code shared/PROTOCOL.md} §1.
 *
 * <p>Wire encoding is a fixed-width, lexicographically comparable string:
 *
 * <pre>{@code <13-digit millis>-<4-digit counter>-<deviceId>}</pre>
 *
 * <p>Note that the deviceId itself may contain hyphens (the design document uses
 * {@code w-a1b2c3d4}), therefore parsing only splits off the first two groups.
 *
 * <p>Comparison order: {@code t} (physical millis), then {@code c} (counter),
 * then {@code d} (deviceId, lexicographic).
 *
 * <p>This class is immutable; {@link #next(long)} / {@link #receive(Hlc)} return new
 * instances. A single generator instance per device must be serialised by the caller
 * (see design doc §3.2) - e.g. keep it behind a mutex in the repository.
 */
public final class Hlc implements Comparable<Hlc> {

    /** Counter is fixed to 4 digits; on overflow we advance the physical clock instead. */
    public static final int MAX_COUNTER = 9999;

    private static final Pattern ENCODED =
            Pattern.compile("^(\\d{13})-(\\d{4})-(.+)$");

    private final long t;
    private final int c;
    private final String d;

    public Hlc(long t, int c, String deviceId) {
        if (t < 0) {
            throw new IllegalArgumentException("HLC physical time must be >= 0: " + t);
        }
        if (c < 0) {
            throw new IllegalArgumentException("HLC counter must be >= 0: " + c);
        }
        if (deviceId == null || deviceId.isEmpty()) {
            throw new IllegalArgumentException("HLC deviceId must not be empty");
        }
        this.t = t;
        this.c = c;
        this.d = deviceId;
    }

    public static Hlc of(long t, int c, String deviceId) {
        return new Hlc(t, c, deviceId);
    }

    /**
     * Parses the encoded form. Throws {@link IllegalArgumentException} on malformed input;
     * a malformed HLC from the wire is a protocol error, never silently repaired.
     */
    public static Hlc parse(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("HLC must not be null");
        }
        Matcher m = ENCODED.matcher(encoded);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "Bad HLC, expected <13-digit millis>-<4-digit counter>-<deviceId>: " + encoded);
        }
        long t = Long.parseLong(m.group(1));
        int c = Integer.parseInt(m.group(2));
        return new Hlc(t, c, m.group(3));
    }

    public static Hlc parseOrNull(String encoded) {
        return encoded == null ? null : parse(encoded);
    }

    public long t() {
        return t;
    }

    public int c() {
        return c;
    }

    public String deviceId() {
        return d;
    }

    /** Generates the next HLC for a local event, using the wall clock. */
    public Hlc next() {
        return next(System.currentTimeMillis());
    }

    /** Generates the next HLC for a local event. {@code now} is milliseconds since epoch. */
    public Hlc next(long now) {
        long nt;
        int nc;
        if (now > t) {
            nt = now;
            nc = 0;
        } else {
            // Clock did not advance (or went backwards): keep physical time and bump counter.
            nt = t;
            nc = c + 1;
        }
        return withCounterOverflowGuard(nt, nc);
    }

    /**
     * Merges a remote HLC into this local clock, per design doc §3.2:
     *
     * <pre>
     *   t = max(t, remote.t)
     *   if t == remote.t -> c = max(c, remote.c) + 1
     *   else             -> c = 0   (remote.t > t)  /  c = c + 1 (remote.t &lt; t)
     * </pre>
     */
    public Hlc receive(Hlc remote) {
        Objects.requireNonNull(remote, "remote HLC must not be null");
        long nt;
        int nc;
        if (remote.t > t) {
            nt = remote.t;
            nc = remote.c + 1;
        } else if (remote.t == t) {
            nt = t;
            nc = Math.max(c, remote.c) + 1;
        } else {
            nt = t;
            nc = c + 1;
        }
        return withCounterOverflowGuard(nt, nc);
    }

    private Hlc withCounterOverflowGuard(long nt, int nc) {
        if (nc > MAX_COUNTER) {
            // Keeps the 4-digit encoding valid in pathological frozen-clock cases.
            return new Hlc(nt + 1, 0, d);
        }
        return new Hlc(nt, nc, d);
    }

    /** Encodes to the fixed 13/4 width representation. */
    public String encode() {
        return String.format(Locale.ROOT, "%013d-%04d-%s", t, c, d);
    }

    @Override
    public int compareTo(Hlc other) {
        int cmp = Long.compare(this.t, other.t);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(this.c, other.c);
        if (cmp != 0) {
            return cmp;
        }
        return this.d.compareTo(other.d);
    }

    // ------------------------------------------------------------------
    // Null-tolerant helpers for encoded HLC strings. A null HLC is treated
    // as "smallest" (useful while parsing POC fixtures); valid protocol
    // records always carry non-null HLCs.
    // ------------------------------------------------------------------

    /** True when encoded {@code a} is strictly after {@code b}; null sorts first. */
    public static boolean isAfter(String a, String b) {
        if (a == null) {
            return false;
        }
        if (b == null) {
            return true;
        }
        return parse(a).compareTo(parse(b)) > 0;
    }

    /** True when encoded {@code a} is strictly before {@code b}; null sorts first. */
    public static boolean isBefore(String a, String b) {
        return isAfter(b, a);
    }

    /** Null-tolerant max; ties return {@code a}. */
    public static String maxEncoded(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return isAfter(a, b) ? a : b;
    }

    /**
     * Null-tolerant min for encoded HLC strings; ties return {@code a}. A null operand is
     * treated as "absent" (the other value is returned), mirroring {@link #maxEncoded}.
     * This keeps a partially populated record from losing its known creation time.
     */
    public static String minEncoded(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return isAfter(a, b) ? b : a;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Hlc)) {
            return false;
        }
        Hlc other = (Hlc) o;
        return t == other.t && c == other.c && d.equals(other.d);
    }

    @Override
    public int hashCode() {
        return Objects.hash(t, c, d);
    }

    @Override
    public String toString() {
        return encode();
    }
}
