package com.n3vgames.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * r145b: intro before the game, drawn as an overlay ON the game screen (no separate Activity: r145's own
 * Activity paused the game behind it and the game came back black).  The game keeps loading underneath.
 * Video (assets/tts_intro.mp4, tap = skip) -> disclaimer (tap = continue) -> overlay removed -> then() runs the
 * normal start (login gate, HUD, pad).  Every step is written to TTSHornPad.log.  Nothing here can keep the
 * game shut: a missing video / player error goes to the disclaimer, a crash removes the overlay.
 */
public final class TTSIntro {
    static volatile boolean done;
    static final String ASSET = "tts_intro.mp4";
    static Overlay ov;

    /** true = intro finished, go on; false = the intro is on screen, then() is run when it closes. */
    public static boolean check(Activity game, Runnable then) {
        if (done) return true;
        try {
            if (ov != null && ov.host == game && ov.root.getParent() != null) {
                ov.then = then;
                ov.resume();
                return false;
            }
            if (ov != null) ov.drop();
            ov = new Overlay(game, then);
            return false;
        } catch (Throwable t) {
            TTSHornPad.log("intro: " + t);
            done = true;                                   // never block the game because of the intro
            return true;
        }
    }

    /** the game went to the background: hold the video */
    public static void pause(Activity a) {
        try {
            if (ov != null && ov.host == a) ov.pause();
        } catch (Throwable t) {
            // keep
        }
    }

    /** the video, kept at its own 16:9 shape in the middle of the black screen */
    static final class FitTexture extends TextureView {
        float aspect = 16f / 9f;

        FitTexture(Activity a) {
            super(a);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = View.MeasureSpec.getSize(ws);
            int h = View.MeasureSpec.getSize(hs);
            if (w > 0 && h > 0) {
                if (w / (float) h > aspect) w = (int) (h * aspect);
                else h = (int) (w / aspect);
            }
            setMeasuredDimension(w, h);
        }
    }

    static final class Overlay implements TextureView.SurfaceTextureListener {
        final Activity host;
        final FrameLayout root;
        final FitTexture tv;
        final Handler h = new Handler(Looper.getMainLooper());
        Runnable then;
        MediaPlayer mp;
        Surface surface;
        String path;
        int stage;                                         // 0 video, 1 disclaimer, 2 gone
        long stageAt;
        boolean prepared, paused;

