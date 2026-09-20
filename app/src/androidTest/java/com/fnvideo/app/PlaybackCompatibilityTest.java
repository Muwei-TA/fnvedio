package com.fnvideo.app;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;

import junit.framework.TestCase;
import java.util.Arrays;


public class PlaybackCompatibilityTest extends TestCase {
    public void testunsupportedAudioWithPlayableVideoNeedsFallback() {
        assertTrue(PlaybackCompatibility.hasUnsupportedAudio(tracks(
                group("video/avc", C.FORMAT_HANDLED),
                group("audio/vnd.dts", C.FORMAT_UNSUPPORTED_SUBTYPE))));
    }

    public void testsupportedAlternativeAudioAvoidsTranscoding() {
        assertFalse(PlaybackCompatibility.hasUnsupportedAudio(tracks(
                group("audio/vnd.dts", C.FORMAT_UNSUPPORTED_SUBTYPE),
                group("audio/mp4a-latm", C.FORMAT_HANDLED))));
    }

    public void testemptyOrVideoOnlyTracksDoNotTriggerFallback() {
        assertFalse(PlaybackCompatibility.hasUnsupportedAudio(Tracks.EMPTY));
        assertFalse(PlaybackCompatibility.hasUnsupportedAudio(tracks(
                group("video/avc", C.FORMAT_HANDLED))));
    }

    public void testaudioExceedingDeviceCapabilitiesNeedsFallback() {
        assertTrue(PlaybackCompatibility.hasUnsupportedAudio(tracks(
                group("audio/mp4a-latm", C.FORMAT_EXCEEDS_CAPABILITIES))));
    }

    private Tracks tracks(Tracks.Group... groups) {
        return new Tracks(Arrays.asList(groups));
    }

    private Tracks.Group group(String mimeType, int support) {
        TrackGroup group = new TrackGroup(new Format.Builder().setSampleMimeType(mimeType).build());
        return new Tracks.Group(group, false, new int[]{support},
                new boolean[]{support == C.FORMAT_HANDLED});
    }
}

