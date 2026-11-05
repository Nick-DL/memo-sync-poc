package com.example.memosync.core;

import java.util.Objects;

/**
 * A single memo record.
 *
 * <p>The public field names match the long keys used by {@code shared/merge_cases.json}
 * and the design document §3.1 ({@code id, title, body, created, updated, titleHlc,
 * bodyHlc, deleted, deletedHlc, rev}). The short wire keys are handled exclusively by
 * {@link ProtocolCodec}.
 *
 * <p>{@link #base} and {@link #force} are wire-only transport flags (design doc §4.3):
 * <ul>
 *   <li>{@code base} - watch -&gt; phone push: the canonical {@code updated} HLC the watch
 *       received at its last pull, used as the three-way merge base;</li>
 *   <li>{@code force} - phone -&gt; watch pull: force delivery of a merged record even when
 *       its {@code updated} is not newer than the watch cursor.</li>
 * </ul>
 * They are not persisted as memo columns by the POC store; merge rules ignore them.
 */
public final class MemoRecord {

    public String id;
    public String title;
    public String body;
    public String created;
    public String updated;
    public String titleHlc;
    public String bodyHlc;
    public boolean deleted;
    public String deletedHlc;
    public int rev;
    /** Wire-only: peer's last known canonical updated HLC (push messages only). */
    public String base;
    /** Wire-only: force pull delivery even if {@code updated <= peer cursor}. */
    public boolean force;

    public MemoRecord() {
    }

    public MemoRecord(String id, String title, String body, String created, String updated,
                      String titleHlc, String bodyHlc, boolean deleted, String deletedHlc, int rev) {
        this.id = id;
        this.title = title;
        this.body = body;
        this.created = created;
        this.updated = updated;
        this.titleHlc = titleHlc;
        this.bodyHlc = bodyHlc;
        this.deleted = deleted;
        this.deletedHlc = deletedHlc;
        this.rev = rev;
    }

    /** Deep copy (all columns + wire-only flags). */
    public static MemoRecord copyOf(MemoRecord other) {
        if (other == null) {
            return null;
        }
        MemoRecord c = new MemoRecord();
        c.id = other.id;
        c.title = other.title;
        c.body = other.body;
        c.created = other.created;
        c.updated = other.updated;
        c.titleHlc = other.titleHlc;
        c.bodyHlc = other.bodyHlc;
        c.deleted = other.deleted;
        c.deletedHlc = other.deletedHlc;
        c.rev = other.rev;
        c.base = other.base;
        c.force = other.force;
        return c;
    }

    public MemoRecord copy() {
        return copyOf(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MemoRecord)) {
            return false;
        }
        MemoRecord other = (MemoRecord) o;
        return deleted == other.deleted
                && rev == other.rev
                && force == other.force
                && Objects.equals(id, other.id)
                && Objects.equals(title, other.title)
                && Objects.equals(body, other.body)
                && Objects.equals(created, other.created)
                && Objects.equals(updated, other.updated)
                && Objects.equals(titleHlc, other.titleHlc)
                && Objects.equals(bodyHlc, other.bodyHlc)
                && Objects.equals(deletedHlc, other.deletedHlc)
                && Objects.equals(base, other.base);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, body, created, updated, titleHlc, bodyHlc,
                deleted, deletedHlc, rev, base, force);
    }

    @Override
    public String toString() {
        return "MemoRecord{id=" + id
                + ", title=" + title
                + ", body=" + body
                + ", created=" + created
                + ", updated=" + updated
                + ", titleHlc=" + titleHlc
                + ", bodyHlc=" + bodyHlc
                + ", deleted=" + deleted
                + ", deletedHlc=" + deletedHlc
                + ", rev=" + rev
                + (base != null ? ", base=" + base : "")
                + (force ? ", force=true" : "")
                + '}';
    }
}
