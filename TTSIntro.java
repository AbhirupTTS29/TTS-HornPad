package com.n3vgames.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * r145: intro before the game.  The first time the game screen comes up in a process, IntroActivity is put in
 * front of it: it plays assets/tts_intro.mp4 (tap = skip), then shows the disclaimer (tap = continue), then
 * closes and the game (and the login gate after it) carries on.  A missing / broken video goes straight to the
 * disclaimer; nothing here can keep the game shut.
 */
public final class TTSIntro {
    static volatile boolean done;
    static volatile boolean showing;
    static final String ASSET = "tts_intro.mp4";

    /** true = intro finished, the game may go on; false = the intro is (being) shown in front. */
    public static boolean check(Activity game) {
        if (done) return true;
        if (showing) return false;
        showing = true;
        try {
            Intent i = new Intent(game, IntroActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            game.startActivity(i);
            game.overridePendingTransition(0, 0);
            return false;
        } catch (Throwable t) {
            TTSHornPad.log("intro: " + t);
            showing = false;
            done = true;                     // never block the game because of the intro
            return true;
        }
    }

    /** the video, kept at its own 16:9 shape in the middle of the black screen */
    static final class FitSurface extends SurfaceView {
        float aspect = 16f / 9f;

        FitSurface(Activity a) {
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

    public static final class IntroActivity extends Activity implements SurfaceHolder.Callback {
        FrameLayout root;
        FitSurface sv;
        TextView hint;
        MediaPlayer mp;
        int stage;                           // 0 video, 1 disclaimer, 2 leaving
        long stageAt;
        boolean surfaceOk;
        final Handler h = new Handler(Looper.getMainLooper());

        @Override
        protected void onCreate(Bundle b) {
            try {
                requestWindowFeature(Window.FEATURE_NO_TITLE);
            } catch (Throwable t) {
                // keep
            }
            super.onCreate(b);
            try {
                setRequestedOrientation(6);  // sensorLandscape, like the game
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Throwable t) {
                // keep
            }
            root = new FrameLayout(this);
            root.setBackgroundColor(Color.BLACK);
            root.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    next();
                }
            });
            sv = new FitSurface(this);
            root.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            hint = new TextView(this);
            hint.setText("Tap to skip");
            hint.setTextColor(0x88FFFFFF);
            hint.setTextSize(12f);
            hint.setPadding(0, 0, dp(22), dp(16));
            root.addView(hint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT));
            setContentView(root);
            immersive();
            stageAt = System.currentTimeMillis();
            sv.getHolder().addCallback(this);
            h.postDelayed(new Runnable() {          // safety: never sit on a black screen
                public void run() {
                    if (stage == 0 && (mp == null || !mp.isPlaying())) next();
                }
            }, 6000);
        }

        int dp(float v) {
            return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
        }

        void immersive() {
            try {
                getWindow().getDecorView().setSystemUiVisibility(0x1706);   // immersive sticky, fullscreen, hide nav
            } catch (Throwable t) {
                // keep
            }
        }

        @Override
        public void onWindowFocusChanged(boolean f) {
            super.onWindowFocusChanged(f);
            if (f) immersive();
        }

        // ------------------------------------------------------------------ video
        public void surfaceCreated(final SurfaceHolder holder) {
            surfaceOk = true;
            if (stage != 0) return;
            if (mp != null) {
                try {
                    mp.setDisplay(holder);
                    mp.start();
                } catch (Throwable t) {
                    // keep
                }
                return;
            }
            new Thread(new Runnable() {
                public void run() {
                    final String path = copyVideo();
                    h.post(new Runnable() {
                        public void run() {
                            play(path, holder);
                        }
                    });
                }
            }, "TTSIntro").start();
        }

        public void surfaceChanged(SurfaceHolder holder, int f, int w, int hh) { }

        public void surfaceDestroyed(SurfaceHolder holder) {
            surfaceOk = false;
            try {
                if (mp != null && mp.isPlaying()) mp.pause();
            } catch (Throwable t) {
                // keep
            }
        }

