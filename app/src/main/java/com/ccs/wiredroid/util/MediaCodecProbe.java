package com.ccs.wiredroid.util;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Inspects a media file's tracks and reports whether this device can decode
 * them, using only Android's own decoders.
 *
 * <p>Everything here relies on the platform's built-in codecs — no bundled
 * decoder, no licensed format. Android's mandatory set covers the royalty-free
 * families: VP8, VP9 and AV1 for video, Vorbis, Opus and FLAC for audio, in the
 * WebM and Matroska containers. Patent-encumbered formats (H.264, H.265, AAC,
 * MP3) usually decode too, because the device manufacturer already licensed
 * them — but that is the device's licence, not this app's, so those tracks are
 * labelled as such rather than advertised as supported features.</p>
 */
public final class MediaCodecProbe {

    /** MIME types that are royalty-free and part of Android's baseline. */
    private static final Set<String> ROYALTY_FREE = new HashSet<>(Arrays.asList(
            MediaFormat.MIMETYPE_VIDEO_VP8,
            MediaFormat.MIMETYPE_VIDEO_VP9,
            MediaFormat.MIMETYPE_VIDEO_AV1,
            MediaFormat.MIMETYPE_AUDIO_VORBIS,
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            MediaFormat.MIMETYPE_AUDIO_FLAC,
            MediaFormat.MIMETYPE_AUDIO_RAW,
            "audio/wav",
            "video/x-vnd.on2.vp8",
            "video/x-vnd.on2.vp9"
    ));

    /** One track of a media file. */
    public static final class Track {
        public final int index;
        public final String mime;
        public final boolean video;
        public final boolean decodable;
        public final boolean royaltyFree;
        /** Width in pixels, or 0 for an audio track. */
        public final int width;
        public final int height;
        /** Sample rate in Hz, or 0 for a video track. */
        public final int sampleRate;
        public final int channels;
        public final long durationUs;
        public final String language;

        Track(int index, String mime, boolean video, boolean decodable, boolean royaltyFree,
              int width, int height, int sampleRate, int channels, long durationUs, String language) {
            this.index = index;
            this.mime = mime;
            this.video = video;
            this.decodable = decodable;
            this.royaltyFree = royaltyFree;
            this.width = width;
            this.height = height;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.durationUs = durationUs;
            this.language = language;
        }

        /** A short human-readable summary, e.g. {@code VP9 1920×1080}. */
        public String describe() {
            StringBuilder sb = new StringBuilder(shortCodecName(mime));
            if (video && width > 0) {
                sb.append(' ').append(width).append('×').append(height);
            } else if (!video && sampleRate > 0) {
                sb.append(' ').append(sampleRate).append(" Hz");
                if (channels > 0) sb.append(' ').append(channels).append("ch");
            }
            if (language != null && !language.isEmpty() && !"und".equals(language)) {
                sb.append(" [").append(language).append(']');
            }
            return sb.toString();
        }
    }

    /** The outcome of probing a file. */
    public static final class Result {
        public final List<Track> tracks = new ArrayList<>();
        /** Non-null when the container itself could not be read. */
        public String error;

        public boolean hasVideo() {
            for (Track t : tracks) {
                if (t.video) return true;
            }
            return false;
        }

        /** True when at least one video track and any audio track can be decoded. */
        public boolean isPlayable() {
            boolean anyDecodable = false;
            for (Track t : tracks) {
                if (t.decodable) anyDecodable = true;
            }
            return error == null && anyDecodable;
        }

        /** Tracks this device cannot decode. */
        public List<Track> undecodableTracks() {
            List<Track> out = new ArrayList<>();
            for (Track t : tracks) {
                if (!t.decodable) out.add(t);
            }
            return out;
        }

        /** Longest track duration in microseconds, or 0 when unknown. */
        public long durationUs() {
            long max = 0;
            for (Track t : tracks) max = Math.max(max, t.durationUs);
            return max;
        }
    }

    private MediaCodecProbe() {}

