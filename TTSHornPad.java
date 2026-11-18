package com.n3vgames.android;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Locale;

/**
 * TTS horn pad (r111): a free joystick over the game.
 *
 * Drag the knob towards a side and hold: up = horn.wav, right = extra.wav, down = idle 1.wav (bell),
 * left = sander1.wav.  The sound loops while the finger stays there and finishes naturally when it is
 * released.  The files come from the horn asset of the driven loco, which the HUD script writes to
 * Trainz.log ("TTSJOY: on <kuid:..> x= y= d=" / "TTSJOY: off").  r118: the small gear at the top-right of
 * the pad (or a long-press on the knob) = settings:
 * move, bigger / smaller, hide (small HORN button) - all remembered.  r117: the pad's finger is taken out
 * of the game's touches only, so camera and other controls keep working while the horn is held.
 *
 * Started by the system as a ContentProvider (AndroidManifest), so no existing class is changed.
 * Everything is inside try / catch: the pad can never take the game down.
 */
public class TTSHornPad extends ContentProvider implements Application.ActivityLifecycleCallbacks {

    static final String GAME = "com.n3vgames.android.trainz.trainz";
    static final Handler H = new Handler(Looper.getMainLooper());
    static PadView pad;
    static Activity owner;
    static Context app;
    static final Mixer mixer = new Mixer();
    static final Watcher watcher = new Watcher();
    static final Weather weather = new Weather();

    // ---------------------------------------------------------------- provider plumbing
    @Override
    public boolean onCreate() {
        try {
            app = getContext().getApplicationContext();
            ((Application) app).registerActivityLifecycleCallbacks(this);
        } catch (Throwable t) {
            log("onCreate: " + t);
        }
        return true;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }

    @Override public void onActivityCreated(Activity a, Bundle b) { }
    @Override public void onActivityStarted(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }

    @Override
    public void onActivityResumed(Activity a) {
        try {
            if (!GAME.equals(a.getClass().getName())) return;
            if (owner != a || pad == null || pad.getParent() == null) {
                owner = a;
                pad = new PadView(a);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(10, 10);
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                a.addContentView(pad, lp);
                pad.setVisibility(View.GONE);
                State last = watcher.last;
                if (last != null) pad.setState(last);     // the HUD said its state before this pad existed
            }
            installRouter(a);
            watcher.start(a);
            weather.setPaused(false);
        } catch (Throwable t) {
            log("attach: " + t);
        }
    }

    @Override
    public void onActivityPaused(Activity a) {
        try {
            if (a == owner) {
                mixer.stopAll();
                weather.setPaused(true);
                if (pad != null) pad.dropTouch();
            }
        } catch (Throwable t) {
            log("pause: " + t);
        }
    }

    @Override
    public void onActivityDestroyed(Activity a) {
        try {
            if (a == owner) {
                mixer.stopAll();
                weather.setRain(0);
                owner = null;
                pad = null;
                if (routedFor == a) routedFor = null;
            }
        } catch (Throwable t) {
            log("destroy: " + t);
        }
    }

    static void log(String s) {
        try {
            android.util.Log.i("TTSHornPad", s);
        } catch (Throwable t) {
            // nothing
        }
        // r118: also a small file he can send: Android/media/com.n3vgames.android.trainz/TTSHornPad.log
        try {
            File f = new File("/sdcard/Android/media/com.n3vgames.android.trainz/TTSHornPad.log");
            boolean append = f.length() < 256 * 1024;
            java.io.FileOutputStream o = new java.io.FileOutputStream(f, append);
            try {
                o.write((System.currentTimeMillis() + " " + s + "\n").getBytes("UTF-8"));
            } finally {
                o.close();
            }
        } catch (Throwable t) {
            // nothing
        }
    }

    // ================================================================ the state from the HUD script
    static final class State {
        boolean on;
        String kuid = "";
        float x = 0.20f, y = 0.32f, d = 0.26f;   // default place / size as parts of the screen
    }

    /** Reads new Trainz.log lines with "TTSJOY:", finds the horn folder, loads the sounds. */
    static final class Watcher implements Runnable {
        Thread th;
        volatile boolean run;
        File logFile;
        long pos = -1;
        String lastLine = "";
        String loadedKuid = null;
        volatile State last;                         // the latest state (a new pad starts from it)
        File base;                                   // .../files (UserData lives under it)
        final HashMap<String, File> kuidDirs = new HashMap<String, File>();
        long lastScan = 0;

        synchronized void start(Activity a) {
            if (base == null) {
                File f = a.getExternalFilesDir(null);
                if (f == null) f = new File("/sdcard/Android/data/com.n3vgames.android.trainz/files");
                base = f;
            }
            if (th != null && th.isAlive()) return;
            run = true;
            th = new Thread(this, "TTSHornPad");
            th.setDaemon(true);
            th.start();
        }