        /** the APK keeps assets compressed, so the player gets a plain copy in the cache */
        String copyVideo() {
            try {
                File f = new File(getCacheDir(), ASSET);
                InputStream in = getAssets().open(ASSET);       // copied every start: an update brings its own intro
                File tmp = new File(getCacheDir(), ASSET + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                if (!tmp.renameTo(f)) return tmp.getAbsolutePath();
                return f.getAbsolutePath();
            } catch (Throwable t) {
                TTSHornPad.log("intro video: " + t);
                return null;
            }
        }

        void play(String path, SurfaceHolder holder) {
            if (stage != 0 || isFinishing()) return;
            if (path == null || !surfaceOk) {
                if (path == null) next();
                return;
            }
            try {
                mp = new MediaPlayer();
                mp.setDataSource(path);
                mp.setDisplay(holder);
                mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    public void onPrepared(MediaPlayer p) {
                        int vw = p.getVideoWidth(), vh = p.getVideoHeight();
                        if (vw > 0 && vh > 0) {
                            sv.aspect = vw / (float) vh;
                            sv.requestLayout();
                        }
                        if (stage == 0) p.start();
                    }
                });
                mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    public void onCompletion(MediaPlayer p) {
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

        void stopVideo() {
            try {
                if (mp != null) {
                    mp.setOnCompletionListener(null);
                    mp.setOnErrorListener(null);
                    mp.stop();
                }
            } catch (Throwable t) {
                // keep
            }
            try {
                if (mp != null) mp.release();
            } catch (Throwable t) {
                // keep
            }
            mp = null;
        }

        // ------------------------------------------------------------------ steps
        void next() {
            long now = System.currentTimeMillis();
            if (stage == 0) {
                stopVideo();
                showDisclaimer();
                stage = 1;
                stageAt = now;
            } else if (stage == 1) {
                if (now - stageAt < 700) return;         // a double tap must not skip the disclaimer unread
                stage = 2;
                leave();
            }
        }

        void showDisclaimer() {
            root.removeAllViews();
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(40), dp(20), dp(40), dp(20));

            TextView t = new TextView(this);
            t.setText("DISCLAIMER");
            t.setTextColor(0xFFFFB347);
            t.setTextSize(22f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setLetterSpacing(0.25f);
            t.setGravity(Gravity.CENTER);
            box.addView(t);

            View line = new View(this);
            line.setBackgroundColor(0xFFFF8C20);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(120), dp(2));
            lp.topMargin = dp(10);
            lp.bottomMargin = dp(16);
            box.addView(line, lp);

            TextView body = new TextView(this);
            body.setText(DISCLAIMER);
            body.setTextColor(0xFFD8DCE2);
            body.setTextSize(13.5f);
            body.setLineSpacing(0f, 1.25f);
            body.setGravity(Gravity.CENTER);
            box.addView(body, new LinearLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView tap = new TextView(this);
            tap.setText("Tap anywhere to continue");
            tap.setTextColor(0xAAFFFFFF);
            tap.setTextSize(12f);
            tap.setGravity(Gravity.CENTER);
            tap.setPadding(0, dp(22), 0, 0);
            box.addView(tap);

            root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            box.setAlpha(0f);
            box.animate().alpha(1f).setDuration(450).start();
        }

        void leave() {
            done = true;
            showing = false;
            try {
                finish();
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            } catch (Throwable t) {
                // keep
            }
        }

        @Override
        public void onBackPressed() {
            next();                                      // back = the same as a tap
        }

        @Override
        protected void onPause() {
            super.onPause();
            try {
                if (mp != null && mp.isPlaying()) mp.pause();
            } catch (Throwable t) {
                // keep
            }
        }

        @Override
        protected void onResume() {
            super.onResume();
            immersive();
            try {
                if (stage == 0 && mp != null && surfaceOk && !mp.isPlaying()) mp.start();
            } catch (Throwable t) {
                // keep
            }
        }

        @Override
        protected void onDestroy() {
            stopVideo();
            h.removeCallbacksAndMessages(null);
            if (!done) showing = false;                  // closed some other way: show again next time
            super.onDestroy();
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
