package com.grocery.ai.voice;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The audio containers the speech-to-text call accepts, recognised from the file's first bytes (its "magic number"),
 * never from the client's Content-Type or file name, which anyone can set. Browsers record WebM/Opus (Chrome, Edge,
 * Firefox), Ogg/Opus (Firefox) or MP4/AAC (Safari); WAV, MP3 and FLAC cover uploaded files.
 */
enum AudioFormat {

    WEBM("audio/webm", "webm"),
    OGG("audio/ogg", "ogg"),
    WAV("audio/wav", "wav"),
    MP3("audio/mpeg", "mp3"),
    MP4("audio/mp4", "m4a"),
    FLAC("audio/flac", "flac");

    /** The type sent to the speech service with the file part. */
    final String mediaType;
    /** The file name extension sent with it: OpenAI-compatible servers pick the decoder from it. */
    final String extension;

    AudioFormat(String mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    static List<String> names() {
        return Arrays.stream(values()).map(format -> format.extension).toList();
    }

    /** The format of {@code audio}, or empty when it is none of the supported containers. */
    static Optional<AudioFormat> detect(byte[] audio) {
        if (audio == null || audio.length < 12) {
            return Optional.empty();
        }
        if (startsWith(audio, 0, 0x1A, 0x45, 0xDF, 0xA3)) {              // EBML header: WebM / Matroska
            return Optional.of(WEBM);
        }
        if (ascii(audio, 0, "OggS")) {
            return Optional.of(OGG);
        }
        if (ascii(audio, 0, "RIFF") && ascii(audio, 8, "WAVE")) {
            return Optional.of(WAV);
        }
        if (ascii(audio, 0, "fLaC")) {
            return Optional.of(FLAC);
        }
        if (ascii(audio, 4, "ftyp")) {                                    // ISO base media: MP4 / M4A
            return Optional.of(MP4);
        }
        if (ascii(audio, 0, "ID3")
                || ((audio[0] & 0xFF) == 0xFF && (audio[1] & 0xE0) == 0xE0)) {  // ID3 tag, or an MPEG frame sync
            return Optional.of(MP3);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, int offset, int... expected) {
        for (int i = 0; i < expected.length; i++) {
            if ((data[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean ascii(byte[] data, int offset, String expected) {
        byte[] bytes = expected.getBytes(StandardCharsets.US_ASCII);
        if (data.length < offset + bytes.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (data[offset + i] != bytes[i]) {
                return false;
            }
        }
        return true;
    }
}