        public void run() {
            loadIndex();
            while (run) {
                try {
                    poll();
                } catch (Throwable t) {
                    log("poll: " + t);
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        File findLog() {
            File best = null;
            File[] c = new File[3];
            c[0] = new File(base, "Trainz.log");
            try {
                File[] m = app.getExternalMediaDirs();
                if (m != null && m.length > 0 && m[0] != null) c[1] = new File(m[0], "Trainz.log");
            } catch (Throwable t) {
                // old Android
            }
            c[2] = new File("/sdcard/Android/media/com.n3vgames.android.trainz/Trainz.log");
            for (File f : c) {
                if (f != null && f.isFile() && (best == null || f.lastModified() > best.lastModified())) best = f;
            }
            return best;
        }

        void poll() throws Exception {
            File f = findLog();
            if (f == null) return;
            if (!f.equals(logFile)) {
                logFile = f;
                pos = -1;
            }
            long len = f.length();
            if (pos < 0 || len < pos) pos = Math.max(0, len - (1 << 20));   // new log: look at its last 1 MB
            if (len == pos) return;
            long n = Math.min(len - pos, 1 << 20);
            if (len - pos > n) pos = len - n;
            byte[] buf = new byte[(int) n];
            RandomAccessFile r = new RandomAccessFile(f, "r");
            try {
                r.seek(pos);
                r.readFully(buf);
            } finally {
                r.close();
            }
            int end = buf.length;
            while (end > 0 && buf[end - 1] != '\n') end--;   // whole lines only
            pos += end;
            String s = new String(buf, 0, end, "ISO-8859-1");
            weatherFromLog(s);
            int i = s.lastIndexOf("TTSJOY: o");
            // the driver session ended (back to the menu, Surveyor / edit session): the game's script
            // router shuts down and the HUD script never gets to write "off" - hide the pad ourselves
            int done = Math.max(s.lastIndexOf("GSRouter::Done()"), s.lastIndexOf("TTSJOY: end"));
            if (done > i) {
                if (lastLine.length() > 0) {
                    lastLine = "";
                    apply(new State());
                }
                return;
            }
            if (i < 0) return;
            int e = s.indexOf('\n', i);
            if (e < 0) e = s.length();
            String line = s.substring(i + 8, e).trim();
            if (line.equals(lastLine)) return;
            lastLine = line;
            apply(parse(line));
        }

        /** rain level from the TTS weather commands of the HUD script ("TTSCMD:rain=N"); session end = dry */
        static void weatherFromLog(String s) {
            int r = s.lastIndexOf("TTSCMD:rain=");
            int done = s.lastIndexOf("GSRouter::Done()");
            if (done > r) {
                weather.setRain(0);
                return;
            }
            if (r >= 0 && r + 12 < s.length()) {
                char c = s.charAt(r + 12);
                if (c >= '0' && c <= '9') weather.setRain(c - '0');
            }
        }

        static State parse(String line) {
            State st = new State();
            String[] p = line.split("\\s+");
            if (p.length == 0 || !p[0].equals("on")) return st;
            st.on = true;
            for (int i = 1; i < p.length; i++) {
                String t = p[i];
                try {
                    if (t.startsWith("x=")) st.x = Integer.parseInt(t.substring(2)) / 1000f;
                    else if (t.startsWith("y=")) st.y = Integer.parseInt(t.substring(2)) / 1000f;
                    else if (t.startsWith("d=")) st.d = Integer.parseInt(t.substring(2)) / 1000f;
                    else if (t.startsWith("<kuid")) st.kuid = normKuid(t);
                } catch (NumberFormatException ex) {
                    // keep the default
                }
            }
            return st;
        }

        /** "<kuid:1078783:1679797>" / "<kuid2:1:2:3>" -> "1078783:1679797" */
        static String normKuid(String t) {
            int a = t.indexOf(':');
            if (a < 0) return "";
            String rest = t.substring(a + 1).replace(">", "");
            String[] q = rest.split(":");
            if (q.length < 2) return "";
            return q[0].trim() + ":" + q[1].trim();
        }

        void apply(final State st) {
            last = st;
            if (st.on && !st.kuid.equals(loadedKuid)) {
                loadedKuid = st.kuid;
                File dir = findDir(st.kuid);
                mixer.load(dir);
                log("horn folder " + st.kuid + " -> " + dir);
            }
            H.post(new Runnable() {
                public void run() {
                    try {
                        if (pad != null) pad.setState(st);
                        if (!st.on) mixer.stopAll();
                    } catch (Throwable t) {
                        log("state: " + t);
                    }
                }
            });
        }

        // ------------------------------------------------ kuid -> asset folder (UserData)
        File findDir(String kuid) {
            if (kuid.length() == 0) return null;
            File d;
            synchronized (kuidDirs) {
                d = kuidDirs.get(kuid);
            }
            if (d != null && d.isDirectory()) return d;
            long now = System.currentTimeMillis();
            if (now - lastScan < 20000) return null;      // a new addon: scan again at most every 20 s
            lastScan = now;
            scan();
            saveIndex();
            synchronized (kuidDirs) {
                return kuidDirs.get(kuid);
            }
        }

        void scan() {
            HashMap<String, File> m = new HashMap<String, File>();
            String[] roots = {"UserData/original", "UserData/local"};
            for (String r : roots) {
                File[] hashes = new File(base, r).listFiles();
                if (hashes == null) continue;
                for (File h : hashes) {
                    File[] assets = h.listFiles();
                    if (assets == null) continue;
                    for (File a : assets) {
                        File cfg = new File(a, "config.txt");
                        String k = configKuid(cfg);
                        if (k != null) m.put(k, a);
                    }
                }
            }
            synchronized (kuidDirs) {
                kuidDirs.clear();
                kuidDirs.putAll(m);
            }
            log("scan: " + m.size() + " assets");
        }

        static String configKuid(File cfg) {
            if (!cfg.isFile()) return null;
            try {
                FileInputStream in = new FileInputStream(cfg);
                byte[] b = new byte[4096];
                int n;
                try {
                    n = in.read(b);
                } finally {
                    in.close();
                }
                if (n <= 0) return null;
                String s = new String(b, 0, n, "ISO-8859-1");
                String low = s.toLowerCase(Locale.US);
                int i = 0;
                while (true) {
                    i = low.indexOf("kuid", i);
                    if (i < 0) return null;
                    boolean lineStart = (i == 0) || low.charAt(i - 1) == '\n' || low.charAt(i - 1) == '\r';
                    int lt = low.indexOf('<', i);
                    int nl = low.indexOf('\n', i);
                    if (lineStart && lt > 0 && (nl < 0 || lt < nl)) {
                        int gt = low.indexOf('>', lt);
                        if (gt > lt) {
                            String k = normKuid(low.substring(lt, gt + 1));
                            if (k.length() > 0) return k;
                        }
                    }
                    i += 4;
                }
            } catch (Throwable t) {
                return null;
            }
        }

        File indexFile() {
            return new File(base, "tts_hornpad.idx");
        }

        void loadIndex() {
            try {
                File f = indexFile();
                if (!f.isFile()) return;
                byte[] b = new byte[(int) Math.min(f.length(), 8 << 20)];
                FileInputStream in = new FileInputStream(f);
                int got = 0;
                try {
                    while (got < b.length) {
                        int n = in.read(b, got, b.length - got);
                        if (n <= 0) break;
                        got += n;
                    }
                } finally {
                    in.close();
                }
                String[] lines = new String(b, 0, got, "UTF-8").split("\n");
                synchronized (kuidDirs) {
                    for (String l : lines) {
                        int t = l.indexOf('\t');
                        if (t > 0) kuidDirs.put(l.substring(0, t), new File(l.substring(t + 1)));
                    }
                }
            } catch (Throwable t) {
                log("index: " + t);
            }
        }

        void saveIndex() {
            try {
                StringBuilder sb = new StringBuilder();
                synchronized (kuidDirs) {
                    for (java.util.Map.Entry<String, File> en : kuidDirs.entrySet())
                        sb.append(en.getKey()).append('\t').append(en.getValue().getPath()).append('\n');
                }
                java.io.FileOutputStream o = new java.io.FileOutputStream(indexFile());
                try {
                    o.write(sb.toString().getBytes("UTF-8"));
                } finally {
                    o.close();
                }
            } catch (Throwable t) {
                log("index save: " + t);
            }
        }
    }

    // ================================================================ sounds
    static final int RATE = 44100;
    static final String[][] NAMES = {
        {},
        {"horn.wav"},                                         // 1 up
        {"extra.wav", "extra1.wav", "extra 1.wav"},           // 2 right
        {"idle 1.wav", "idle1.wav", "bell.wav"},              // 3 down
        {"sander1.wav", "sander 1.wav", "sander.wav"},        // 4 left
    };

    /** 16-bit stereo 44.1 kHz copy of a .wav, plus its loop window. */
    static final class Snd {
        short[] pcm;       // interleaved L R
        int frames;
        int loopA, loopB;  // loop window (frames)
        int xf;            // cross-fade length (frames)
        float corr = 1f;   // how well the seam matches (1 = same waveform)
        float seamGain = 1f;
        boolean tailOk;    // what follows the loop body really dies away (horn release), not more of the sound
    }

    static Snd readWav(File f) {
        try {
            if (f == null || !f.isFile() || f.length() > (40L << 20)) return null;
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int got = 0;
            try {
                while (got < b.length) {
                    int n = in.read(b, got, b.length - got);
                    if (n <= 0) break;
                    got += n;
                }
            } finally {
                in.close();
            }
            if (got < 44 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') return null;
            int fmt = 0, ch = 0, rate = 0, bits = 0, dataOff = -1, dataLen = 0;
            int p = 12;
            while (p + 8 <= got) {
                String id = new String(b, p, 4, "ISO-8859-1");
                int len = le32(b, p + 4);
                if (len < 0) break;
                if (id.equals("fmt ") && p + 24 <= got) {
                    fmt = le16(b, p + 8);
                    ch = le16(b, p + 10);
                    rate = le32(b, p + 12);
                    bits = le16(b, p + 22);
                    if (fmt == 0xFFFE && p + 34 <= got) fmt = le16(b, p + 32);   // extensible: sub format
                } else if (id.equals("data")) {
                    dataOff = p + 8;
                    dataLen = Math.min(len, got - dataOff);
                    break;
                }
                p += 8 + len + (len & 1);
            }
            if (dataOff < 0 || ch < 1 || ch > 8 || rate < 4000) return null;
            int bps = bits / 8;
            if (!((fmt == 1 && (bits == 8 || bits == 16 || bits == 24 || bits == 32)) || (fmt == 3 && bits == 32))) return null;
            int inFrames = dataLen / (bps * ch);
            if (inFrames < 16) return null;
            // source -> float L / R
            float[] L = new float[inFrames];
            float[] R = new float[inFrames];
            for (int i = 0; i < inFrames; i++) {
                int q = dataOff + i * bps * ch;
                L[i] = sample(b, q, fmt, bits);
                R[i] = ch > 1 ? sample(b, q + bps, fmt, bits) : L[i];
            }
            // resample to 44.1 kHz (linear)
            int outFrames = (int) ((long) inFrames * RATE / rate);
            if (outFrames < 16) return null;
            Snd s = new Snd();
            s.frames = outFrames;
            s.pcm = new short[outFrames * 2];
            double step = (double) rate / RATE;
            for (int i = 0; i < outFrames; i++) {
                double x = i * step;
                int a = (int) x;
                float t = (float) (x - a);
                int c = Math.min(a + 1, inFrames - 1);
                a = Math.min(a, inFrames - 1);
                s.pcm[2 * i] = clip(L[a] + (L[c] - L[a]) * t);
                s.pcm[2 * i + 1] = clip(R[a] + (R[c] - R[a]) * t);
            }
            findLoop(s);
            return s;
        } catch (Throwable t) {
            log("wav " + f + ": " + t);
            return null;
        }
    }

    /**
     * Loop window = the long steady body of the sound (horn: the blast, not its attack or its fading tail).
     * Envelope in 10 ms blocks, smoothed over 100 ms.  The body is the longest run of blocks that stay within
     * -4.5 dB of the level around the peak.  The end is moved (+-30 ms) to where the waveform matches the
     * start best, the seam is cross-faded (up to 150 ms, equal power when the two parts do not match).
     * What follows the window is the sound's own release; it plays when the finger lets go.
     */
    static void findLoop(Snd s) {
        int win = RATE / 100;                      // 10 ms blocks
        int nb = s.frames / win;
        s.loopA = 0;
        s.loopB = s.frames;
        s.xf = Math.min(RATE * 30 / 1000, s.frames / 4);
        s.corr = 1f;
        if (nb < 20) return;
        float[] raw = new float[nb];
        for (int b = 0; b < nb; b++) {
            double sum = 0;
            for (int i = b * win; i < (b + 1) * win; i++) {
                float l = s.pcm[2 * i], r = s.pcm[2 * i + 1];
                sum += l * l + r * r;
            }
            raw[b] = (float) Math.sqrt(sum / (2 * win));
        }
        float[] env = new float[nb];               // 100 ms moving average
        for (int b = 0; b < nb; b++) {
            double sum = 0;
            int c = 0;
            for (int k = Math.max(0, b - 5); k <= Math.min(nb - 1, b + 5); k++) {
                sum += raw[k];
                c++;
            }
            env[b] = (float) (sum / c);
        }
        float peak = 0;
        int pk = 0;
        for (int b = 0; b < nb; b++) if (env[b] > peak) { peak = env[b]; pk = b; }
        if (peak <= 0) return;
        // longest run within -4.5 dB of the peak level; if that is short, accept -7 dB
        int a = pk, z = pk;
        float[] lim = {0.60f, 0.45f};
        for (float f : lim) {
            a = pk;
            z = pk;
            while (a > 0 && env[a - 1] >= peak * f) a--;
            while (z < nb - 1 && env[z + 1] >= peak * f) z++;
            if ((z + 1 - a) * win >= RATE * 6 / 10) break;
        }
        // the start: after the attack has settled (first block at 80 % of the run's own average)
        double avg = 0;
        for (int b = a; b <= z; b++) avg += env[b];
        avg /= (z + 1 - a);
        while (a < z && env[a] < avg * 0.80) a++;
        while (z > a && env[z] < avg * 0.80) z--;
        // flattest part: drop a loud first burst (it would come back as a bump every loop) as long as
        // at least 0.35 s remain
        float[] part = new float[z + 1 - a];
        for (int b = a; b <= z; b++) part[b - a] = env[b];
        java.util.Arrays.sort(part);
        float med = part[part.length / 2];
        int minLen = RATE * 35 / 100 / win;
        while (z - a > minLen && (env[a] > med * 1.10f || env[a] < med * 0.90f)) a++;
        while (z - a > minLen && (env[z] > med * 1.10f || env[z] < med * 0.90f)) z--;
        int la = a * win + win, lb = z * win;
        if (lb - la < RATE / 5) {
            if (s.frames < RATE) return;            // short sound: loop all of it
            la = Math.max(0, pk * win - RATE / 10);
            lb = Math.min(s.frames, la + RATE / 5);
        }
        int xf = Math.min(RATE * 100 / 1000, (lb - la) / 3);
        // move the end (+-60 ms, a few pitch periods) to where the waveform just before it matches the one
        // just before the loop start best: then [end - xf, end) and [la - xf, la) are near copies and the
        // seam needs only a short, energy-preserving cross-fade
        int range = RATE * 60 / 1000;
        int cw = RATE * 30 / 1000;
        int best = lb;
        double bestC = -2;
        int lo = Math.max(la + xf + RATE / 10, lb - range), hi = Math.min(s.frames - 1, lb + range);
        int ref = Math.max(0, la - cw);
        for (int c = lo; c <= hi; c++) {
            double sab = 0, saa = 0, sbb = 0;
            for (int i = 0; i < cw; i += 2) {
                float x = s.pcm[2 * (ref + i)] + s.pcm[2 * (ref + i) + 1];
                float y = s.pcm[2 * (c - cw + i)] + s.pcm[2 * (c - cw + i) + 1];
                sab += x * y;
                saa += x * x;
                sbb += y * y;
            }
            double cor = (saa > 0 && sbb > 0) ? sab / Math.sqrt(saa * sbb) : -1;
            if (cor > bestC) { bestC = cor; best = c; }
        }
        s.loopA = la;
        s.loopB = best;
        s.xf = xf;
        // correlation of the two parts that are actually mixed (decides the cross-fade weights)
        double sab = 0, saa = 0, sbb = 0;
        for (int i = 0; i < xf; i++) {
            float x = s.pcm[2 * (best - xf + i)] + s.pcm[2 * (best - xf + i) + 1];
            float y = s.pcm[2 * (la + i)] + s.pcm[2 * (la + i) + 1];
            sab += x * y;
            saa += x * x;
            sbb += y * y;
        }
        s.corr = (saa > 0 && sbb > 0) ? (float) Math.max(-1, Math.min(1, sab / Math.sqrt(saa * sbb))) : 0f;
        s.seamGain = (saa > 0 && sbb > 0) ? (float) Math.min(1.5, Math.max(0.67, Math.sqrt(sbb / saa))) : 1f;
        s.tailOk = tailDecays(s);
    }

    /**
     * The part after the loop body is used as the release only if it really dies away: within 1.2 s its level
     * must fall below 40 % of the level where it starts, and it must not be longer than 4 s.  A bell recording
     * that just goes on ringing (or a horn file with a second blast) fails this and gets a short fade instead.
     */
    static boolean tailDecays(Snd s) {
        int tail = s.frames - s.loopB;
        if (tail < RATE / 5 || tail > RATE * 4) return false;
        int w = RATE / 10;
        double a = rms(s, s.loopB, w);
        int far = Math.min(s.frames - w, s.loopB + RATE * 12 / 10);
        double b = rms(s, far, w);
        double end = rms(s, s.frames - w, w);
        return a > 0 && b < a * 0.40 && end < a * 0.25;
    }

    static double rms(Snd s, int from, int n) {
        double sum = 0;
        int c = 0;
        for (int i = Math.max(0, from); i < Math.min(s.frames, from + n); i++) {
            float l = s.pcm[2 * i], r = s.pcm[2 * i + 1];
            sum += l * l + r * r;
            c += 2;
        }
        return c > 0 ? Math.sqrt(sum / c) : 0;
    }

    static int le16(byte[] b, int p) { return (b[p] & 255) | ((b[p + 1] & 255) << 8); }
    static int le32(byte[] b, int p) { return (b[p] & 255) | ((b[p + 1] & 255) << 8) | ((b[p + 2] & 255) << 16) | ((b[p + 3] & 255) << 24); }

    static float sample(byte[] b, int q, int fmt, int bits) {
        if (fmt == 3) return Float.intBitsToFloat(le32(b, q));
        if (bits == 8) return ((b[q] & 255) - 128) / 128f;
        if (bits == 16) return ((short) le16(b, q)) / 32768f;
        if (bits == 24) return (((b[q] & 255) << 8 | (b[q + 1] & 255) << 16 | b[q + 2] << 24) >> 8) / 8388608f;
        return le32(b, q) / 2147483648f;
    }

    static short clip(float v) {
        int s = (int) (v * 32767f);
        if (s > 32767) s = 32767;
        if (s < -32768) s = -32768;
        return (short) s;
    }

    /** One playing sound.  Loops while held; on release it moves (cross-fade) into the sound's own ending. */
    static final class Voice {
        Snd s;
        int pos;           // frame
        boolean held;
        float gain = 1f;
        float fade = 1f;   // release fade multiplier (only for sounds without an ending of their own)
        float fadeMul = 1f;
        int tailPos = -1;  // release: the ending we cross-fade into
        int tailN, tailK;  // release cross-fade length / progress
        boolean done;
    }

    /** Small mixer on one AudioTrack: four sides, one voice each. */
    static final class Mixer implements Runnable {
        final Snd[] snd = new Snd[5];
        final Voice[] v = new Voice[5];
        final boolean[] has = new boolean[5];
        AudioTrack track;
        Thread th;
        final Object lock = new Object();

        void load(File dir) {
            Snd[] n = new Snd[5];
            if (dir != null && dir.isDirectory()) {
                File[] files = dir.listFiles();
                HashMap<String, File> byName = new HashMap<String, File>();
                if (files != null) for (File f : files) byName.put(f.getName().toLowerCase(Locale.US), f);
                for (int k = 1; k <= 4; k++) {
                    for (String nm : NAMES[k]) {
                        File f = byName.get(nm);
                        if (f != null) {
                            n[k] = readWav(f);
                            if (n[k] != null) break;
                        }
                    }
                }
            }
            synchronized (lock) {
                for (int k = 1; k <= 4; k++) {
                    snd[k] = n[k];
                    has[k] = n[k] != null;
                    v[k] = null;
                }
            }
            H.post(new Runnable() {
                public void run() {
                    if (pad != null) pad.invalidate();
                }
            });
        }

        boolean has(int k) {
            synchronized (lock) {
                return has[k];
            }
        }

        void press(int k) {
            synchronized (lock) {
                if (k < 1 || k > 4 || snd[k] == null) return;
                Voice o = new Voice();
                o.s = snd[k];
                o.held = true;
                v[k] = o;
                if (th == null || !th.isAlive()) {
                    th = new Thread(this, "TTSHornPadAudio");
                    th.setDaemon(true);
                    th.start();
                }
                lock.notifyAll();
            }
        }

        void release(int k) {
            synchronized (lock) {
                if (k < 1 || k > 4 || v[k] == null) return;
                Voice o = v[k];
                if (!o.held) return;
                o.held = false;
                startRelease(o);
            }
        }

        /** let go: into the recorded ending (horn tail) or, without a real one (bell), a quick fade */
        static void startRelease(Voice o) {
            Snd s = o.s;
            int tail = s.tailOk ? s.frames - s.loopB : 0;
            if (o.pos >= s.loopB) return;                       // already in the ending
            if (o.pos < s.loopA) {                              // still in the attack: just play on
                if (tail >= RATE / 5) {
                    o.tailPos = s.loopB - Math.min(s.loopB - o.pos, RATE / 20);
                    o.tailN = Math.max(1, Math.min(RATE / 20, s.loopB - o.pos));
                    o.tailK = 0;
                } else o.fadeMul = (float) Math.exp(Math.log(0.001) / (RATE * 0.35));
                return;
            }
            if (tail >= RATE / 5) {
                o.tailPos = s.loopB;                            // the ending starts right after the loop body
                o.tailN = RATE * 80 / 1000;
                o.tailK = 0;
            } else {
                o.fadeMul = (float) Math.exp(Math.log(0.001) / (RATE * 0.35));   // -60 dB in 0.35 s
            }
        }

        void stopAll() {
            synchronized (lock) {
                for (int k = 1; k <= 4; k++) v[k] = null;
            }
        }

        boolean busy() {
            for (int k = 1; k <= 4; k++) if (v[k] != null) return true;
            return false;
        }

        public void run() {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            } catch (Throwable t) {
                // keep normal
            }
            final int chunk = 256;
            short[] out = new short[chunk * 2];
            float[] acc = new float[chunk * 2];
            int idle = 0;
            while (true) {
                try {
                    synchronized (lock) {
                        while (!busy()) {
                            if (track != null && idle > 0) {
                                try {
                                    track.pause();
                                    track.flush();
                                } catch (Throwable t) {
                                    // ignore
                                }
                                idle = 0;
                            }
                            lock.wait();
                        }
                        for (int i = 0; i < acc.length; i++) acc[i] = 0f;
                        for (int k = 1; k <= 4; k++) {
                            Voice o = v[k];
                            if (o == null) continue;
                            mix(o, acc, chunk);
                            if (o.done) v[k] = null;
                        }
                    }
                    for (int i = 0; i < out.length; i++) out[i] = clip(acc[i] * 0.9f);
                    if (track == null) track = newTrack();
                    if (track == null) {
                        stopAll();
                        continue;
                    }
                    if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
                    track.write(out, 0, out.length);
                    idle = 1;
                } catch (Throwable t) {
                    log("audio: " + t);
                    stopAll();
                    try {
                        if (track != null) track.release();
                    } catch (Throwable t2) {
                        // ignore
                    }
                    track = null;
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }

        static AudioTrack newTrack() {
            try {
                int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) min = 4096;
                return new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 2048), AudioTrack.MODE_STREAM);
            } catch (Throwable t) {
                log("track: " + t);
                return null;
            }
        }

        /** adds chunk frames of voice o to acc (stereo) */
        static void mix(Voice o, float[] acc, int chunk) {
            Snd s = o.s;
            short[] p = s.pcm;
            for (int i = 0; i < chunk; i++) {
                if (o.pos >= s.frames) {
                    o.done = true;
                    return;
                }
                float l = p[2 * o.pos], r = p[2 * o.pos + 1];
                if (o.held && s.xf > 0 && o.pos >= s.loopB - s.xf) {
                    // seam: end of the body cross-faded into its start
                    int j = s.loopA + (o.pos - (s.loopB - s.xf));
                    float t = (o.pos - (s.loopB - s.xf)) / (float) s.xf;
                    // linear shape, normalised so the loudness stays the same whatever the two parts' correlation
                    float k = 1f / (float) Math.sqrt((1f - t) * (1f - t) + t * t + 2f * s.corr * t * (1f - t));
                    float wa = (1f - t) * k, wb = t * k;
                    float ga = s.seamGain + (1f - s.seamGain) * t;   // end part brought to the start's level
                    l = l * wa * ga + p[2 * j] * wb;
                    r = r * wa * ga + p[2 * j + 1] * wb;
                }
                if (o.tailPos >= 0 && o.tailK < o.tailN) {
                    // release: cross-fade from where we are into the recorded ending
                    int q = o.tailPos + o.tailK;
                    if (q < s.frames) {
                        float t = o.tailK / (float) o.tailN;
                        float wa = (float) Math.cos(t * Math.PI / 2), wb = (float) Math.sin(t * Math.PI / 2);
                        l = l * wa + p[2 * q] * wb;
                        r = r * wa + p[2 * q + 1] * wb;
                    }
                    o.tailK++;
                }
                int next = o.pos + 1;
                if (o.tailPos >= 0 && o.tailK >= o.tailN) {
                    next = o.tailPos + o.tailN;          // the cross-fade is over: carry on in the ending
                    o.tailPos = -1;
                }
                float g = o.gain * o.fade / 32768f;
                acc[2 * i] += l * g;
                acc[2 * i + 1] += r * g;
                if (o.fadeMul < 1f) {
                    o.fade *= o.fadeMul;
                    if (o.fade < 0.001f) {
                        o.done = true;
                        return;
                    }
                }
                o.pos = next;
                if (o.held && o.pos >= s.loopB) o.pos = s.loopA + s.xf;
            }
        }
    }

    // ================================================================ rain + thunder sound
    /**
     * Rain and thunder, made here (no sound files needed).  Rain = several layers of filtered noise (the wash of
     * the rain, the hiss near by) plus single drops; thunder = a crack and a long low rolling rumble, now and then
     * while it storms.  Levels follow the TTS weather of the driver session (TTSCMD:rain=N from the HUD script):
     *   0 dry, 1 rain, 2 heavy rain, 3 thunderstorm, 4 drizzle.
     */
    static final class Weather implements Runnable {
        volatile int rain;
        volatile boolean paused;
        Thread th;
        AudioTrack track;
        final java.util.Random rnd = new java.util.Random();
        float level = 0f;                     // smoothed rain level 0..1
        // rain layers (stereo): wash (band-limited pink noise), patter (dense soft taps), near drops, roar
        float b0L, b1L, b2L, b0R, b1R, b2R;
        float w1L, w2L, w1R, w2R, hL, hR, hxL, hxR;
        float tapL, tapR, tapLp, tapRp;
        float dropEnv, dropPan, dropY1, dropY2, dropA1, dropA2;
        float roar1, roar2;
        float gust = 1f, gustT = 1f;
        int gustN;
        // thunder
        short[] thunder;                      // prepared rumble (mono)
        int thPos = -1;
        float thGain = 0f;
        long nextThunder = 0;

        synchronized void setRain(int r) {
            if (r < 0 || r > 9) r = 0;
            if (r != rain) TTSHornPad.log("weather: rain " + rain + " -> " + r);
            rain = r;
            if (r > 0 && (th == null || !th.isAlive())) {
                th = new Thread(this, "TTSWeather");
                th.setDaemon(true);
                th.start();
            }
            notifyAll();
        }

        synchronized void setPaused(boolean p) {
            paused = p;
            notifyAll();
        }

        static float target(int r) {
            switch (r) {
                case 1: return 0.45f;
                case 2: return 0.80f;
                case 3: return 1.00f;
                case 4: return 0.20f;
                default: return 0f;
            }
        }

        public void run() {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            } catch (Throwable t) {
                // keep
            }
            final int chunk = 512;
            short[] out = new short[chunk * 2];
            while (true) {
                try {
                    synchronized (this) {
                        while (paused || (rain == 0 && level < 0.002f && thPos < 0)) {
                            if (track != null) {
                                try {
                                    track.pause();
                                    track.flush();
                                } catch (Throwable t) {
                                    // ignore
                                }
                            }
                            wait();
                        }
                    }
                    render(out, chunk);
                    if (track == null) track = Mixer.newTrack();
                    if (track == null) {
                        Thread.sleep(500);
                        continue;
                    }
                    if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
                    track.write(out, 0, out.length);
                } catch (Throwable t) {
                    TTSHornPad.log("weather audio: " + t);
                    try {
                        if (track != null) track.release();
                    } catch (Throwable t2) {
                        // ignore
                    }
                    track = null;
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }

        /** fills chunk stereo frames */
        void render(short[] out, int chunk) {
            int r = rain;
            float tgt = target(r);
            boolean storm = r == 3;
            long now = System.currentTimeMillis();
            if (storm && thPos < 0 && now > nextThunder) {
                if (nextThunder != 0) {
                    float dist = rnd.nextFloat();                 // 0 = right overhead, 1 = far away
                    thunder = makeThunder(dist, rnd.nextLong());
                    thPos = 0;
                    thGain = 0.75f - 0.4f * dist;
                }
                nextThunder = now + 12000 + rnd.nextInt(26000);
            }
            if (!storm && thPos < 0) nextThunder = 0;
            for (int i = 0; i < chunk; i++) {
                level += (tgt - level) * (1f / (RATE * 2.5f));      // rain swells / dies away over a few seconds
                if (--gustN <= 0) {                                  // gusts: the rain comes in waves
                    gustT = 0.78f + 0.44f * rnd.nextFloat();
                    gustN = RATE * 2 + rnd.nextInt(RATE * 4);
                }
                gust += (gustT - gust) * (1f / (RATE * 1.5f));
                float wL = rnd.nextFloat() * 2f - 1f, wR = rnd.nextFloat() * 2f - 1f;
                // 1. wash: pink noise (Kellet economy), low-passed twice (~3 kHz) and high-passed (~150 Hz)
                b0L = 0.99765f * b0L + wL * 0.0990460f; b1L = 0.96300f * b1L + wL * 0.2965164f; b2L = 0.57000f * b2L + wL * 1.0526913f;
                b0R = 0.99765f * b0R + wR * 0.0990460f; b1R = 0.96300f * b1R + wR * 0.2965164f; b2R = 0.57000f * b2R + wR * 1.0526913f;
                float pL = (b0L + b1L + b2L + wL * 0.1848f) * 0.11f, pR = (b0R + b1R + b2R + wR * 0.1848f) * 0.11f;
                w1L += (pL - w1L) * 0.36f; w2L += (w1L - w2L) * 0.36f;
                w1R += (pR - w1R) * 0.36f; w2R += (w1R - w2R) * 0.36f;
                hL = 0.979f * (hL + w2L - hxL); hxL = w2L;
                hR = 0.979f * (hR + w2R - hxR); hxR = w2R;
                float L = hL * 1.6f, R = hR * 1.6f;
                // 2. patter: many small soft taps (decay ~4 ms, low-passed) - the texture of rain, no sizzle
                float rate = (0.010f + 0.025f * level) * gust;          // denser and softer: a hiss of taps, no single ones
                if (rnd.nextFloat() < rate) tapL = (rnd.nextFloat() * 0.6f + 0.2f) * (rnd.nextBoolean() ? 1f : -1f);
                if (rnd.nextFloat() < rate) tapR = (rnd.nextFloat() * 0.6f + 0.2f) * (rnd.nextBoolean() ? 1f : -1f);
                tapLp += (tapL * (rnd.nextFloat() + 0.5f) - tapLp) * 0.18f;
                tapRp += (tapR * (rnd.nextFloat() + 0.5f) - tapRp) * 0.18f;
                tapL *= 0.9945f;
                tapR *= 0.9945f;
                L += tapLp * 0.12f;
                R += tapRp * 0.12f;
                // (r115b: no single near drops any more - they sounded like dripping "top top")
                // 4. heavy rain / storm: a low roar
                roar1 += ((wL + wR) * 0.5f - roar1) * 0.010f;
                roar2 += (roar1 - roar2) * 0.010f;
                float roar = roar2 * 6f * Math.max(0f, level - 0.5f) * 2f;
                L += roar;
                R += roar;
                float g = level * 0.65f * gust;
                L *= g;
                R *= g;
                if (thPos >= 0) {
                    if (thPos < thunder.length) {
                        float t = thunder[thPos++] / 32768f * thGain;
                        L += t;
                        R += t;
                    } else thPos = -1;
                }
                // soft limit (a loud thunder clap must not crackle)
                L = L / (1f + Math.abs(L) * 0.5f) * 1.25f;
                R = R / (1f + Math.abs(R) * 0.5f) * 1.25f;
                out[2 * i] = clip(L);
                out[2 * i + 1] = clip(R);
            }
        }

        /** thunder: crack (close strikes) + low rolling rumble with a few swells, 5..9 s, mono 44.1 kHz */
        static short[] makeThunder(float dist, long seed) {
            java.util.Random q = new java.util.Random(seed);
            float len = 5f + 4f * q.nextFloat();
            int n = (int) (RATE * len);
            short[] s = new short[n];
            float lp1 = 0f, lp2 = 0f, lp3 = 0f;
            float cut = 0.020f + 0.03f * (1f - dist);              // farther = darker
            int swells = 2 + q.nextInt(4);
            float[] at = new float[swells], amp = new float[swells], wid = new float[swells];
            for (int k = 0; k < swells; k++) {
                at[k] = 0.15f + q.nextFloat() * (len * 0.55f);
                amp[k] = 0.45f + q.nextFloat() * 0.55f;
                wid[k] = 0.5f + q.nextFloat() * 1.4f;
            }
            float crack = dist < 0.35f ? (0.35f - dist) / 0.35f : 0f;
            float delay = 0.05f + dist * 0.6f;                      // light first, sound later
            for (int i = 0; i < n; i++) {
                float t = i / (float) RATE - delay;
                float w = q.nextFloat() * 2f - 1f;
                lp1 += (w - lp1) * cut;
                lp2 += (lp1 - lp2) * cut;
                lp3 += (lp2 - lp3) * 0.5f;
                float env = 0f;
                if (t > 0f) {
                    for (int k = 0; k < swells; k++) {
                        float x = (t - at[k]) / wid[k];
                        env += amp[k] * (x < 0f ? (float) Math.exp(-x * x * 18f) : (float) Math.exp(-x * 1.6f));
                    }
                    env *= Math.min(1f, t / 0.08f);
                }
                float v = lp3 * 9f * env;
                if (crack > 0f && t > 0f && t < 0.35f) v += w * crack * (float) Math.exp(-t * 14f) * 0.8f;
                float tail = Math.min(1f, (n - i) / (RATE * 0.8f));     // no click at the end
                v *= tail;
                if (v > 1f) v = 1f;
                if (v < -1f) v = -1f;
                s[i] = (short) (v * 30000f);
            }
            return s;
        }
    }

    // ================================================================ touch router
    /**
     * The game reads touches in its Activity.onTouchEvent, which only runs when no view takes the
     * gesture.  A pad view that took its finger therefore froze every other control and the free camera
     * while the horn was held.  This wrapper sits in front of the window: the pad's finger goes to the
     * pad only, and the game gets the same event with that finger taken out (its own DOWN / UP when its
     * first finger comes or its last finger goes), so camera and controls keep working during the horn.
     */
    static Router router;
    static Activity routedFor;

    static void installRouter(Activity a) {
        try {
            if (routedFor == a) return;
            Window w = a.getWindow();
            if (w == null) return;
            Window.Callback cb = w.getCallback();
            if (cb == null) return;
            if (Proxy.isProxyClass(cb.getClass()) && Proxy.getInvocationHandler(cb) instanceof Router) {
                routedFor = a;
                return;
            }
            log("router: window callback " + cb.getClass().getName());
            Router r = new Router(cb);
            Window.Callback p = (Window.Callback) Proxy.newProxyInstance(TTSHornPad.class.getClassLoader(),
                    new Class<?>[]{Window.Callback.class}, r);
            w.setCallback(p);
            router = r;
            routedFor = a;
            log("router on");
        } catch (Throwable t) {
            log("router: " + t);
            routedFor = null;
        }
    }

    static final class Router implements InvocationHandler {
        final Window.Callback orig;
        int padId = -1;            // pointer id held by the pad
        long gameDown;             // down time of the game's own part of the gesture

        Router(Window.Callback o) {
            orig = o;
        }

        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            String n = m.getName();
            if (args != null && args.length == 1 && args[0] instanceof MotionEvent && "dispatchTouchEvent".equals(n)) {
                MotionEvent ev = (MotionEvent) args[0];
                try {
                    return route(ev);
                } catch (Throwable t) {
                    log("route: " + t);
                    padId = -1;
                    return orig.dispatchTouchEvent(ev);
                }
            }
            if ("equals".equals(n) && args != null && args.length == 1) return proxy == args[0];
            if ("hashCode".equals(n) && (args == null || args.length == 0)) return System.identityHashCode(proxy);
            if ("toString".equals(n) && (args == null || args.length == 0)) return "TTSHornPad.Router";
            try {
                return m.invoke(orig, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        boolean route(MotionEvent ev) {
            PadView p = pad;
            int act = ev.getActionMasked();
            int ai = ev.getActionIndex();
            if (act == MotionEvent.ACTION_DOWN) {
                if (padId >= 0 && p != null) p.lift(true);
                padId = -1;
            }
            if ((act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) && padId < 0
                    && p != null && p.claim(ev.getX(ai), ev.getY(ai))) {
                padId = ev.getPointerId(ai);
                return forward(ev, act, ai);          // the game sees the other fingers only
            }
            if (padId < 0) {
                gameDown = ev.getDownTime();
                return orig.dispatchTouchEvent(ev);
            }
            int pi = ev.findPointerIndex(padId);
            boolean padGone = act == MotionEvent.ACTION_CANCEL
                    || ((act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_POINTER_UP) && ai == pi);
            if (p != null) {
                if (padGone) p.lift(act == MotionEvent.ACTION_CANCEL);
                else if (pi >= 0 && act == MotionEvent.ACTION_MOVE) p.drag(ev.getX(pi), ev.getY(pi));
            }
            boolean r = forward(ev, act, ai);
            if (padGone || pi < 0) padId = -1;
            return r;
        }

        boolean forward(MotionEvent ev, int act, int ai) {
            int n = ev.getPointerCount();
            int si = ev.findPointerIndex(padId);
            if (si < 0) {
                if (act == MotionEvent.ACTION_DOWN) gameDown = ev.getDownTime();
                return orig.dispatchTouchEvent(ev);
            }
            int m = n - 1;
            if (m <= 0) return true;                   // only the pad's finger
            MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[m];
            MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[m];
            int j = 0, nai = 0;
            for (int i = 0; i < n; i++) {
                if (i == si) continue;
                pp[j] = new MotionEvent.PointerProperties();
                ev.getPointerProperties(i, pp[j]);
                pc[j] = new MotionEvent.PointerCoords();
                ev.getPointerCoords(i, pc[j]);
                if (i == ai) nai = j;
                j++;
            }
            int na;
            if (ai == si && act != MotionEvent.ACTION_MOVE && act != MotionEvent.ACTION_CANCEL) {
                na = MotionEvent.ACTION_MOVE;          // the pad's finger came or went: nothing new for the game
            } else if (act == MotionEvent.ACTION_POINTER_DOWN) {
                na = m == 1 ? MotionEvent.ACTION_DOWN
                        : (MotionEvent.ACTION_POINTER_DOWN | (nai << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
            } else if (act == MotionEvent.ACTION_POINTER_UP) {
                na = m == 1 ? MotionEvent.ACTION_UP
                        : (MotionEvent.ACTION_POINTER_UP | (nai << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
            } else {
                na = act;
            }
            if (na == MotionEvent.ACTION_DOWN) gameDown = ev.getEventTime();
            MotionEvent e2 = MotionEvent.obtain(gameDown, ev.getEventTime(), na, m, pp, pc, ev.getMetaState(),
                    ev.getButtonState(), ev.getXPrecision(), ev.getYPrecision(), ev.getDeviceId(),
                    ev.getEdgeFlags(), ev.getSource(), ev.getFlags());
            try {
                return orig.dispatchTouchEvent(e2);
            } finally {
                e2.recycle();
            }
        }
    }

    // ================================================================ the pad
    static final int[] DIR_COL_OFF = {0, 0xFF8FA2C4, 0xFF8FA2C4, 0xFF8FA2C4, 0xFF8FA2C4};
    static final String[] LABEL = {"", "HORN", "EXTRA", "BELL", "SAND"};
    static final String[] EDIT_LABEL = {"", "+", "OK", "−", "HIDE"};
    static final float MIN_SCALE = 0.5f, MAX_SCALE = 1.8f;

    /**
     * The pad.  Long-press the knob = SETTINGS (orange): drag the knob to move the pad, tap "+" / "-" to make
     * it bigger / smaller, "HIDE" folds it into a small round horn button (tap it to open the pad again,
     * drag it to move it), "OK" ends the settings.  Place, size and hidden state are remembered.  The pad
     * has its own hide; the HUD's "TTS hide" no longer touches it.
     */
    static final class PadView extends View {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final float dpi;
        final int[] loc = new int[2];
        State st = new State();
        boolean userPlaced;
        float ux, uy;                  // user place (top-left), parts of the screen
        float scale = 1f;              // user size
        boolean hidden;                // folded into the small horn button
        int size = 10;
        // touch (window coordinates, fed by the Router)
        boolean held;
        float kx, ky;                  // knob offset from the centre (px)
        int dir;                       // side held: 0 none, 1 up, 2 right, 3 down, 4 left
        boolean edit;                  // settings mode
        boolean editDrag;              // settings: the pad follows the finger
        int editAct;                   // settings: side touched (tap on release)
        boolean miniTap, miniDrag;
        boolean gearTap;               // r118: the small gear button at the top-right corner
        float downX, downY;
        int startLeft, startTop;
        final Runnable longPress = new Runnable() {
            public void run() {
                if (held && dir == 0 && !edit && !hidden) {
                    edit = true;
                    editDrag = true;
                    log("settings open (long-press)");
                    kx = 0;
                    ky = 0;
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    invalidate();
                }
            }
        };
        final Runnable editEnd = new Runnable() {
            public void run() {
                if (edit && !held) {
                    edit = false;
                    invalidate();
                }
            }
        };
        final Runnable spring = new Runnable() {
            public void run() {
                if (held && !edit) return;
                kx *= 0.55f;
                ky *= 0.55f;
                if (Math.abs(kx) < 1 && Math.abs(ky) < 1) {
                    kx = 0;
                    ky = 0;
                } else {
                    postDelayed(this, 16);
                }
                invalidate();
            }
        };

        PadView(Activity a) {
            super(a);
            DisplayMetrics m = a.getResources().getDisplayMetrics();
            dpi = m.densityDpi > 0 ? m.densityDpi : 320;
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
            try {
                SharedPreferences sp = a.getSharedPreferences("tts_hornpad", Context.MODE_PRIVATE);
                userPlaced = sp.getBoolean("placed", false);
                ux = sp.getFloat("x", 0.2f);
                uy = sp.getFloat("y", 0.32f);
                scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, sp.getFloat("scale", 1f)));
                hidden = sp.getBoolean("hidden", false);
            } catch (Throwable t) {
                userPlaced = false;
            }
        }

        void setState(State s) {
            st = s;
            if (!s.on) {
                dropTouch();
                edit = false;
                setVisibility(View.GONE);
                return;
            }
            setVisibility(View.VISIBLE);
            place();
            invalidate();
        }

        int screenW() {
            View root = getRootView();
            int w = root != null ? root.getWidth() : 0;
            return w > 0 ? w : getResources().getDisplayMetrics().widthPixels;
        }

        int screenH() {
            View root = getRootView();
            int h = root != null ? root.getHeight() : 0;
            return h > 0 ? h : getResources().getDisplayMetrics().heightPixels;
        }

        int fullSize() {
            int d = (int) (st.d * screenH() * scale);
            return Math.max((int) (dpi * 0.45f), Math.min(d, (int) (screenH() * 0.9f)));
        }

        int miniSize() {
            return Math.max(56, (int) (dpi * 0.36f));
        }

        void place() {
            int sw = screenW(), sh = screenH();
            int d = hidden ? miniSize() : fullSize();
            float fx = userPlaced ? ux : st.x;
            float fy = userPlaced ? uy : st.y;
            int left = (int) (fx * sw);
            int top = (int) (fy * sh);
            left = Math.max(0, Math.min(left, sw - d));
            top = Math.max(0, Math.min(top, sh - d));
            ViewGroup.LayoutParams p = getLayoutParams();
            if (p instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) p;
                if (lp.width != d || lp.height != d || lp.leftMargin != left || lp.topMargin != top) {
                    lp.width = d;
                    lp.height = d;
                    lp.leftMargin = left;
                    lp.topMargin = top;
                    lp.gravity = Gravity.TOP | Gravity.LEFT;
                    setLayoutParams(lp);
                }
            }
            size = d;
        }

        void dropTouch() {
            removeCallbacks(longPress);
            if (dir != 0) mixer.release(dir);
            dir = 0;
            held = false;
            editDrag = false;
            editAct = 0;
            miniTap = false;
            miniDrag = false;
            gearTap = false;
            post(spring);
        }

        /** fallback when the window router could not be installed: the view gets its own touches */
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (routedFor != null && routedFor == owner) return false;
            try {
                int act = e.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN) return claim(e.getRawX(), e.getRawY());
                if (act == MotionEvent.ACTION_MOVE) drag(e.getRawX(), e.getRawY());
                else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) lift(act == MotionEvent.ACTION_CANCEL);
                return held;
            } catch (Throwable t) {
                log("touch: " + t);
                return false;
            }
        }

        /** a finger came down at window (x, y): true = it is on the pad and the pad keeps it */
        boolean claim(float wx, float wy) {
            if (getVisibility() != View.VISIBLE || getWidth() <= 0) return false;
            getLocationInWindow(loc);
            float c = getWidth() / 2f, R = c - 2;
            float dx = wx - loc[0] - c, dy = wy - loc[1] - c;
            boolean gear = false;
            if (!hidden) {
                float g = getWidth() * 0.11f;
                float gx = wx - loc[0] - (getWidth() - g), gy = wy - loc[1] - g;
                gear = gx * gx + gy * gy < g * g * 2.0f;
            }
            if (!gear && dx * dx + dy * dy > R * R) return false;   // outside the circle: the game gets it
            held = true;
            downX = wx;
            downY = wy;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            startLeft = lp.leftMargin;
            startTop = lp.topMargin;
            removeCallbacks(spring);
            removeCallbacks(editEnd);
            if (hidden) {
                miniTap = true;
                miniDrag = false;
                invalidate();
                return true;
            }
            if (gear) {
                gearTap = true;
                log("gear down");
                invalidate();
                return true;
            }
            float rk = R * 0.27f;
            if (edit) {
                editDrag = dx * dx + dy * dy < rk * rk * 2.2f;
                editAct = 0;
                if (!editDrag) {
                    if (Math.abs(dx) > Math.abs(dy)) editAct = dx > 0 ? 2 : 4;
                    else editAct = dy < 0 ? 1 : 3;
                }
                invalidate();
                return true;
            }
            if (dx * dx + dy * dy < rk * rk * 1.6f) postDelayed(longPress, 550);
            follow(dx, dy);
            return true;
        }

        void drag(float wx, float wy) {
            if (!held) return;
            float mx = wx - downX, my = wy - downY;
            float far = mx * mx + my * my;
            if (gearTap) {
                if (far > 40 * 40) gearTap = false;
                return;
            }
            if (hidden) {
                if (far > 22 * 22) {
                    miniTap = false;
                    miniDrag = true;
                }
                if (miniDrag) movePad(mx, my);
                return;
            }
            if (edit) {
                if (editDrag) movePad(mx, my);
                else if (far > 40 * 40 && editAct != 0) {
                    editAct = 0;
                    invalidate();
                }
                return;
            }
            if (far > 18 * 18) removeCallbacks(longPress);
            getLocationInWindow(loc);
            float c = getWidth() / 2f;
            follow(wx - loc[0] - c, wy - loc[1] - c);
        }

        void lift(boolean cancel) {
            if (!held) return;
            held = false;
            removeCallbacks(longPress);
            if (gearTap) {
                gearTap = false;
                if (!cancel) {
                    edit = !edit;
                    log("settings " + (edit ? "open" : "closed"));
                    if (dir != 0) mixer.release(dir);
                    dir = 0;
                    kx = 0;
                    ky = 0;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (edit) postDelayed(editEnd, 12000);
                }
                invalidate();
                return;
            }
            if (hidden) {
                if (miniDrag) savePlace();
                else if (miniTap && !cancel) setHidden(false);
                miniTap = false;
                miniDrag = false;
                invalidate();
                return;
            }
            if (edit) {
                if (editDrag) savePlace();
                else if (!cancel && editAct != 0) editTap(editAct);
                editDrag = false;
                editAct = 0;
                if (edit) postDelayed(editEnd, 12000);       // settings close by themselves after 12 s
                invalidate();
                return;
            }
            if (dir != 0) mixer.release(dir);
            dir = 0;
            post(spring);
            invalidate();
        }

        void editTap(int k) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (k == 1 || k == 3) {
                float ns = Math.round((scale + (k == 1 ? 0.1f : -0.1f)) * 10f) / 10f;
                scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, ns));
                savePrefs();
                place();
            } else if (k == 4) {
                edit = false;
                setHidden(true);
            } else {
                edit = false;
            }
            invalidate();
        }

