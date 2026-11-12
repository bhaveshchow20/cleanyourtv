package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

/**
 * design_language tvos27's navigation sounds: a soft tick when focus moves between cards and rows, and a lower one when
 * one is selected, the sounds cleanyourtv-ui's Click makes. Android's own sound effects are off on many TVs
 * (sound_effects_enabled=0, TCL), so the two sounds are synthesized here, with no assets.
 *
 * They play through one quiet, low-latency media stream that runs, playing silence, only while one of the launcher's screens
 * is in front, and the clicks are mixed into it. A TV that sends its sound to a soundbar over HDMI ARC drops the first
 * few hundred milliseconds of every new stream while the link locks, which swallowed a 30 ms click played on its own.
 * The stream stops a moment after the last screen leaves, so nothing holds audio under another app. Where the TV's own
 * sound effects are on, Android already ticks on every focus move, so these stay silent rather than double it. Mute,
 * or the volume all the way down, silences them and stops the stream. Off with
 * Settings > Navigation sounds, or navigation_sounds: false in a theme.
 */
final class NavSound {
    private static final String TAG = "NavSound";
    private static final int RATE = 48_000;
    /** Frames per write: 5 ms, so a click waits at most that long for its turn. */
    private static final int CHUNK = RATE / 200;
    /** How long the stream outlives the last screen, so going from Home to a panel keeps it locked. */
    private static final long LINGER_MS = 1500;

    private static short[] moveSound, openSound;
    private static boolean enabled = true, systemSounds;
    private static int users, shown;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Player player;
    private static AudioManager audio;

    private NavSound() {
    }

    /** A screen that makes the sounds is created. Pair with {@link #release()}. */
    static void acquire(Context ctx, boolean on) {
        enabled = on;
        systemSounds = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 0) != 0;
        users++;
        if (audio == null) audio = ctx.getApplicationContext().getSystemService(AudioManager.class);
        if (moveSound == null) {
            moveSound = tone(1450f, 0.009f, 0.030f, 0.14f);
            openSound = tone(880f, 0.022f, 0.090f, 0.18f);
        }
    }

    /** That screen is gone. */
    static void release() {
        if (users > 0) users--;
        if (users == 0) stopNow();
    }

    /** One of the screens came to the front: the stream runs while any does. Pair with {@link #paused()}. */
    static void resumed() {
        shown++;
        main.removeCallbacks(stop);
        startPlayer();
    }

    private static void startPlayer() {
        if (player == null && enabled && !systemSounds && !muted()) {
            player = new Player();
            player.start();
        }
    }

    /** That screen left the front. */
    static void paused() {
        if (shown > 0) shown--;
        if (shown == 0) {
            main.removeCallbacks(stop);
            main.postDelayed(stop, LINGER_MS);
        }
    }

    private static final Runnable stop = NavSound::stopNow;

    private static void stopNow() {
        main.removeCallbacks(stop);
        if (player != null) player.finish();
        player = null;
    }

    static void setEnabled(boolean on) {
        enabled = on;
        if (!on) stopNow();
        else if (shown > 0) startPlayer();
    }

    /** Ticks on every focus move inside {@code root}'s window, from one item to another. */
    static void watch(View root) {
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((from, to) -> {
            if (from != null && to != null && from != to) move(to);
        });
    }

    static void move(View v) {
        play(v, moveSound);
    }

    static void open(View v) {
        play(v, openSound);
    }

    /** Select let go on {@code focused}: the open sound. Call from a window's key dispatch. */
    static void key(KeyEvent e, View focused) {
        int c = e.getKeyCode();
        if (focused != null && e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()
                && (c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER
                || c == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            open(focused);
        }
    }

    private static void play(View v, short[] sound) {
        if (!enabled || systemSounds || sound == null || v == null || !v.hasWindowFocus()) return;
        // Mute on the remote mutes these too; the stream stops while muted and comes back with the sound.
        if (muted()) {
            stopNow();
            return;
        }
        // Not resumed(): that counts a screen, and one counted twice never lets the stream stop.
        if (player == null && shown > 0) startPlayer();
        Player p = player;
        if (p != null) p.queue(sound);
    }

    /** The TV is muted, or its volume is all the way down: every stream on a TV follows the music stream's. */
    private static boolean muted() {
        AudioManager a = audio;
        return a != null && (a.isStreamMute(AudioManager.STREAM_MUSIC) || a.getStreamVolume(AudioManager.STREAM_MUSIC) == 0);
    }

    /** The stream: silence, with the latest click mixed in from where it was asked for. */
    private static final class Player extends Thread {
        private volatile boolean running = true;
        private volatile short[] next;
        private short[] current;
        private int at;

        Player() {
            super("nav-sound");
        }

        void queue(short[] sound) {
            next = sound;
        }

        void finish() {
            running = false;
            interrupt();
        }

        @Override
        public void run() {
            AudioTrack track;
            try {
                int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                // Media, not sonification: TV audio policies drop sonification when the TV's
                                // own sound effects are off (TCL).
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .setBufferSizeInBytes(Math.max(min, CHUNK * 2 * 2))
                        .build();
                // Only ~20 ms of silence queued ahead, so a click is not stuck behind the whole buffer (hundreds of ms
                // on some TVs). An underrun here just plays a little extra silence.
                track.setBufferSizeInFrames(CHUNK * 4);
                track.play();
            } catch (RuntimeException e) {
                Log.w(TAG, "navigation sounds unavailable: " + e.getMessage());
                return;
            }
            short[] chunk = new short[CHUNK];
            try {
                while (running) {
                    short[] n = next;
                    if (n != null) {
                        next = null;
                        current = n;
                        at = 0;
                    }
                    java.util.Arrays.fill(chunk, (short) 0);
                    if (current != null) {
                        int count = Math.min(CHUNK, current.length - at);
                        System.arraycopy(current, at, chunk, 0, count);
                        at += count;
                        if (at >= current.length) current = null;
                    }
                    if (track.write(chunk, 0, CHUNK) < 0) break;
                }
            } finally {
                try {
                    track.pause();
                    track.flush();
                } catch (IllegalStateException ignored) {
                }
                track.release();
            }
        }
    }

    /** A sine with one harmonic, a 1.5 ms attack (no pop) and an exponential decay, at {@code volume}. */
    private static short[] tone(float freq, float decay, float seconds, float volume) {
        int n = Math.round(RATE * seconds);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double env = Math.min(1, t / 0.0015) * Math.exp(-t / decay);
            double s = 0.75 * Math.sin(2 * Math.PI * freq * t) + 0.25 * Math.sin(2 * Math.PI * freq * 2.01 * t);
            out[i] = (short) Math.round(s * env * 0.7 * volume * Short.MAX_VALUE);
        }
        return out;
    }
}
