package com.grocery.ai.voice;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Audio format detection, the text read aloud and the per-user voice limit. */
class VoiceUnitTest {

    private static byte[] header(int... bytes) {
        byte[] data = new byte[64];
        Arrays.fill(data, (byte) 1);
        for (int i = 0; i < bytes.length; i++) {
            data[i] = (byte) bytes[i];
        }
        return data;
    }

    private static byte[] ascii(int offset, String text) {
        byte[] data = new byte[64];
        Arrays.fill(data, (byte) 1);
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, data, offset, bytes.length);
        return data;
    }

    @Test
    void theFormatComesFromTheFirstBytesNotTheName() {
        assertThat(AudioFormat.detect(header(0x1A, 0x45, 0xDF, 0xA3))).contains(AudioFormat.WEBM);
        assertThat(AudioFormat.detect(ascii(0, "OggS"))).contains(AudioFormat.OGG);
        byte[] wav = ascii(0, "RIFF");
        System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, wav, 8, 4);
        assertThat(AudioFormat.detect(wav)).contains(AudioFormat.WAV);
        assertThat(AudioFormat.detect(ascii(0, "ID3"))).contains(AudioFormat.MP3);
        assertThat(AudioFormat.detect(header(0xFF, 0xFB))).contains(AudioFormat.MP3);
        assertThat(AudioFormat.detect(ascii(4, "ftypM4A "))).contains(AudioFormat.MP4);
        assertThat(AudioFormat.detect(ascii(0, "fLaC"))).contains(AudioFormat.FLAC);

        assertThat(AudioFormat.detect(ascii(0, "RIFF"))).isEmpty();               // RIFF but not WAVE (e.g. AVI)
        assertThat(AudioFormat.detect(ascii(0, "<html><body>"))).isEmpty();
        assertThat(AudioFormat.detect(ascii(0, "%PDF-1.7"))).isEmpty();
        assertThat(AudioFormat.detect(new byte[5])).isEmpty();
        assertThat(AudioFormat.detect(null)).isEmpty();
    }

    @Test
    void markdownIsReadAsPlainWords() {
        assertThat(SpeechText.plain("**112** vendors are approved. [View vendors](/admin/vendors)"))
                .isEqualTo("112 vendors are approved. View vendors");
        assertThat(SpeechText.plain("## Low stock\n- Tomato – 3 left\n- Onion – 5 left"))
                .isEqualTo("Low stock. Tomato – 3 left. Onion – 5 left");
        assertThat(SpeechText.plain("Order `GA-7K3F9Q2M` is *on its way*.")).isEqualTo("Order GA-7K3F9Q2M is on its way.");
        assertThat(SpeechText.plain(null)).isEmpty();
        assertThat(SpeechText.plain(" \n ")).isEmpty();
    }

    @Test
    void eachUserHasTheirOwnHourlyAllowance() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-15T10:59:30Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        VoiceRateLimiter limiter = new VoiceRateLimiter(clock);
        UUID asha = UUID.randomUUID();
        UUID ravi = UUID.randomUUID();

        assertThat(limiter.acquire(asha, 2)).isZero();
        assertThat(limiter.acquire(asha, 2)).isZero();
        assertThat(limiter.acquire(asha, 2)).isEqualTo(30);          // seconds until 11:00
        assertThat(limiter.acquire(ravi, 2)).isZero();               // someone else is not affected

        now.set(Instant.parse("2026-11-15T11:00:01Z"));
        assertThat(limiter.acquire(asha, 2)).isZero();               // a new hour
    }
}
