package com.n3vgames.android;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;

/**
 * r136: Open Rails .sms engine sounds inside Trainz.
 *
 * An enginesound asset that carries Open Rails / MSTS .sms files (and their .wav files) is played by this
 * class exactly the way Open Rails plays it - the same parser rules, the same trigger logic
 * (Variable_Trigger Inc_Past / Dec_Past, Initial_Trigger, Discrete_Trigger, Random_Trigger), the same
 * curves (Volume / Frequency, Distance curves on squared distance), the same cue-point buffers and the same
 * per-stream play queue (OneShot, Loop, LoopRelease, Release, ReleaseWithJump) as Open Rails'
 * Sound.cs / ALSoundHelper.cs / OpenAL.cs, with a small OpenAL source emulation underneath.  The stock Trainz
 * engine sound of such an asset is a set of silent .wav files, so only this plays.
 *
 * Input: the HUD script writes "TTSSMS: <kuid> v= f= t= b= p= mr= bp= rv= tb= lb= cf= s=" lines into Trainz.log
 * (5 per second, only on change); the camera place comes from the TTSCAM lines (TTSHornPad.camD / camIn).
 * Speed is followed smoothly between those updates (slope of the last two), so pitch curves glide.
 */
public final class TTSSms {

    static final int RATE = 48000;
    static final int CHUNK = 480;                 // 10 ms per mix block
    static final double UPDATE_S = 0.02;          // the "sound process" runs every 20 ms (Open Rails: 50 ms)

    // ====================================================================================== inputs
    static final Object in = new Object();
    static String inKuid = "";
    static boolean inOn;
    static float inV, inVPrev, inSlope;           // m/s
    static long inVAt;                            // now() of the last speed
    static float inGameT = -1f;
    static float inF;                             // |applied force| / max-te  (0..1)
    static float inT;                             // throttle 0..1
    static float inB;                             // dynamic brake 0..1
    static float inCF;                            // curve force (N)
    static int inP = -1, inMR = -1, inBP = -1, inRV = -9999, inTB = -1, inLB = -1;
    static int inBC = -1;                         // r158: brake cylinder pressure x10 (HUD "bc="), -1 = not sent
    static boolean inDcc;
    static final int[] evq = new int[64];
    static int evH, evT;
    static float masterVol = 1f;

    static volatile boolean running;
    static volatile boolean paused;
    static Thread th;
    static AudioTrack track;
    static String loadedKuid;
    static volatile Engine eng;                  // the loaded sound set (null = nothing)
    static volatile Engine[] trk = new Engine[0]; // r139: the track sounds (.sms of the tracksound assets of the bogeys, one per kind), play with eng
    static volatile boolean gamePaused;          // r157: the game is paused (router "World, Paused" in Trainz.log)
    static volatile boolean nearAC;              // r156: the vehicle nearest to the camera is an AC coach (TTSNEAR)
    static String trkLine = "";                  // last TTSTRK line handled
    static long trkTry;                          // when a track sound that could not be found was tried

    static long testNs = -1;          // host test: a virtual clock (and no audio thread)

    static long now() {
        return testNs >= 0 ? testNs : System.nanoTime();
    }

    static void post(int ev) {
        synchronized (in) {
            evq[evH & 63] = ev;
            evH++;
            if (evH - evT > 64) evT = evH - 64;
        }
    }

    // ------------------------------------------------------------------ Trainz.log lines from the HUD
    /** called by the TTSHornPad watcher with every new piece of Trainz.log */
    static void fromLog(String s, File base, TTSHornPad.Watcher w) {
        trackFromLog(s, w);
        nearFromLog(s);
        pauseFromLog(s);
        int i = s.lastIndexOf("TTSSMS: ");
        int done = s.lastIndexOf("GSRouter::Done()");
        if (done > i) {
            if (inOn) {
                synchronized (in) {
                    inOn = false;
                }
                stop();
            }
            return;
        }
        if (i < 0) return;
        int e = s.indexOf('\n', i);
        if (e < 0) e = s.length();
        String line = s.substring(i + 8, e).trim();
        if (line.startsWith("off")) {
            if (inOn) {
                synchronized (in) {
                    inOn = false;
                }
                stop();
            }
            return;
        }
        String[] p = line.split("\\s+");
        if (p.length < 2) return;
        String kuid = TTSHornPad.Watcher.normKuid(p[0]);
        long now = now();
        synchronized (in) {
            float v = inV, f = inF, t = inT, b = inB, cf = inCF, gt = -1f;
            int pp = inP, mr = inMR, bp = inBP, rv = inRV, tb = inTB, lb = inLB, bc = inBC;
            boolean dcc = false;
            for (int k = 1; k < p.length; k++) {
                String a = p[k];
                int q = a.indexOf('=');
                if (q <= 0) continue;
                String key = a.substring(0, q);
                int val;
                try {
                    val = Integer.parseInt(a.substring(q + 1));
                } catch (NumberFormatException x) {
                    continue;
                }
                if (key.equals("v")) v = Math.abs(val) / 100f;
                else if (key.equals("f")) f = Math.min(1f, Math.abs(val) / 1000f);
                else if (key.equals("t")) t = val / 100f;
                else if (key.equals("b")) b = val / 100f;
                else if (key.equals("cf")) cf = val * 10f;
                else if (key.equals("s")) gt = val / 100f;
                else if (key.equals("p")) pp = val;
                else if (key.equals("mr")) mr = val;
                else if (key.equals("bp")) bp = val;
                else if (key.equals("bc")) bc = val;
                else if (key.equals("rv")) rv = val;
                else if (key.equals("tb")) tb = val;
                else if (key.equals("lb")) lb = val;
                else if (key.equals("d")) dcc = val != 0;
            }
            boolean first = !inOn || !kuid.equals(inKuid);
            // speed: keep the slope of the game's own clock so the value can glide between updates
            if (!first && gt > 0f && inGameT > 0f && gt > inGameT && gt - inGameT < 2f) {
                float dt = gt - inGameT;
                inSlope = (v - inV) / dt;
                if (inSlope > 4f) inSlope = 4f;
                if (inSlope < -4f) inSlope = -4f;
            } else inSlope = 0f;
            inVPrev = currentSpeed(now);
            inV = v;
            inVAt = now;
            inGameT = gt;
            inF = f;
            inT = t;
            inB = b;
            inCF = cf;
            inDcc = dcc;
            if (!first) events(pp, mr, bp, rv, tb, lb, t);
            inP = pp;
            inMR = mr;
            inBP = bp;
            inBC = bc;
            inRV = rv;
            inTB = tb;
            inLB = lb;
            inKuid = kuid;
            inOn = true;
            if (first) {
                evH = evT = 0;
                mrRise = 0;
                bpRise = 0;
                // the loco is there and powered (Open Rails: EnginePowerOn), compressor resting (CompressorOff)
                cancelLater();
                if (pp != 0) {
                    post(23);
                    // r158: a powered electric loco starts with its circuit breaker closed: the idling (blower /
                    // auxiliaries) loop of the .sms (CircuitBreakerClosed 152) runs from the start
                    post(157);
                    post(152);
                }
                post(13);
            }
        }
        if (!kuid.equals(loadedKuid)) {
            loadedKuid = kuid;
            final File dir = w == null ? null : w.findDir(kuid);
            load(dir, kuid);
        }
        start();
    }

    static int mrRise, bpRise;
    static float lastThr = -1f;

    /** Open Rails discrete events from the changes of the loco's controls (pantograph, compressor, brakes) */
    static void events(int pp, int mr, int bp, int rv, int tb, int lb, float t) {
        if (pp != inP && inP >= 0 && pp >= 0) {
            if (pp > 0 && inP == 0) {
                post(45);            // Pantograph1Up
                post(23);            // EnginePowerOn
                // r158: Open Rails electric loco power-up: the circuit breaker gets its closing authorisation,
                // closes after the pantograph is up (blower start sound, then the idling blower / auxiliaries loop)
                post(157);           // CircuitBreakerClosingAuthorizationOn
                later(151, 2.0);     // CircuitBreakerClosing
                later(152, 3.5);     // CircuitBreakerClosed
            } else if (pp == 0 && inP > 0) {
                cancelLater();
                post(150);           // CircuitBreakerOpen  (auxiliaries / idle loop released)
                post(158);           // CircuitBreakerClosingAuthorizationOff
                post(46);            // Pantograph1Down
                post(24);            // EnginePowerOff
            }
        }
        if (mr >= 0 && inMR >= 0) {          // compressor: main reservoir rising
            if (mr > inMR) {
                if (mrRise == 0) post(12);   // CompressorOn
                mrRise = 4;
            } else if (mrRise > 0) {
                mrRise--;
                if (mrRise == 0) post(13);   // CompressorOff
            }
        }
        // r158: brake events now from brakeTick() (brake cylinder / brake pipe, Open Rails rules)
        if (rv != inRV && inRV != -9999) post(15);                       // ReverserChange
        if (lastThr >= 0f && Math.abs(t - lastThr) > 0.004f) post(16);   // ThrottleChange
        lastThr = t;
        if (tb != inTB && inTB >= 0) post(17);                           // TrainBrakeChange
        if (lb != inLB && inLB >= 0) post(18);                           // EngineBrakeChange
    }

    // r158: Open Rails AirSinglePipe sound events, checked every 0.5 s on a change of more than a step:
    // brake CYLINDER rising 14 TrainBrakePressureIncrease, falling 54 ..Decrease, steady again 139 ..StoppedChanging;
    // brake PIPE rising 141, falling 142, steady 143.  Without a cylinder value (DCC mode, or the loco does not
    // give "brake-cylinder-pressure") the cylinder is modelled from the braking: deceleration with no throttle
    // fills it (about 3 s, like a real wagon cylinder), it empties when the braking ends.
    static float cylP, cylPrev = -1f, pipePrev = -1f;
    static double brkT;
    static boolean cylChanging, pipeChanging;
    static long brkLast;

    // r158: events that come a moment later (circuit breaker closing after the pantograph)
    static final int[] lateEv = new int[8];
    static final long[] lateAt = new long[8];
    static int lateN;

    static void later(int ev, double s) {
        if (lateN >= lateEv.length) return;
        lateEv[lateN] = ev;
        lateAt[lateN] = now() + (long) (s * 1e9);
        lateN++;
    }

    static void cancelLater() {
        lateN = 0;
    }

    // r158: loco (independent) brake: Open Rails EngineBrakePressureIncrease 21 / Decrease 22 / StoppedChanging 140
    static float engCyl, engPrev = -1f;
    static boolean engChanging;

    static void brakeTick(long now) {
        for (int i = 0; i < lateN; i++) {
            if (now >= lateAt[i]) {
                post(lateEv[i]);
                for (int k = i + 1; k < lateN; k++) {
                    lateEv[k - 1] = lateEv[k];
                    lateAt[k - 1] = lateAt[k];
                }
                lateN--;
                i--;
            }
        }
        if (!inOn) {
            cylPrev = pipePrev = engPrev = -1f;
            cylP = engCyl = 0f;
            engChanging = false;
            cylChanging = pipeChanging = false;
            brkLast = 0;
            return;
        }
        double dt = brkLast == 0 ? 0.0 : (now - brkLast) / 1e9;
        brkLast = now;
        if (dt > 0.5) dt = 0.5;
        float target;
        boolean real = inBC > 0 && !inDcc;
        if (real) {
            cylP = inBC / 10f;                     // the game's value
        } else {
            float a = inSlope;                     // m/s2, minus = slowing down
            float sp = currentSpeed(now);
            target = (sp > 0.2f && inT < 0.02f && a < -0.12f) ? Math.min(1f, -a / 0.9f) * 50f : 0f;
            float tau = target > cylP ? 1.2f : 2.0f;
            cylP += (target - cylP) * (float) Math.min(1.0, dt / tau);
        }
        if (inLB >= 0) {
            float tgt = inLB / 10f * 50f;
            engCyl += (tgt - engCyl) * (float) Math.min(1.0, dt / 1.0);
        }
        brkT += dt;
        if (brkT < 0.5) return;
        brkT = 0;
        if (inLB >= 0) {
            if (engPrev >= 0f) {
                if (Math.abs(engCyl - engPrev) > 0.6f) {
                    if (!engChanging) {
                        post(engCyl > engPrev ? 21 : 22);
                        engChanging = true;
                    }
                } else if (engChanging) {
                    engChanging = false;
                    post(140);
                }
            }
            engPrev = engCyl;
        }
        if (cylPrev >= 0f) {
            float step = real ? 0.25f : 0.6f;
            if (Math.abs(cylP - cylPrev) > step) {
                if (!cylChanging) {
                    post(cylP > cylPrev ? 14 : 54);
                    cylChanging = true;
                }
            } else if (cylChanging) {
                cylChanging = false;
                post(139);
            }
        }
        cylPrev = cylP;
        if (inBP > 0 && !inDcc) {
            float bp = inBP / 10f;
            if (pipePrev >= 0f) {
                if (Math.abs(bp - pipePrev) > 0.25f) {
                    if (!pipeChanging) {
                        post(bp > pipePrev ? 141 : 142);
                        pipeChanging = true;
                    }
                } else if (pipeChanging) {
                    pipeChanging = false;
                    post(143);
                }
            }
            pipePrev = bp;
        }
    }