        void setHidden(boolean h) {
            hidden = h;
            if (h) {
                if (dir != 0) mixer.release(dir);
                dir = 0;
            }
            kx = 0;
            ky = 0;
            savePrefs();
            place();
            invalidate();
        }

        void movePad(float mx, float my) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            int sw = screenW(), sh = screenH();
            lp.leftMargin = Math.max(0, Math.min(startLeft + (int) mx, sw - getWidth()));
            lp.topMargin = Math.max(0, Math.min(startTop + (int) my, sh - getHeight()));
            setLayoutParams(lp);
        }

        /** knob follows the finger; past 30 % of the radius the side is held */
        void follow(float dx, float dy) {
            float R = getWidth() / 2f - 2;
            float max = R * 0.62f;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > max) {
                dx = dx * max / len;
                dy = dy * max / len;
                len = max;
            }
            kx = dx;
            ky = dy;
            int nd = 0;
            if (len > R * 0.30f) {
                if (Math.abs(dx) > Math.abs(dy)) nd = dx > 0 ? 2 : 4;
                else nd = dy < 0 ? 1 : 3;
            }
            if (nd != dir) {
                if (dir != 0) mixer.release(dir);
                dir = nd;
                if (nd != 0) {
                    removeCallbacks(longPress);
                    mixer.press(nd);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
            }
            invalidate();
        }

