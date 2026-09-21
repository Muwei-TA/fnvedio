package com.fnvideo.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** App-owned boundary. No Android classes or NAS JSON escape through this API. */
public interface MediaRepository {
    List<Library> libraries() throws Exception;
    Page page(Query query, String cursor) throws Exception;
    /**
     * Page through works in the catalog. Implementations may override this
     * with catalog-specific type filtering; the default keeps older adapters
     * source-compatible until they do so.
     */
    default Page catalogPage(Query query, String cursor) throws Exception {
        return page(query, cursor);
    }

    /** Return the best available details for an item already read from the API. */
    default Video details(Video item) throws Exception {
        return item;
    }

    Source resolve(Video video) throws Exception;
    Source resolveCompatible(Video video) throws Exception;
    /** Episodes of a series/season container, ordered by season and episode number. */
    List<Video> seriesEpisodes(Video series) throws Exception;

    final class Video {
        public String id = "";
        public String title = "";
        public String subtitle = "";
        public String poster = "";
        public String type = "";
        /** Full synopsis when the server provides one. */
        public String overview = "";
        /** Release year as returned by the server; preserve string formatting. */
        public String year = "";
        /** Stable parent series id for an episode, when available. */
        public String seriesId = "";
        /** Stable season container id for an episode, when available. */
        public String seasonId = "";
        /** Season number for episodes; zero when the server does not provide one. */
        public int season = 0;
        /** Episode number for episodes; zero when the server does not provide one. */
        public int episode = 0;
        /** Parent series/season container id for episodes; empty for standalone items. */
        public String parentId = "";
    }

    final class Library {
        public final String id;
        public final String title;
        public Library(String id, String title) { this.id = id; this.title = title; }
    }

    final class Query {
        public final String query;
        public final String libraryId;
        /** Empty means all catalog work kinds; feed queries leave this empty. */
        public final String kind;
        public final List<String> mediaTypes = FeedPolicy.PLAYABLE_TYPES;

        public Query(String query, String libraryId) {
            this(query, libraryId, "");
        }

        public Query(String query, String libraryId, String kind) {
            this.query = query == null ? "" : query.trim();
            this.libraryId = libraryId == null ? "" : libraryId;
            this.kind = kind == null ? "" : kind.trim();
        }
    }

    final class Page {
        public final List<Video> items;
        /** Empty means the end; opaque nonempty values belong to the adapter. */
        public final String nextCursor;
        public Page(List<Video> items, String nextCursor) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.nextCursor = nextCursor == null ? "" : nextCursor;
        }
    }

    final class Source {
        public final String url;
        public final Map<String, String> headers;
        public final String mimeType;
        public Source(String url, Map<String, String> headers, String mimeType) {
            if (url == null || url.isEmpty()) throw new IllegalArgumentException("Missing playback URL");
            this.url = url;
            this.headers = Collections.unmodifiableMap(new HashMap<>(headers));
            this.mimeType = mimeType == null ? "" : mimeType;
        }
    }
}
