package com.fnvideo.app;

/** Main-thread state separating a selected page from media actually loaded in the player. */
public final class PlaybackSession {
    public static final class Ticket {
        public final String serverOrigin;
        public final String videoId;
        public final long feedGeneration;

        private Ticket(String serverOrigin, String videoId, long feedGeneration) {
            this.serverOrigin = serverOrigin;
            this.videoId = videoId;
            this.feedGeneration = feedGeneration;
        }

        public String resumeKey() {
            return "position:" + serverOrigin + ":" + videoId;
        }
    }

    private Ticket current;
    private Ticket loaded;

    public Ticket select(String server, String videoId, long feedGeneration) {
        if (videoId == null || videoId.isEmpty()) {
            throw new IllegalArgumentException("Missing media ID");
        }
        current = new Ticket(ServerAddress.normalize(server), videoId, feedGeneration);
        loaded = null;
        return current;
    }

    /** Object identity also rejects A -> B -> A callbacks and same-item retries. */
    public boolean isCurrent(Ticket ticket) {
        return ticket != null && current == ticket;
    }

    public boolean markLoaded(Ticket ticket) {
        if (!isCurrent(ticket)) return false;
        loaded = ticket;
        return true;
    }

    public Ticket current() { return current; }
    public Ticket loaded() { return loaded; }
    public void clearLoaded() { loaded = null; }

    public void invalidate() {
        current = null;
        loaded = null;
    }
}
