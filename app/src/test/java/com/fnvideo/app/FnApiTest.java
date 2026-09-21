package com.fnvideo.app;

import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.MockResponse;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.Assert.*;

/** Contract fixtures contain synthetic media only; no account or NAS data. */
public class FnApiTest {
    private MockWebServer server;
    private FnApi api;
    private String base;
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final Map<String, String> requestResponses = new ConcurrentHashMap<>();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private volatile String authx;
    private volatile String authorization;
    private volatile String rawQuery;

    @Before public void startServer() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                String requestBody = request.getBody().readUtf8();
                bodies.put(path, requestBody);
                authx = request.getHeader("authx");
                authorization = request.getHeader("Authorization");
                rawQuery = request.getRequestUrl().encodedQuery();
                String requestKey = path;
                if (!requestBody.isEmpty()) {
                    try {
                        JSONObject body = new JSONObject(requestBody);
                        requestKey += "|" + body.optString("parent_guid", "")
                                + "|" + body.optInt("page", 0);
                    } catch (Exception ignored) {
                        // Existing tests intentionally assert raw bodies; use
                        // the path response when a fixture is not an object.
                    }
                }
                return new MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(requestResponses.getOrDefault(requestKey,
                                responses.getOrDefault(path, "{\"code\":5001,\"msg\":\"Unexpected route\"}")));
            }
        });
        server.start();
        base = server.url("/").toString();
        api = new FnApi(base, "synthetic-session");
    }

    @After public void stopServer() throws Exception { server.shutdown(); }

    @Test public void signatureTravelsInHeaderAndMatchesTheRequestPath() throws Exception {
        responses.put("/v/api/v1/mdb/list", "{\"code\":0,\"data\":[{\"guid\":\"lib-a\",\"name\":\"Test library\"}]}");
        assertEquals("lib-a", api.libraries().get(0).id);
        assertEquals("synthetic-session", authorization);
        assertNull(rawQuery);
        assertNotNull(authx);
        Map<String, String> fields = new HashMap<>();
        for (String part : authx.split("&")) {
            String[] kv = part.split("=", 2); fields.put(kv[0], kv[1]);
        }
        String expected = md5("NDzZTVxnRKP8Z0jXg1VAMonaG8akvh_/v/api/v1/mdb/list_"
                + fields.get("nonce") + "_" + fields.get("timestamp") + "_" + md5("")
                + "_16CCEB3D-AB42-077D-36A1-F355324E4237");
        assertEquals(expected, fields.get("sign"));
    }

    @Test public void pagePreservesLibraryAndNextCursor() throws Exception {
        responses.put("/v/api/v1/item/list", "{\"code\":0,\"data\":{\"total\":101,\"list\":[{\"guid\":\"film\",\"type\":\"Movie\",\"title\":\"Example\"}]}}");
        MediaRepository.Page page = api.page(new MediaRepository.Query("", "lib-a"), "2");
        JSONObject body = new JSONObject(bodies.get("/v/api/v1/item/list"));
        assertEquals("lib-a", body.getString("ancestor_guid"));
        assertEquals(2, body.getInt("page"));
        assertEquals("DESC", body.getString("sort_type"));
        assertEquals("create_time", body.getString("sort_column"));
        assertEquals(1, body.get("exclude_grouped_video"));
        assertEquals("3", page.nextCursor);
        assertEquals("film", page.items.get(0).id);
    }

    @Test public void directStorageSourceDoesNotReceiveNasHeaders() throws Exception {
        responses.put("/v/api/v1/play/info", "{\"code\":0,\"data\":{\"media_guid\":\"media-a\"}}");
        responses.put("/v/api/v1/stream", "{\"code\":0,\"data\":{\"direct_link_qualities\":[{\"url\":\"https://storage.example/test.mp4\"}]}}");
        MediaRepository.Video video = new MediaRepository.Video(); video.id = "film";
        MediaRepository.Source source = api.resolve(video);
        assertEquals("https://storage.example/test.mp4", source.url);
        assertNull(source.headers.get("Authorization"));
        assertNull(source.headers.get("Play-Link"));
        JSONObject streamRequest = new JSONObject(bodies.get("/v/api/v1/stream"));
        assertEquals(streamRequest.getJSONObject("header").getJSONArray("User-Agent").getString(0),
                source.headers.get("User-Agent"));
    }

    @Test public void compatiblePlaybackCapsMultichannelAudioAtStereo() throws Exception {
        responses.put("/v/api/v1/play/info", "{\"code\":0,\"data\":{"
                + "\"media_guid\":\"media-a\",\"video_guid\":\"video-a\","
                + "\"audio_guid\":\"audio-a\",\"subtitle_guid\":\"subtitle-a\"}}");
        responses.put("/v/api/v1/stream", "{\"code\":0,\"data\":{"
                + "\"direct_link_qualities\":[{\"url\":\"https://storage.example/original-hevc.mp4\"}],"
                + "\"video_stream\":{\"guid\":\"video-a\",\"codec_name\":\"hevc\","
                + "\"profile\":\"Main 10\"},"
                + "\"audio_streams\":[{\"guid\":\"audio-a\",\"codec_name\":\"aac\","
                + "\"channels\":6,\"is_default\":true}],"
                + "\"subtitle_streams\":[{\"guid\":\"subtitle-a\",\"is_default\":true}],"
                + "\"qualities\":[{\"resolution\":\"1080p\",\"bitrate\":3000000}]}}");
        responses.put("/v/api/v1/play/play", "{\"code\":0,\"data\":{"
                + "\"play_link\":\"https://storage.example/transcoded-h264.mp4\"}}");

        MediaRepository.Video video = new MediaRepository.Video();
        video.id = "film";
        MediaRepository.Source source = api.resolveCompatible(video);
        assertEquals("application/x-mpegURL", source.mimeType);

        assertNotEquals("https://storage.example/original-hevc.mp4", source.url);
        assertEquals("https://storage.example/transcoded-h264.mp4", source.url);
        JSONObject streamRequest = new JSONObject(bodies.get("/v/api/v1/stream"));
        assertEquals("media-a", streamRequest.getString("media_guid"));
        assertTrue(streamRequest.getString("ip").length() > 0);
        assertEquals("FnVideo/1.0 (Android)",
                streamRequest.getJSONObject("header").getJSONArray("User-Agent").getString(0));
        assertEquals(1, streamRequest.getInt("level"));
        JSONObject playRequest = new JSONObject(bodies.get("/v/api/v1/play/play"));
        assertEquals("media-a", playRequest.getString("media_guid"));
        assertEquals("video-a", playRequest.getString("video_guid"));
        assertEquals("h264", playRequest.getString("video_encoder"));
        assertEquals("1080p", playRequest.getString("resolution"));
        assertEquals(3000000L, playRequest.getLong("bitrate"));
        assertEquals(0L, playRequest.getLong("startTimestamp"));
        assertEquals("aac", playRequest.getString("audio_encoder"));
        assertEquals("audio-a", playRequest.getString("audio_guid"));
        assertEquals("subtitle-a", playRequest.getString("subtitle_guid"));
        assertEquals(2, playRequest.getInt("channels"));
    }

    @Test public void compatiblePlaybackPreservesMonoAudio() throws Exception {
        responses.put("/v/api/v1/play/info", "{\"code\":0,\"data\":{"
                + "\"media_guid\":\"media-mono\",\"video_guid\":\"video-mono\","
                + "\"audio_guid\":\"audio-mono\"}}");
        responses.put("/v/api/v1/stream", "{\"code\":0,\"data\":{"
                + "\"video_stream\":{\"guid\":\"video-mono\",\"codec_name\":\"hevc\"},"
                + "\"audio_streams\":[{\"guid\":\"audio-mono\",\"codec_name\":\"opus\","
                + "\"channels\":1,\"is_default\":true}],"
                + "\"qualities\":[{\"resolution\":\"720p\",\"bitrate\":1000000}]}}");
        responses.put("/v/api/v1/play/play", "{\"code\":0,\"data\":{"
                + "\"play_link\":\"https://storage.example/transcoded-mono.mp4\"}}");

        MediaRepository.Video video = new MediaRepository.Video();
        video.id = "mono";
        api.resolveCompatible(video);

        JSONObject playRequest = new JSONObject(bodies.get("/v/api/v1/play/play"));
        assertEquals("aac", playRequest.getString("audio_encoder"));
        assertEquals("audio-mono", playRequest.getString("audio_guid"));
        assertEquals(1, playRequest.getInt("channels"));
    }

    @Test public void compatiblePlaybackPreservesNoAudioAsZeroChannels() throws Exception {
        responses.put("/v/api/v1/play/info", "{\"code\":0,\"data\":{"
                + "\"media_guid\":\"media-silent\",\"video_guid\":\"video-silent\"}}");
        responses.put("/v/api/v1/stream", "{\"code\":0,\"data\":{"
                + "\"video_stream\":{\"guid\":\"video-silent\",\"codec_name\":\"hevc\"},"
                + "\"qualities\":[{\"resolution\":\"720p\",\"bitrate\":1000000}]}}");
        responses.put("/v/api/v1/play/play", "{\"code\":0,\"data\":{"
                + "\"play_link\":\"https://storage.example/transcoded-silent.mp4\"}}");

        MediaRepository.Video video = new MediaRepository.Video();
        video.id = "silent";
        api.resolveCompatible(video);

        JSONObject playRequest = new JSONObject(bodies.get("/v/api/v1/play/play"));
        assertEquals("", playRequest.getString("audio_encoder"));
        assertEquals("", playRequest.getString("audio_guid"));
        assertEquals(0, playRequest.getInt("channels"));
    }

    @Test public void normalPlaybackPreservesSourceAudioChannels() throws Exception {
        responses.put("/v/api/v1/play/info", "{\"code\":0,\"data\":{"
                + "\"media_guid\":\"media-normal\",\"video_guid\":\"video-normal\","
                + "\"audio_guid\":\"audio-normal\"}}");
        responses.put("/v/api/v1/stream", "{\"code\":0,\"data\":{"
                + "\"video_stream\":{\"guid\":\"video-normal\",\"codec_name\":\"hevc\"},"
                + "\"audio_streams\":[{\"guid\":\"audio-normal\",\"codec_name\":\"eac3\","
                + "\"channels\":6,\"is_default\":true}],"
                + "\"qualities\":[{\"resolution\":\"1080p\",\"bitrate\":2000000}]}}");
        responses.put("/v/api/v1/play/play", "{\"code\":0,\"data\":{"
                + "\"play_link\":\"https://storage.example/original-normal.mp4\"}}");

        MediaRepository.Video video = new MediaRepository.Video();
        video.id = "normal";
        api.resolve(video);

        JSONObject playRequest = new JSONObject(bodies.get("/v/api/v1/play/play"));
        assertEquals("eac3", playRequest.getString("audio_encoder"));
        assertEquals("audio-normal", playRequest.getString("audio_guid"));
        assertEquals(6, playRequest.getInt("channels"));
    }

    @Test public void seriesEpisodesRequestsParentSortedBySeasonAndEpisode() throws Exception {
        responses.put("/v/api/v1/item/list", "{\"code\":0,\"data\":{\"total\":3,\"list\":["
                + "{\"guid\":\"ep-2\",\"type\":\"Episode\",\"title\":\"S1E2\",\"episode\":2,\"season\":1},"
                + "{\"guid\":\"ep-10\",\"type\":\"Episode\",\"title\":\"S1E10\",\"episode\":10,\"season\":1},"
                + "{\"guid\":\"ep-1\",\"type\":\"Episode\",\"title\":\"S2E1\",\"episode\":1,\"season\":2}]}}");
        MediaRepository.Video series = new MediaRepository.Video();
        series.id = "series-a"; series.type = "TV";
        List<MediaRepository.Video> episodes = api.seriesEpisodes(series);
        assertEquals(3, episodes.size());
        assertEquals("ep-2", episodes.get(0).id);
        assertEquals("ep-10", episodes.get(1).id);
        assertEquals("ep-1", episodes.get(2).id);
        JSONObject body = new JSONObject(bodies.get("/v/api/v1/item/list"));
        assertEquals("series-a", body.getString("parent_guid"));
        assertEquals("ASC", body.getString("sort_type"));
        assertEquals("episode", body.getString("sort_column"));
        assertEquals(1, body.getInt("page"));
    }

    @Test public void catalogPageUsesWorkKindsAndMapsMetadataFields() throws Exception {
        responses.put("/v/api/v1/item/list", "{\"code\":0,\"data\":{\"total\":5,\"list\":["
                + "{\"guid\":\"movie\",\"type\":\"Movie\",\"title\":\"Film\","
                + "\"overview\":\"A synopsis\",\"year\":2024},"
                + "{\"guid\":\"series\",\"type\":\"TV\",\"title\":\"Show\","
                + "\"year\":\"2025\",\"series_guid\":\"series\"},"
                + "{\"guid\":\"video\",\"type\":\"Video\",\"title\":\"Clip\"},"
                + "{\"guid\":\"episode\",\"type\":\"Episode\",\"title\":\"S1E1\"},"
                + "{\"guid\":\"season\",\"type\":\"Season\",\"title\":\"Season 1\"}]}} ");

        MediaRepository.Page page = api.catalogPage(new MediaRepository.Query("", "lib-a"), "");
        JSONObject body = new JSONObject(bodies.get("/v/api/v1/item/list"));
        assertEquals("lib-a", body.getString("ancestor_guid"));
        assertEquals(3, body.getJSONObject("tags").getJSONArray("type").length());
        assertEquals("Movie", body.getJSONObject("tags").getJSONArray("type").getString(0));
        assertEquals("TV", body.getJSONObject("tags").getJSONArray("type").getString(1));
        assertEquals("Video", body.getJSONObject("tags").getJSONArray("type").getString(2));
        assertEquals(3, page.items.size());
        assertEquals("A synopsis", page.items.get(0).overview);
        assertEquals("2024", page.items.get(0).year);
        assertEquals("series", page.items.get(1).seriesId);
        assertEquals("", page.nextCursor);
    }

    @Test public void catalogKindTvUsesObservedTvTypeAndFiltersSearchEpisodes() throws Exception {
        responses.put("/v/api/v1/item/list", "{\"code\":0,\"data\":{\"total\":2,\"list\":["
                + "{\"guid\":\"series\",\"type\":\"TV\",\"title\":\"Show\"},"
                + "{\"guid\":\"movie\",\"type\":\"Movie\",\"title\":\"Film\"}]}} ");
        MediaRepository.Page tv = api.catalogPage(new MediaRepository.Query("", "", "tv"), "");
        JSONObject body = new JSONObject(bodies.get("/v/api/v1/item/list"));
        assertEquals(1, body.getJSONObject("tags").getJSONArray("type").length());
        assertEquals("TV", body.getJSONObject("tags").getJSONArray("type").getString(0));
        assertEquals(1, tv.items.size());
        assertEquals("series", tv.items.get(0).id);

        responses.put("/v/api/v1/search/list", "{\"code\":0,\"data\":{\"list\":["
                + "{\"guid\":\"movie\",\"type\":\"Movie\"},"
                + "{\"guid\":\"episode\",\"type\":\"Episode\"},"
                + "{\"guid\":\"series\",\"type\":\"TV\"}]}} ");
        MediaRepository.Page search = api.catalogPage(new MediaRepository.Query("show", ""), "");
        assertEquals(2, search.items.size());
        assertEquals("movie", search.items.get(0).id);
        assertEquals("series", search.items.get(1).id);
        assertEquals("q=show", rawQuery);
    }

    @Test public void seriesEpisodesWalksSeasonContainersAndPagesPastFiftyWithStableIds() throws Exception {
        String itemList = "/v/api/v1/item/list";
        requestResponses.put(itemList + "|series-a|1", "{\"code\":0,\"data\":{\"total\":2,\"list\":["
                + "{\"guid\":\"season-1\",\"type\":\"Season\",\"season\":1,\"parent_guid\":\"series-a\"},"
                + "{\"guid\":\"season-2\",\"type\":\"Season\",\"season\":2,\"parent_guid\":\"series-a\"}]}} ");
        requestResponses.put(itemList + "|season-1|1", episodePage("season-1", 1, 50, 51));
        requestResponses.put(itemList + "|season-1|2", episodePage("season-1", 50, 2, 51));
        requestResponses.put(itemList + "|season-2|1", episodePage("season-2", 1, 1, 1));

        MediaRepository.Video series = new MediaRepository.Video();
        series.id = "series-a";
        series.type = "TV";
        List<MediaRepository.Video> episodes = api.seriesEpisodes(series);
        assertEquals(52, episodes.size());
        assertEquals("season-1-ep-1", episodes.get(0).id);
        assertEquals("season-1-ep-51", episodes.get(50).id);
        assertEquals("season-2-ep-1", episodes.get(51).id);
        assertEquals("series-a", episodes.get(0).seriesId);
        assertEquals("season-1", episodes.get(0).seasonId);
        assertEquals("season-1", episodes.get(50).parentId);
    }

    @Test public void seriesEpisodesFailsWhenTheServerRepeatsAnUnadvancedPage() throws Exception {
        String itemList = "/v/api/v1/item/list";
        String first = episodePage("series-a", 1, 50, 101);
        requestResponses.put(itemList + "|series-a|1", first);
        requestResponses.put(itemList + "|series-a|2", first);
        MediaRepository.Video series = new MediaRepository.Video();
        series.id = "series-a";
        try {
            api.seriesEpisodes(series);
            fail("Expected an explicit pagination failure");
        } catch (FnApi.FnApiException error) {
            assertTrue(error.getMessage().contains("pagination"));
        }
    }

    @Test public void containerCannotBeResolvedAsPlayback() throws Exception {
        MediaRepository.Video series = new MediaRepository.Video();
        series.id = "series-a";
        series.type = "TV";
        try {
            api.resolve(series);
            fail("Expected a container rejection");
        } catch (FnApi.FnApiException error) {
            assertTrue(error.getMessage().contains("container"));
        }
        assertNull(bodies.get("/v/api/v1/play/info"));
    }

    @Test public void detailsDefaultPreservesAlreadyLoadedItem() throws Exception {
        MediaRepository.Video item = new MediaRepository.Video();
        item.id = "movie";
        item.overview = "Loaded from catalog";
        assertSame(item, api.details(item));
    }

    @Test public void authFailureRemainsDistinguishableFromNetworkFailure() throws Exception {
        responses.put("/v/api/v1/mdb/list", "{\"code\":-2,\"msg\":\"Auth Failed\"}");
        try { api.libraries(); fail("Expected expired session error"); }
        catch (FnApi.FnApiException error) { assertEquals(-2, error.apiCode); }
    }

    private String md5(String text) throws Exception {
        byte[] bytes = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private String episodePage(String seasonId, int firstEpisode, int count, int total) {
        int season = seasonId.endsWith("2") ? 2 : 1;
        StringBuilder body = new StringBuilder("{\"code\":0,\"data\":{\"total\":")
                .append(total).append(",\"list\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) body.append(',');
            int episode = firstEpisode + i;
            body.append("{\"guid\":\"").append(seasonId).append("-ep-").append(episode)
                    .append("\",\"type\":\"Episode\",\"season\":").append(season)
                    .append(",\"episode\":").append(episode)
                    .append(",\"series_guid\":\"series-a\",\"season_guid\":\"")
                    .append(seasonId).append("\"}");
        }
        return body.append("]}} ").toString();
    }
}