    static float currentSpeed(long now) {
        float dt = (now - inVAt) / 1e9f;
        if (dt < 0f) dt = 0f;
        if (dt > 0.35f) dt = 0.35f;                 // never guess further than ~2 updates
        float v = inV + inSlope * dt;
        return v < 0f ? 0f : v;
    }

    // ====================================================================================== start / stop
    static synchronized void start() {
        if (eng == null && trk.length == 0) return;   // r154: a track sound plays also behind a loco without .sms
        running = true;
        if (testNs >= 0) return;
        if (th != null && th.isAlive()) {
            synchronized (in) {
                in.notifyAll();
            }
            return;
        }
        th = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "TTSSms");
        th.setDaemon(true);
        th.start();
    }

    static volatile boolean needReset;

    static synchronized void stop() {
        running = false;
        gamePaused = false;
        needReset = true;            // the audio thread resets the streams (never while it mixes them)
        loadedKuid = null;
    }

    static void setPaused(boolean p) {
        paused = p;
        if (!p) {
            synchronized (in) {
                in.notifyAll();
            }
        }
    }

    /** r139: "TTSTRK: <track-sound kuid> [<kuid> ...]" (the bogeys of the train) -> the first one with a .sms plays */
    static void trackFromLog(String s, TTSHornPad.Watcher w) {
        try {
            int i = s.lastIndexOf("TTSTRK: ");
            if (i < 0) return;
            int e = s.indexOf('\n', i);
            if (e < 0) e = s.length();
            String line = s.substring(i + 8, e).trim();
            long now = System.currentTimeMillis();
            boolean same = line.equals(trkLine);
            if (same && (trk.length > 0 || line.equals("none") || now - trkTry < 20000)) return;
            trkLine = line;
            trkTry = now;
            if (line.equals("none") || line.length() == 0 || w == null) {
                trk = new Engine[0];
                return;
            }
            ArrayList<Engine> list = new ArrayList<Engine>();
            ArrayList<String> seen = new ArrayList<String>();
            StringBuilder what = new StringBuilder();
            for (String q : line.split("\\s+")) {
                String k = TTSHornPad.Watcher.normKuid(q);
                if (k.length() == 0 || seen.contains(k)) continue;
                seen.add(k);
                File dir = w.findDir(k);
                if (dir != null && dir.isDirectory()) {
                    // r158: a folder with an AC .sms ("ac" in its name) and a non-AC one: both, the coach next to
                    // the camera decides which plays; else the listed .sms as before
                    Engine na = Engine.open(dir, 0), ac = Engine.open(dir, 1);
                    Engine[] got = (na != null && ac != null) ? new Engine[]{na, ac} : new Engine[]{Engine.open(dir)};
                    for (Engine en : got) {
                        if (en == null) continue;
                        if (got.length == 1) en.acRole = -1;
                        en.isTrack = true;
                        list.add(en);
                        what.append(' ').append(k).append(en.acRole == 1 ? " AC" : en.acRole == 0 ? " non-AC" : "").append(" streams=").append(en.streamCount());
                    }
                }
            }
            if (!list.isEmpty() || !same) TTSHornPad.log("sms track " + line + " -> " + (list.isEmpty() ? "(no .sms)" : what.toString().trim()));
            trk = list.toArray(new Engine[0]);
            if (!list.isEmpty()) {
                synchronized (in) {
                    in.notifyAll();
                }
                if (inOn) start();                     // r154: the loco's own sound may be the game's (no .sms)
            }
        } catch (Throwable t) {
            TTSHornPad.log("sms track: " + t);
        }
    }

    /** r157: the game's pause (Esc / pause button): every .sms sound stops until "World, Unpaused" */
    static void pauseFromLog(String s) {
        int pi = s.lastIndexOf("message World, Paused");
        int ui = s.lastIndexOf("message World, Unpaused");
        int di = s.lastIndexOf("GSRouter::Done()");
        if (pi < 0 && ui < 0 && di < 0) return;
        boolean p = pi > ui && pi > di;
        if (p == gamePaused) return;
        gamePaused = p;
        TTSHornPad.log("sms: game " + (p ? "paused" : "running"));
        if (!p) {
            synchronized (in) {
                in.notifyAll();
            }
        }
    }

    /** r156: "TTSNEAR: <vehicle name> | <username>" - the vehicle nearest to the camera */
    static void nearFromLog(String s) {
        int i = s.lastIndexOf("TTSNEAR: ");
        if (i < 0) return;
        int e = s.indexOf('\n', i);
        if (e < 0) e = s.length();
        String name = s.substring(i + 9, e).trim();
        // r158: an AC coach is one whose config description says "use ac.sms" (HUD: acsms=1), not a name with AC
        // in it (that made locos and AC EMU / local trains play the AC sound set too)
        boolean ac = name.contains("acsms=1");
        if (ac != nearAC) TTSHornPad.log("near: " + name + (ac ? " = AC coach" : " = non-AC"));
        nearAC = ac;
    }

    /**
     * r156: an AC coach = "AC" in its name as a word of its own or with a number ("AC", "3AC", "AC3", "2-AC",
     * "A.C."), not inside a word ("COACH") and not after "NON" ("NON AC", "NON-AC").
     */
    static boolean isAC(String name) {
        String u = name.toUpperCase(Locale.US).replace("A.C.", "AC").replace("A/C", "AC");
        String[] w = u.split("[^A-Z0-9]+");
        for (int k = 0; k < w.length; k++) {
            String t = w[k];
            String core = t.replaceAll("^[0-9]+", "").replaceAll("[0-9]+$", "");
            if (!core.equals("AC")) continue;
            if (k > 0 && w[k - 1].equals("NON")) continue;
            return true;
        }
        return false;
    }

    static boolean active() {
        return running && eng != null;
    }

    static void load(File dir, String kuid) {
        Engine e = null;
        try {
            if (dir != null && dir.isDirectory()) e = Engine.open(dir);
        } catch (Throwable t) {
            TTSHornPad.log("sms load: " + t);
        }
        eng = e;
        TTSHornPad.log("sms " + kuid + " -> " + dir + (e == null ? " (no .sms)" : " streams=" + e.streamCount()));
    }

    static void loop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Throwable t) {
            // keep normal
        }
        float[] acc = new float[CHUNK];
        float[] acc2 = new float[CHUNK];
        float[] lim = new float[CHUNK];
        Limiter limiter = new Limiter();
        short[] out = new short[CHUNK * 2];
        double tick = 0;
        while (true) {
            try {
                if (needReset) {
                    needReset = false;
                    Engine r = eng;
                    if (r != null) r.reset();
                    for (Engine rt : trk) rt.reset();
                }
                synchronized (in) {
                    while (!running || paused || gamePaused || (eng == null && trk.length == 0)) {
                        if (track != null) {
                            try {
                                track.pause();
                                track.flush();
                            } catch (Throwable t) {
                                // ignore
                            }
                        }
                        in.wait();
                    }
                }
                Engine e = eng;
                Engine[] tks = trk;
                if (e == null && tks.length == 0) continue;
                if (tick <= 0) {
                    long tn = now();
                    int[] evs;
                    synchronized (in) {
                        brakeTick(tn);                        // r158
                        int n = evH - evT;
                        evs = new int[n];
                        for (int i = 0; i < n; i++) evs[i] = evq[(evT + i) & 63];
                        evT = evH;
                    }
                    if (e != null) e.control(tn, evs);
                    for (Engine tk : tks) tk.control(tn, evs);
                    tick += UPDATE_S;
                }
                tick -= (double) CHUNK / RATE;
                if (e != null) e.render(acc, CHUNK);
                else Arrays.fill(acc, 0f);
                for (Engine tk : tks) {
                    tk.render(acc2, CHUNK);
                    for (int i = 0; i < CHUNK; i++) acc[i] += acc2[i];
                }
                limiter.run(acc, lim);
                for (int i = 0; i < CHUNK; i++) {
                    short sv = pcm16(lim[i]);
                    out[2 * i] = sv;
                    out[2 * i + 1] = sv;
                }
                if (track == null) track = newTrack();
                if (track == null) {
                    Thread.sleep(200);
                    continue;
                }
                if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
                int wr = track.write(out, 0, out.length);
                if (wr < 0 || track.getState() != AudioTrack.STATE_INITIALIZED) {
                    // r158: the Android audio output went away (screen recorder, a call, Bluetooth / route
                    // change, audio server restart): a dead track takes the samples silently forever - make a new one
                    TTSHornPad.log("sms audio: track lost (" + wr + "), new one");
                    try {
                        track.release();
                    } catch (Throwable t2) {
                        // gone
                    }
                    track = null;
                    Thread.sleep(50);
                }
            } catch (Throwable t) {
                TTSHornPad.log("sms audio: " + t);
                try {
                    if (track != null) track.release();
                } catch (Throwable t2) {
                    // gone
                }
                track = null;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    static AudioTrack newTrack() {
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            return new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 4 * 150 / 1000), AudioTrack.MODE_STREAM);   // r158: 150 ms (was 40): no underrun crackle / gaps when the game is busy
        } catch (Throwable t) {
            TTSHornPad.log("sms track: " + t);
            return null;
        }
    }

    /**
     * Look-ahead limiter (no waveshaping, so no crackle): output is one block late; each block's gain ramps
     * between two values that already keep THAT block under the ceiling (-1 dBFS), and recovers slowly.
     */
    static final class Limiter {
        static final float CEIL = 0.5f;        // r158: -6 dBFS (was -1): this track + the horn pad + the game are summed by Android
        final float[] prev = new float[CHUNK];
        float needPrev = 1f, g = 1f;
        boolean have;

        static float need(float[] x) {
            float pk = 0f;
            for (float v : x) {
                float a = v < 0 ? -v : v;
                if (a > pk) pk = a;
            }
            return pk > CEIL ? CEIL / pk : 1f;
        }

        /** takes the newest block, writes the (one block older) limited block into out */
        void run(float[] in, float[] out) {
            float needNow = need(in);
            if (!have) {
                System.arraycopy(in, 0, prev, 0, CHUNK);
                needPrev = needNow;
                have = true;
                java.util.Arrays.fill(out, 0f);
                return;
            }
            float target = Math.min(needPrev, needNow);
            if (target > g) target = Math.min(target, g * 1.006f);        // ~ +5 dB per second back up
            float dg = (target - g) / CHUNK;
            float gg = g;
            for (int i = 0; i < CHUNK; i++) {
                gg += dg;
                float v = prev[i] * Math.min(gg, needPrev);
                out[i] = v;
            }
            g = target;
            System.arraycopy(in, 0, prev, 0, CHUNK);
            needPrev = needNow;
        }
    }

    static short pcm16(float x) {
        int v = (int) (x * 32767f);
        if (v > 32767) v = 32767;
        if (v < -32768) v = -32768;
        return (short) v;
    }

    // ====================================================================================== the engine
    /** One loaded sound set: every .sms of the asset folder, each an Open Rails SoundSource. */
    static final class Engine {
        final ArrayList<Source> sources = new ArrayList<Source>();
        final HashMap<String, Piece> pieces = new HashMap<String, Piece>();   // SoundItem.AllPieces
        final HashMap<String, Wav> wavs = new HashMap<String, Wav>();
        final File dir;
        HashMap<String, File> byName;
        float v2 = 0f;               // Open Rails Variable2 (0..100, slewed)
        double fZero;
        float dopp = 1f;
        long lastNs;
        double gameTime;             // seconds of sound time (for Random_Trigger and the 0.2 s stop rule)
        float volume = 1f;           // tts-sms-volume from config.txt

        Engine(File d) {
            dir = d;
        }

        int streamCount() {
            int n = 0;
            for (Source s : sources) n += s.streams.size();
            return n;
        }

        static Engine open(File dir) throws Exception {
            return open(dir, -1);
        }

        /** r158: acMode -1 = the .sms as listed; 0 = only the non-AC ones; 1 = only the AC ones of the folder ("ac" in the name) */
        static Engine open(File dir, int acMode) throws Exception {
            File[] fs = dir.listFiles();
            if (fs == null) return null;
            Engine e = new Engine(dir);
            e.byName = new HashMap<String, File>();
            for (File f : fs) e.byName.put(f.getName().toLowerCase(Locale.US), f);
            // which .sms: config.txt "tts-sms" list, else every .sms of the folder (sorted)
            ArrayList<String> want = new ArrayList<String>();
            File cfg = e.byName.get("config.txt");
            String cfgText = cfg == null ? "" : readText(cfg);
            for (String l : cfgText.split("\n")) {
                String t = l.trim();
                String low = t.toLowerCase(Locale.US);
                if (low.startsWith("tts-sms-volume")) {
                    try {
                        e.volume = Float.parseFloat(t.substring(14).replace("\"", "").trim());
                    } catch (NumberFormatException x) {
                        // keep 1
                    }
                } else if (low.startsWith("tts-sms ") || low.startsWith("tts-sms\t")) {
                    for (String q : t.substring(7).split("[,;]")) {
                        String n = q.replace("\"", "").trim().toLowerCase(Locale.US);
                        if (n.length() == 0) continue;
                        if (!e.byName.containsKey(n) && e.byName.containsKey(n + ".sms")) n = n + ".sms";  // r154: name given without .sms
                        if (!e.byName.containsKey(n)) TTSHornPad.log("sms: " + dir.getName() + " config tts-sms \"" + n + "\" not in the folder");
                        want.add(n);
                    }
                }
            }
            if (want.isEmpty() || acMode == 1) {
                if (acMode == 1) want.clear();
                ArrayList<String> all = new ArrayList<String>(e.byName.keySet());
                java.util.Collections.sort(all);
                for (String n : all) if (n.endsWith(".sms")) want.add(n);
            }
            if (acMode >= 0) {
                ArrayList<String> keep = new ArrayList<String>();
                for (String n : want) if (isAC(n.replace(".sms", "")) == (acMode == 1)) keep.add(n);
                want = keep;
                e.acRole = acMode;
            }
            for (String n : want) {
                File f = e.byName.get(n);
                if (f == null) continue;
                Source s = Source.parse(e, f);
                if (s != null && !s.streams.isEmpty()) e.sources.add(s);
            }
            if (e.sources.isEmpty()) return null;
            // map every wav that a trigger may play now (not on the audio thread)
            for (Source s : e.sources)
                for (Stream st : s.streams)
                    for (Trig tr : st.trigs)
                        if (tr.cmd != null && tr.cmd.files != null)
                            for (String fn : tr.cmd.files) e.wav(fn);
            return e;
        }

        Wav wav(String name) {
            String k = name.toLowerCase(Locale.US);
            if (wavs.containsKey(k)) return wavs.get(k);
            Wav w0 = wavFind(k);
            if (w0 == null) TTSHornPad.log("sms: " + dir.getName() + " wav missing: " + name);   // r158
            return w0;
        }

        Wav wavFind(String k) {
            Wav w = null;
            File f = byName.get(k);
            if (f == null) {
                // a path inside the sms ("..\\sound\\x.wav"): use the file name
                int sl = Math.max(k.lastIndexOf('\\'), k.lastIndexOf('/'));
                if (sl >= 0) f = byName.get(k.substring(sl + 1));
            }
            if (f != null) w = Wav.shared(f);
            wavs.put(k, w);
            return w;
        }

        void reset() {
            for (Source s : sources) s.reset();
            v2 = 0f;
        }

        /** one run of the Open Rails sound process */
        int[] control(long now, int[] share) {
            double dt = lastNs == 0 ? UPDATE_S : (now - lastNs) / 1e9;
            if (dt > 0.2) dt = 0.2;
            lastNs = now;
            gameTime += dt;
            float thr, f;
            boolean dcc;
            int[] evs;
            synchronized (in) {
                vars.speed = currentSpeed(now);
                thr = inT;
                f = inF;
                dcc = inDcc;
                vars.v1 = inT * 100f;
                vars.v3 = inB;
                vars.curveForce = inCF;
                if (share != null) evs = share;
                else {
                    int n = evH - evT;
                    evs = new int[n];
                    for (int i = 0; i < n; i++) evs[i] = evq[(evT + i) & 63];
                    evT = evH;
                }
            }
            // a loco whose force numbers cannot be read (still 0 with the throttle open): the throttle stands in
            if (thr > 0.05f && f < 0.002f) fZero += dt;
            else fZero = 0;
            if (fZero > 1.5) f = thr;
            // MSTSElectricLocomotive.UpdateSoundVariables: V2 = |F| / Fmax * 100, at most +-2 per frame (60 fps)
            boolean power = dcc ? f > 0.001f : thr > 0f;
            if (!power) v2 = 0f;
            else {
                float dV = f * 100f - v2;
                float max = (float) (2.0 * 60.0 * dt);
                if (dV > max) dV = max;
                if (dV < -max) dV = -max;
                v2 += dV;
            }
            vars.v2 = v2;
            // camera
            float d = TTSHornPad.camD;
            int ci = TTSHornPad.camIn;
            // r158: the pass of a standing camera, followed smoothly between the 5-per-second HUD lines:
            // s = along-track offset of the loco (minus = still coming), b = passing distance, u = speed
            float ps = 0f, pb = 0f, pu = 0f;
            boolean pass = false;
            if (ci == 0 && TTSHornPad.passU >= 6f) {
                double age = (now - TTSHornPad.passT) / 1e9;
                if (age >= 0 && age < 1.5) {
                    pu = TTSHornPad.passU;
                    pb = Math.max(2f, TTSHornPad.passB);
                    ps = TTSHornPad.passS + pu * (float) age;
                    pass = true;
                }
            }
            float vr = 0f;                                    // range rate, minus = coming closer
            if (isTrack) {
                // r155/r158: a track sound = the bogeys next to the camera: the nearest vehicle.  While the train
                // runs past the camera that is a coach right beside it: distance = the passing distance, no
                // Doppler (it moves across, not towards); before the loco / after the last coach: like a source there
                float n = TTSHornPad.camN;
                if (ci != 1 && n >= 0f) {
                    if (pass) {
                        float side = (float) Math.sqrt(pb * pb + 144f);
                        if (ps < 0f) {
                            d = (float) Math.sqrt(ps * ps + pb * pb);
                            vr = pu * ps / d;
                        } else if (n <= side) {
                            d = pb;
                        } else {
                            d = n;
                            float sn = (float) Math.sqrt(Math.max(0f, n * n - pb * pb));
                            vr = pu * sn / Math.max(1f, n);
                        }
                    } else d = n;
                }
            } else if (pass) {
                d = (float) Math.sqrt(ps * ps + pb * pb);
                vr = pu * ps / d;
            }
            if (d < 0f) d = 5f;
            // smooth distance: a nearest-coach change or a 5-per-second step must not jump the volume
            if (dSm < 0f || Math.abs(d - dSm) > 150f) dSm = d;
            else dSm += (d - dSm) * (float) Math.min(1.0, UPDATE_S / 0.25);
            d = dSm;
            vars.dist = d;
            vars.cam = ci;      // 0 outside, 1 cab, 2 passenger seat
            // r154: a track sound .sms made only for the outside view (Activation ExternalCam): in the cab / a coach
            // the game's own track sound still plays, so here it plays too, heard through the body (muffled, -6 dB)
            // r156: heard from inside: loco cab and non-AC coach (open windows) only a little quieter, an AC
            // coach (sealed) much quieter and duller.  An .sms with its own cab / passenger streams keeps them;
            // in an AC coach it is still taken down (also the "ac ext" .sms).
            muffleTo = 0f;
            if (isTrack && ci != 0) {
                boolean own = false;
                for (Source s : sources) if (ci == 1 ? s.actCab : s.actPass) own = true;
                if (!own) vars.cam = 0;
                if (ci == 2 && nearAC) {
                    muffleTo = 1f;
                    mGainTo = own ? 0.65f : 0.35f;      // an own passenger stream is already at half (pass-through)
                    mFc = 900f;
                } else if (!own) {
                    muffleTo = 1f;
                    mGainTo = ci == 1 ? 0.75f : 0.85f;
                    mFc = ci == 1 ? 3500f : 5000f;
                }
            }
            // r158: Doppler of a moving source and a standing listener, f' = f c / (c + vr) (vr = range rate,
            // minus = coming closer), only for a standing camera (r157); glides 0.12 s
            if (vr > 90f) vr = 90f;
            if (vr < -90f) vr = -90f;
            float target = 343f / (343f + vr);
            dopp += (target - dopp) * (float) Math.min(1.0, dt / 0.12);
            // r158: AC / non-AC sound set of a track sound asset: the one of the coach next to the camera plays
            if (acRole >= 0) roleTo = ((acRole == 1) == nearAC) ? 1f : 0f;
            for (int ev : evs)
                for (Source s : sources) s.handleEvent(ev);
            for (Source s : sources) s.update(this);
            return evs;
        }

        final Vars vars = new Vars();

        boolean isTrack;            // r154: a track sound (bogeys), not the loco
        float muffleTo, muffle, lp; // r154: 0 = open, 1 = heard through the cab / coach body
        float mGainTo = 0.5f, mGain = 0.5f, mFc = 1400f;   // r156: gain / low-pass of that body (cab, coach, AC coach)
        float dSm = -1f;            // r158: smoothed distance
        int acRole = -1;            // r158: -1 one sound set, 0 = the non-AC set, 1 = the AC set of a track asset
        float roleTo = 1f, roleG = 1f;

        void render(float[] acc, int n) {
            Arrays.fill(acc, 0f);
            float mv = masterVol * volume * MASTER;
            for (Source s : sources)
                for (Stream st : s.streams)
                    if (st.al != null) st.al.render(acc, n, mv, dopp);
            if (acRole >= 0 && !(roleG == 1f && roleTo == 1f)) {     // r158: AC / non-AC cross-fade 0.4 s
                float rk = 1f / (0.4f * RATE);
                for (int i = 0; i < n; i++) {
                    if (roleG < roleTo) roleG = Math.min(roleTo, roleG + rk);
                    else if (roleG > roleTo) roleG = Math.max(roleTo, roleG - rk);
                    acc[i] *= roleG;
                }
            }
            if (muffle == 0f && muffleTo == 0f) {
                lp = 0f;
                return;
            }
            float a = 1f - (float) Math.exp(-2.0 * Math.PI * mFc / RATE);      // one-pole low-pass (r156: per body)
            float gk = 10f / RATE;
            float dm = muffleTo > muffle ? 10f / RATE : muffleTo < muffle ? -10f / RATE : 0f;   // 0.1 s glide
            float m = muffle, y = lp;
            for (int i = 0; i < n; i++) {
                if ((dm > 0 && m < muffleTo) || (dm < 0 && m > muffleTo)) m += dm;
                if (m < 0f) m = 0f;
                if (m > 1f) m = 1f;
                float x = acc[i];
                y += a * (x - y);
                mGain += (mGainTo - mGain) * gk;
                acc[i] = x * (1f - m) + y * mGain * m;
            }
            muffle = m;
            lp = y;
        }
    }

    static final float MASTER = 0.5f;          // Open Rails sources (<= 1 each) to our track: -6 dB headroom

    static final class Vars {
        float speed, v1, v2, v3, curveForce, dist;
        int cam;
    }

    static String readText(File f) {
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 4 << 20)];
            FileInputStream inp = new FileInputStream(f);
            int got = 0;
            try {
                while (got < b.length) {
                    int n = inp.read(b, got, b.length - got);
                    if (n <= 0) break;
                    got += n;
                }
            } finally {
                inp.close();
            }
            if (got >= 2 && (b[0] & 255) == 0xFF && (b[1] & 255) == 0xFE) return new String(b, 2, got - 2, "UTF-16LE");
            if (got >= 2 && (b[0] & 255) == 0xFE && (b[1] & 255) == 0xFF) return new String(b, 2, got - 2, "UTF-16BE");
            if (got >= 3 && (b[0] & 255) == 0xEF && (b[1] & 255) == 0xBB && (b[2] & 255) == 0xBF)
                return new String(b, 3, got - 3, "UTF-8");
            // UTF-16LE without a BOM ("S\0I\0...")
            if (got >= 4 && b[1] == 0 && b[3] == 0) return new String(b, 0, got, "UTF-16LE");
            return new String(b, 0, got, "ISO-8859-1");
        } catch (Throwable t) {
            return "";
        }
    }

    // ====================================================================================== .sms parser (STF)
    static final class Tok {
        final ArrayList<String> t = new ArrayList<String>();
        int p;

        Tok(String s) {
            int n = s.length(), i = 0;
            while (i < n) {
                char c = s.charAt(i);
                if (c <= ' ') {
                    i++;
                } else if (c == '(' || c == ')') {
                    t.add(String.valueOf(c));
                    i++;
                } else if (c == '"') {
                    StringBuilder sb = new StringBuilder();
                    i++;
                    while (i < n && s.charAt(i) != '"') {
                        char d = s.charAt(i);
                        if (d == '\\' && i + 1 < n && s.charAt(i + 1) == 'n') {
                            sb.append('\n');
                            i += 2;
                            continue;
                        }
                        sb.append(d);
                        i++;
                    }
                    i++;
                    // "a" + "b" concatenation
                    t.add("\"" + sb.toString());
                } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                    while (i < n && s.charAt(i) != '\n') i++;
                } else {
                    int a = i;
                    while (i < n && s.charAt(i) > ' ' && s.charAt(i) != '(' && s.charAt(i) != ')' && s.charAt(i) != '"') i++;
                    t.add(s.substring(a, i));
                }
            }
        }

        boolean end() {
            return p >= t.size();
        }

        String peek() {
            return p < t.size() ? t.get(p) : ")";
        }

        String next() {
            return p < t.size() ? t.get(p++) : ")";
        }

        /** after a block name: expects '(' */
        boolean open() {
            if (peek().equals("(")) {
                p++;
                return true;
            }
            return false;
        }

        /** skips to the matching ')' of a block whose '(' was already read */
        void skipRest() {
            int depth = 1;
            while (!end() && depth > 0) {
                String s = next();
                if (s.equals("(")) depth++;
                else if (s.equals(")")) depth--;
            }
        }

        /** skips "name ( ... )" when the name was already read */
        void skipBlock() {
            if (open()) skipRest();
        }

        float num(int units) {
            String s = next();
            if (s.equals("(") || s.equals(")")) {
                p--;
                return 0f;
            }
            return parseNum(s, units);
        }

        int integer() {
            String s = next();
            try {
                return (int) Float.parseFloat(s);
            } catch (NumberFormatException e) {
                if (s.equals(")") || s.equals("(")) p--;
                return 0;
            }
        }

        /** a value block "( x )" */
        float numBlock(int units) {
            if (!open()) return 0f;
            float v = num(units);
            skipRest();
            return v;
        }
    }

    static final int U_NONE = 0, U_SPEED = 1, U_DIST = 2, U_FORCE = 3;

    static float parseNum(String s, int units) {
        String low = s.toLowerCase(Locale.US);
        int i = 0;
        while (i < low.length() && "+-0123456789.eE".indexOf(low.charAt(i)) >= 0) {
            // stop at an 'e' that starts a unit word ("10kmh" has none, but keep 1e3 working)
            if ((low.charAt(i) == 'e') && (i + 1 >= low.length() || "+-0123456789".indexOf(low.charAt(i + 1)) < 0)) break;
            i++;
        }
        float v;
        try {
            v = Float.parseFloat(low.substring(0, i));
        } catch (NumberFormatException e) {
            return 0f;
        }
        String u = low.substring(i);
        if (units == U_SPEED) {
            if (u.equals("kmh") || u.equals("km/h") || u.equals("kph")) v /= 3.6f;
            else if (u.equals("mph")) v *= 0.44704f;
        } else if (units == U_DIST) {
            if (u.equals("km")) v *= 1000f;
            else if (u.equals("cm")) v *= 0.01f;
            else if (u.equals("mm")) v *= 0.001f;
            else if (u.equals("ft")) v *= 0.3048f;
            else if (u.equals("in")) v *= 0.0254f;
            else if (u.equals("mi")) v *= 1609.344f;
        } else if (units == U_FORCE) {
            if (u.equals("kn")) v *= 1000f;
            else if (u.equals("lbf")) v *= 4.44822f;
        }
        return v;
    }

    // sound variables (Open Rails SoundVariable.ControlType)
    static final int V_NONE = 0, V_DIST = 1, V_SPEED = 2, V_V1 = 3, V_V2 = 4, V_V3 = 5, V_BRAKECYL = 6, V_CURVE = 7, V_OTHER = 8;

    static int varOf(String name) {
        String s = name.toLowerCase(Locale.US);
        if (s.equals("distance")) return V_DIST;
        if (s.equals("speed")) return V_SPEED;
        if (s.equals("variable1")) return V_V1;
        if (s.equals("variable2")) return V_V2;
        if (s.equals("variable3")) return V_V3;
        if (s.equals("brakecyl")) return V_BRAKECYL;
        if (s.equals("curveforce")) return V_CURVE;
        if (s.length() > 0) return V_OTHER;
        return V_NONE;
    }

    static int unitsOf(int var) {
        if (var == V_DIST) return U_DIST;
        if (var == V_SPEED) return U_SPEED;
        if (var == V_CURVE) return U_FORCE;
        return U_NONE;
    }

    static final class Curve {
        int var;
        float[] x, y;

        static Curve parse(Tok k) {
            Curve c = new Curve();
            String type = k.next().toLowerCase(Locale.US);
            if (type.endsWith("controlled")) c.var = varOf(type.substring(0, type.length() - 10));
            if (c.var == V_NONE) {
                k.skipRest();
                return null;
            }
            int units = unitsOf(c.var);
            while (!k.end()) {
                String w = k.next();
                if (w.equals(")")) break;
                String lw = w.toLowerCase(Locale.US);
                if (lw.equals("curvepoints")) {
                    if (!k.open()) continue;
                    int n = k.integer();
                    if (n < 0) n = 0;
                    c.x = new float[n];
                    c.y = new float[n];
                    for (int i = 0; i < n; i++) {
                        c.x[i] = k.num(units);
                        if (c.var == V_DIST) c.x[i] = c.x[i] >= 0 ? c.x[i] * c.x[i] : -c.x[i] * c.x[i];
                        c.y[i] = k.num(U_NONE);
                    }
                    k.skipRest();
                } else if (!w.equals("(")) {
                    k.skipBlock();
                } else k.skipRest();
            }
            return c;
        }

        /** SoundStream.Interpolate */
        float at(float v) {
            if (x == null || x.length == 0) return 1f;
            if (x.length < 2) return y[0];
            if (v < x[0]) return y[0];
            if (v > x[x.length - 1]) return y[y.length - 1];
            int i = 1;
            while (i < x.length - 1 && x[i] < v) ++i;
            float dx = x[i] - x[i - 1];
            float rx = dx == 0f ? 0f : (v - x[i - 1]) / dx;
            return y[i - 1] + rx * (y[i] - y[i - 1]);
        }
    }

    // sound commands
    static final int C_NOP = 0, C_ONESHOT = 1, C_LOOP = 2, C_LOOPREL = 3, C_REL = 4, C_RELJUMP = 5, C_SETVOL = 6,
            C_DISABLE = 7, C_ENABLE = 8;
    // play modes (ALSoundHelper.PlayMode)
    static final int M_ONESHOT = 0, M_LOOP = 1, M_LOOPREL = 2, M_REL = 3, M_RELJUMP = 4;
    // play states
    static final int S_NOP = 0, S_NEW = 1, S_PLAYING = 2;

    static final class Cmd {
        int type;
        String[] files;
        boolean random;
        int iFile;
        float value;
        int index;

        static boolean isCmd(String w) {
            String s = w.toLowerCase(Locale.US);
            return s.equals("playoneshot") || s.equals("startloop") || s.equals("startlooprelease")
                    || s.equals("releaselooprelease") || s.equals("releaseloopreleasewithjump")
                    || s.equals("setstreamvolume") || s.equals("disabletrigger") || s.equals("enabletrigger");
        }

        static Cmd parse(String name, Tok k) {
            Cmd c = new Cmd();
            String s = name.toLowerCase(Locale.US);
            if (s.equals("playoneshot")) c.type = C_ONESHOT;
            else if (s.equals("startloop")) c.type = C_LOOP;
            else if (s.equals("startlooprelease")) c.type = C_LOOPREL;
            else if (s.equals("releaselooprelease")) c.type = C_REL;
            else if (s.equals("releaseloopreleasewithjump")) c.type = C_RELJUMP;
            else if (s.equals("setstreamvolume")) c.type = C_SETVOL;
            else if (s.equals("disabletrigger")) c.type = C_DISABLE;
            else if (s.equals("enabletrigger")) c.type = C_ENABLE;
            if (!k.open()) return c;
            if (c.type == C_ONESHOT || c.type == C_LOOP || c.type == C_LOOPREL) {
                ArrayList<String> fl = new ArrayList<String>();
                int n = k.integer();
                while (!k.end()) {
                    String w = k.next();
                    if (w.equals(")")) break;
                    String lw = w.toLowerCase(Locale.US);
                    if (lw.equals("file")) {
                        if (!k.open()) continue;
                        String f = k.next();
                        if (f.startsWith("\"")) f = f.substring(1);
                        fl.add(f);
                        k.skipRest();
                    } else if (lw.equals("selectionmethod")) {
                        if (!k.open()) continue;
                        String m = k.next().toLowerCase(Locale.US);
                        c.random = m.startsWith("random");
                        k.skipRest();
                    } else if (!w.equals("(")) k.skipBlock();
                    else k.skipRest();
                }
                c.files = fl.toArray(new String[0]);
                if (n == 0 && c.files.length == 0) c.type = C_NOP;
            } else if (c.type == C_SETVOL) {
                c.value = k.num(U_NONE);
                k.skipRest();
            } else if (c.type == C_DISABLE || c.type == C_ENABLE) {
                c.index = k.integer();
                k.skipRest();
            } else {
                k.skipRest();
            }
            return c;
        }
    }

    // triggers
    static final int T_NULL = 0, T_INITIAL = 1, T_VAR = 2, T_DISCRETE = 3, T_RANDOM = 4;

    static final class Trig {
        int kind;
        Cmd cmd;
        boolean enabled = true, signaled;
        // variable
        int var;
        boolean inc;
        float thr, startValue;
        boolean isBelow;
        // discrete
        int event;
        boolean triggered;
        // random
        float dMin, dMax, vMin = 1f, vMax = 1f;
        double atS;
    }

    static final class Stream {
        Source src;
        float volume, fileVolume = 1f;
        final ArrayList<Trig> trigs = new ArrayList<Trig>();
        final ArrayList<Curve> vol = new ArrayList<Curve>();
        Curve freq;
        AL al;
        Trig initial;
        final ArrayList<Trig> varTrigs = new ArrayList<Trig>();
        boolean releasedWithJump;
        Trig lastTriggered;
        boolean repeated;
        boolean frequent;

        void setFreqAndVolume(Vars v) {
            TTSSms.setFreqAndVolume(this, v);
        }

        void update(Vars v, Engine e) {
            streamUpdate(this, v, e);
        }

        float read(int var, Vars v) {
            switch (var) {
                case V_DIST: return src.distSq;
                case V_SPEED: return v.speed;
                case V_V1: return v.v1;
                case V_V2: return v.v2;
                case V_V3: return v.v3;
                case V_CURVE: return v.curveForce;
                default: return 0f;
            }
        }
    }

    // ------------------------------------------------------------------ one .sms = Open Rails SoundSource
    static final class Source {
        final ArrayList<Stream> streams = new ArrayList<Stream>();
        boolean actCab, actExt, actPass, deCab, deExt, dePass;
        float actDist = 1000f, deDist = 1000f;      // Activation/Deactivation default distance 1000 (sms)
        float volume = 1f;
        boolean stereo, ignore3d;
        float rolloff;
        boolean active, wasOut = true;
        boolean hornChecked, partial;       // r141: the horn / bell / sander streams stay audible in a passenger view
        final HashSet<Stream> keep = new HashSet<Stream>();
        float distSq;
        String name;
        Engine eng;
        final Random rnd = new Random();

        static Source parse(Engine e, File f) {
            Tok k = new Tok(readText(f));
            Source s = new Source();
            s.name = f.getName();
            s.eng = e;
            // find Tr_SMS ( ScalabiltyGroup ( <detail> ...
            boolean got = false;
            while (!k.end() && !got) {
                String w = k.next();
                if (w.equalsIgnoreCase("ScalabiltyGroup") || w.equalsIgnoreCase("ScalabilityGroup")) {
                    if (!k.open()) continue;
                    int detail = k.integer();
                    if (detail > 5) {                       // Open Rails SoundDetailLevel 5: first group <= 5
                        k.skipRest();
                        continue;
                    }
                    s.parseGroup(k, e);
                    got = true;
                }
            }
            if (!got) return null;
            s.setRolloff();
            return s;
        }

        void parseGroup(Tok k, Engine e) {
            while (!k.end()) {
                String w = k.next();
                if (w.equals(")")) return;
                String lw = w.toLowerCase(Locale.US);
                if (lw.equals("activation") || lw.equals("deactivation")) {
                    boolean act = lw.equals("activation");
                    if (!k.open()) continue;
                    while (!k.end()) {
                        String a = k.next();
                        if (a.equals(")")) break;
                        String la = a.toLowerCase(Locale.US);
                        if (la.equals("cabcam")) {
                            if (act) actCab = true;
                            else deCab = true;
                            k.skipBlock();
                        } else if (la.equals("externalcam")) {
                            if (act) actExt = true;
                            else deExt = true;
                            k.skipBlock();
                        } else if (la.equals("passengercam")) {
                            if (act) actPass = true;
                            else dePass = true;
                            k.skipBlock();
                        } else if (la.equals("distance")) {
                            float d = k.numBlock(U_DIST);
                            if (act) actDist = d;
                            else deDist = d;
                        } else if (!a.equals("(")) k.skipBlock();
                        else k.skipRest();
                    }
                } else if (lw.equals("volume")) {
                    volume = k.numBlock(U_NONE);
                } else if (lw.equals("stereo")) {
                    stereo = true;
                    k.skipBlock();
                } else if (lw.equals("ignore3d")) {
                    ignore3d = true;
                    k.skipBlock();
                } else if (lw.equals("streams")) {
                    if (!k.open()) continue;
                    k.integer();
                    while (!k.end()) {
                        String a = k.next();
                        if (a.equals(")")) break;
                        if (a.equalsIgnoreCase("stream")) {
                            if (!k.open()) continue;
                            Stream st = parseStream(k);
                            streams.add(st);
                        } else if (!a.equals("(")) k.skipBlock();
                        else k.skipRest();
                    }
                } else if (!w.equals("(")) k.skipBlock();
                else k.skipRest();
            }
        }

        Stream parseStream(Tok k) {
            Stream st = new Stream();
            st.src = this;
            st.fileVolume = 1f;
            while (!k.end()) {
                String w = k.next();
                if (w.equals(")")) break;
                String lw = w.toLowerCase(Locale.US);
                if (lw.equals("volume")) {
                    st.fileVolume = k.numBlock(U_NONE);
                } else if (lw.equals("volumecurve")) {
                    if (!k.open()) continue;
                    Curve c = Curve.parse(k);
                    if (c != null) st.vol.add(c);
                } else if (lw.equals("frequencycurve")) {
                    if (!k.open()) continue;
                    Curve c = Curve.parse(k);
                    if (c != null) st.freq = c;
                } else if (lw.equals("triggers")) {
                    if (!k.open()) continue;
                    k.integer();
                    while (!k.end()) {
                        String a = k.next();
                        if (a.equals(")")) break;
                        if (a.equals("(")) {
                            k.skipRest();
                            continue;
                        }
                        Trig t = parseTrig(a, k);
                        st.trigs.add(t);
                        if (t.kind == T_INITIAL) st.initial = t;
                        if (t.kind == T_VAR) st.varTrigs.add(t);
                        if (t.cmd != null && t.cmd.type == C_RELJUMP) st.releasedWithJump = true;
                    }
                } else if (!w.equals("(")) k.skipBlock();
                else k.skipRest();
            }
            st.volume = st.fileVolume;
            st.al = new AL(st);
            for (Trig t : st.trigs) init(t);
            return st;
        }

        Trig parseTrig(String name, Tok k) {
            Trig t = new Trig();
            String s = name.toLowerCase(Locale.US);
            if (!k.open()) return t;
            if (s.equals("initial_trigger")) t.kind = T_INITIAL;
            else if (s.equals("variable_trigger")) {
                t.kind = T_VAR;
                String type = k.next().toLowerCase(Locale.US);
                if (type.endsWith("_inc_past")) {
                    t.inc = true;
                    t.var = varOf(type.substring(0, type.length() - 9));
                } else if (type.endsWith("_dec_past")) {
                    t.inc = false;
                    t.var = varOf(type.substring(0, type.length() - 9));
                }
                if (t.var == V_NONE) {
                    k.skipRest();
                    t.kind = T_NULL;
                    return t;
                }
                t.thr = k.num(unitsOf(t.var));
                if (t.var == V_DIST) t.thr = t.thr * t.thr;
            } else if (s.equals("discrete_trigger")) {
                t.kind = T_DISCRETE;
                t.event = k.integer();
            } else if (s.equals("random_trigger")) {
                t.kind = T_RANDOM;
            } else {
                t.kind = T_NULL;                    // Dist_Travelled / joint / switch triggers: not for engines
                k.skipRest();
                return t;
            }
            while (!k.end()) {
                String w = k.next();
                if (w.equals(")")) break;
                String lw = w.toLowerCase(Locale.US);
                if (Cmd.isCmd(w)) {
                    t.cmd = Cmd.parse(w, k);
                } else if (lw.equals("delay_min_max")) {
                    if (!k.open()) continue;
                    t.dMin = k.num(U_NONE);
                    t.dMax = k.num(U_NONE);
                    k.skipRest();
                } else if (lw.equals("volume_min_max")) {
                    if (!k.open()) continue;
                    t.vMin = k.num(U_NONE);
                    t.vMax = k.num(U_NONE);
                    k.skipRest();
                } else if (!w.equals("(")) k.skipBlock();
                else k.skipRest();
            }
            if (t.cmd == null) t.kind = T_NULL;
            return t;
        }

        void init(Trig t) {
            if (t.kind == T_VAR) {
                t.startValue = (t.var == V_DIST && !t.inc) ? Float.MAX_VALUE : 0f;
                t.isBelow = t.startValue < t.thr;
            } else if (t.kind == T_RANDOM) {
                t.atS = eng.gameTime + rnd.nextDouble() * (t.dMax - t.dMin) + t.dMin;
            }
        }

        /** SoundSource.SetRolloffFactor */
        void setRolloff() {
            float deact = deDist != 0 ? deDist : 2000f;
            float maxD = Math.min(2000f, deact);
            rolloff = 8f * (1f / 0.025f - 1f) / (maxD - 8f);
        }

        void reset() {
            for (Stream st : streams) {
                st.al = new AL(st);
                st.volume = st.fileVolume;
                st.lastTriggered = null;
                for (Trig t : st.trigs) {
                    t.enabled = true;
                    t.signaled = false;
                    t.triggered = false;
                    init(t);
                }
            }
            wasOut = true;
            active = false;
        }

        void handleEvent(int ev) {
            for (Stream st : streams)
                for (Trig t : st.trigs)
                    if (t.kind == T_DISCRETE && t.event == ev) t.triggered = true;
        }

        /** which camera conditions are met (SoundSource.ConditionsMet) */
        boolean met(boolean cab, boolean ext, boolean pass, int cam) {
            if (cab && cam == 1) return true;
            if (pass && cam == 2) return true;
            return ext && cam == 0;
        }

        /** SoundSource.Update */
        void update(Engine e) {
            Vars v = e.vars;
            distSq = v.dist * v.dist;
            boolean out = distSq > 4000000f;          // CutOffDistanceM2
            // InitInitials
            if (out) {
                if (!wasOut) {
                    for (Stream st : streams) st.al.hardDeactivate(e);
                    wasOut = true;
                }
            } else if (wasOut) {
                for (Stream st : streams) {
                    st.al.hardActivate();
                    boolean released = false;
                    for (Trig t : st.trigs) {
                        if (t.kind == T_INITIAL) initialRun(st, e);
                        else init(t);
                        tryTrigger(st, t, v, e);
                        released |= t.signaled && t.cmd != null && (t.cmd.type == C_REL || t.cmd.type == C_RELJUMP);
                        if (t.kind == T_DISCRETE) t.signaled = false;
                    }
                    if (!released && !st.al.isPlaying) {
                        for (Trig t : st.trigs)
                            if (t.signaled && t.enabled && t.cmd != null && (t.cmd.type == C_LOOP || t.cmd.type == C_LOOPREL))
                                run(st, t.cmd, e);
                    }
                }
                wasOut = false;
            }
            if (wasOut) {
                for (Stream st : streams)
                    for (Trig t : st.varTrigs) tryTrigger(st, t, v, e);
                return;
            }
            if (!hornChecked) {
                hornChecked = true;
                for (Stream st : streams)
                    for (Trig t : st.trigs)
                        if (t.kind == T_DISCRETE && (t.event == 8 || t.event == 9 || t.event == 10 || t.event == 11 || t.event == 4 || t.event == 5))
                            keep.add(st);
            }
            if (!active) {
                if (met(actCab, actExt, actPass, v.cam) && (actDist == 0 || distSq < actDist * actDist)) {
                    active = true;
                    partial = false;
                    for (Stream st : streams) {
                        st.setFreqAndVolume(v);
                        st.al.setActive(true, e);
                    }
                } else if (v.cam == 2 && !keep.isEmpty() && (actDist == 0 || distSq < actDist * actDist)) {
                    // entered / started inside a coach: only the horn / bell / sander streams
                    active = true;
                    partial = true;
                    for (Stream st : streams)
                        if (keep.contains(st)) {
                            st.setFreqAndVolume(v);
                            st.al.setActive(true, e);
                        }
                }
            } else {
                boolean far = deDist != 0 && distSq > deDist * deDist;
                if (met(deCab, deExt, dePass, v.cam) || far) {
                    if (!far && v.cam == 2 && !keep.isEmpty()) {
                        // inside a coach: the engine loops stay quiet (the .sms says so), the horn / bell / sander play
                        if (!partial) {
                            partial = true;
                            for (Stream st : streams)
                                if (!keep.contains(st)) {
                                    st.al.setActive(false, e);
                                    st.al.hardDeactivate(e);
                                }
                        }
                    } else {
                        for (Stream st : streams) {
                            st.al.setActive(false, e);
                            st.al.hardDeactivate(e);
                        }
                        active = false;
                        partial = false;
                    }
                } else if (partial) {
                    partial = false;
                    for (Stream st : streams)
                        if (!keep.contains(st)) {
                            st.setFreqAndVolume(v);
                            st.al.setActive(true, e);
                        }
                }
            }
            for (Stream st : streams) st.update(v, e);
        }
    }

    // ------------------------------------------------------------------ triggers (Sound.cs)
    static void tryTrigger(Stream st, Trig t, Vars v, Engine e) {
        switch (t.kind) {
            case T_VAR: {
                float nv = st.read(t.var, v);
                boolean fire = false;
                t.signaled = false;
                if (!t.inc) {
                    if (nv < t.thr) {
                        t.signaled = true;
                        if (t.thr <= t.startValue) fire = true;
                    }
                } else {
                    if (nv > t.thr) {
                        t.signaled = true;
                        if (t.thr >= t.startValue) fire = true;
                    }
                }
                t.startValue = nv;
                t.isBelow = nv < t.thr;
                if (fire && t.enabled) {
                    st.repeated = t == st.lastTriggered;
                    run(st, t.cmd, e);
                    st.lastTriggered = t;
                }
                break;
            }
            case T_DISCRETE: {
                t.triggered &= t.enabled;
                if (t.triggered) {
                    t.triggered = false;
                    st.repeated = t == st.lastTriggered;
                    run(st, t.cmd, e);
                    st.lastTriggered = t;
                    t.signaled = true;
                }
                if (!st.src.active) st.al.setActive(false, e);
                break;
            }
            case T_RANDOM: {
                if (e.gameTime > t.atS) {
                    t.signaled = true;
                    if (t.enabled) {
                        st.repeated = t == st.lastTriggered;
                        run(st, t.cmd, e);
                        st.volume = st.src.rnd.nextFloat() * (t.vMax - t.vMin) + t.vMin;
                        st.lastTriggered = t;
                    }
                    t.atS = e.gameTime + st.src.rnd.nextDouble() * (t.dMax - t.dMin) + t.dMin;
                } else t.signaled = false;
                break;
            }
            default:
                break;
        }
    }

    /** ORTSInitialTrigger.Initialize */
    static void initialRun(Stream st, Engine e) {
        Trig t = st.initial;
        if (t.enabled) {
            st.repeated = t == st.lastTriggered;
            run(st, t.cmd, e);
            st.lastTriggered = t;
        }
        t.signaled = true;
    }

    /** ORTSSoundCommand.Run */
    static void run(Stream st, Cmd c, Engine e) {
        if (c == null) return;
        switch (c.type) {
            case C_ONESHOT: {
                String p = nextFile(c, st, e);
                if (p != null) st.al.queue(p, M_ONESHOT, st.repeated, e);
                break;
            }
            case C_LOOP: {
                String p = nextFile(c, st, e);
                if (p != null) st.al.queue(p, M_LOOP, false, e);
                break;
            }
            case C_LOOPREL: {
                String p = nextFile(c, st, e);
                if (p != null) st.al.queue(p, M_LOOPREL, st.releasedWithJump, e);
                break;
            }
            case C_REL:
                st.al.queue("", M_REL, false, e);
                break;
            case C_RELJUMP:
                st.al.queue("", M_RELJUMP, true, e);
                break;
            case C_SETVOL:
                st.volume = c.value;
                break;
            case C_DISABLE:
                if (c.index - 1 >= 0 && c.index - 1 < st.trigs.size()) st.trigs.get(c.index - 1).enabled = false;
                break;
            case C_ENABLE:
                if (c.index - 1 >= 0 && c.index - 1 < st.trigs.size()) st.trigs.get(c.index - 1).enabled = true;
                break;
            default:
                break;
        }
    }

    /** ORTSSoundPlayCommand.GetNextFile (sequential starts at the SECOND file, like Open Rails) */
    static String nextFile(Cmd c, Stream st, Engine e) {
        if (c.files == null || c.files.length == 0) return null;
        if (!c.random) {
            ++c.iFile;
            if (c.iFile >= c.files.length) c.iFile = 0;
        } else c.iFile = st.src.rnd.nextInt(c.files.length);
        String n = c.files[c.iFile];
        return e.wav(n) == null ? null : n;
    }

    // SoundStream.Update / SetFreqAndVolume
    static void streamUpdate(Stream st, Vars v, Engine e) {
        for (Trig t : st.trigs) tryTrigger(st, t, v, e);
        if (st.initial != null) {
            if (!st.al.isPlaying) {
                if (st.varTrigs.size() > 0 || st.trigs.size() == 1) {
                    int below = 0;
                    for (Trig t : st.varTrigs) if (t.isBelow) below++;
                    Cmd ic = st.initial.cmd;
                    boolean play = ic != null && (ic.type == C_ONESHOT || ic.type == C_LOOP || ic.type == C_LOOPREL);
                    if (below == st.varTrigs.size() && play && !(ic.type == C_ONESHOT && st.initial.signaled))
                        initialRun(st, e);
                }
            } else {
                int n = 0;
                for (Trig t : st.trigs)
                    if (t.signaled && t.cmd != null && (t.cmd.type == C_LOOP || t.cmd.type == C_LOOPREL)) n++;
                if (n > 1 && st.initial.signaled) st.initial.signaled = false;
            }
        }
        st.setFreqAndVolume(v);
        st.al.update(e);
    }

    // ====================================================================================== wav data
    /** A .wav as Open Rails reads it for an external sound: mono (16-bit: the RIGHT channel, 8-bit: the left). */
    static final class Wav {
        ByteBuffer bb;
        int dataOff, frames, rate, bits, ch;
        int[] cues;         // sample frames, sorted (Open Rails: chunk + block + sample offset)
        boolean origMono;
        java.nio.ShortBuffer sfl;   // r158: a clipped recording, repaired (de-clipped, -3 dB) - read from here instead
                                    // r164: 16-bit in native memory (was a float[] on the Java heap: big track sound
                                    // packs filled the 256 MB heap -> horn pad sounds / font glyphs failed, game crash)
        String name = "";

        // r158: one mapping per file for every sound set (the AC / non-AC sets and both .sms of an asset share
        // them) and no forced load of the whole file: r157 kept every wav of the sets resident, 700 MB+ with a
        // big track sound pack, which crowded the game's own models / textures out of memory
        static final HashMap<String, Wav> SHARED = new HashMap<String, Wav>();

        static Wav shared(File f) {
            String k = f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();   // a replaced file is read again
            synchronized (SHARED) {
                if (SHARED.containsKey(k)) return SHARED.get(k);
            }
            Wav w = open(f);
            if (w != null) w.name = f.getName();
            synchronized (SHARED) {
                SHARED.put(k, w);
            }
            if (w != null) prefetch(w);
            return w;
        }

        static final java.util.concurrent.LinkedBlockingQueue<Wav> PF = new java.util.concurrent.LinkedBlockingQueue<Wav>();
        static Thread pfThread;

        /** touches the first second of a file off the audio thread, so its first play does not wait for the disk */
        static synchronized void prefetch(Wav w) {
            PF.offer(w);
            if (pfThread != null) return;
            pfThread = new Thread(new Runnable() {
                public void run() {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    while (true) {
                        try {
                            Wav x = PF.take();
                            if (x.sfl != null) continue;
                            int bytes = Math.min(x.frames, x.rate) * x.ch * (x.bits / 8);
                            int sum = 0;
                            for (int i = 0; i < bytes; i += 4096) sum += x.bb.get(x.dataOff + i);
                            if (sum == 42) Thread.yield();
                        } catch (Throwable t) {
                            // next
                        }
                    }
                }
            }, "TTSSmsPrefetch");
            pfThread.setDaemon(true);
            pfThread.start();
        }

        /**
         * r158: a recording with flat-topped (clipped) peaks crackles ("fete jay") on a phone speaker whatever the
         * volume.  Runs of 2+ samples stuck at full scale are rebuilt with a cubic through the neighbours, the
         * whole file goes 3 dB down to make room for the rebuilt peaks.
         */
        void declipIfNeeded(String name) {
            if (bits != 16 || frames < 64 || frames > 3000000) return;
            int clipped = 0;
            for (int i = 0; i < frames; i++) {
                float v = raw(i);
                if (v >= 0.995f || v <= -0.995f) clipped++;
            }
            if (clipped < 32) return;
            // r164: two passes straight from the mapped file (peak first, then the scaled 16-bit copy), nothing
            // of the size of the file on the Java heap
            float[] pk = new float[1];
            declipPass(null, 1f, pk);
            float g = Math.min(0.708f, 0.98f / Math.max(pk[0], 1e-6f));
            java.nio.ShortBuffer out = java.nio.ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
            int runs = declipPass(out, g, pk);
            sfl = out;
            TTSHornPad.log("sms: " + name + " was clipped (" + clipped + " samples, " + runs + " runs): repaired, x" + String.format(Locale.US, "%.2f", g));
        }

        /** one walk over the file: rebuilds the flat-topped runs (cubic through the neighbours); writes x g into out */
        int declipPass(java.nio.ShortBuffer out, float g, float[] peak) {
            int runs = 0;
            float pk = 0f;
            int i = 0;
            while (i < frames) {
                float v = raw(i);
                if (i >= 2 && i < frames - 3 && Math.abs(v) >= 0.995f) {
                    int a = i, b = i;
                    float sg = Math.signum(v);
                    while (b + 1 < frames - 3 && Math.abs(raw(b + 1)) >= 0.995f && Math.signum(raw(b + 1)) == sg) b++;
                    if (b > a) {
                        int L = b - a + 2;                       // from a-1 to b+1
                        float p0 = raw(a - 1), p1 = raw(b + 1);
                        float m0 = (raw(a - 1) - raw(a - 2)) * L, m1 = (raw(b + 2) - raw(b + 1)) * L;
                        for (int k = a; k <= b; k++) {
                            float t = (float) (k - (a - 1)) / L;
                            float t2 = t * t, t3 = t2 * t;
                            float y = (2 * t3 - 3 * t2 + 1) * p0 + (t3 - 2 * t2 + t) * m0 + (-2 * t3 + 3 * t2) * p1 + (t3 - t2) * m1;
                            float xk = raw(k);
                            if (sg > 0 && y < xk) y = xk;
                            if (sg < 0 && y > xk) y = xk;
                            if (y > 1.6f) y = 1.6f;
                            if (y < -1.6f) y = -1.6f;
                            pk = Math.max(pk, Math.abs(y));
                            if (out != null) out.put(k, q16(y * g));
                        }
                        runs++;
                    } else {
                        pk = Math.max(pk, Math.abs(v));
                        if (out != null) out.put(a, q16(v * g));
                    }
                    i = b + 1;
                } else {
                    pk = Math.max(pk, Math.abs(v));
                    if (out != null) out.put(i, q16(v * g));
                    i++;
                }
            }
            peak[0] = pk;
            return runs;
        }

        static short q16(float v) {
            int q = Math.round(v * 32767f);
            return (short) (q > 32767 ? 32767 : q < -32768 ? -32768 : q);
        }

        float raw(int f) {
            int q = dataOff + f * 2 * ch + (ch == 2 ? 2 : 0);
            return bb.getShort(q) * (1f / 32768f);
        }

        static Wav open(File f) {
            RandomAccessFile raf = null;
            try {
                raf = new RandomAccessFile(f, "r");
                FileChannel fc = raf.getChannel();
                MappedByteBuffer m = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
                m.order(ByteOrder.LITTLE_ENDIAN);
                int size = (int) fc.size();
                if (size < 44 || m.get(0) != 'R' || m.get(1) != 'I' || m.get(2) != 'F' || m.get(3) != 'F') return null;
                Wav w = new Wav();
                w.bb = m;
                int fmt = 0, dataLen = 0;
                w.dataOff = -1;
                ArrayList<Integer> cues = new ArrayList<Integer>();
                int p = 12;
                while (p + 8 <= size) {
                    int id0 = m.get(p), id1 = m.get(p + 1), id2 = m.get(p + 2), id3 = m.get(p + 3);
                    int len = m.getInt(p + 4);
                    if (len < 0) break;
                    int body = p + 8;
                    if (id0 == 'f' && id1 == 'm' && id2 == 't' && id3 == ' ' && len <= 40) {
                        fmt = m.getShort(body) & 0xFFFF;
                        w.ch = m.getShort(body + 2) & 0xFFFF;
                        w.rate = m.getInt(body + 4);
                        w.bits = m.getShort(body + 14) & 0xFFFF;
                        if (fmt == 0xFFFE && len >= 24) {
                            // WAVE_FORMAT_EXTENSIBLE: Open Rails takes it only for 1 channel (front centre / any)
                            // or 2 channels front L+R; 8 / 16 bit only
                            int mask = m.getInt(body + 20);
                            if (w.ch == 2 && mask != 3) fmt = 0;
                            else if (w.ch == 1 && !(mask == 4 || mask == 3 || mask == 0)) fmt = 0;
                            else fmt = 1;
                        }
                    } else if (id0 == 'd' && id1 == 'a' && id2 == 't' && id3 == 'a') {
                        w.dataOff = body;
                        dataLen = Math.min(len, size - body);
                    } else if (id0 == 'c' && id1 == 'u' && id2 == 'e' && id3 == ' ') {
                        int n = m.getInt(body);
                        cues.clear();
                        for (int i = 0; i < n && body + 4 + i * 24 + 24 <= size; i++) {
                            int q = body + 4 + i * 24;
                            long pos = (m.getInt(q + 12) & 0xFFFFFFFFL) + (m.getInt(q + 16) & 0xFFFFFFFFL) + (m.getInt(q + 20) & 0xFFFFFFFFL);
                            cues.add((int) Math.min(pos, Integer.MAX_VALUE));
                        }
                    } else if (id0 == 's' && id1 == 'm' && id2 == 'p' && id3 == 'l' && len >= 36) {
                        int n = m.getInt(body + 28);
                        if (n > 0) {
                            cues.clear();
                            for (int i = 0; i < n && body + 36 + i * 24 + 24 <= size; i++) {
                                int q = body + 36 + i * 24;
                                cues.add(m.getInt(q + 8));
                                cues.add(m.getInt(q + 12));
                            }
                        }
                    }
                    p = body + len + (len & 1);
                }
                if (fmt != 1 || w.dataOff < 0 || dataLen <= 0) return null;
                if (w.bits != 8 && w.bits != 16) return null;            // GetALFormat: no 24-bit
                if (w.ch != 1 && w.ch != 2) return null;
                w.origMono = w.ch == 1;
                w.frames = dataLen / (w.bits / 8 * w.ch);
                if (!cues.isEmpty()) {
                    int[] c = new int[cues.size()];
                    for (int i = 0; i < c.length; i++) c[i] = cues.get(i);
                    Arrays.sort(c);
                    if (w.ch == 2) for (int i = 0; i < c.length; i++) c[i] = (c[i] / 2) * 2;   // ConvertToMono halves, OpenWavFile doubles
                    w.cues = c;
                }
                w.declipIfNeeded(f.getName());
                return w;
            } catch (Throwable t) {
                TTSHornPad.log("sms wav " + f.getName() + ": " + t);
                return null;
            } finally {
                try {
                    if (raf != null) raf.close();
                } catch (Throwable t) {
                    // mapping stays valid
                }
            }
        }

        float at(int f) {
            if (f < 0 || f >= frames) return 0f;
            if (sfl != null) return sfl.get(f) * (1f / 32768f);
            if (bits == 16) {
                int q = dataOff + f * 2 * ch + (ch == 2 ? 2 : 0);
                return bb.getShort(q) * (1f / 32768f);
            }
            int q = dataOff + f * ch;          // 8 bit: Open Rails keeps the first byte of each pair
            return ((bb.get(q) & 255) - 128) * (1f / 128f);
        }
    }

    /** OpenAL buffer = one cue slice of a wav */
    static final class ABuf {
        Wav w;
        int a, n;          // first frame, frames
    }

    /** ALSoundHelper.SoundPiece */
    static final class Piece {
        String name;
        ABuf[] ids;        // null entry = buffer id 0 (empty slice)
        int[] lens;
        int rate;
        boolean valid, single;

        static Piece make(String name, Wav w) {
            Piece p = new Piece();
            p.name = name;
            if (w == null) {
                p.ids = new ABuf[1];
                p.lens = new int[1];
                p.valid = false;
                return p;
            }
            p.valid = true;
            p.rate = w.rate;
            int[] cues = w.cues;
            if (cues == null || cues.length == 1) {
                p.ids = new ABuf[1];
                p.lens = new int[1];
                p.lens[0] = w.frames;
                if (w.frames > 0) p.ids[0] = buf(w, 0, w.frames);
            } else {
                int n = cues.length;
                p.ids = new ABuf[n + 1];
                p.lens = new int[n + 1];
                int prev = 0;
                for (int i = 0; i < n; i++) {
                    int adj = cues[i];
                    if (adj > w.frames || adj < 0) adj = prev;            // invalid cue point
                    p.lens[i] = adj - prev;
                    if (p.lens[i] > 0) p.ids[i] = buf(w, prev, p.lens[i]);
                    if (i == n - 1) {
                        p.lens[i + 1] = w.frames - adj;
                        if (p.lens[i + 1] > 0) p.ids[i + 1] = buf(w, adj, p.lens[i + 1]);
                    }
                    prev = adj;
                }
            }
            p.single = p.ids.length == 1;
            return p;
        }

        static ABuf buf(Wav w, int a, int n) {
            ABuf b = new ABuf();
            b.w = w;
            b.a = a;
            b.n = n;
            return b;
        }

        boolean isMine(ABuf b) {
            if (b == null) return false;
            for (ABuf x : ids) if (x == b) return true;
            return false;
        }

        void queueAll(Src s) {
            for (ABuf b : ids) if (b != null) s.queue(b);
        }

        // r159: the loop position (NextBuffer) belongs to the sound item, not to the shared piece.  The same wav
        // is often used by the cab and the outside .sms (and by several streams); with one shared counter the
        // other source's start put it back to 0 every time, so a cue-point loop restarted from its beginning
        // every first slice (XD.wav sander: a cut every 0.45 s).
        void queue2(Src s, Item it) {
            if (!valid) return;
            if (it.nb < 0 || it.nb >= ids.length) it.nb = 0;
            if (ids[it.nb] != null) s.queue(ids[it.nb]);
            if (ids.length > 1) {
                it.nb++;
                it.nb %= ids.length - 1;
                if (it.nb == 0) it.nb++;
            }
        }

        void queue3(Src s, Item it) {
            if (valid && !single && ids[ids.length - 1] != null) s.queue(ids[ids.length - 1]);
            it.nb = 0;
        }

        /** SoundPiece.IsCheckpoint: within the last 0.2 s (times pitch) of the buffer being played */
        boolean isCheckpoint(Src s, ABuf b, float pitch) {
            int bid = -1;
            for (int i = 0; i < ids.length; i++) if (b != null && ids[i] == b) {
                bid = i;
                break;
            }
            if (bid == -1) return false;
            int len = (int) (0.2f * rate * pitch);
            if (lens[bid] < len) return true;
            int pos = s == null ? 0 : s.offset();
            return lens[bid] - len < pos && pos < lens[bid];
        }
    }

    /** ALSoundHelper.SoundItem */
    static final class Item {
        Piece piece;
        int mode;
        int state;
        float pitch;
        int nb;            // r159: SoundPiece.NextBuffer, per item

        void setPiece(String name, boolean relJump, Engine e) {
            if (name == null || name.length() == 0) return;
            String key = name.toLowerCase(Locale.US) + (relJump ? ".j" : "") + ".x";
            Piece p = e.pieces.get(key);
            if (p == null) {
                p = Piece.make(name, e.wav(name));
                e.pieces.put(key, p);
            }
            piece = p;
        }

        boolean isCheckpoint(Src s, float pitch) {
            this.pitch = pitch;
            return piece.isCheckpoint(s, s == null ? null : s.buffer(), pitch);
        }

        /** SoundItem.Update: keeps the loop going; false = leave it (release) */
        boolean update(Src s, float pitch) {
            this.pitch = pitch;
            if (mode == M_REL && nb < 2 || mode == M_RELJUMP) return false;
            if (s != null) {
                ABuf b = s.buffer();
                if (b == null) {
                    if (s.q.size() == 0) piece.queue2(s, this);
                    s.play();
                } else if (piece.isCheckpoint(s, b, pitch)) {
                    if (s.q.size() < 2) piece.queue2(s, this);
                }
            }
            if (mode == M_REL && nb < 2) return false;
            return true;
        }

        void initItemPlay(Src s) {
            switch (mode) {
                case M_ONESHOT:
                case M_LOOP:
                    if (s != null) piece.queueAll(s);
                    state = S_PLAYING;
                    break;
                case M_LOOPREL:
                    nb = 0;
                    if (s != null) piece.queue2(s, this);
                    state = S_PLAYING;
                    break;
                default:
                    state = S_NOP;
                    break;
            }
        }

        void leaveItemPlay(Src s) {
            if (mode == M_RELJUMP || mode == M_REL) {
                if (s != null) piece.queue3(s, this);
                state = S_NOP;
            }
        }
    }

    // ------------------------------------------------------------------ the OpenAL source emulation
    static final int AL_INITIAL = 0, AL_PLAYING = 1, AL_STOPPED = 2;

    static final class Src {
        final ArrayList<ABuf> q = new ArrayList<ABuf>();
        int cur;
        double pos;
        boolean looping;
        int state = AL_INITIAL;
        float gain;            // AL_GAIN (already 0 when inactive)
        float pitch = 1f;
        float gNow;            // the gain heard right now (glides to the target in each block)
        float h1, h0;          // the last two samples played (interpolation history)

        ABuf buffer() {
            if (q.isEmpty()) return null;
            if (state == AL_STOPPED) return null;
            if (cur >= q.size()) return null;
            return q.get(cur);
        }

        int processed() {
            if (state == AL_STOPPED) return q.size();
            if (state == AL_INITIAL || looping) return 0;
            return Math.min(cur, q.size());
        }

        void unqueueOne() {
            if (processed() > 0 && !q.isEmpty()) {
                q.remove(0);
                if (state != AL_STOPPED) cur--;
                if (cur < 0) cur = 0;
            }
        }

        boolean queue(ABuf b) {
            // OpenAL: every buffer of a queue must have the same format (rate)
            for (ABuf x : q)
                if (x.w.rate != b.w.rate) return false;
            q.add(b);
            if (state == AL_STOPPED && cur >= q.size() - 1) {
                // a stopped source keeps its queue; nothing changes until Play
            }
            return true;
        }

        void play() {
            if (state == AL_PLAYING) return;
            if (q.isEmpty()) {
                state = AL_STOPPED;
                return;
            }
            if (state == AL_STOPPED || state == AL_INITIAL) {
                cur = 0;
                pos = 0;
                h1 = 0f;
                seam = true;
            }
            state = AL_PLAYING;
        }

        void stop() {
            if (state != AL_INITIAL || !q.isEmpty()) {
                state = AL_STOPPED;
                cur = q.size();
            }
        }

        void clear() {                     // alSourcei(AL_BUFFER, AL_NONE)
            q.clear();
            cur = 0;
            pos = 0;
            if (state != AL_PLAYING) state = AL_INITIAL;
        }

        /** AL_SAMPLE_OFFSET relative to the queue start (processed buffers still queued count) */
        int offset() {
            if (state != AL_PLAYING) return 0;
            int o = 0;
            for (int i = 0; i < cur && i < q.size(); i++) o += q.get(i).n;
            return o + (int) pos;
        }

        /** sample at (current + k) frames ahead, walking the queue (loop wraps) */
        float ahead(int k) {
            int c = cur;
            int p = (int) pos + k;
            int guard = 0;
            while (c < q.size()) {
                ABuf b = q.get(c);
                if (p < b.n) return b.w.at(b.a + p);
                p -= b.n;
                c++;
                if (c >= q.size() && looping) c = 0;
                if (++guard > 64) break;
            }
            return 0f;
        }

        // r136 de-click: a jump where two buffers meet that are not one piece of the same file (a loop wrapping
        // round, the next file in the queue, the start and the end of a sound) is taken out by an offset that
        // dies away in 2.5 ms - the step is gone, the sound is untouched (Open Rails / OpenAL play the click)
        float lastOut, dc;
        boolean seam;
        static final float DC_K = (float) Math.exp(-1.0 / (0.0025 * RATE));

        /** mix this source into acc (mono) for n frames; gTarget = final gain incl. distance, clamped */
        void mix(float[] acc, int n, float gTarget, float step0) {
            if (gNow == 0f && gTarget == 0f && dc == 0f && step0 > 0f) {
                // r158: inaudible (volume 0 - most speed-band streams most of the time): keep the play position
                // moving exactly as before, without reading or interpolating a single sample (CPU, memory)
                if (state == AL_PLAYING && !q.isEmpty() && cur < q.size()) {
                    double rem = n;
                    int guard = 0;
                    while (rem > 1e-9 && state == AL_PLAYING && ++guard < 256) {
                        ABuf b = q.get(cur);
                        double step = step0 * (double) b.w.rate / RATE;
                        double left = (b.n - pos) / step;
                        if (left > rem) {
                            pos += rem * step;
                            rem = 0;
                        } else {
                            rem -= Math.max(0.0, left);
                            pos = 0;
                            cur++;
                            if (cur >= q.size()) {
                                if (looping) cur = 0;
                                else {
                                    state = AL_STOPPED;
                                    cur = q.size();
                                }
                            }
                        }
                    }
                    h1 = 0f;
                    seam = true;
                }
                lastOut = 0f;
                return;
            }
            float g = gNow;
            float dg = (gTarget - g) / n;
            for (int i = 0; i < n; i++) {
                g += dg;
                if (state != AL_PLAYING || q.isEmpty() || cur >= q.size()) {
                    if (dc != 0f) {                       // the end of a sound glides to silence
                        acc[i] += dc * g;
                        lastOut = dc;
                        dc *= DC_K;
                        if (Math.abs(dc) < 1e-5f) dc = 0f;
                    } else lastOut = 0f;
                    continue;
                }
                ABuf b = q.get(cur);
                int ip = (int) pos;
                float fr = (float) (pos - ip);
                float s0 = ahead(0), s1 = ahead(1), s2 = ahead(2);
                float sm = h1;
                // 4-point Hermite (OpenAL Soft "cubic" resampler class)
                float c0 = s0;
                float c1 = 0.5f * (s1 - sm);
                float c2 = sm - 2.5f * s0 + 2f * s1 - 0.5f * s2;
                float c3 = 0.5f * (s2 - sm) + 1.5f * (s0 - s1);
                float smp = ((c3 * fr + c2) * fr + c1) * fr + c0;
                if (seam) {
                    dc = lastOut - smp;
                    seam = false;
                }
                float o = smp + dc;
                dc *= DC_K;
                if (Math.abs(dc) < 1e-6f) dc = 0f;
                lastOut = o;
                acc[i] += o * g;
                double step = step0 * (double) b.w.rate / RATE;
                double np = pos + step;
                // history follows each whole input sample passed
                int adv = (int) np - ip;
                if (adv >= 1) {
                    h1 = ahead(adv - 1);
                }
                pos = np;
                while (state == AL_PLAYING && pos >= q.get(cur).n) {
                    ABuf was = q.get(cur);
                    pos -= was.n;
                    cur++;
                    if (cur >= q.size()) {
                        if (looping && !q.isEmpty()) cur = 0;
                        else {
                            state = AL_STOPPED;
                            cur = q.size();
                            pos = 0;
                            h1 = 0f;
                            dc = lastOut;                 // glide from the last sample to silence
                            break;
                        }
                    }
                    ABuf nb = q.get(cur);
                    if (nb.w != was.w || nb.a != was.a + was.n) seam = true;
                }
            }
            gNow = gTarget;
        }
    }

    /** ALSoundHelper.ALSoundSource (one per stream) */
    static final class AL {
        static final int QL = 16;
        final Stream st;
        Src src;                         // null = SoundSourceID -1
        boolean looping;
        final Item[] sq = new Item[QL];
        int head, tail;
        boolean mustActivate;
        boolean active;
        float volume = 1f;
        float playbackSpeed = 1f;
        float sampleRate;
        boolean isPlaying, wasPlaying;
        double stoppedAt = Double.MAX_VALUE;
        float atten = 1f;

        AL(Stream s) {
            st = s;
            for (int i = 0; i < QL; i++) sq[i] = new Item();
        }

        Item at(int i) {
            return sq[((i % QL) + QL) % QL];
        }

        void hardActivate() {
            mustActivate = true;
            if (src == null) hardCleanQueue();
        }

        void hardDeactivate(Engine e) {
            if (src != null) {
                stopSrc();
                src = null;
            }
            hardCleanQueue();
        }

        void setActive(boolean a, Engine e) {
            active = a;
            setVolume();
        }

        void setVolume() {
            if (src != null) src.gain = active ? volume : 0f;
        }

        void setVolumeValue(float v, Engine e) {
            float nv = v < 0 ? 0 : v;
            if (volume != nv) {
                volume = nv;
                setVolume();
                xCheck(e);
            }
        }

        void setPlaybackSpeed(float v) {
            if (playbackSpeed != v && !Float.isNaN(v) && v != 0 && !Float.isInfinite(v)) {
                playbackSpeed = v;
                if (src != null) src.pitch = v;
            }
        }

        int tryActivate() {
            if (!mustActivate || src != null || !active) return 0;
            src = new Src();
            mustActivate = false;
            wasPlaying = false;
            stoppedAt = Double.MAX_VALUE;
            src.pitch = playbackSpeed;
            src.looping = looping;
            src.gNow = 0f;
            setVolume();
            return 1;
        }

        void start() {
            if (src != null && src.state != AL_PLAYING) src.play();
            isPlaying = true;
        }

        void stopSrc() {
            if (src != null) {
                src.stop();
                src.clear();
            }
            skipProcessed();
            isPlaying = false;
        }

        void enterLoop() {
            if (looping) return;
            if (src != null) src.looping = true;
            looping = true;
        }

        void leaveLoop() {
            if (src != null) src.looping = false;
            looping = false;
        }

        void skipProcessed() {
            while (at(tail).state == S_NOP && head != tail) tail++;
        }

        void xCheck(Engine e) {
            Item t = at(tail);
            if (volume == 0 && (t.mode == M_REL || t.mode == M_RELJUMP)) {
                stopSrc();
                at(tail).state = S_NOP;
            }
            if (wasPlaying && !isPlaying || !active) {
                int state = src == null ? AL_STOPPED : src.state;
                if (state != AL_PLAYING) {
                    if (stoppedAt == Double.MAX_VALUE) stoppedAt = e.gameTime;
                    else if (stoppedAt < e.gameTime - 0.2) {
                        stoppedAt = Double.MAX_VALUE;
                        hardDeactivate(e);
                        wasPlaying = false;
                        mustActivate = true;
                    }
                }
            }
        }

        /** ALSoundSource.Update */
        void update(Engine e) {
            if (!wasPlaying && isPlaying) wasPlaying = true;
            skipProcessed();
            if (head == tail) {
                xCheck(e);
                return;
            }
            if (src != null) {
                int p = src.processed();
                while (p > 0) {
                    src.unqueueOne();
                    p--;
                }
            }
            Item t = at(tail);
            Item nx = at(tail + 1);
            switch (t.state) {
                case S_PLAYING: {
                    int justActivated = tryActivate();
                    switch (t.mode) {
                        case M_LOOPREL:
                        case M_REL:
                        case M_RELJUMP:
                            if (nx.state == S_NEW && (nx.mode == M_REL || nx.mode == M_RELJUMP)) t.mode = nx.mode;
                            if (t.update(src, playbackSpeed)) {
                                start();
                            } else {
                                leaveLoop();
                                t.leaveItemPlay(src);
                                start();
                                isPlaying = false;
                            }
                            break;
                        case M_LOOP:
                        case M_ONESHOT:
                            if (nx.state == S_NEW && (nx.mode == M_REL || nx.mode == M_RELJUMP)) {
                                t.mode = nx.mode;
                                nx.state = S_NOP;
                                leaveLoop();
                                isPlaying = false;
                            } else if (t.mode == M_LOOP) {
                                if (justActivated == 1) t.state = S_NEW;
                                ABuf b = src == null ? null : src.buffer();
                                if (t.piece.isMine(b)) {
                                    enterLoop();
                                    isPlaying = true;
                                } else {
                                    leaveLoop();
                                    isPlaying = false;
                                }
                            } else {
                                int state = src == null ? AL_STOPPED : src.state;
                                if (state != AL_PLAYING || t.isCheckpoint(src, playbackSpeed)) {
                                    t.state = S_NOP;
                                    isPlaying = false;
                                }
                            }
                            break;
                        default:
                            break;
                    }
                    break;
                }
                case S_NEW: {
                    if (t.mode != M_REL && t.mode != M_RELJUMP) {
                        int justActivated = tryActivate();
                        ABuf b = src == null ? null : src.buffer();
                        if (isPlaying && justActivated == 1 && t.mode == M_LOOPREL) {
                            t.state = S_PLAYING;
                        } else if (!isPlaying || !t.piece.isMine(b)) {
                            t.initItemPlay(src);
                            float old = sampleRate;
                            sampleRate = t.piece.rate;
                            if (old != sampleRate && sampleRate != 0 && old != 0) setPlaybackSpeed(playbackSpeed * old / sampleRate);
                            start();
                        }
                    } else {
                        t.state = S_NOP;
                        isPlaying = false;
                    }
                    break;
                }
                case S_NOP:
                    leaveLoop();
                    isPlaying = false;
                    break;
                default:
                    break;
            }
            xCheck(e);
        }

        /** ALSoundSource.Queue */
        void queue(String name, int mode, boolean relJumpOrRepeated, Engine e) {
            if (src == null) {
                if (mode == M_REL || mode == M_RELJUMP) {
                    head = tail;
                    at(head).state = S_NOP;
                } else if (head != tail && at(head - 1).piece != null && name.equalsIgnoreCase(at(head - 1).piece.name)
                        && at(head - 1).mode == M_LOOP && mode == M_LOOP) {
                    // don't put into the queue repeatedly
                } else {
                    Item it = at(head);
                    it.setPiece(name, relJumpOrRepeated, e);
                    it.state = S_NEW;
                    it.mode = mode;
                    if (head == tail && at(tail).piece != null) sampleRate = at(tail).piece.rate;
                    head++;
                }
                return;
            }
            if (head != tail) {
                int prevMode;
                Item prev;
                for (int i = 1; i < 5; i++) {
                    prev = at(head - i);
                    prevMode = prev.mode;
                    if (prevMode == mode && (mode != M_ONESHOT || relJumpOrRepeated) && prev.piece != null
                            && prev.piece.name.equalsIgnoreCase(name))
                        return;
                    if (head - i == tail) break;
                }
                prev = at(head - 1);
                prevMode = prev.mode;
                boolean optimized = false;
                if (prev.state == S_NEW) {
                    switch (prev.mode) {
                        case M_LOOP:
                            if (mode == M_REL || mode == M_RELJUMP) prevMode = mode;
                            break;
                        case M_LOOPREL:
                            if (prev.piece != null && prev.piece.name.equalsIgnoreCase(name) && mode == M_LOOP) prevMode = mode;
                            else if (mode == M_REL || mode == M_RELJUMP) prevMode = mode;
                            break;
                        case M_ONESHOT:
                            if (prev.piece != null && prev.piece.name.equalsIgnoreCase(name) && mode == M_LOOP) prevMode = mode;
                            break;
                        default:
                            break;
                    }
                    if (prevMode != at(head - 1).mode) {
                        at(head - 1).mode = prevMode;
                        optimized = true;
                    }
                }
                if (head - 1 > tail && (mode == M_REL || mode == M_RELJUMP)) {
                    for (int i = head - 1; i > tail; i--)
                        if (at(i - 1).mode == M_LOOP || at(i - 1).mode == M_LOOPREL) at(i - 1).mode = mode;
                }
                if (optimized) return;
            }
            Item it = at(head);
            it.setPiece(name, mode == M_LOOPREL && relJumpOrRepeated, e);
            it.state = S_NEW;
            it.mode = mode;
            if (head == tail && at(tail).piece != null) sampleRate = at(tail).piece.rate;
            head++;
        }

        void hardCleanQueue() {
            if (head == tail) return;
            int h = head;
            while (h >= tail) {
                Item it = at(h);
                if (it.state == S_NOP) {
                    h--;
                    continue;
                }
                if ((it.mode == M_LOOP || it.mode == M_LOOPREL || (it.mode == M_ONESHOT && it.piece != null
                        && totalLen(it.piece) > 25000)) && (it.state == S_NEW || it.state == S_PLAYING))
                    break;
                if (it.mode == M_REL || it.mode == M_RELJUMP) h = tail - 1;
                h--;
            }
            if (h >= tail) {
                for (int i = h - 1; i >= tail; i--) at(i).state = S_NOP;
                for (int i = head; i > h; i--) at(i).state = S_NOP;
                at(h).state = S_NEW;
                at(h + 1).state = S_NOP;
                head = h + 1;
                tail = h;
            } else {
                for (int i = tail; i <= head; i++) at(i).state = S_NOP;
                head = tail;
            }
        }

        static int totalLen(Piece p) {
            int s = 0;
            for (int l : p.lens) s += l;
            return s;          // frames (Open Rails compares 50000 bytes of 16-bit mono = 25000 frames)
        }

        void render(float[] acc, int n, float master, float dopp) {
            if (src == null) return;
            float g = src.gain * atten;
            if (g > 1f) g = 1f;                    // AL_MAX_GAIN 1
            src.mix(acc, n, g * master, src.pitch * dopp);
        }
    }

    // SoundStream.SetFreqAndVolume (+ the OpenAL distance model for the source)
    static void setFreqAndVolume(Stream st, Vars v) {
        AL al = st.al;
        if (st.freq != null) {
            float x = st.read(st.freq.var, v);
            float y = st.freq.at(x);
            if (st.src.stereo && al.src != null) {
                // MstsMonoTreatment: Stereo() group and a mono 16-bit file
                Item t = al.at(al.tail);
                if (t.piece != null && t.piece.valid && t.piece.ids.length > 0) {
                    ABuf b = null;
                    for (ABuf q : t.piece.ids) if (q != null) {
                        b = q;
                        break;
                    }
                    if (b != null && b.w.origMono && b.w.bits == 16) y *= 2;
                }
            }
            if (al.sampleRate > 0) al.setPlaybackSpeed(y / al.sampleRate);
        }
        // r142: horn / bell / sander fade like the horn pad's own sound: full loudness up to 12 m, then 1/r
        // (the .sms curves keep them near full gain out to ~100 m, because the 1.0 AL gain clamp hides the fall)
        final float hornRef = 12f;
        boolean hornLaw = v.dist > hornRef && st.src.keep.contains(st);
        float realDist = v.dist, realSq = st.src.distSq;
        if (hornLaw) {
            v.dist = hornRef;
            st.src.distSq = hornRef * hornRef;
        }
        float volume = st.src.volume * st.volume;
        for (Curve c : st.vol) volume *= c.at(st.read(c.var, v));
        // in the cab / a coach the outside sounds come through the body (ExternalSoundPassThruPercent 50)
        if (v.cam != 0 && st.src.actExt) volume *= 0.5f;
        al.setVolumeValue(volume, st.src.eng);
        // OpenAL inverse distance clamped: reference 8 m, the group's rolloff, max 2000 m
        float atten = 1f;
        if (st.src.actExt && !st.src.ignore3d && !st.src.stereo) {
            float d = v.dist;
            if (d < 8f) d = 8f;
            if (d > 2000f) d = 2000f;
            atten = 8f / (8f + st.src.rolloff * (d - 8f));
        }
        if (hornLaw) {
            v.dist = realDist;
            st.src.distSq = realSq;
            al.setVolumeValue(Math.min(1f, volume * atten), st.src.eng);   // the loudness at 12 m, then 1/r
            atten = hornRef / realDist;
        }
        al.atten = atten;
    }
}