        void savePlace() {
            try {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
                ux = lp.leftMargin / (float) screenW();
                uy = lp.topMargin / (float) screenH();
                userPlaced = true;
                savePrefs();
            } catch (Throwable t) {
                log("save: " + t);
            }
        }

        void savePrefs() {
            try {
                getContext().getSharedPreferences("tts_hornpad", Context.MODE_PRIVATE).edit()
                        .putBoolean("placed", userPlaced).putFloat("x", ux).putFloat("y", uy)
                        .putFloat("scale", scale).putBoolean("hidden", hidden).apply();
            } catch (Throwable t) {
                log("prefs: " + t);
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            try {
                if (hidden) drawMini(cv);
                else draw2(cv);
            } catch (Throwable t) {
                log("draw: " + t);
            }
        }

        void drawMini(Canvas cv) {
            float c = getWidth() / 2f;
            float R = c - 2;
            if (R < 6) return;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(miniTap || miniDrag ? 0xE02EA8FF : 0xC80B1322);
            cv.drawCircle(c, c, R, fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Math.max(2f, R * 0.07f));
            fill.setColor(0xCC2EA8FF);
            cv.drawCircle(c, c, R - fill.getStrokeWidth() / 2, fill);
            fill.setStyle(Paint.Style.FILL);
            speaker(cv, c, c - R * 0.12f, R * 0.42f);
            text.setColor(0xE0FFFFFF);
            text.setTextSize(Math.max(8f, R * 0.30f));
            cv.drawText("HORN", c, c + R * 0.66f, text);
        }

        void draw2(Canvas cv) {
            float c = getWidth() / 2f;
            float R = c - 2;
            if (R < 10) return;
            // base
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xD80B1322);
            cv.drawCircle(c, c, R, fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Math.max(2f, R * 0.025f));
            fill.setColor(edit ? 0xFFFF8A1E : 0x664A6390);
            cv.drawCircle(c, c, R - fill.getStrokeWidth() / 2, fill);
            fill.setStyle(Paint.Style.FILL);
            // sides: dot + name (settings: + / OK / - / HIDE)
            float edge = R * 0.16f;
            float dr = Math.max(3f, R / 16f);
            for (int k = 1; k <= 4; k++) {
                float px = c, py = c;
                if (k == 1) py = c - R + edge;
                else if (k == 2) px = c + R - edge;
                else if (k == 3) py = c + R - edge;
                else px = c - R + edge;
                if (edit) {
                    boolean hot = k == editAct;
                    float bx = c, by = c, off = R * 0.62f;
                    if (k == 1) by = c - off;
                    else if (k == 2) bx = c + off;
                    else if (k == 3) by = c + off;
                    else bx = c - off;
                    fill.setColor(hot ? 0xFFFF8A1E : 0x88FF8A1E);
                    cv.drawCircle(bx, by, R * 0.26f, fill);
                    text.setColor(0xFFFFFFFF);
                    text.setTextSize(Math.max(9f, R * (k == 1 || k == 3 ? 0.26f : 0.13f)));
                    cv.drawText(EDIT_LABEL[k], bx, by + text.getTextSize() * 0.36f, text);
                    continue;
                }
                text.setTextSize(Math.max(9f, R * 0.115f));
                boolean ok = mixer.has(k);
                boolean on = (k == dir);
                int col = on ? 0xFF2EA8FF : (ok ? 0xFF8FA2C4 : 0xFF4A5468);
                fill.setColor(col);
                cv.drawCircle(px, py, dr, fill);
                text.setColor(on ? 0xFFFFFFFF : (ok ? 0xC8A9B8D2 : 0x80707A90));
                float tx = px, ty = py;
                float gap = dr + text.getTextSize() * 0.95f;
                if (k == 1) ty = py + gap + text.getTextSize() * 0.35f;
                else if (k == 3) ty = py - gap + text.getTextSize() * 0.35f;
                else if (k == 2) {
                    text.setTextAlign(Paint.Align.RIGHT);
                    tx = px - dr * 2.2f;
                    ty = py + text.getTextSize() * 0.35f;
                } else {
                    text.setTextAlign(Paint.Align.LEFT);
                    tx = px + dr * 2.2f;
                    ty = py + text.getTextSize() * 0.35f;
                }
                cv.drawText(LABEL[k], tx, ty, text);
                text.setTextAlign(Paint.Align.CENTER);
            }
            // knob
            float rk = R * 0.27f;
            float x = c + kx, y = c + ky;
            fill.setColor(0x33000000);
            cv.drawCircle(x, y + rk * 0.08f, rk * 1.04f, fill);
            fill.setColor(edit ? 0xFFFF8A1E : (dir != 0 ? 0xFF2EA8FF : 0xFF2C3D5E));
            cv.drawCircle(x, y, rk, fill);
            fill.setColor(0x26FFFFFF);
            cv.drawCircle(x, y - rk * 0.25f, rk * 0.62f, fill);
            if (edit) {
                text.setColor(0xFFFFFFFF);
                text.setTextSize(Math.max(9f, rk * 0.40f));
                cv.drawText("MOVE", x, y + text.getTextSize() * 0.35f, text);
            } else {
                speaker(cv, x, y, rk * 0.55f);
            }
            gear(cv);
        }