    /** Reads the container and tests each track against the device's decoders. */
    public static Result probe(File file) {
        Result result = new Result();
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int count = extractor.getTrackCount();
            for (int i = 0; i < count; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;

                boolean video = mime.startsWith("video/");
                result.tracks.add(new Track(
                        i,
                        mime,
                        video,
                        hasDecoderFor(mime),
                        isRoyaltyFree(mime),
                        optInt(format, MediaFormat.KEY_WIDTH),
                        optInt(format, MediaFormat.KEY_HEIGHT),
                        optInt(format, MediaFormat.KEY_SAMPLE_RATE),
                        optInt(format, MediaFormat.KEY_CHANNEL_COUNT),
                        optLong(format, MediaFormat.KEY_DURATION),
                        optString(format, MediaFormat.KEY_LANGUAGE)));
            }
        } catch (Exception e) {
            result.error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /** True when this device ships a decoder for the given MIME type. */
    public static boolean hasDecoderFor(String mime) {
        if (mime == null) return false;
        return decoderMimes().contains(mime.toLowerCase(Locale.ROOT));
    }

    /**
     * Starts building the decoder table on a background thread. Callers that
     * know a probe is coming — an activity being launched on a media file —
     * can call this first so {@link #probe(File)} finds the table ready.
     */
    public static void warmUp() {
        if (decoderMimes != null) return;
        new Thread(MediaCodecProbe::decoderMimes, "MediaCodecProbe-warmUp").start();
    }

    /** Every MIME type this device can decode, lowercased. */
    private static volatile Set<String> decoderMimes;

    /**
     * The device's codec table cannot change while the process lives, and
     * building it is a round-trip into the media server, so it is built once
     * instead of once per track.
     */
    private static Set<String> decoderMimes() {
        Set<String> cached = decoderMimes;
        if (cached != null) return cached;
        Set<String> mimes = new HashSet<>();
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) continue;
                for (String supported : info.getSupportedTypes()) {
                    mimes.add(supported.toLowerCase(Locale.ROOT));
                }
            }
        } catch (Exception e) {
            // An enumeration that failed is not cached, so a later call retries
            // rather than declaring every codec unsupported for good.
            return mimes;
        }
        decoderMimes = mimes;
        return mimes;
    }

    /**
     * True for formats that carry no patent-licensing obligation — the ones this
     * app can rely on without a codec licence.
     */
    public static boolean isRoyaltyFree(String mime) {
        if (mime == null) return false;
        return ROYALTY_FREE.contains(mime.toLowerCase(Locale.ROOT));
    }

    /** Compact display name for a MIME type. */
    public static String shortCodecName(String mime) {
        if (mime == null) return "?";
        switch (mime.toLowerCase(Locale.ROOT)) {
            case "video/x-vnd.on2.vp8": return "VP8";
            case "video/x-vnd.on2.vp9": return "VP9";
            case "video/av01":          return "AV1";
            case "video/avc":           return "H.264";
            case "video/hevc":          return "H.265";
            case "video/mp4v-es":       return "MPEG-4";
            case "video/3gpp":          return "H.263";
            case "audio/vorbis":        return "Vorbis";
            case "audio/opus":          return "Opus";
            case "audio/flac":          return "FLAC";
            case "audio/mp4a-latm":     return "AAC";
            case "audio/mpeg":          return "MP3";
            case "audio/raw":           return "PCM";
            case "audio/ac3":           return "AC-3";
            case "audio/eac3":          return "E-AC-3";
            default:
                int slash = mime.indexOf('/');
                return slash >= 0 ? mime.substring(slash + 1).toUpperCase(Locale.ROOT) : mime;
        }
    }

    private static int optInt(MediaFormat format, String key) {
        try {
            return format.containsKey(key) ? format.getInteger(key) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long optLong(MediaFormat format, String key) {
        try {
            return format.containsKey(key) ? format.getLong(key) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String optString(MediaFormat format, String key) {
        try {
            return format.containsKey(key) ? format.getString(key) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
