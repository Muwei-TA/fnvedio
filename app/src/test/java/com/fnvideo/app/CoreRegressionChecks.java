package com.fnvideo.app;

import java.util.Arrays;
import java.util.Collections;

/** Dependency-free checks, also invoked by JUnit and runnable with a plain JDK. */
public final class CoreRegressionChecks {
    private CoreRegressionChecks() { }

    public static void main(String[] args) {
        addresses();
        playbackOwnership();
        pagination();
        authentication();
        System.out.println("PASS: address/session, playback ownership, pagination and authentication checks");
    }

    static void addresses() {
        equal("https://nas.example.test", ServerAddress.normalize(" HTTPS://NAS.EXAMPLE.TEST:443/v/ "));
        equal("http://[::1]:5666", ServerAddress.normalize("http://[::1]:5666/v/api/v1"));
        equal("http://nas.example.test", ServerAddress.normalize("http://nas.example.test:80/"));
        String old = "https://nas.example.test:443/v";
        equal("synthetic-token", ServerAddress.retainedToken(old, "https://NAS.example.test", "synthetic-token"));
        equal("", ServerAddress.retainedToken(old, "https://other.example.test", "synthetic-token"));
        equal("", ServerAddress.retainedToken(old, "http://nas.example.test", "synthetic-token"));
        equal("", ServerAddress.retainedToken(old, "https://nas.example.test:444", "synthetic-token"));
        check(ServerAddress.sameOrigin(old, "https://nas.example.test/media/1?signature=test"), "URLs with paths remain comparable");
        for (String value : Arrays.asList("", "nas", "file:///tmp/video", "http://user:pass@nas.example.test",
                "http://nas.example.test:0", "http://nas.example.test:65536", "http://nas.example.test:",
                "http://nas.example.test:abc", "http://nas.example.test?token=test", "http://nas.example.test#v",
                "http://nas.example.test/unrelated", "http://nas.example.test\\@other.example.test")) {
            expectInvalid(() -> ServerAddress.normalize(value));
        }
    }

    static void playbackOwnership() {
        PlaybackSession state = new PlaybackSession();
        PlaybackSession.Ticket a = state.select("http://nas.example.test:5666/v", "a", 1);
        check(state.loaded() == null, "selection is not loaded playback");
        check(state.markLoaded(a), "current source may attach");
        equal("position:http://nas.example.test:5666:a", state.loaded().resumeKey());
        PlaybackSession.Ticket b = state.select("http://nas.example.test:5666", "b", 1);
        check(state.loaded() == null, "A position must not be written under B while B resolves");
        check(!state.markLoaded(a), "late A result cannot attach");
        check(state.markLoaded(b), "B result may attach");
        PlaybackSession.Ticket secondA = state.select("http://nas.example.test:5666", "a", 1);
        check(!state.isCurrent(a), "A-B-A must reject the first A callback");
        check(state.isCurrent(secondA), "latest selection remains valid");
        PlaybackSession.Ticket retry = state.select("http://nas.example.test:5666", "a", 1);
        check(!state.isCurrent(secondA), "retry invalidates old work for the same ID");
        state.markLoaded(retry);
        state.clearLoaded();
        check(state.loaded() == null && state.isCurrent(retry), "stop clears ownership without reviving old callbacks");
        state.invalidate();
        check(!state.markLoaded(retry), "logout/teardown rejects late results");
        PlaybackSession.Ticket changed = state.select("https://other.example.test", "a", 2);
        equal("position:https://other.example.test:a", changed.resumeKey());
        equal(2L, changed.feedGeneration);
    }

    static void pagination() {
        FeedState state = new FeedState();
        long ticket = state.reset();
        state.append(ticket, page("2", video("a", "Video")));
        state.append(ticket, page("3", video("a", "Video"), video("folder", "Directory")));
        equal(1, state.items().size());
        check(state.shouldAutoLoad(0), "duplicate/filtered later page must continue");
        state.append(ticket, page("", video("b", "Episode")));
        equal(2, state.items().size());
        check(!state.shouldAutoLoad(1), "last page terminates loading");

        ticket = state.reset();
        for (int page = 0; page < 5; page++) {
            state.append(ticket, new MediaRepository.Page(Collections.emptyList(), Integer.toString(page + 2)));
        }
        check(state.hasMore() && !state.shouldAutoLoad(0), "empty-page scan is bounded");
        state.restartEmptyPageScan();
        check(state.shouldAutoLoad(0), "explicit retry opens a fresh scan budget");

        ticket = state.reset();
        state.append(ticket, page("2", video("a", "Video")));
        long current = ticket;
        expectState(() -> state.append(current, page("2", video("b", "Video"))));
        equal(1, state.items().size());
        state.append(ticket, page("3", video("b", "Video")));
        expectState(() -> state.append(current, page("2", video("c", "Video"))));
        state.reset();
        check(!state.append(current, page("", video("stale", "Movie"))), "changed query discards old page");
        check(state.items().isEmpty(), "stale page does not change results");
    }

    static void authentication() {
        RepositoryFailure expired = new RepositoryFailure(RepositoryFailure.Kind.AUTHENTICATION_REQUIRED, "arbitrary localized message", null);
        check(RepositoryFailure.requiresLogin(expired), "typed expiry routes to login");
        check(RepositoryFailure.requiresLogin(new Exception("wrapped", expired)), "wrapped expiry routes to login");
        check(!RepositoryFailure.requiresLogin(new RepositoryFailure(RepositoryFailure.Kind.PERMISSION_DENIED, "403", null)), "permission denial does not erase session");
        check(!RepositoryFailure.requiresLogin(new Exception("401 unauthorized")), "error text is not an authentication contract");
    }

    private static MediaRepository.Video video(String id, String type) {
        MediaRepository.Video video = new MediaRepository.Video();
        video.id = id;
        video.type = type;
        return video;
    }

    private static MediaRepository.Page page(String next, MediaRepository.Video... videos) {
        return new MediaRepository.Page(Arrays.asList(videos), next);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
    }

    private static void expectInvalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid address rejection");
    }

    private static void expectState(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected cyclic cursor rejection");
    }
}