        Overlay(Activity a, Runnable then) {
            host = a;
            this.then = then;
            root = new FrameLayout(a);
            root.setBackgroundColor(Color.BLACK);
            root.setClickable(true);                       // the game under it gets no touches
            root.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    TTSHornPad.log("intro: tap (stage " + stage + ")");
                    next();
                }
            });
            tv = new FitTexture(a);
            tv.setSurfaceTextureListener(this);
            root.addView(tv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            TextView hint = new TextView(a);
            hint.setText("Tap to skip");
            hint.setTextColor(0x88FFFFFF);
            hint.setTextSize(12f);
            hint.setPadding(0, 0, dp(22), dp(16));
            root.addView(hint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT));
            a.addContentView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            stageAt = System.currentTimeMillis();
            TTSHornPad.log("intro: overlay on");
            new Thread(new Runnable() {
                public void run() {
                    final String p = copyVideo();
                    h.post(new Runnable() {
                        public void run() {
                            path = p;
                            if (p == null) {
                                next();
                                return;
                            }
                            tryStart();
                        }
                    });
                }
            }, "TTSIntro").start();
            h.postDelayed(new Runnable() {                 // safety: never sit on a black screen
                public void run() {
                    if (stage == 0 && !prepared) {
                        TTSHornPad.log("intro: video did not start in 6 s");
                        next();
                    }
                }
            }, 6000);
        }

        int dp(float v) {
            return (int) (v * host.getResources().getDisplayMetrics().density + 0.5f);
        }

        /** the APK keeps assets compressed, so the player gets a plain copy in the cache */
        String copyVideo() {
            try {
                File f = new File(host.getCacheDir(), ASSET);
                File tmp = new File(host.getCacheDir(), ASSET + ".tmp");
                InputStream in = host.getAssets().open(ASSET);
                FileOutputStream out = new FileOutputStream(tmp);
                byte[] buf = new byte[65536];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
                out.close();
                in.close();
                if (!tmp.renameTo(f)) f = tmp;
                TTSHornPad.log("intro: video ready " + total + " bytes");
                return f.getAbsolutePath();
            } catch (Throwable t) {
                TTSHornPad.log("intro video: " + t);
                return null;
            }
        }

        // ------------------------------------------------------------------ TextureView
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
            surface = new Surface(st);
            tryStart();
        }

        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) { }

        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            try {
                if (mp != null) mp.setSurface(null);
            } catch (Throwable t) {
                // keep
            }
            try {
                if (surface != null) surface.release();
            } catch (Throwable t) {
                // keep
            }
            surface = null;
            return true;
        }

        public void onSurfaceTextureUpdated(SurfaceTexture st) { }

        void tryStart() {
            if (stage != 0 || mp != null || path == null || surface == null) return;
            try {
                mp = new MediaPlayer();
                mp.setDataSource(path);
                mp.setSurface(surface);
                mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    public void onPrepared(MediaPlayer p) {
                        prepared = true;
                        int vw = p.getVideoWidth(), vh = p.getVideoHeight();
                        if (vw > 0 && vh > 0) {
                            tv.aspect = vw / (float) vh;
                            tv.requestLayout();
                        }
                        TTSHornPad.log("intro: playing " + vw + "x" + vh + " " + p.getDuration() + " ms");
                        if (stage == 0 && !paused) p.start();
                        // the end does not depend on the completion callback alone
                        int dur = p.getDuration();
                        if (dur <= 0 || dur > 30000) dur = 12000;
                        h.postDelayed(new Runnable() {
                            public void run() {
                                if (stage == 0 && !paused) {
                                    TTSHornPad.log("intro: end by timer");
                                    next();
                                }
                            }
                        }, dur + 1500);
                    }
                });
                mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    public void onCompletion(MediaPlayer p) {
                        TTSHornPad.log("intro: video end");
                        if (stage == 0) next();
                    }
                });
                mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    public boolean onError(MediaPlayer p, int what, int extra) {
                        TTSHornPad.log("intro player error " + what + "/" + extra);
                        if (stage == 0) next();
                        return true;
                    }
                });
                mp.prepareAsync();
            } catch (Throwable t) {
                TTSHornPad.log("intro play: " + t);
                next();
            }
        }

        void pause() {
            paused = true;
            try {
                if (mp != null && mp.isPlaying()) mp.pause();
            } catch (Throwable t) {
                // keep
            }
        }

        void resume() {
            paused = false;
            try {
                if (stage == 0 && mp != null && prepared && !mp.isPlaying()) mp.start();
            } catch (Throwable t) {
                // keep
            }
        }

        void stopVideo() {
            MediaPlayer p = mp;
            mp = null;
            if (p == null) return;
            try {
                p.setOnCompletionListener(null);
                p.setOnErrorListener(null);
                p.stop();
            } catch (Throwable t) {
                // keep
            }
            try {
                p.release();
            } catch (Throwable t) {
                // keep
            }
        }

        // ------------------------------------------------------------------ steps
        void next() {
            try {
                long now = System.currentTimeMillis();
                if (stage == 0) {
                    stage = 1;
                    stageAt = now;
                    stopVideo();
                    showDisclaimer();
                    TTSHornPad.log("intro: disclaimer");
                } else if (stage == 1) {
                    if (now - stageAt < 700) return;      // a double tap must not skip the disclaimer unread
                    finishIntro();
                }
            } catch (Throwable t) {
                TTSHornPad.log("intro next: " + t);
                finishIntro();
            }
        }

        void showDisclaimer() {
            root.removeAllViews();
            LinearLayout box = new LinearLayout(host);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(40), dp(20), dp(40), dp(20));

            TextView t = new TextView(host);
            t.setText("DISCLAIMER");
            t.setTextColor(0xFFFFB347);
            t.setTextSize(22f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setLetterSpacing(0.25f);
            t.setGravity(Gravity.CENTER);
            box.addView(t);

            View line = new View(host);
            line.setBackgroundColor(0xFFFF8C20);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(120), dp(2));
            lp.topMargin = dp(10);
            lp.bottomMargin = dp(16);
            box.addView(line, lp);

            TextView body = new TextView(host);
            body.setText(DISCLAIMER);
            body.setTextColor(0xFFD8DCE2);
            body.setTextSize(13.5f);
            body.setLineSpacing(0f, 1.25f);
            body.setGravity(Gravity.CENTER);
            box.addView(body, new LinearLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView tap = new TextView(host);
            tap.setText("Tap anywhere to continue");
            tap.setTextColor(0xAAFFFFFF);
            tap.setTextSize(12f);
            tap.setGravity(Gravity.CENTER);
            tap.setPadding(0, dp(22), 0, 0);
            box.addView(tap);

            root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        }

        void finishIntro() {
            if (stage == 2) return;
            stage = 2;
            done = true;
            drop();
            TTSHornPad.log("intro: done -> game");
            Runnable r = then;
            then = null;
            if (r != null) {
                try {
                    r.run();
                } catch (Throwable t) {
                    TTSHornPad.log("intro then: " + t);
                }
            }
        }

        void drop() {
            h.removeCallbacksAndMessages(null);
            stopVideo();
            try {
                ViewGroup p = (ViewGroup) root.getParent();
                if (p != null) p.removeView(root);
            } catch (Throwable t) {
                // keep
            }
            if (ov == this) ov = null;
        }
    }

    static final String DISCLAIMER =
            "TTS Trainz Simulator is a fan-made modification of Trainz Android by Total Trainz Productions (TTS).\n"
            + "Trainz is a trademark of N3V Games Pty Ltd. This project is not affiliated with or endorsed by "
            + "N3V Games or Indian Railways.\n"
            + "Indian Railways names, logos and liveries appear only for simulation.\n"
            + "All addon content belongs to its respective creators.\n"
            + "Do not re-upload, sell or share this APK without permission.";
}
