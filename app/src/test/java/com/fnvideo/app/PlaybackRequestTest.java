package com.fnvideo.app;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression coverage for the bounded Activity handoff used by long seasons. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class PlaybackRequestTest {
    @Test public void longQueueIsBoundedAndSelectedEpisodeKeepsItsIdentity() {
        List<MediaRepository.Video> values = new ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            MediaRepository.Video video = new MediaRepository.Video();
            video.id = "episode-" + i;
            video.title = "Episode " + i;
            video.type = "Episode";
            video.seriesId = "series-1";
            video.seasonId = "season-4";
            video.season = 4;
            video.episode = i;
            video.poster = "https://nas.example.test/poster/" + i;
            values.add(video);
        }

        Intent intent = new Intent();
        PlaybackRequest.put(intent, values.get(449), values, 449, true, "series");

        List<MediaRepository.Video> handoff = PlaybackRequest.queue(intent);
        assertTrue(handoff.size() <= 48);
        assertEquals("episode-450", PlaybackRequest.video(intent).id);
        assertEquals("episode-450", handoff.get(PlaybackRequest.queueIndex(intent)).id);
        assertTrue(PlaybackRequest.queueIndex(intent) >= 0);
        assertFalse(intent.getStringExtra(PlaybackRequest.EXTRA_QUEUE).contains("overview"));
        assertTrue(intent.getStringExtra(PlaybackRequest.EXTRA_QUEUE).length() < 100_000);
    }
}
