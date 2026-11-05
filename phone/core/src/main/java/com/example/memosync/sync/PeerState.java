package com.example.memosync.sync;

/** Per-peer sync cursors (design doc §3.3 {@code sync_state} table, POC subset). */
public final class PeerState {

    public String peerId;
    /**
     * Largest phone-record HLC the watch has received (the watch's pull cursor). The phone uses
     * it as the {@code cur} of a phone-initiated {@code start} and for {@code changedSince}.
     */
    public String lastPullHlc;
    /** Largest watch-record HLC the phone has received (phone's push cursor). */
    public String lastPushHlc;

    public PeerState() {
    }

    public PeerState(String peerId, String lastPullHlc, String lastPushHlc) {
        this.peerId = peerId;
        this.lastPullHlc = lastPullHlc;
        this.lastPushHlc = lastPushHlc;
    }

    @Override
    public String toString() {
        return "PeerState{peerId=" + peerId + ", lastPullHlc=" + lastPullHlc
                + ", lastPushHlc=" + lastPushHlc + '}';
    }
}
