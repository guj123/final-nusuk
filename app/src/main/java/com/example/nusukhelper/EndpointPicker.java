package com.example.nusukhelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure Java: decides which AI endpoint to call first.
 *  - alternate on: calls go 1,2,1,2... so both endpoints share the load (and their rate limits).
 *  - alternate off: endpoint 1 first, the others only as backup.
 * An endpoint that failed is skipped until its cool-down ends (e.g. the 429 retry-after).
 */
public final class EndpointPicker {
    private final int n;
    private final boolean alternate;
    private final long[] readyAt;
    private int next;

    public EndpointPicker(int n, boolean alternate) {
        this.n = n; this.alternate = alternate; this.readyAt = new long[n];
    }

    public int size() { return n; }

    /** Endpoints to try for one question, in order. Endpoints that are cooling down are left out. */
    public synchronized int[] order(long now) {
        List<Integer> out = new ArrayList<>();
        int start = alternate ? next : 0;
        for (int k = 0; k < n; k++) {
            int i = (start + k) % n;
            if (readyAt[i] <= now) out.add(i);
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    public synchronized void success(int i) {
        readyAt[i] = 0;
        if (alternate) next = (i + 1) % n;
    }

    public synchronized void failed(int i, long now, long cooldownMs) {
        readyAt[i] = now + Math.max(cooldownMs, 1000);
    }

    /** Milliseconds until the first endpoint is usable again (0 if one is usable now). */
    public synchronized long soonestReady(long now) {
        long best = Long.MAX_VALUE;
        for (long r : readyAt) best = Math.min(best, r);
        return Math.max(0, best - now);
    }
}