        /** r118: settings button in the free top-right corner of the pad's square */
        void gear(Canvas cv) {
            float w = getWidth();
            float g = w * 0.11f;
            float gx = w - g, gy = g, r = g * 0.82f;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(edit || gearTap ? 0xFFFF8A1E : 0xD80B1322);
            cv.drawCircle(gx, gy, r, fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Math.max(1.5f, r * 0.10f));
            fill.setColor(edit ? 0xFFFFFFFF : 0xCCFF8A1E);
            cv.drawCircle(gx, gy, r * 0.97f, fill);
            fill.setColor(0xFFFFFFFF);
            fill.setStrokeWidth(Math.max(2f, r * 0.20f));
            fill.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                cv.drawLine(gx + ca * r * 0.38f, gy + sa * r * 0.38f, gx + ca * r * 0.62f, gy + sa * r * 0.62f, fill);
            }
            fill.setStrokeWidth(Math.max(1.5f, r * 0.14f));
            cv.drawCircle(gx, gy, r * 0.33f, fill);
            fill.setStyle(Paint.Style.FILL);
        }

        void speaker(Canvas cv, float x, float y, float s) {
            fill.setColor(0xFFFFFFFF);
            fill.setStyle(Paint.Style.FILL);
            path.reset();
            path.moveTo(x - s * 0.9f, y - s * 0.30f);
            path.lineTo(x - s * 0.45f, y - s * 0.30f);
            path.lineTo(x + s * 0.05f, y - s * 0.75f);
            path.lineTo(x + s * 0.05f, y + s * 0.75f);
            path.lineTo(x - s * 0.45f, y + s * 0.30f);
            path.lineTo(x - s * 0.9f, y + s * 0.30f);
            path.close();
            cv.drawPath(path, fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Math.max(1.5f, s * 0.13f));
            fill.setStrokeCap(Paint.Cap.ROUND);
            android.graphics.RectF r1 = new android.graphics.RectF(x - s * 0.15f, y - s * 0.45f, x + s * 0.55f, y + s * 0.45f);
            cv.drawArc(r1, -50, 100, false, fill);
            android.graphics.RectF r2 = new android.graphics.RectF(x - s * 0.25f, y - s * 0.80f, x + s * 0.95f, y + s * 0.80f);
            cv.drawArc(r2, -50, 100, false, fill);
            fill.setStyle(Paint.Style.FILL);
        }

        void arrow(Canvas cv, float x, float y, float s, int k, int col) {
            fill.setColor(col);
            fill.setStyle(Paint.Style.FILL);
            path.reset();
            if (k == 1) {
                path.moveTo(x, y - s * 0.5f); path.lineTo(x + s * 0.6f, y + s * 0.4f); path.lineTo(x - s * 0.6f, y + s * 0.4f);
            } else if (k == 3) {
                path.moveTo(x, y + s * 0.5f); path.lineTo(x + s * 0.6f, y - s * 0.4f); path.lineTo(x - s * 0.6f, y - s * 0.4f);
            } else if (k == 2) {
                path.moveTo(x + s * 0.5f, y); path.lineTo(x - s * 0.4f, y + s * 0.6f); path.lineTo(x - s * 0.4f, y - s * 0.6f);
            } else {
                path.moveTo(x - s * 0.5f, y); path.lineTo(x + s * 0.4f, y + s * 0.6f); path.lineTo(x + s * 0.4f, y - s * 0.6f);
            }
            path.close();
            cv.drawPath(path, fill);
        }
    }
}
