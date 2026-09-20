package com.fnvideo.app;

import androidx.media3.common.C;
import androidx.media3.common.Tracks;

/** Device track capabilities, independent of NAS codecs and request fields. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class PlaybackCompatibility {
    private PlaybackCompatibility() {}

    static boolean hasUnsupportedAudio(Tracks tracks) {
        // Empty tracks occur during preparation; genuinely silent videos need no transcode.
        return tracks.containsType(C.TRACK_TYPE_AUDIO)
                && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO);
    }
}
