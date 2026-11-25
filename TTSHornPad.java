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
import android.graphics.RectF;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * the pad (or a long-press on the knob) = settings:  r119: the horn is heard from where the camera is
 * (TTSCAM: 1/r loudness, air low-pass, muffled in the cab).
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
    static final Hiss hiss = new Hiss();           // r143: air brake hiss
    static final boolean SQUEAL_ON = false;        // r144c: squeal switched off for now (code kept; set true when a good sample is in)
    static final Squeal squeal = new Squeal();     // r144: wheel/brake squeal while braking below 12 km/h
    static volatile boolean brk;                   // brake applied (from TTSBRK)

    // ---------------------------------------------------------------- provider plumbing
    @Override
    public boolean onCreate() {
        try {
            app = getContext().getApplicationContext();
            ((Application) app).registerActivityLifecycleCallbacks(this);
        } catch (Throwable t) {
            log("onCreate: " + t);
        }
        installAddons(app);
        Markers.start(app);
        return true;
    }

    // ================================================================ r162: marker lamps at the ditch-light points
    /**
     * Every locomotive in UserData (addons) whose mesh has ditch-light attachment points (a.ditch0 .. a.ditch3)
     * gets TTS marker lamps there - corona effects tts_marker_front_d0 / _d1 (a.ditch0 / 1) and
     * tts_marker_back_d2 / _d3 (a.ditch2 / 3), invisible until the CONTROL PANEL switches them on in white or
     * red.  Nothing has to be written in the loco by its maker.  The config.txt is changed once (a copy is
     * kept as config.txt.ttsorig); its new size makes the loader ask the game for a database update at the
     * next start.  Runs in the background; checked folders are remembered (tts_markers.idx).
     */
    static final class Markers implements Runnable {
        static final String TAG = "tts_marker_";
        static final String VER = "m1";
        final Context c;

        Markers(Context c) {
            this.c = c;
        }

        static void start(Context c) {
            if (c == null) return;
            // r163: only phones that ran r162 (its index file is there) - done here, before the game's own
            // loader starts, so the database is put right in this same start
            if (!new File(c.getFilesDir(), "tts_markers.idx").isFile()) return;
            new Markers(c).run();
        }

        // r163: the r162 config change made many addon locos FAULTY after the database update (his log:
        // "has faulty dependencies due to <kuid:761728:100546>" and more).  Undone: every config.txt that has a
        // config.txt.ttsorig gets its original back (the size change makes the loader update the database
        // again at the next start).  Nothing is added to any loco any more.
        public void run() {
            try {
                File files = new File("/sdcard/Android/data/" + c.getPackageName() + "/files/UserData");
                int restored = 0;
                for (String root : new String[]{"original", "local"}) {
                    File[] hashes = new File(files, root).listFiles();
                    if (hashes == null) continue;
                    for (File h : hashes) {
                        File[] assets = h.listFiles();
                        if (assets == null) continue;
                        for (File a : assets) {
                            File bak = new File(a, "config.txt.ttsorig");
                            if (!bak.isFile()) continue;
                            try {
                                File cfg = new File(a, "config.txt");
                                File tmp = new File(a, "config.txt.ttsnew");
                                byte[] d = readAll(bak);
                                FileOutputStream o = new FileOutputStream(tmp);
                                try {
                                    o.write(d);
                                } finally {
                                    o.close();
                                }
                                if (tmp.renameTo(cfg)) {
                                    bak.delete();
                                    restored++;
                                } else tmp.delete();
                            } catch (Throwable e) {
                                log("markers: restore " + a.getName() + ": " + e);
                            }
                        }
                    }
                }
                new File(c.getFilesDir(), "tts_markers.idx").delete();
                if (restored > 0) log("markers: " + restored + " loco configs restored to the original (database update at the next start)");
            } catch (Throwable t) {
                log("markers: " + t);
            }
        }

        /** tokens of a Trainz config: words / quoted strings / { / } with their start and end offsets */
        static java.util.ArrayList<int[]> tokens(String s) {
            java.util.ArrayList<int[]> t = new java.util.ArrayList<int[]>();
            int i = 0, n = s.length();
            while (i < n) {
                char ch = s.charAt(i);
                if (ch <= ' ') {
                    i++;
                } else if (ch == '{' || ch == '}') {
                    t.add(new int[]{i, i + 1});
                    i++;
                } else if (ch == '"') {
                    int j = s.indexOf('"', i + 1);
                    if (j < 0) j = n - 1;
                    t.add(new int[]{i, j + 1});
                    i = j + 1;
                } else {
                    int j = i;
                    while (j < n && s.charAt(j) > ' ' && s.charAt(j) != '{' && s.charAt(j) != '}' && s.charAt(j) != '"') j++;
                    t.add(new int[]{i, j});
                    i = j;
                }
            }
            return t;
        }

        static String tok(String s, int[] k) {
            String v = s.substring(k[0], k[1]);
            if (v.length() >= 2 && v.charAt(0) == '"') v = v.substring(1, v.length() - 1);
            return v;
        }

        static File child(File dir, String name) {
            File f = new File(dir, name);
            if (f.isFile()) return f;
            File cur = dir;                              // any letter case, part by part (a/B/c.im)
            for (String part : name.replace('\\', '/').split("/")) {
                if (part.length() == 0) continue;
                File next = new File(cur, part);
                if (!next.exists()) {
                    String[] l = cur.list();
                    if (l != null) for (String x : l) if (x.equalsIgnoreCase(part)) next = new File(cur, x);
                }
                cur = next;
            }
            return cur.isFile() ? cur : f;
        }

        /** which of a.ditch0..3 the mesh file names (bit 0..3) */
        static int ditchPoints(File mesh) throws IOException {
            if (!mesh.isFile() || mesh.length() > 64L * 1024 * 1024) return 0;
            byte[] d = new byte[(int) mesh.length()];
            FileInputStream in = new FileInputStream(mesh);
            try {
                int o = 0, r;
                while (o < d.length && (r = in.read(d, o, d.length - o)) > 0) o += r;
            } finally {
                in.close();
            }
            int bits = 0;
            byte[] pat = "a.ditch".getBytes("ISO-8859-1");
            outer:
            for (int i = 0; i + pat.length < d.length; i++) {
                for (int k = 0; k < pat.length; k++) if (d[i + k] != pat[k]) continue outer;
                int dg = d[i + pat.length] - '0';
                if (dg >= 0 && dg <= 3) bits |= 1 << dg;
            }
            return bits;
        }

        /** adds the TTS marker coronas to one loco; returns the number of lamps added */
        static int patch(File dir, File cfg) throws IOException {
            if (cfg.length() > 1024 * 1024) return 0;
            byte[] raw = new byte[(int) cfg.length()];
            FileInputStream in = new FileInputStream(cfg);
            try {
                int o = 0, r;
                while (o < raw.length && (r = in.read(raw, o, raw.length - o)) > 0) o += r;
            } finally {
                in.close();
            }
            if (raw.length >= 2 && ((raw[0] & 0xFF) == 0xFF || (raw[0] & 0xFF) == 0xFE)) return 0;   // UTF-16: leave it
            String s = new String(raw, "ISO-8859-1");
            if (s.indexOf(TAG) >= 0) return 0;                     // done already
            java.util.ArrayList<int[]> t = tokens(s);
            // a locomotive: kind "traincar" with an engine
            boolean traincar = false, engine = false;
            int depth = 0;
            for (int i = 0; i < t.size(); i++) {
                String v = tok(s, t.get(i));
                if (v.equals("{")) depth++;
                else if (v.equals("}")) depth--;
                else if (depth == 0 && i + 1 < t.size()) {
                    String nx = tok(s, t.get(i + 1));
                    if (v.equalsIgnoreCase("kind") && nx.equalsIgnoreCase("traincar")) traincar = true;
                    if (v.equalsIgnoreCase("engine") && nx.equals("1")) engine = true;
                }
            }
            if (!traincar || !engine) return 0;
            // the mesh-table entry whose mesh has the ditch points
            depth = 0;
            int insertAt = -1, bits = 0;
            boolean hasEffects = false;
            for (int i = 0; i < t.size() && insertAt < 0; i++) {
                String v = tok(s, t.get(i));
                if (v.equals("{")) {
                    depth++;
                    continue;
                }
                if (v.equals("}")) {
                    depth--;
                    continue;
                }
                if (depth != 0 || !v.equalsIgnoreCase("mesh-table") || i + 1 >= t.size() || !tok(s, t.get(i + 1)).equals("{"))
                    continue;
                // walk the entries of the mesh-table
                int j = i + 2, d2 = 1;
                while (j < t.size() && d2 > 0 && insertAt < 0) {
                    String w = tok(s, t.get(j));
                    if (w.equals("}")) {
                        d2--;
                        j++;
                        continue;
                    }
                    if (d2 == 1 && j + 1 < t.size() && tok(s, t.get(j + 1)).equals("{")) {
                        // one entry: find its mesh, effects and end
                        int k = j + 2, d3 = 1, effOpen = -1, close = -1;
                        String mesh = null;
                        while (k < t.size()) {
                            String x = tok(s, t.get(k));
                            if (x.equals("{")) d3++;
                            else if (x.equals("}")) {
                                d3--;
                                if (d3 == 0) {
                                    close = k;
                                    break;
                                }
                            } else if (d3 == 1 && k + 1 < t.size()) {
                                String y = tok(s, t.get(k + 1));
                                if (x.equalsIgnoreCase("mesh") && !y.equals("{")) mesh = y;
                                if (x.equalsIgnoreCase("effects") && y.equals("{")) effOpen = k + 1;
                            }
                            k++;
                        }
                        if (close < 0) break;
                        if (mesh != null) {
                            int b = ditchPoints(child(dir, mesh));
                            if (b == 0 && mesh.toLowerCase(Locale.US).endsWith(".lm.txt")) {
                                // level-of-detail list: look in the meshes it names
                                File lm = child(dir, mesh);
                                if (lm.isFile()) {
                                    String lt = new String(readAll(lm), "ISO-8859-1");
                                    java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\"([^\"]+\\.(im|trainzmesh))\"",
                                            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(lt);
                                    while (mm.find()) b |= ditchPoints(child(dir, mm.group(1)));
                                }
                            }
                            if (b != 0) {
                                bits = b;
                                if (effOpen >= 0) {
                                    insertAt = t.get(effOpen)[1];
                                    hasEffects = true;
                                } else insertAt = t.get(close)[0];
                            }
                        }
                        d2 = 1;
                        j = close + 1;
                        continue;
                    }
                    j++;
                }
            }
            if (insertAt < 0 || bits == 0) return 0;
            String nl = s.indexOf("\r\n") >= 0 ? "\r\n" : "\n";
            StringBuilder e = new StringBuilder();
            int n = 0;
            for (int i = 0; i < 4; i++) {
                if ((bits & (1 << i)) == 0) continue;
                String name = TAG + (i < 2 ? "front" : "back") + "_d" + i;
                e.append(nl).append("      ").append(name).append(nl).append("      {").append(nl)
                        .append("        kind                            \"corona\"").append(nl)
                        .append("        att                             \"a.ditch").append(i).append("\"").append(nl)
                        .append("        texture-kuid                    <kuid:-1:6338>").append(nl)
                        .append("        object-size                     0.45").append(nl)
                        .append("      }").append(nl);
                n++;
            }
            String ins = hasEffects ? e.toString()
                    : "  effects" + nl + "    {" + e.toString() + "    }" + nl + "  ";
            String out = s.substring(0, insertAt) + ins + s.substring(insertAt);
            File bak = new File(dir, "config.txt.ttsorig");
            if (!bak.exists()) {
                FileOutputStream o = new FileOutputStream(bak);
                try {
                    o.write(raw);
                } finally {
                    o.close();
                }
            }
            File tmp = new File(dir, "config.txt.ttsnew");
            FileOutputStream o = new FileOutputStream(tmp);
            try {
                o.write(out.getBytes("ISO-8859-1"));
            } finally {
                o.close();
            }
            if (!tmp.renameTo(cfg)) {
                tmp.delete();
                return 0;
            }
            return n;
        }

        static byte[] readAll(File f) throws IOException {
            byte[] d = new byte[(int) Math.min(f.length(), 4L * 1024 * 1024)];
            FileInputStream in = new FileInputStream(f);
            try {
                int o = 0, r;
                while (o < d.length && (r = in.read(d, o, d.length - o)) > 0) o += r;
            } finally {
                in.close();
            }
            return d;
        }
    }

    // r159: TTS built-in addons (assets/tts_addons/hash-XX/<asset folder>/...) are put once per version into
    // Android/media/<pkg>/Addons/hash-XX/, before the game starts; the loader's own addon import moves them into
    // UserData/original and asks the engine for its database update, exactly like an addon the player adds.
    static final String ADDONS_VER = "r159-door1";

    static void installAddons(Context c) {
        if (c == null) return;
        try {
            SharedPreferences sp = c.getSharedPreferences("tts_addons", Context.MODE_PRIVATE);
            android.content.res.AssetManager am = c.getAssets();
            String[] hashes = am.list("tts_addons");
            if (hashes == null || hashes.length == 0) return;
            File media = null;
            try {
                File[] m = c.getExternalMediaDirs();
                if (m != null && m.length > 0) media = m[0];
            } catch (Throwable t) {
                // old Android
            }
            if (media == null) media = new File("/sdcard/Android/media/" + c.getPackageName());
            for (String h : hashes) {
                String[] assets = am.list("tts_addons/" + h);
                if (assets == null) continue;
                for (String a : assets) {
                    String key = h + "/" + a;
                    if (ADDONS_VER.equals(sp.getString(key, ""))) continue;     // once per TTS version
                    File dst = new File(new File(new File(media, "Addons"), h), a);
                    int n = copyAssetDir(am, "tts_addons/" + key, dst);
                    if (n > 0) {
                        sp.edit().putString(key, ADDONS_VER).apply();
                        log("addon " + key + ": " + n + " files -> " + dst);
                    }
                }
            }
        } catch (Throwable t) {
            log("addons: " + t);
        }
    }

    static int copyAssetDir(android.content.res.AssetManager am, String path, File dst) throws IOException {
        String[] kids = am.list(path);
        if (kids == null || kids.length == 0) {
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            InputStream in = am.open(path);
            try {
                FileOutputStream out = new FileOutputStream(dst);
                try {
                    byte[] b = new byte[16384];
                    int r;
                    while ((r = in.read(b)) > 0) out.write(b, 0, r);
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            return 1;
        }
        int n = 0;
        dst.mkdirs();
        for (String k : kids) n += copyAssetDir(am, path + "/" + k, new File(dst, k));
        return n;
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
            if (a == owner) {
                // r158: sound back on first: a resume that the intro / login check turned back early (system
                // dialog of the screen recorder, notification shade) left every TTS sound paused for good
                weather.setPaused(false);
                hiss.setPaused(false);
                squeal.setPaused(false);
                TTSSms.setPaused(false);
            }
            final Activity fa = a;                         // r145b: intro overlay first, then this same start again
            if (!TTSIntro.check(a, new Runnable() {
                public void run() {
                    onActivityResumed(fa);
                }
            })) return;
            if (!TTSGate.check(a)) return;                 // login gate: the game stays shut until an approved login
            if (owner != a || hud.view == null || hud.view.getParent() == null) {
                hud.loadPrefs(a);
                HudView hv = new HudView(a);
                a.addContentView(hv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                hud.view = hv;
                hv.update();
            }
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
            hiss.setPaused(false);
            squeal.setPaused(false);
            TTSSms.setPaused(false);
            hud.resume();
            boost.start();
            watchRecording(a);
        } catch (Throwable t) {
            log("attach: " + t);
        }
    }

    @Override
    public void onActivityPaused(Activity a) {
        try {
            TTSIntro.pause(a);
            if (a == owner) {
                mixer.stopAll();
                weather.setPaused(true);
                hiss.setPaused(true);
                squeal.setPaused(true);
                TTSSms.setPaused(true);
                hud.pause();
                boost.stop();
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
                TTSSms.stop();
                hud.pause();
                boost.stop();
                hud.view = null;
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
        boolean loadFailed;                // r164
        long loadFailAt;
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
                    Thread.sleep(hud.active || TTSSms.active() ? 100 : 300);
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
                pos = logFile == null ? -1 : 0;            // r120: another log file appearing later is new
                logFile = f;
            }
            long len = f.length();
            // r120: what is already in the log when the game starts belongs to the LAST run (a driver
            // session that was never closed left "TTSJOY: on" there and the pad showed on the menu):
            // start at its end.  A log that got shorter is a new one: read it from the start.
            if (pos < 0) {
                pos = len;
                return;
            }
            if (len < pos) pos = 0;
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
            brakeFromLog(s);
            hud.fromLog(s);
            camFromLog(s);
            TTSSms.fromLog(s, base, this);              // r136: Open Rails .sms engine sound
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

        /**
         * r119: where the camera is ("TTSCAM: d=<metres> in=<0|1>" from the HUD script): the horn pad plays
         * the horn like it is heard there - quieter and duller far away, muffled inside the cab.
         */
        static void camFromLog(String s) {
            int c = s.lastIndexOf("TTSCAM: d=");
            int done = s.lastIndexOf("GSRouter::Done()");
            if (done > c) {
                if (done >= 0) {
                    camD = -1f;
                    camN = -1f;
                    camV = 0f;
                }
                return;
            }
            if (c < 0) return;
            int p = c + 10, e = p;
            while (e < s.length() && s.charAt(e) >= '0' && s.charAt(e) <= '9') e++;
            if (e == p) return;
            try {
                float d = Integer.parseInt(s.substring(p, e));
                int q = s.indexOf("in=", e);
                int in = 0;
                if (q >= 0 && q < e + 8 && q + 3 < s.length()) {
                    char ic = s.charAt(q + 3);
                    if (ic == '1') in = 1;
                    else if (ic == '2') in = 2;
                }
                camIn = in;
                camD = d;
                int vq = s.indexOf(" v=", e);
                float vv = 0f;
                if (vq >= 0 && vq < e + 16) {
                    int a = vq + 3, b = a;
                    if (b < s.length() && s.charAt(b) == '-') b++;
                    while (b < s.length() && s.charAt(b) >= '0' && s.charAt(b) <= '9') b++;
                    if (b > a && !(b == a + 1 && s.charAt(a) == '-')) vv = Integer.parseInt(s.substring(a, b));
                }
                camV = vv;
                int nq = s.indexOf(" n=", e);
                int nle = s.indexOf('\n', e);
                if (nle < 0) nle = s.length();
                camN = (nq > 0 && nq < nle) ? intAt(s, nq + 3) : -1f;   // r155
                // r120c: pass geometry (decimetres): the pad follows the pass by itself between updates
                int sq = s.indexOf(" S=", e), bq = s.indexOf(" B=", e), uq = s.indexOf(" U=", e);
                int le = s.indexOf('\n', e);
                if (le < 0) le = s.length();
                if (sq > 0 && bq > 0 && uq > 0 && sq < le && bq < le && uq < le) {
                    float ps = intAt(s, sq + 3) * 0.1f, pb = intAt(s, bq + 3) * 0.1f, pu = intAt(s, uq + 3) * 0.1f;
                    passS = ps;
                    passB = pb;
                    passU = pu;
                    passT = System.nanoTime() - 200000000L;     // ~0.2 s from the game to here
                } else {
                    passU = 0f;
                }
            } catch (NumberFormatException x) {
                // keep
            }
        }

        static int intAt(String s, int a) {
            int b = a;
            if (b < s.length() && s.charAt(b) == '-') b++;
            while (b < s.length() && s.charAt(b) >= '0' && s.charAt(b) <= '9') b++;
            if (b == a || (b == a + 1 && s.charAt(a) == '-')) return 0;
            return Integer.parseInt(s.substring(a, b));
        }

        /** rain level from the TTS weather commands of the HUD script ("TTSCMD:rain=N"); session end = dry */
        /** r143: "TTSBRK: a <lag x10>" (brake applied) / "TTSBRK: r" (released) from the HUD script -> air brake hiss */
        static void brakeFromLog(String s) {
            int i = 0;
            while (true) {
                i = s.indexOf("TTSBRK: ", i);
                if (i < 0) return;
                i += 8;
                if (i >= s.length()) return;
                char c = s.charAt(i);
                if (c == 'a') {
                    brk = true;
                    hiss.play(1);
                } else if (c == 'r') {
                    brk = false;
                    hiss.play(2);
                }
            }
        }

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
            if (st.on && (!st.kuid.equals(loadedKuid) || (loadFailed && System.currentTimeMillis() - loadFailAt > 8000))) {
                loadedKuid = st.kuid;
                File dir = findDir(st.kuid);
                boolean ok = mixer.load(dir);
                // r164: a sound that could not be read (his log: OutOfMemoryError on horn.wav) left the pad
                // silent for that loco until another loco was driven - now it is read again 8 s later
                loadFailed = !ok;
                loadFailAt = System.currentTimeMillis();
                log("horn folder " + st.kuid + " -> " + dir + (ok ? "" : " (a sound failed, retry in 8 s)"));
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
    // r119: listener place for the horn pad (from TTSCAM lines); camD < 0 = not known yet (plain sound)
    static volatile float camD = -1f;
    static volatile float camN = -1f;      // r155: metres camera -> nearest vehicle of the train (TTSCAM n=), -1 unknown
    static volatile int camIn;             // r123: 0 outside, 1 loco cab, 2 inside a coach (d = real distance)
    static volatile float camV;
    // r120c: straight pass model from the HUD (along-track offset S, passing distance B, relative speed U)
    static volatile float passS, passB, passU;
    static volatile long passT;            // r120: m/s the camera and the loco move apart (+) / closer (-)
    static final float HORN_REF = 12f;     // full loudness up to 12 m, then 1/r (-6 dB per doubling)
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
        float norm = 1f;   // r123: brings the steady body to the same loudness for every horn file
    }

    static float herm(float[] x, int a, float t, int n) {
        float sm = x[Math.max(0, Math.min(n - 1, a - 1))], s0 = x[Math.max(0, Math.min(n - 1, a))];
        float s1 = x[Math.max(0, Math.min(n - 1, a + 1))], s2 = x[Math.max(0, Math.min(n - 1, a + 2))];
        float c1 = 0.5f * (s1 - sm), c2 = sm - 2.5f * s0 + 2f * s1 - 0.5f * s2, c3 = 0.5f * (s2 - sm) + 1.5f * (s0 - s1);
        return ((c3 * t + c2) * t + c1) * t + s0;
    }

    /** r158: runs of 2+ samples stuck at full scale rebuilt with a cubic through the neighbours; returns runs fixed */
    static int declip(float[] x) {
        int n = x.length, runs = 0, i = 2;
        while (i < n - 3) {
            if (Math.abs(x[i]) >= 0.995f) {
                int a = i, b = i;
                float sg = Math.signum(x[i]);
                while (b + 1 < n - 3 && Math.abs(x[b + 1]) >= 0.995f && Math.signum(x[b + 1]) == sg) b++;
                if (b > a) {
                    int L = b - a + 2;
                    float p0 = x[a - 1], p1 = x[b + 1];
                    float m0 = (x[a - 1] - x[a - 2]) * L, m1 = (x[b + 2] - x[b + 1]) * L;
                    for (int k = a; k <= b; k++) {
                        float t = (float) (k - (a - 1)) / L, t2 = t * t, t3 = t2 * t;
                        float y = (2 * t3 - 3 * t2 + 1) * p0 + (t3 - 2 * t2 + t) * m0 + (-2 * t3 + 3 * t2) * p1 + (t3 - t2) * m1;
                        if (sg > 0 && y < x[k]) y = x[k];
                        if (sg < 0 && y > x[k]) y = x[k];
                        x[k] = Math.max(-1.6f, Math.min(1.6f, y));
                    }
                    runs++;
                }
                i = b + 1;
            } else i++;
        }
        return runs;
    }

    static volatile boolean readWavOom;      // r164: the last readWav failed for lack of memory

    static Snd readWav(File f) {
        readWavOom = false;
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
            // r158: a clipped (flat-topped) recording crackles on a phone speaker: rebuild the peaks, -3 dB
            int cl = declip(L) + declip(R);
            if (cl > 0) {
                float pk = 0f;
                for (int i = 0; i < inFrames; i++) pk = Math.max(pk, Math.max(Math.abs(L[i]), Math.abs(R[i])));
                float g = Math.min(0.708f, 0.98f / Math.max(pk, 1e-6f));
                for (int i = 0; i < inFrames; i++) {
                    L[i] *= g;
                    R[i] *= g;
                }
                log("wav " + f.getName() + ": " + cl + " clipped peaks rebuilt, x" + String.format(java.util.Locale.US, "%.2f", g));
            }
            // resample to 44.1 kHz (r158: 4-point Hermite, was linear - less harsh)
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
                s.pcm[2 * i] = clip(herm(L, a, t, inFrames));
                s.pcm[2 * i + 1] = clip(herm(R, a, t, inFrames));
            }
            findLoop(s);
            loudness(s);
            return s;
        } catch (Throwable t) {
            readWavOom = t instanceof OutOfMemoryError;
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
    /**
     * r123: horn files come in at any level (many are cut to full scale - with the ground bounce and the old
     * hard clip that "cracked" the phone speaker).  The steady body is brought to -16 dBFS RMS (at most +6 dB
     * up, any amount down); the limiter at the output keeps every peak under -1 dBFS.
     */
    static void loudness(Snd s) {
        int a = Math.max(0, s.loopA), b = Math.min(s.frames, s.loopB);
        if (b - a < RATE / 20) {
            a = 0;
            b = s.frames;
        }
        double e = 0;
        for (int i = a; i < b; i++) {
            float l = s.pcm[2 * i], r = s.pcm[2 * i + 1];
            e += 0.5 * (l * l + r * r);
        }
        double rms = Math.sqrt(e / Math.max(1, b - a)) / 32768.0;
        if (rms < 1e-5) return;
        float g = (float) (0.16 / rms);
        if (g > 2f) g = 2f;
        s.norm = g;
    }

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
     * r153: the bell (down) loops the WHOLE file, the way the game itself plays a bell (idle 1.wav): a bell
     * recording is a run of strikes, and a cut-out "steady part" of it (what findLoop picks for a horn) does not
     * sound like a ringing bell.  Very short (5 ms) cross-fade at the seam, quick fade on release.
     */
    static void bellLoop(Snd s) {
        s.loopA = 0;
        s.loopB = s.frames;
        s.xf = Math.max(1, Math.min(RATE * 5 / 1000, s.frames / 8));     // 5 ms: keeps the strike at the start sharp
        double sab = 0, saa = 0, sbb = 0;
        for (int i = 0; i < s.xf; i++) {
            float x = s.pcm[2 * (s.frames - s.xf + i)] + s.pcm[2 * (s.frames - s.xf + i) + 1];
            float y = s.pcm[2 * i] + s.pcm[2 * i + 1];
            sab += x * y;
            saa += x * x;
            sbb += y * y;
        }
        s.corr = (saa > 0 && sbb > 0) ? (float) Math.max(-1, Math.min(1, sab / Math.sqrt(saa * sbb))) : 0f;
        s.seamGain = 1f;
        s.tailOk = false;
        loudness(s);
        log("bell: whole-file loop " + (s.frames * 1000 / RATE) + " ms");
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
        float gCur = 1f, aCur = 1f, lpL, lpR;     // r119: distance gain / air low-pass state
        // r120: Doppler - the mixed horn is read back at speed p = c / (c + v), v = how fast the camera and
        // the loco move apart (TTSCAM v=, m/s).  Coming closer = higher, going away = lower, like a real
        // train passing while honking.  Linear-interpolated variable-rate read of the mix.
        float[] inBuf = new float[2 * 2048];
        float[] tmp = new float[2 * 64];
        int inN;
        double rpos;
        float pCur = 1f;

        /** adds 64 freshly mixed frames to inBuf; false = nothing is playing any more */
        boolean mixMore() {
            if (2 * (inN + 64) > inBuf.length) inN = 0;            // cannot happen (p < 1.4), stay safe
            for (int i = 0; i < tmp.length; i++) tmp[i] = 0f;
            synchronized (lock) {
                if (!busy()) return false;
                for (int k = 1; k <= 4; k++) {
                    Voice o = v[k];
                    if (o == null) continue;
                    mix(o, tmp, 64);
                    if (o.done) v[k] = null;
                }
            }
            System.arraycopy(tmp, 0, inBuf, 2 * inN, tmp.length);
            inN += 64;
            return true;
        }

        /**
         * r120b: the horn as a real moving source: read back through a moving-delay line.  The delay is the
         * travel time d / c (343 m/s): the horn reaches you later the farther the loco is, and while the
         * distance changes the read speed is 1 - v / c - the true retarded-time Doppler (a train coming
         * at 108 km/h sounds ~9 % higher, after it passes ~9 % lower).  The speed glides (0.25 s) between the
         * HUD's 5-per-second updates so the pitch sweeps smoothly instead of stepping.
         */
        float vCur;

        double travelFrames() {
            if (camIn == 1 || gd < 0f) return 0;
            return Math.min(3.0, gd / 343.0) * RATE;
        }

        void doppler(float[] acc, int chunk) {
            float vr = gv;
            if (vr > 90f) vr = 90f;
            if (vr < -90f) vr = -90f;
            final float kv = 1f / (RATE * 0.08f);
            for (int k = 0; k < chunk; k++) {
                vCur += (vr - vCur) * kv;
                pCur = 1f - vCur / 343f;
                if (rpos < 0) {                                    // still on its way to the ear
                    acc[2 * k] = 0f;
                    acc[2 * k + 1] = 0f;
                    rpos += pCur;
                    continue;
                }
                int i0 = (int) rpos;
                boolean over = false;
                while (i0 + 1 >= inN) {
                    if (!mixMore()) {
                        over = true;
                        break;
                    }
                }
                if (over) {                                        // all played out: silence, let it rest
                    acc[2 * k] = 0f;
                    acc[2 * k + 1] = 0f;
                    rpos = Math.max(rpos, inN);
                    continue;
                }
                float f = (float) (rpos - i0);
                acc[2 * k] = inBuf[2 * i0] * (1f - f) + inBuf[2 * i0 + 2] * f;
                acc[2 * k + 1] = inBuf[2 * i0 + 1] * (1f - f) + inBuf[2 * i0 + 3] * f;
                rpos += pCur;
            }
            int drop = (int) rpos;
            if (drop > 0) {
                System.arraycopy(inBuf, 2 * drop, inBuf, 0, 2 * (inN - drop));
                inN -= drop;
                rpos -= drop;
            }
        }

        /**
         * r120b: the second way a horn reaches you outdoors - bounced off the ground.  Horn on the loco roof
         * (4.2 m), ear 1.7 m up: the bounce is a few ms later and the two mix into the moving "whoosh"
         * (comb filter sliding through the sound) you hear when a train passes close.  Not in the cab.
         */
        final float[] gBuf = new float[2 * 4096];
        int gPos;
        float gDel, gAmp;

        void ground(float[] acc, int chunk) {
            float d = gd, delT = 0f, ampT = 0f;
            if (camIn == 0 && d >= 0f) {
                d = Math.max(d, 2f);
                float hs = 4.2f, hl = 1.7f;
                float direct = (float) Math.sqrt(d * d + (hs - hl) * (hs - hl));
                float bounce = (float) Math.sqrt(d * d + (hs + hl) * (hs + hl));
                delT = Math.min(4000f, (bounce - direct) / 343f * RATE);
                ampT = 0.55f * direct / bounce;
            }
            final float k1 = 1f / (RATE * 0.2f);
            int n = gBuf.length / 2;
            for (int k = 0; k < chunk; k++) {
                gDel += (delT - gDel) * k1;
                gAmp += (ampT - gAmp) * k1;
                gBuf[2 * gPos] = acc[2 * k];
                gBuf[2 * gPos + 1] = acc[2 * k + 1];
                float rp = gPos - gDel;
                if (rp < 0) rp += n;
                // r123: gPos - (tiny delay) + n rounds to n in float: index n -> ArrayIndexOutOfBounds killed the
                // audio thread (stopAll) - the horn stopped dead when the camera went into the cab
                int a = (int) rp;
                if (a >= n) a -= n;
                if (a < 0) a = 0;
                float f = rp - (int) rp;
                if (f < 0f || f >= 1f) f = 0f;
                int b = a + 1 >= n ? 0 : a + 1;
                // r123: direct + bounce scaled so the average loudness stays the same (it added up to +3.8 dB)
                float gn = 1f / (float) Math.sqrt(1f + gAmp * gAmp);
                acc[2 * k] = (acc[2 * k] + gAmp * (gBuf[2 * a] * (1f - f) + gBuf[2 * b] * f)) * gn;
                acc[2 * k + 1] = (acc[2 * k + 1] + gAmp * (gBuf[2 * a + 1] * (1f - f) + gBuf[2 * b + 1] * f)) * gn;
                gPos = gPos + 1 == n ? 0 : gPos + 1;
            }
        }

        boolean load(File dir) {
            Snd[] n = new Snd[5];
            boolean ok = true;
            synchronized (lock) {                          // r164: let the old loco's sounds go before reading new ones
                for (int k = 1; k <= 4; k++) {
                    snd[k] = null;
                    has[k] = false;
                    v[k] = null;
                }
            }
            if (dir != null && dir.isDirectory()) {
                File[] files = dir.listFiles();
                HashMap<String, File> byName = new HashMap<String, File>();
                if (files != null) for (File f : files) byName.put(f.getName().toLowerCase(Locale.US), f);
                for (int k = 1; k <= 4; k++) {
                    for (String nm : NAMES[k]) {
                        File f = byName.get(nm);
                        if (f != null) {
                            n[k] = readWav(f);
                            if (n[k] != null && k == 3) bellLoop(n[k]);   // r153: bell loops like stock Trainz
                            if (n[k] != null) break;
                            if (readWavOom) ok = false;
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
            return ok;
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
                    boolean waited = false;
                    synchronized (lock) {
                        // r120: keep going while delayed sound is still on its way (inBuf not played out)
                        while (!busy() && inN - rpos < 2) {
                            if (track != null && idle > 0) {
                                try {
                                    track.pause();
                                    track.flush();
                                } catch (Throwable t) {
                                    // ignore
                                }
                                idle = 0;
                            }
                            inN = 0;
                            rpos = 0;
                            waited = true;
                            lock.wait();
                        }
                    }
                    geom();
                    if (waited) rpos = -travelFrames();         // the sound needs d / c to reach the ear
                    doppler(acc, chunk);
                    ground(acc, chunk);
                    hear(acc, chunk);
                    limit(acc, chunk);
                    for (int i = 0; i < out.length; i++) out[i] = clip(acc[i]);
                    if (track == null) track = newTrack();
                    if (track == null) {
                        stopAll();
                        continue;
                    }
                    if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
                    if (track.write(out, 0, out.length) < 0 || track.getState() != AudioTrack.STATE_INITIALIZED) {
                        // r158: a dead output (screen recorder, call, route change) swallows everything silently: new track
                        log("audio: track lost, new one");
                        try {
                            track.release();
                        } catch (Throwable tl) {
                            // gone
                        }
                        track = null;
                    }
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

        /**
         * r119: the horn as heard where the camera is.  Outside: 1/r loudness from 12 m (100 m = -18 dB,
         * 500 m = -32 dB) and air taking the highs away (low-pass 18 kHz near, ~10 kHz at 100 m, ~3.5 kHz at
         * 500 m).  In the cab: through the cab body - lower and muffled (2.6 kHz).  Changes glide (0.12 s).
         */
        /**
         * r120c: where the loco is right now.  With a pass model from the HUD the distance, the speed toward /
         * away and the side of the horn are worked out for every audio block (no 0.2 s steps):
         *   s = S + U * t,  d = sqrt(B^2 + s^2),  v = U * s / d,  facing = -s / d (1 = in front of the horn).
         */
        float gd = -1f, gv, gface = 1f;

        void geom() {
            gd = camD;
            gv = 0f;                       // r121: no pitch change unless a real pass is going on
            gface = 1f;
            float u = passU;
            // r121: below 6 m/s (22 km/h) the "speed" is only camera / position jitter (his r120 log:
            // standing train, U 0.5..4 m/s with S jumping +-3 m) - that bent the horn pitch up and down
            if (camIn != 0 || camD < 0f || u < 6f) return;
            float age = (System.nanoTime() - passT) * 1e-9f;
            if (age < 0f || age > 2.5f) return;
            float s = passS + u * age, b = passB;
            float d = (float) Math.sqrt(b * b + s * s);
            if (d < 1f) d = 1f;
            gd = d;
            gv = u * s / d;
            gface = -s / d;
        }

        /**
         * r124: look-ahead peak limiter.  r123 dropped the gain at the very sample of a new peak (and had a
         * tanh knee from -4.4 dBFS): while the horn swelled up at its start every new cycle was flattened =
         * the crackle "at first".  Now the sound is delayed 2.5 ms and the gain glides down BEFORE a peak
         * arrives (no waveform is ever cut), recovers in 0.2 s.  Ceiling -6 dBFS: the game's own sound
         * (peaks about -9 dBFS) is mixed with ours in the phone, the sum must stay under full scale.
         */
        static final int LA = 110;                       // 2.5 ms at 44.1 kHz
        static final int HOLD = 1323;                    // 30 ms: longer than one cycle of the lowest horn
        final float[] laBuf = new float[2 * LA];
        int laPos;
        float limG = 1f;
        // sliding minimum of the wanted gain over the last HOLD samples (monotonic queue, ring buffers)
        final float[] mqV = new float[HOLD + 1];
        final long[] mqT = new long[HOLD + 1];
        int mqHead, mqTail;
        long mqNow;

        void limit(float[] acc, int chunk) {
            final float ceil = 0.5f;
            final float att = (float) (1.0 - Math.exp(-5.0 / LA));
            final float rel = (float) (1.0 - Math.exp(-1.0 / (RATE * 0.2)));
            final int cap = HOLD + 1;
            for (int i = 0; i < chunk; i++) {
                float l = acc[2 * i], r = acc[2 * i + 1];
                float pk = Math.max(Math.abs(l), Math.abs(r));
                float want = pk > ceil ? ceil / pk : 1f;
                long t = mqNow++;
                while (mqTail != mqHead && mqV[(mqTail - 1 + cap) % cap] >= want)
                    mqTail = (mqTail - 1 + cap) % cap;
                mqV[mqTail] = want;
                mqT[mqTail] = t;
                mqTail = (mqTail + 1) % cap;
                while (mqT[mqHead] <= t - HOLD) mqHead = (mqHead + 1) % cap;
                float holdG = mqV[mqHead];
                if (holdG < limG) limG += (holdG - limG) * att;
                else limG += (holdG - limG) * rel;
                float dl = laBuf[2 * laPos], dr = laBuf[2 * laPos + 1];
                laBuf[2 * laPos] = l;
                laBuf[2 * laPos + 1] = r;
                laPos = laPos + 1 == LA ? 0 : laPos + 1;
                dl *= limG;
                dr *= limG;
                // safety only (the glide above keeps the delayed sample under the ceiling)
                if (dl > ceil) dl = ceil;
                if (dl < -ceil) dl = -ceil;
                if (dr > ceil) dr = ceil;
                if (dr < -ceil) dr = -ceil;
                acc[2 * i] = dl;
                acc[2 * i + 1] = dr;
            }
        }

        void hear(float[] acc, int chunk) {
            float d = gd, gT, fc;
            int in = camIn;
            if (d < 0f) {
                gT = 0.85f;
                fc = 20000f;
            } else if (in == 1) {
                // r123: in the loco cab the horn sits on the roof just above - loud, the roof only takes a
                // little of the top off (r119 had -4.4 dB and 2.6 kHz: the horn vanished under the cab noise)
                gT = 0.9f;
                fc = 7000f;
            } else if (in == 2) {
                // r123: inside a coach: the real distance to the loco and the coach body in between
                // (about -5 dB, the highs stay outside)
                d = Math.max(d, 8f);
                gT = 0.55f * Math.min(1f, 8f / (8f + 0.156f * (d - 8f)));
                fc = Math.min(3200f, Math.max(1800f, 18000f / (1f + d / 300f)));
            } else {
                d = Math.max(d, 1f);
                // r122: Open Rails distance law (reference 8 m, gain 0.025 at 2 km) like the engine sounds -
                // a horn carries far: 100 m = -7 dB, 500 m = -21 dB (1/r was -18 / -32 dB: "too quiet")
                gT = Math.min(1f, 8f / (8f + 0.156f * (d - 8f)));
                fc = Math.max(2500f, 18000f / (1f + d / 300f));
                // (r120d: no front/back difference - his real pass-by recordings keep the horn just as loud
                // and clear after the loco has gone past; only the pitch drops)
            }
            float aT = (float) (1.0 - Math.exp(-2.0 * Math.PI * Math.min(fc, RATE * 0.45f) / RATE));
            if (d < 0f) aT = 1f;
            float sm = (float) (1.0 - Math.exp(-1.0 / (RATE * 0.12)));
            for (int i = 0; i < chunk; i++) {
                gCur += (gT - gCur) * sm;
                aCur += (aT - aCur) * sm;
                lpL += aCur * (acc[2 * i] - lpL);
                lpR += aCur * (acc[2 * i + 1] - lpR);
                acc[2 * i] = lpL * gCur;
                acc[2 * i + 1] = lpR * gCur;
            }
        }

        static AudioTrack newTrack() {
            try {
                int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) min = 4096;
                return new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 4 * 60 / 1000), AudioTrack.MODE_STREAM);   // r158: 60 ms (was ~12): no underrun crackle
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
                float g = o.gain * o.fade * s.norm / 32768f;
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
    /** r143: the air brake of an Indian coach: a sharp "pssh" while the brake valve opens (apply), a longer, softer
     *  "pshhhh" with a last puff when the brake lets go.  Made from filtered noise, own AudioTrack. */
    static final class Hiss implements Runnable {
        Thread th;
        AudioTrack track;
        final java.util.Random rnd = new java.util.Random();
        volatile int kind;                 // 0 idle, 1 apply, 2 release
        volatile boolean paused;
        int pos, len;
        float gain = 1f;
        float hp1, hp1x, lp1, lp2, lp3;

        synchronized void play(int k) {
            float d = TTSHornPad.camD;
            int ci = TTSHornPad.camIn;
            if (d < 0f) d = 5f;
            // in the cab the valve is right there, in a coach it comes through the body, outside it carries only a little
            float g = ci == 1 ? 1f : ci == 2 ? 0.55f : Math.min(1f, 14f / Math.max(d, 14f)) * 0.8f;
            if (g < 0.02f) return;
            gain = g;
            kind = k;
            pos = 0;
            len = (int) (RATE * (k == 1 ? 2.3f : 3.8f));
            hp1 = hp1x = lp1 = lp2 = lp3 = 0f;
            if (th == null || !th.isAlive()) {
                th = new Thread(this, "TTSHiss");
                th.setDaemon(true);
                th.start();
            }
            notifyAll();
        }

        synchronized void setPaused(boolean p) {
            paused = p;
            notifyAll();
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
                        while (paused || kind == 0) {
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
                    if (track.write(out, 0, out.length) < 0 || track.getState() != AudioTrack.STATE_INITIALIZED) {
                        // r158: a dead output (screen recorder, call, route change) swallows everything silently: new track
                        log("audio: track lost, new one");
                        try {
                            track.release();
                        } catch (Throwable tl) {
                            // gone
                        }
                        track = null;
                    }
                } catch (Throwable t) {
                    TTSHornPad.log("hiss audio: " + t);
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

        void render(short[] out, int chunk) {
            int k = kind;
            for (int i = 0; i < chunk; i++) {
                float v = 0f;
                if (k != 0 && pos < len) {
                    float t = pos / (float) RATE;
                    float env;
                    if (k == 1) {
                        // apply: opens fast, falls away in ~2 s, a small second puff as the valve shuts
                        env = (1f - (float) Math.exp(-t / 0.015f)) * (float) Math.exp(-t / 0.75f);
                        env += 0.18f * (float) Math.exp(-(t - 1.5f) * (t - 1.5f) / 0.06f);
                    } else {
                        // release: swells, long soft exhaust, a last short puff
                        env = (1f - (float) Math.exp(-t / 0.12f)) * (float) Math.exp(-t / 1.35f);
                        env += 0.22f * (float) Math.exp(-(t - 2.7f) * (t - 2.7f) / 0.05f);
                    }
                    float w = rnd.nextFloat() * 2f - 1f;
                    // band: ~1.3 kHz up to ~4 kHz (air escaping through a small hole), a little body at ~600 Hz
                    hp1 = 0.82f * (hp1 + w - hp1x);
                    hp1x = w;
                    lp1 += (hp1 - lp1) * 0.30f;
                    lp2 += (w - lp2) * 0.07f;
                    lp3 += (lp2 - lp3) * 0.07f;
                    float n = lp1 * (k == 1 ? 1.0f : 0.8f) + lp3 * (k == 1 ? 1.4f : 2.0f);
                    v = n * env * 0.8f * gain;
                    pos++;
                } else if (k != 0) {
                    kind = 0;
                    k = 0;
                }
                v = v / (1f + Math.abs(v)) * 1.5f;
                short sv = clip(v);
                out[2 * i] = sv;
                out[2 * i + 1] = sv;
            }
        }
    }

    /** r144b: the squeal voice (pure maths, no android): metallic stick-slip screech, NOT a pure tone.
     *  Two FM voices (the second one inharmonic), a wandering pitch, ~30 Hz stick-slip roughness, scrape noise,
     *  and it comes in bursts with gaps like a real brake shoe / wheel. */
    static final class SqDsp {
        final java.util.Random rnd = new java.util.Random(7);
        float amp, ph1, ph2, ph3, fj, fjT, rough, hp, hpx, lp, burst, burstT, bTarget = 1f, nzS = 0.5f, nz = 0.5f, nzN;
        boolean inBurst = true;

        float tick(float lvl, float x, float fc) {
            // pitch: low for a steel screech, creeping up a little as the wheels slow
            float f0 = fc > 0f ? fc : 560f + 330f * x;
            amp += (lvl - amp) * (lvl > amp ? 0.0009f : 0.0005f);
            if (--burstT <= 0f) {
                inBurst = !inBurst;
                burstT = inBurst ? (int) (RATE * (0.5f + 1.1f * rnd.nextFloat())) : (int) (RATE * (0.25f + 0.9f * rnd.nextFloat()));
                bTarget = inBurst ? 0.55f + 0.45f * rnd.nextFloat() : 0f;
            }
            burst += (bTarget - burst) * 0.0016f;
            if (--nzN <= 0f) {
                nz = 0.25f + 0.75f * rnd.nextFloat();
                nzN = 500f + rnd.nextInt(2500);
            }
            nzS += (nz - nzS) * 0.002f;
            // wandering pitch (random walk), never a clean vibrato
            if (--fjT <= 0f) {
                fj = (rnd.nextFloat() - 0.5f) * 0.09f;
                fjT = 800f + rnd.nextInt(3000);
            }
            fjS += (fj - fjS) * 0.0008f;
            float f = f0 * (1f + fjS);
            ph1 += f / RATE;
            ph2 += f * 1.337f / RATE;
            ph3 += f * 0.5f / RATE;
            if (ph1 > 1f) ph1 -= 1f;
            if (ph2 > 1f) ph2 -= 1f;
            if (ph3 > 1f) ph3 -= 1f;
            float t1 = 6.2831853f * ph1;
            float s1 = (float) Math.sin(t1 + 1.1f * Math.sin(2f * t1));
            float s2 = (float) Math.sin(6.2831853f * ph2 + 0.7f * Math.sin(6.2831853f * ph2 * 1.41f));
            float s3 = (float) Math.sin(6.2831853f * ph3);
            // stick-slip roughness
            rough += (28f + 10f * x) / RATE;
            if (rough > 1f) rough -= 1f;
            float r = 0.55f + 0.45f * (float) Math.sin(6.2831853f * rough);
            float w = rnd.nextFloat() * 2f - 1f;
            hp = 0.8f * (hp + w - hpx);
            hpx = w;
            lp += (hp - lp) * 0.10f;
            float s = (s1 * 0.55f + s2 * 0.32f + s3 * 0.2f) * r + lp * 0.30f;
            return s * amp * burst * (0.3f + 0.7f * nzS) * 0.9f;
        }
        float fjS;
    }

    /** r144: brake squeal. Only while braking (speed falling, brake applied) and only from 12 km/h down to a stop. */
    static final class Squeal implements Runnable {
        Thread th;
        AudioTrack track;
        volatile boolean paused;
        final SqDsp dsp = new SqDsp();
        boolean active;
        int chirp;                               // samples left of the final stop chirp
        float lastV;

        synchronized void setPaused(boolean p) {
            paused = p;
            if (SQUEAL_ON && !p && (th == null || !th.isAlive())) {
                th = new Thread(this, "TTSSqueal");
                th.setDaemon(true);
                th.start();
            }
            notifyAll();
        }

        float viewGain() {
            float d = TTSHornPad.camD;
            int ci = TTSHornPad.camIn;
            if (d < 0f) d = 5f;
            return ci == 1 ? 0.45f : ci == 2 ? 0.7f : Math.min(1f, 10f / Math.max(d, 10f));
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
                        while (paused || (!TTSSms.inOn && dsp.amp < 0.001f && chirp == 0)) {
                            if (track != null) {
                                try {
                                    track.pause();
                                    track.flush();
                                } catch (Throwable t) {
                                    // ignore
                                }
                            }
                            wait(paused ? 0 : 400);
                            if (!paused && TTSSms.inOn) break;
                        }
                    }
                    float v = TTSSms.inOn ? TTSSms.currentSpeed(System.nanoTime()) : 0f;
                    float slope = TTSSms.inSlope;
                    boolean cond = TTSSms.inOn && v <= 3.33f && v > 0.08f
                            && (active ? slope < -0.02f : slope < -0.10f) && (brk || slope < -0.30f);
                    if (active && !cond && lastV < 0.5f && v < 0.5f && dsp.amp > 0.05f && chirp == 0) chirp = (int) (RATE * 0.35f);
                    active = cond;
                    lastV = v;
                    render(out, chunk, v, cond);
                    if (track == null) track = Mixer.newTrack();
                    if (track == null) {
                        Thread.sleep(500);
                        continue;
                    }
                    if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
                    if (track.write(out, 0, out.length) < 0 || track.getState() != AudioTrack.STATE_INITIALIZED) {
                        // r158: a dead output (screen recorder, call, route change) swallows everything silently: new track
                        log("audio: track lost, new one");
                        try {
                            track.release();
                        } catch (Throwable tl) {
                            // gone
                        }
                        track = null;
                    }
                } catch (Throwable t) {
                    TTSHornPad.log("squeal audio: " + t);
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

        void render(short[] out, int chunk, float v, boolean on) {
            float g = viewGain();
            float x = 1f - Math.min(1f, v / 3.33f);              // 0 at 12 km/h, 1 at standstill
            float lvl = on ? Math.min(1f, (3.33f - v) / 1.2f) * (0.45f + 0.55f * x) : 0f;
            for (int i = 0; i < chunk; i++) {
                float tgt = lvl;
                float fc = 0f;
                if (chirp > 0) {
                    float c = chirp / (RATE * 0.35f);
                    tgt = 0.8f * c;
                    fc = 900f - 330f * (1f - c);                  // last groan as the wheels stop
                    chirp--;
                }
                float val = dsp.tick(tgt, x, fc) * g;
                val = val / (1f + Math.abs(val)) * 1.4f;
                short sv = clip(val);
                out[2 * i] = sv;
                out[2 * i + 1] = sv;
            }
        }
    }

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
        float cabG = 1f, cabM = 0f, cfL, cfR;   // r142: rain heard from the cab / a coach: quieter and muffled
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
                    if (track.write(out, 0, out.length) < 0 || track.getState() != AudioTrack.STATE_INITIALIZED) {
                        // r158: a dead output (screen recorder, call, route change) swallows everything silently: new track
                        log("audio: track lost, new one");
                        try {
                            track.release();
                        } catch (Throwable tl) {
                            // gone
                        }
                        track = null;
                    }
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
            int cin = TTSHornPad.camIn;
            float cabTg = cin == 1 ? 0.30f : cin == 2 ? 0.40f : 1f;
            float cabMt = cin == 1 ? 0.85f : cin == 2 ? 0.75f : 0f;
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
                float g = level * 0.42f * gust;              // r142: was 0.65 (too loud)
                L *= g;
                R *= g;
                if (thPos >= 0) {
                    if (thPos < thunder.length) {
                        float t = thunder[thPos++] / 32768f * thGain;
                        L += t;
                        R += t;
                    } else thPos = -1;
                }
                // r142: in the cab (and a coach) the outside rain comes through the body: lower and dull, as in Trainz
                cabG += (cabTg - cabG) * (1f / (RATE * 0.35f));
                cabM += (cabMt - cabM) * (1f / (RATE * 0.35f));
                cfL += (L - cfL) * 0.10f;                 // ~700 Hz low-pass
                cfR += (R - cfR) * 0.10f;
                L = (L * (1f - cabM) + cfL * cabM) * cabG;
                R = (R * (1f - cabM) + cfR * cabM) * cabG;
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

    // ================================================================ r126: HUD overlay (track monitor, LIGHTS, TTS hide, clean view)
    /**
     * The TTS HUD drawn here instead of the game's HTML windows: the script (driver module) only sends the
     * data (TTSHV / TTSHT / TTSHO / TTSHP / TTSHL lines in Trainz.log) and reads the buttons tapped here from
     * the libttsshrink mailbox (Hud.send -> tts_cmd.txt).  A tap is answered on screen at once; folding,
     * compact view and TTS hide are kept here (no round trip at all).  While this overlay lives it says so
     * every 2 s (command 999); without that the script goes back to its own HTML panels by itself.
     */
    static final Hud hud = new Hud();

    static final class Hud {
        // ---- data from the script (watcher thread writes, UI thread reads)
        volatile boolean active;              // the script draws nothing itself, we show the HUD
        volatile int spdDm, limK = -1, over, trav, projK, rev;
        volatile String[] t;                  // TTSHT fields
        volatile int[][] obj = new int[0][];  // k, side, dist, val, lim, aspect level, route
        volatile int[] prof;                  // here + 20 columns (dm), Integer.MIN_VALUE = no data
        volatile String[] lights;             // TTSHL fields
        volatile long objTime;                // when the objects came (distances run on with the speed)
        volatile int objTrav;
        // ---- kept here (instant)
        boolean monOn = true, compact, lightsOn = true, hideAll;
        boolean prefsLoaded;
        // ---- optimistic states until the script confirms (ms time stamps)
        long cleanUntil, lightUntil[] = new long[8];
        boolean cleanWant, lightWant[] = new boolean[8];
        long colUntil[] = new long[2];          // r162: marker colour (0 front, 1 back), optimistic until the script says
        boolean colWant[] = new boolean[2];
        HudView view;

        static String[] split(String s, char c) {
            java.util.ArrayList<String> a = new java.util.ArrayList<String>();
            int p = 0;
            for (int i = 0; i <= s.length(); i++) {
                if (i == s.length() || s.charAt(i) == c) {
                    a.add(s.substring(p, i));
                    p = i + 1;
                }
            }
            return a.toArray(new String[0]);
        }

        static int num(String s, int def) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Throwable e) {
                return def;
            }
        }

        static String lineAfter(String s, String key) {
            int i = s.lastIndexOf(key);
            if (i < 0) return null;
            int e = s.indexOf('\n', i);
            if (e < 0) e = s.length();
            String l = s.substring(i + key.length(), e);
            if (l.endsWith("\r")) l = l.substring(0, l.length() - 1);
            return l;
        }

        /** the HUD lines of one poll of Trainz.log */
        void fromLog(String s) {
            boolean changed = false;
            int done = Math.max(s.lastIndexOf("GSRouter::Done()"), s.lastIndexOf("TTSUI: java hud off"));
            int startAt = Math.max(Math.max(s.lastIndexOf("TTSHT: "), s.lastIndexOf("TTSHV: ")), s.lastIndexOf("TTSUI: java hud on"));
            if (done >= 0 && done > startAt) {
                if (active) {
                    active = false;
                    changed = true;
                    log("hud: off");
                }
            }
            String l = lineAfter(s, "TTSHV: ");
            if (l != null && s.lastIndexOf("TTSHV: ") > done) {
                String[] f = split(l.trim(), ' ');
                if (f.length >= 6) {
                    spdDm = num(f[0], 0);
                    limK = num(f[1], -1);
                    over = num(f[2], 0);
                    trav = num(f[3], 0);
                    projK = num(f[4], 0);
                    rev = num(f[5], 0);
                    changed = true;
                }
            }
            l = lineAfter(s, "TTSHT: ");
            if (l != null && s.lastIndexOf("TTSHT: ") > done) {
                String[] f = split(l, '^');
                if (f.length >= 24) {
                    t = f;
                    if (!active) log("hud: on");
                    active = true;
                    changed = true;
                    if (cleanUntil != 0 && (num(f[5], 0) != 0) == cleanWant) cleanUntil = 0;
                }
            }
            l = lineAfter(s, "TTSHO: ");
            if (l != null && s.lastIndexOf("TTSHO: ") > done) {
                String[] f = split(l.trim(), ';');
                java.util.ArrayList<int[]> a = new java.util.ArrayList<int[]>();
                for (String o : f) {
                    if (o.length() == 0) continue;
                    String[] g = split(o, ',');
                    if (g.length < 7) continue;
                    int[] v = new int[7];
                    for (int i = 0; i < 7; i++) v[i] = num(g[i], 0);
                    a.add(v);
                }
                obj = a.toArray(new int[0][]);
                objTrav = trav;
                objTime = System.currentTimeMillis();
                changed = true;
            }
            l = lineAfter(s, "TTSHP: ");
            if (l != null && s.lastIndexOf("TTSHP: ") > done) {
                String[] f = split(l.trim(), ',');
                int[] p = new int[f.length];
                for (int i = 0; i < f.length; i++) p[i] = f[i].equals("x") ? Integer.MIN_VALUE : num(f[i], Integer.MIN_VALUE);
                prof = p;
                changed = true;
            }
            l = lineAfter(s, "TTSHL: ");
            if (l != null && s.lastIndexOf("TTSHL: ") > done) {
                String[] f = split(l, '^');
                if (f.length >= 9) {
                    lights = f;
                    for (int i = 0; i < 8; i++)
                        if (lightUntil[i] != 0 && i < 7 && (num(f[i], 0) != 0) == lightWant[i]) lightUntil[i] = 0;
                    for (int e = 0; e < 2; e++)
                        if (colUntil[e] != 0 && f.length > 9 + e && (num(f[9 + e], 0) != 0) == colWant[e]) colUntil[e] = 0;
                    changed = true;
                }
            }
            if (changed) refresh();
        }

        void refresh() {
            H.post(new Runnable() {
                public void run() {
                    HudView v = view;
                    if (v != null) v.update();
                }
            });
        }

        // ---- commands to the script
        static final Object cmdLock = new Object();
        static long cmdId;
        static final java.util.ArrayList<String> cmdLines = new java.util.ArrayList<String>();
        static Thread cmdThread;
        static boolean cmdDirty;

        static void send(final int cmd) {
            synchronized (cmdLock) {
                long id = System.currentTimeMillis() * 100;
                if (id <= cmdId) id = cmdId + 1;
                cmdId = id;
                cmdLines.add(id + " " + cmd);
                while (cmdLines.size() > 16) cmdLines.remove(0);
                cmdDirty = true;
                if (cmdThread == null || !cmdThread.isAlive()) {
                    cmdThread = new Thread(new Runnable() {
                        public void run() {
                            cmdLoop();
                        }
                    }, "TTSHudCmd");
                    cmdThread.setDaemon(true);
                    cmdThread.start();
                }
                cmdLock.notifyAll();
            }
        }

        static void cmdLoop() {
            File dir = new File("/sdcard/Android/media/com.n3vgames.android.trainz");
            while (true) {
                String text;
                synchronized (cmdLock) {
                    while (!cmdDirty) {
                        try {
                            cmdLock.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    cmdDirty = false;
                    StringBuilder b = new StringBuilder();
                    for (String l : cmdLines) b.append(l).append('\n');
                    text = b.toString();
                }
                try {
                    dir.mkdirs();
                    File tmp = new File(dir, "tts_cmd.tmp");
                    java.io.FileOutputStream o = new java.io.FileOutputStream(tmp, false);
                    try {
                        o.write(text.getBytes("ISO-8859-1"));
                    } finally {
                        o.close();
                    }
                    if (!tmp.renameTo(new File(dir, "tts_cmd.txt"))) log("hud: rename failed");
                } catch (Throwable e) {
                    log("hud cmd: " + e);
                }
            }
        }

        // ---- heartbeat: "the overlay is here" every 2 s while the game is in front
        volatile boolean beating;
        final Runnable beat = new Runnable() {
            public void run() {
                if (!beating) return;
                send(999);
                H.postDelayed(this, 2000);
            }
        };

        void resume() {
            if (!beating) {
                beating = true;
                H.post(beat);
            }
        }

        void pause() {
            beating = false;
            H.removeCallbacks(beat);
        }

        // ---- per-viewer settings
        void loadPrefs(Context c) {
            if (prefsLoaded) return;
            prefsLoaded = true;
            try {
                SharedPreferences sp = c.getSharedPreferences("tts_hud", Context.MODE_PRIVATE);
                monOn = sp.getBoolean("monOn", true);
                compact = sp.getBoolean("compact", false);
                lightsOn = sp.getBoolean("lightsOn", true);
                hideAll = sp.getBoolean("hideAll", false);
            } catch (Throwable e) {
                log("hud prefs: " + e);
            }
        }

        void savePrefs(Context c) {
            try {
                c.getSharedPreferences("tts_hud", Context.MODE_PRIVATE).edit().putBoolean("monOn", monOn)
                        .putBoolean("compact", compact).putBoolean("lightsOn", lightsOn).putBoolean("hideAll", hideAll).apply();
            } catch (Throwable e) {
                log("hud prefs: " + e);
            }
        }

        boolean clean() {
            String[] f = t;
            boolean gs = f != null && num(f[5], 0) != 0;
            if (cleanUntil != 0 && System.currentTimeMillis() < cleanUntil) return cleanWant;
            cleanUntil = 0;
            return gs;
        }

        /** r162: marker colour of an end (0 front, 1 back): true = red.  TTSHL fields 9 / 10; default front white, back red */
        boolean colour(int end) {
            String[] f = lights;
            boolean gs = end == 1;
            if (f != null && f.length > 9 + end) gs = num(f[9 + end], end) != 0;
            if (colUntil[end] != 0 && System.currentTimeMillis() < colUntil[end]) return colWant[end];
            colUntil[end] = 0;
            return gs;
        }

        boolean light(int i) {
            String[] f = lights;
            boolean gs = f != null && num(f[i], 0) != 0;
            if (lightUntil[i] != 0 && System.currentTimeMillis() < lightUntil[i]) return lightWant[i];
            lightUntil[i] = 0;
            return gs;
        }
    }

    static int col(String s, int def) {
        try {
            if (s == null) return def;
            s = s.trim();
            if (!s.startsWith("#")) return def;
            long v = Long.parseLong(s.substring(1), 16);
            if (s.length() == 7) return (int) (0xFF000000L | v);
            if (s.length() == 9) return (int) (((v & 0xFF) << 24) | (v >>> 8));     // #RRGGBBAA (the HUD's own)
        } catch (Throwable e) {
            // keep
        }
        return def;
    }

    /** one full-screen view that draws the HUD panels; taps come from the Router (claim / tap) */
    static final class HudView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF(0, 0, 0, 0);
        final java.util.ArrayList<float[]> hits = new java.util.ArrayList<float[]>();   // l, t, r, b, action
        final int[] loc = new int[2];
        float sc = 1f;                 // view px per script px
        int downAction = -1;
        // r127: the game's own menus (camera views, main menu ...) open under the HUD - it is drawn above the
        // whole game.  A tap on the game's top toolbar hides the HUD; the next tap anywhere (picking a menu item
        // or closing it) brings it back 0.4 s later, a second toolbar tap at once, and after 20 s in any case.
        boolean menuHidden;
        long menuSince;
        float toolbarH;
        final Runnable unhide = new Runnable() {
            public void run() {
                menuHidden = false;
                invalidate();
            }
        };

        /** other views of the game window that are not ours or the game's surface (TTS camera bar, knobs ...) */
        java.util.ArrayList<float[]> obstacles() {
            java.util.ArrayList<float[]> out = new java.util.ArrayList<float[]>();
            android.view.ViewParent pp = getParent();
            if (!(pp instanceof ViewGroup)) return out;
            ViewGroup g = (ViewGroup) pp;
            getLocationInWindow(loc);
            int[] o = new int[2];
            for (int i = 0; i < g.getChildCount(); i++) {
                View v = g.getChildAt(i);
                if (v == this || v == pad || v instanceof android.view.SurfaceView || v.getVisibility() != View.VISIBLE) continue;
                int w = v.getWidth(), hh = v.getHeight();
                if (w <= 0 || hh <= 0 || w > getWidth() * 0.9f || hh > getHeight() * 0.7f) continue;
                v.getLocationInWindow(o);
                out.add(new float[]{o[0] - loc[0], o[1] - loc[1], o[0] - loc[0] + w, o[1] - loc[1] + hh});
            }
            return out;
        }

        /** lowest bottom of the obstacles crossing x range [l, r] that start above yMax (0 = none) */
        float obstacleBottom(float l, float r, float yMax) {
            float best = 0;
            for (float[] b : obstacles()) if (b[2] > l && b[0] < r && b[1] < yMax) best = Math.max(best, b[3]);
            return best;
        }

        /** an obstacle overlapping the rect: its right edge (or left edge when toLeft), -1 = none */
        float obstacleRight(float l, float r, float t, float b, boolean toLeft) {
            float best = -1;
            for (float[] o : obstacles()) {
                if (o[2] <= l || o[0] >= r || o[3] <= t || o[1] >= b) continue;
                if (toLeft) best = best < 0 ? o[0] : Math.min(best, o[0]);
                else best = Math.max(best, o[2]);
            }
            return best;
        }

        /** is window point (x, y) inside view v (no side effects) */
        static boolean onView(View v, float x, float y) {
            if (v == null || v.getVisibility() != View.VISIBLE) return false;
            int[] l = new int[2];
            v.getLocationInWindow(l);
            return x >= l[0] && y >= l[1] && x < l[0] + v.getWidth() && y < l[1] + v.getHeight();
        }

        /** a finger came down on the game (not on the HUD), window coordinates */
        void gameDown(float wx, float wy) {
            if (getVisibility() != View.VISIBLE) return;
            getLocationInWindow(loc);
            float y = wy - loc[1];
            removeCallbacks(unhide);
            if (y < toolbarH) {
                if (menuHidden) {
                    menuHidden = false;
                } else {
                    menuHidden = true;
                    menuSince = System.currentTimeMillis();
                    postDelayed(unhide, 20000);
                }
                invalidate();
            } else if (menuHidden) {
                postDelayed(unhide, 400);
            }
        }
        long lastFlash;
        boolean flash;

        HudView(Context c) {
            super(c);
        }

        void update() {
            boolean show = hud.active && hud.t != null && Hud.num(hud.t[0], 0) != 0;
            int v = show ? View.VISIBLE : View.GONE;
            if (getVisibility() != v) setVisibility(v);
            if (show) invalidate();
        }

        // ---- touch (window coordinates)
        boolean claim(float wx, float wy) {
            if (getVisibility() != View.VISIBLE || menuHidden) return false;
            getLocationInWindow(loc);
            float x = wx - loc[0], y = wy - loc[1];
            for (int i = hits.size() - 1; i >= 0; i--) {
                float[] h = hits.get(i);
                if (x >= h[0] && x < h[2] && y >= h[1] && y < h[3]) {
                    downAction = (int) h[4];
                    return true;
                }
            }
            downAction = -1;
            return false;
        }

        void tap(float wx, float wy) {
            int a = downAction;
            downAction = -1;
            if (a < 0) return;
            getLocationInWindow(loc);
            float x = wx - loc[0], y = wy - loc[1];
            boolean inside = false;
            for (float[] h : hits) if ((int) h[4] == a && x >= h[0] - 30 && x < h[2] + 30 && y >= h[1] - 30 && y < h[3] + 30) inside = true;
            if (!inside) return;
            try {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            } catch (Throwable e) {
                // no haptics
            }
            act(a);
        }

        void act(int a) {
            Hud h = hud;
            long now = System.currentTimeMillis();
            switch (a) {
                case 1:       // fold the monitor
                    h.monOn = !h.monOn;
                    break;
                case 2: {     // title: acknowledge VCD / penalty, else compact view
                    String[] f = h.t;
                    if (f != null && Hud.num(f[12], 0) != 0) Hud.send(2);
                    else h.compact = !h.compact;
                    break;
                }
                case 3:
                    Hud.send(3);
                    break;
                case 4:
                    Hud.send(4);
                    break;
                case 5:
                    h.hideAll = !h.hideAll;
                    break;
                case 6:
                    h.lightsOn = !h.lightsOn;
                    break;
                case 20:
                    h.cleanWant = !h.clean();
                    h.cleanUntil = now + 1500;
                    Hud.send(20);
                    break;
                case 21:
                    Hud.send(21);
                    break;
                case 18:      // r162: front marker colour white <-> red
                case 19: {    //       back marker colour
                    int e = a - 18;
                    h.colWant[e] = !h.colour(e);
                    h.colUntil[e] = now + 1500;
                    Hud.send(a);
                    break;
                }
                default:
                    if (a >= 10 && a <= 17) {
                        int i = LIGHT_IDX[a - 10];
                        if (i >= 0) {
                            h.lightWant[i] = !h.light(i);
                            h.lightUntil[i] = now + 1500;
                        } else {                           // all markers: front and back together
                            boolean both = !(h.light(2) && h.light(3));
                            h.lightWant[2] = both;
                            h.lightWant[3] = both;
                            h.lightUntil[2] = now + 1500;
                            h.lightUntil[3] = now + 1500;
                        }
                        Hud.send(a);
                    }
            }
            h.savePrefs(getContext());
            invalidate();
        }

        // command 10..17 (tts-l0..l7) -> index in the TTSHL fields (-1 = no own field)
        static final int[] LIGHT_IDX = {0, -1, 2, 3, 4, 5, 6, 1};

        void hit(float l, float tp, float rt, float b, int a) {
            hits.add(new float[]{l, tp, rt, b, a});
        }

        void box(Canvas c, float l, float tp, float w, float h, int color) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.drawRect(l, tp, l + w, tp + h, p);
        }

        void text(Canvas c, String s, float x, float tp, float size, int color, boolean bold, float maxW) {
            if (s == null) return;
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            p.setTextSize(size);
            p.setFakeBoldText(bold);
            p.setTextAlign(Paint.Align.LEFT);
            if (maxW > 0 && p.measureText(s) > maxW) {
                while (s.length() > 1 && p.measureText(s + ".") > maxW) s = s.substring(0, s.length() - 1);
                s = s + ".";
            }
            c.drawText(s, x, tp + size * 0.86f, p);
        }

        void textC(Canvas c, String s, float l, float tp, float w, float h, float size, int color, boolean bold) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            p.setTextSize(size);
            p.setFakeBoldText(bold);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(s, l + w / 2f, tp + h / 2f + size * 0.34f, p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        static String dist(float d) {
            if (d < 1000f) return ((int) (d / 10f + 0.5f) * 10) + " m";
            int t = (int) (d / 100f + 0.5f);
            return (t / 10) + "." + (t % 10) + " km";
        }

        @Override
        protected void onDraw(Canvas c) {
            hits.clear();
            Hud h = hud;
            String[] f = h.t;
            if (!h.active || f == null) return;
            toolbarH = Math.max(1, Hud.num(f[9], 0)) * (getWidth() / (float) Math.max(1, Hud.num(f[6], getWidth())));
            if (menuHidden) return;
            long now = System.currentTimeMillis();
            if (now - lastFlash > 400) {
                lastFlash = now;
                flash = !flash;
            }
            int sw = Math.max(1, Hud.num(f[6], getWidth()));
            sc = getWidth() / (float) sw;
            float ppi = Hud.num(f[8], 326) * sc;
            int side = Hud.num(f[1], 0);
            float topPx = Hud.num(f[9], 0) * sc;
            float bottomFree = Hud.num(f[10], 0) * sc;
            float W = getWidth(), Hh = getHeight();
            boolean clean = h.clean();
            float fs = Math.max(11f * sc, 0.068f * ppi - 8f * sc);
            float fsSmall = Math.max(10f * sc, fs - 3f * sc);
            float rowH = fs + 7f * sc;

            // ---- button bar: TTS hide | Clean view  (clean: a small UI button)
            float bw = clean ? 0.20f * ppi : 0.66f * ppi, bh = 0.13f * ppi;
            float bfs = Math.max(9f * sc, 0.068f * ppi - 11f * sc);
            float bl = side == 1 ? W - bw - 4 * sc : 4 * sc, bt = topPx;
            // r127: keep clear of the other TTS controls (camera bar, screenshot / record knobs)
            float ob = obstacleBottom(side == 1 ? W - 1.10f * ppi - 4 * sc : bl, side == 1 ? W : bl + 1.10f * ppi, Hh * 0.5f);
            if (ob > bt - 2 * sc) bt = ob + 4 * sc;
            if (clean) {
                box(c, bl, bt, bw, bh, 0x700A1424);
                textC(c, "UI", bl, bt, bw, bh, bfs, 0xFFC8D4E4, false);
                hit(bl, bt, bl + bw, bt + bh, 20);
                return;
            }
            float b1 = bw * 0.52f;
            box(c, bl, bt, b1 - 2 * sc, bh, h.hideAll ? 0xEC4A2A06 : 0xEC12284A);
            box(c, bl, bt, 3 * sc, bh, 0xFFFF8A1E);
            textC(c, h.hideAll ? "TTS show" : "TTS hide", bl, bt, b1 - 2 * sc, bh, bfs, 0xFFFFB733, false);
            hit(bl, bt, bl + b1 - 2 * sc, bt + bh, 5);
            box(c, bl + b1, bt, bw - b1, bh, 0xEC24406E);
            textC(c, "Clean view", bl + b1, bt, bw - b1, bh, bfs, 0xFFFFFFFF, false);
            hit(bl + b1, bt, bl + bw, bt + bh, 20);
            if (h.hideAll) return;

            drawMonitor(c, h, f, side, ppi, bt, bottomFree, fs, fsSmall, rowH, W, Hh);
            drawLights(c, h, side, ppi, fs, fsSmall, rowH, W, Hh);
        }

        void drawMonitor(Canvas c, Hud h, String[] f, int side, float ppi, float topPx, float bottomFree,
                         float fs, float fsSmall, float rowH, float W, float Hh) {
            float hh = rowH + 8 * sc;
            float panelW = 1.10f * ppi, panelH = 3.60f * ppi;
            float top = topPx + 0.13f * ppi + 5 * sc;
            if (!h.monOn) panelH = hh;
            else if (h.compact) panelH = hh + 4 * sc + 3 * (rowH + 2 * sc) + 12 * sc;
            if (top + panelH > Hh - bottomFree) panelH = Hh - bottomFree - top;
            if (panelH < hh) panelH = hh;
            float L = side == 1 ? W - panelW - 4 * sc : 4 * sc;
            int tst = Hud.num(f[12], 0);
            if (tst != 0 || h.over != 0) postInvalidateDelayed(400);     // the title flashes
            int cTitle = 0xF412284A;
            if (tst == 1 || h.over != 0) cTitle = flash ? 0xF4C80000 : 0xF4640000;
            else if (tst == 2) cTitle = flash ? 0xF4C88000 : 0xF4644000;
            if (h.monOn) box(c, L, top, panelW, panelH, 0xE60A1424);
            box(c, L, top, panelW, hh, cTitle);
            box(c, L, top, 4 * sc, hh, 0xFFFF8A1E);
            box(c, L, top + hh - 2 * sc, panelW, 2 * sc, 0xFFFF8A1E);
            float foldW = 0.22f * ppi;
            String title = f[11];
            if (h.compact && tst == 0 && h.over == 0 && title.equals("TRACK MONITOR")) title = "MONITOR  mini";
            text(c, title, L + 12 * sc, top + 4 * sc, fs, 0xFFFFFFFF, true, panelW - foldW - 22 * sc);
            hit(L, top, L + panelW - foldW - 8 * sc, top + hh, 2);
            box(c, L + panelW - foldW - 5 * sc, top + 4 * sc, foldW, hh - 10 * sc, 0xFF24406E);
            textC(c, h.monOn ? "-" : "+", L + panelW - foldW - 5 * sc, top + 4 * sc, foldW, hh - 10 * sc, fs, 0xFFFFFFFF, true);
            hit(L + panelW - foldW - 8 * sc, top, L + panelW, top + hh, 1);
            if (!h.monOn) return;

            float spd = h.spdDm / 10f;
            int limK = h.limK;
            float lim = limK > 0 ? limK / 3.6f : 0f;
            int cWhite = 0xFFFFFFFF, cLabel = 0xFF93A7C2, cGreen = 0xFF38E07B, cWarn = 0xFFFFB020, cBad = 0xFFFF4A4A, cCard = 0xEB16243E;
            int spdCol = cWhite, barCol = cGreen, railCol = 0xFF5C7AA8;
            if (lim > 0) {
                if (h.over != 0) spdCol = barCol = railCol = cBad;
                else if (spd >= lim - 0.3f) spdCol = barCol = railCol = cWarn;
            }
            float pad = 6 * sc, cw = panelW - 2 * pad;
            float y = top + hh + 4 * sc;
            int spdK = (int) (spd * 3.6f + 0.5f);
            if (h.compact) {
                // speed + limit, next text, next signal / object
                text(c, spdK + " km/h", L + pad + 4 * sc, y, fs + 2 * sc, spdCol, true, cw * 0.6f);
                text(c, "Limit " + (limK > 0 ? limK + "" : "--"), L + pad + cw * 0.6f, y + 2 * sc, fsSmall, cLabel, false, cw * 0.4f);
                y += rowH + 2 * sc;
                text(c, f[14], L + pad + 4 * sc, y, fsSmall, col(f[15], cWhite), false, cw - 8 * sc);
                y += rowH + 2 * sc;
                signalStrip(c, h, L + pad + 4 * sc, y, cw - 8 * sc, rowH, fsSmall);
                return;
            }
            text(c, f[13], L + pad + 2 * sc, y, fsSmall, cLabel, false, cw);
            y += rowH + 1 * sc;

            // speed card
            float fsBig = fs * 2, bigH = fsBig + 6 * sc, spH = bigH + 12 * sc + rowH;
            box(c, L + pad, y, cw, spH, cCard);
            text(c, spdK + "", L + pad + 8 * sc, y + 1 * sc, fsBig, spdCol, true, cw / 2);
            p.setTextSize(fsBig);
            p.setFakeBoldText(true);
            float sw = p.measureText(spdK + "");
            text(c, "km/h", L + pad + 14 * sc + sw, y + bigH - rowH, fsSmall, cLabel, false, 0);
            float bs = bigH - 4 * sc, bx = L + pad + cw - bs - 9 * sc;
            box(c, bx - 3 * sc, y + 2 * sc, bs + 6 * sc, bs + 6 * sc, 0xFFD32F2F);
            box(c, bx, y + 5 * sc, bs, bs, 0xFFF5F5F0);
            textC(c, limK > 0 ? limK + "" : "--", bx, y + 5 * sc, bs, bs, fs + 2 * sc, 0xFF111111, true);
            float barY = y + bigH + 5 * sc, barW = cw - 16 * sc;
            float scaleMax = lim > 0 ? lim * 1.25f : 44.4f;
            if (scaleMax < 8f) scaleMax = 8f;
            float fillW = Math.min(barW, spd / scaleMax * barW);
            box(c, L + pad + 8 * sc, barY, barW, 6 * sc, 0xFF2A3A58);
            if (fillW > 0) box(c, L + pad + 8 * sc, barY, fillW, 6 * sc, barCol);
            if (lim > 0) box(c, L + pad + 8 * sc + lim / scaleMax * barW - 1 * sc, barY - 3 * sc, 3 * sc, 12 * sc, cWhite);
            text(c, "Projected  " + h.projK + " km/h", L + pad + 8 * sc, barY + 8 * sc, fsSmall, cLabel, false, cw - 16 * sc);
            y += spH + 5 * sc;

            // next card: next change / braking warning, next three signals, MAIN / LOOP
            float nH = rowH * 2 + 8 * sc, badgeW = 0.34f * ppi;
            box(c, L + pad, y, cw, nH, cCard);
            text(c, f[14], L + pad + 8 * sc, y + 3 * sc, fsSmall, col(f[15], cWhite), false, cw - 14 * sc);
            signalStrip(c, h, L + pad + 8 * sc, y + 4 * sc + rowH, cw - badgeW - 22 * sc, rowH, fsSmall);
            String line = f[16];
            if (line.length() > 0) {
                int lc = line.equals("STOP") ? cBad : (line.equals("LOOP") ? cWarn : cGreen);
                float bx2 = L + pad + cw - badgeW - 7 * sc;
                box(c, bx2, y + 6 * sc + rowH, badgeW, rowH - 4 * sc, lc);
                textC(c, line, bx2, y + 6 * sc + rowH, badgeW, rowH - 4 * sc, fsSmall, 0xFF0A1424, true);
            }
            y += nH + 5 * sc;

            // gradient card
            float gcH = rowH * 3 + 8 * sc;
            box(c, L + pad, y, cw, gcH, cCard);
            text(c, f[17], L + pad + 8 * sc, y + 3 * sc, fsSmall, col(f[18], cWhite), false, cw - 14 * sc);
            float pfW = (cw - 16 * sc) * 0.64f, pfH = rowH * 2 - 2 * sc;
            profile(c, h, L + pad + 8 * sc, y + 3 * sc + rowH, pfW, pfH);
            text(c, "next 2 km", L + pad + 14 * sc + pfW, y + 3 * sc + rowH, fsSmall, cLabel, false, cw - pfW - 20 * sc);
            text(c, f[19], L + pad + 14 * sc + pfW, y + 3 * sc + rowH * 2, fsSmall, cWhite, false, cw - pfW - 20 * sc);
            y += gcH + 5 * sc;

            // info card: station, trip (tap = reset), mode (tap = PHYS) [, rail line when the script sends one]
            boolean railRow = f[23].length() > 0;
            float icH = rowH * (railRow ? 4 : 3) + 8 * sc;
            box(c, L + pad, y, cw, icH, cCard);
            text(c, f[20], L + pad + 8 * sc, y + 3 * sc, fsSmall, 0xFF7FD3FF, false, cw - 14 * sc);
            text(c, f[21], L + pad + 8 * sc, y + 3 * sc + rowH, fsSmall, cLabel, false, cw - 14 * sc);
            hit(L + pad, y + 3 * sc + rowH, L + pad + cw, y + 3 * sc + rowH * 2, 21);
            text(c, f[22], L + pad + 8 * sc, y + 3 * sc + rowH * 2, fsSmall, cGreen, false, cw - 14 * sc);
            hit(L + pad, y + 3 * sc + rowH * 2, L + pad + cw, y + 3 * sc + rowH * 3, 4);
            if (railRow) text(c, f[23], L + pad + 8 * sc, y + 3 * sc + rowH * 3, fsSmall, 0xFFFFD27F, false, cw - 14 * sc);
            y += icH + 4 * sc;

            lineDiagram(c, h, L, y, panelW, top + panelH, fs, fsSmall, rowH, ppi, railCol, lim);
        }

        /** distance of an object now: its distance at the scan minus what the train ran since */
        float objDist(Hud h, int[] o) {
            float run = h.trav;                    // metres run since the script's scan of the line
            if (run < 0) run = 0;
            float d = o[2];
            int want = h.rev != 0 ? -1 : 1;
            if (o[1] == want) d -= run;
            else d += run;
            return Math.max(0f, d);
        }

        void signalStrip(Canvas c, Hud h, float x, float y, float w, float rh, float fsSmall) {
            int want = h.rev != 0 ? -1 : 1;
            float slotW = w / 3, dot = Math.max(6 * sc, rh - 6 * sc);
            int shown = 0;
            for (int[] o : h.obj) {
                if (shown >= 3) break;
                if (o[0] != 0 || o[1] != want) continue;
                float d = objDist(h, o);
                float qd = (int) (d / 25f + 0.5f) * 25f;
                float sx = x + shown * slotW, sy = y + 3 * sc;
                box(c, sx, sy - 1 * sc, dot + 2 * sc, dot + 2 * sc, 0xFF000000);
                int lvl = o[5];
                if (lvl == 2) {
                    float half = dot / 2 - 1 * sc;
                    box(c, sx + 1 * sc, sy, dot, half, 0xFFFFBE1E);
                    box(c, sx + 1 * sc, sy + dot - half, dot, half, 0xFFFFBE1E);
                } else {
                    box(c, sx + 1 * sc, sy, dot, dot, lvl == 0 ? 0xFFFF2A1E : (lvl == 3 ? 0xFF28EB78 : 0xFFFFBE1E));
                }
                text(c, dist(qd), sx + dot + 5 * sc, y, fsSmall, 0xFFFFFFFF, false, slotW - dot - 6 * sc);
                shown++;
            }
            if (shown == 0) text(c, "No signal ahead", x, y, fsSmall, 0xFF8FA596, false, w);
        }

        void profile(Canvas c, Hud h, float x, float y, float w, float ph) {
            box(c, x, y, w, ph, 0xFF0C1A30);
            int[] pf = h.prof;
            if (pf == null || pf.length < 2 || pf[0] == Integer.MIN_VALUE) return;
            int cols = Math.min(20, pf.length - 1);
            float cw = Math.max(2 * sc, (int) (w / 20));
            float here = pf[0] / 10f, lo = here, hi = here;
            for (int i = 1; i <= cols; i++) {
                if (pf[i] == Integer.MIN_VALUE) continue;
                lo = Math.min(lo, pf[i] / 10f);
                hi = Math.max(hi, pf[i] / 10f);
            }
            float range = hi - lo;
            if (range < 6f) {
                lo -= (6f - range) / 2f;
                range = 6f;
            }
            float bottom = y + ph - 1 * sc, ah = ph - 4 * sc;
            float prev = here;
            for (int i = 1; i <= cols; i++) {
                if (pf[i] == Integer.MIN_VALUE) break;
                float hv = pf[i] / 10f;
                float bh = 2 * sc + (hv - lo) / range * (ah - 2 * sc);
                float slope = (hv - prev) / 100f * 100f;
                int bc = slope > 0.15f ? 0xFFE8C030 : (slope < -0.15f ? 0xFF4AA8E8 : 0xFF3CC070);
                prev = hv;
                box(c, x + (i - 1) * cw, bottom - bh, cw - 1 * sc, bh, bc);
            }
            float hy = bottom - 2 * sc - (here - lo) / range * (ah - 2 * sc);
            box(c, x, hy, cols * cw, 1 * sc, 0xFFFFFFFF);
            box(c, x, y, 2 * sc, ph, 0xFFFFFFFF);
        }

        void signalHead(Canvas c, float x, float y, float w, float hgt, int level) {
            box(c, x, y, w, hgt, 0xFFF0F0EA);
            box(c, x + 2 * sc, y + 2 * sc, w - 4 * sc, hgt - 4 * sc, 0xFF0A0A0C);
            float step = (hgt - 8 * sc) / 4;
            float lamp = Math.max(3 * sc, Math.min(w - 8 * sc, step - 2 * sc));
            float lx = x + (w - lamp) / 2;
            for (int k = 0; k < 4; k++) {
                int cl = 0xFF3A2A08;
                if (k == 0 && level == 2) cl = 0xFFFFBE1E;
                if (k == 1) cl = level == 3 ? 0xFF28EB78 : 0xFF0A3A1C;
                if (k == 2 && (level == 1 || level == 2)) cl = 0xFFFFBE1E;
                if (k == 3) cl = level == 0 ? 0xFFFF2A1E : 0xFF3A0A08;
                p.setStyle(Paint.Style.FILL);
                p.setColor(cl);
                c.drawCircle(lx + lamp / 2, y + 4 * sc + k * step + step / 2, lamp / 2, p);
            }
        }

        void lineDiagram(Canvas c, Hud h, float L, float y, float panelW, float panelBottom, float fs, float fsSmall,
                         float rowH, float ppi, int railCol, float lim) {
            float distW = 0.34f * ppi, sigX = L + distW, iconW = 0.09f * ppi, iconH = 0.20f * ppi;
            float rail1X = sigX + iconW + 6 * sc, rail2X = rail1X + 0.05f * ppi, ptsX = rail2X + 6 * sc;
            float spdX = ptsX + 0.10f * ppi, spdW = L + panelW - spdX - 3 * sc;
            int cLabel = 0xFF93A7C2, cWhite = 0xFFFFFFFF;
            text(c, "Dist", L + 4 * sc, y, fsSmall, cLabel, false, 0);
            text(c, "Limit", spdX, y, fsSmall, cLabel, false, 0);
            y += rowH;
            float gTop = y + iconH / 2, gBot = panelBottom - iconH / 2 - 4 * sc, gH = gBot - gTop;
            if (gH <= 40 * sc) return;
            float trainH = 0.16f * ppi, trainY = gTop + gH * 0.64f;
            float fwdScale = (trainY - gTop) / 3000f, backScale = (gBot - (trainY + trainH)) / 1500f;
            box(c, rail1X, gTop, 2 * sc, gH, railCol);
            box(c, rail2X, gTop, 2 * sc, gH, railCol);
            float[][] marks = {{500, 1}, {1000, 1}, {2000, 1}, {500, -1}, {1000, -1}};
            for (float[] m : marks) {
                float my = m[1] > 0 ? trainY - m[0] * fwdScale : trainY + trainH + m[0] * backScale;
                box(c, rail1X - 6 * sc, my, rail2X - rail1X + 14 * sc, 1 * sc, 0xFF2A3A56);
            }
            float trainX = rail1X - 4 * sc, trainW = rail2X - rail1X + 10 * sc;
            box(c, trainX, trainY, trainW, trainH, 0xFFFF8A1E);
            float capY = h.rev != 0 ? trainY + trainH - 2 * sc - trainH / 4 : trainY + 2 * sc;
            box(c, trainX + 2 * sc, capY, trainW - 4 * sc, trainH / 4, cWhite);
            float lastAhead = trainY, lastBehind = trainY + trainH;
            boolean shownSpeed = false;
            int ahead = h.rev != 0 ? -1 : 1;
            for (int[] o : h.obj) {
                float qd = (int) (objDist(h, o) / 25f + 0.5f) * 25f;
                float iy;
                if (o[1] == ahead) {
                    iy = trainY - qd * fwdScale - iconH / 2;
                    if (iy > lastAhead - iconH) iy = lastAhead - iconH;
                    if (iy < gTop - iconH / 2) continue;
                    lastAhead = iy;
                } else {
                    iy = trainY + trainH + qd * backScale - iconH / 2;
                    if (iy < lastBehind) iy = lastBehind;
                    if (iy + iconH > gBot + iconH / 2) continue;
                    lastBehind = iy + iconH;
                }
                float midY = iy + iconH / 2 - rowH / 2;
                String dt = dist(qd);
                int k = o[0];
                if (k == 0) {
                    signalHead(c, sigX, iy, iconW, iconH, o[5]);
                    text(c, dt, L + 4 * sc, midY, fsSmall, cWhite, false, distW - 4 * sc);
                    if (o[6] != 0) {
                        box(c, spdX, iy, spdW, rowH, 0xFF38E07B);
                        text(c, o[6] < 0 ? "<LOOP" : "LOOP>", spdX + 2 * sc, iy + 1 * sc, fsSmall, 0xFF000000, false, spdW - 3 * sc);
                    }
                    if (o[4] > 0) text(c, o[4] + "", spdX, iy + rowH, fs, 0xFFFFE040, false, spdW);
                } else if (k == 4 || k == 5) {
                    int oc = k == 5 ? 0xFFFF9030 : 0xFFFF5050;
                    box(c, rail1X - 6 * sc, midY + rowH / 2 - 2 * sc, rail2X - rail1X + 14 * sc, 4 * sc, oc);
                    text(c, k == 5 ? "END" : "TRAIN", ptsX, midY, fsSmall, oc, false, L + panelW - ptsX - 3 * sc);
                    text(c, dt, L + 4 * sc, midY, fsSmall, oc, false, distW - 4 * sc);
                } else if (k == 1) {
                    int pc = (lim > 0 && o[3] < (int) (lim * 3.6f + 0.5f)) ? 0xFFFFE040 : cWhite;
                    text(c, o[3] + "", spdX, midY, fs, pc, false, spdW);
                    if (!shownSpeed) text(c, dt, L + 4 * sc, midY, fsSmall, pc, false, distW - 4 * sc);
                    shownSpeed = true;
                } else {
                    // points: Junction.DIRECTION_LEFT = 0, DIRECTION_RIGHT = 2 (straight / unknown = |)
                    String pt = o[3] == 0 ? "<" : (o[3] == 2 ? ">" : "|");
                    text(c, pt, ptsX, midY, fs, k == 3 ? 0xFF5A6A85 : cWhite, false, spdX - ptsX);
                    if (k == 2) text(c, dt, L + 4 * sc, midY, fsSmall, 0xFFB0C4CC, false, distW - 4 * sc);
                }
            }
        }

        static final int[] LIGHT_CMD = {10, 17, 11, 12, 13, 14, 15, 16};

        // r162: CONTROL PANEL (was LIGHTS): grouped rows, marker colour per end (white / red)
        void section(Canvas c, String title, float L, float y, float panelW, float fsSmall) {
            text(c, title, L + 10 * sc, y, fsSmall, 0xFFFFB733, true, panelW - 20 * sc);
            box(c, L + 10 * sc, y + fsSmall * 1.25f, panelW - 20 * sc, 1.5f * sc, 0x55FFB733);
        }

        void toggleRow(Canvas c, String label, boolean on, int cmd, float L, float y, float panelW, float rh, float fs,
                       float rightRoom) {
            box(c, L + 6 * sc, y, panelW - 12 * sc, rh, on ? 0xF0143C28 : 0xF0162440);
            box(c, L + 6 * sc, y, 4 * sc, rh, on ? 0xFF38E07B : 0xFF3A4A66);
            text(c, label, L + 16 * sc, y + (rh - fs) / 2, fs * 0.92f, on ? 0xFFFFFFFF : 0xFFB8C4D6, false,
                    panelW - rightRoom - 30 * sc);
            float sw = rh * 1.15f, sh = rh * 0.52f, sx = L + panelW - sw - 12 * sc, sy = y + (rh - sh) / 2;
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? 0xFF38E07B : 0xFF46546E);
            r.set(sx, sy, sx + sw, sy + sh);
            c.drawRoundRect(r, sh / 2, sh / 2, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(on ? sx + sw - sh / 2 : sx + sh / 2, sy + sh / 2, sh / 2 - 2 * sc, p);
            hit(L, y, L + panelW, y + rh, cmd);
        }

        /** the WHITE / RED chooser of a marker row (tap = the other colour) */
        void colourChip(Canvas c, boolean red, int cmd, float L, float y, float panelW, float rh, float fsSmall) {
            float cw = rh * 2.9f, ch = rh * 0.64f;
            float cx = L + panelW - rh * 1.15f - 12 * sc - cw - 8 * sc, cy = y + (rh - ch) / 2;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF0B1322);
            r.set(cx, cy, cx + cw, cy + ch);
            c.drawRoundRect(r, ch / 2, ch / 2, p);
            float half = cw / 2;
            p.setColor(red ? 0xFFE0383F : 0xFFF2F2EA);
            float hx = red ? cx + half : cx;
            r.set(hx + 2 * sc, cy + 2 * sc, hx + half - 2 * sc, cy + ch - 2 * sc);
            c.drawRoundRect(r, ch / 2, ch / 2, p);
            float ts = Math.min(fsSmall * 0.82f, ch * 0.52f);
            p.setTextSize(ts);
            p.setFakeBoldText(true);
            float wW = p.measureText("WHITE"), wR = p.measureText("RED");
            text(c, "WHITE", cx + (half - wW) / 2, cy + (ch - ts) / 2, ts, red ? 0xFF8A97AD : 0xFF10202E, true, 0);
            text(c, "RED", cx + half + (half - wR) / 2, cy + (ch - ts) / 2, ts, red ? 0xFFFFFFFF : 0xFF8A97AD, true, 0);
            hit(cx - 6 * sc, y, cx + cw + 6 * sc, y + rh, cmd);
        }

        void drawLights(Canvas c, Hud h, int side, float ppi, float fs, float fsSmall, float rowH, float W, float Hh) {
            float step = rowH + 14 * sc, panelW = 1.12f * ppi, rh = step - 4 * sc, sec = fsSmall * 1.9f;
            float bodyH = 8 * step + 3 * sec + 1.3f * rowH + 4 * sc;
            float panelH = h.lightsOn ? rowH + 8 * sc + bodyH : rowH + 8 * sc;
            float top = Hh * 0.22f;
            if (top + panelH > Hh - 0.45f * ppi) top = Hh - 0.45f * ppi - panelH;
            if (top < 40 * sc) top = 40 * sc;
            float L = side == 0 ? W - panelW - 4 * sc : 4 * sc;
            // r127: beside the TTS screenshot / record knobs instead of on top of them
            float orr = obstacleRight(L, L + panelW, top, top + panelH, side == 0);
            if (orr >= 0) L = side == 0 ? orr - panelW - 6 * sc : orr + 6 * sc;
            if (h.lightsOn) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xEA0A1424);
                r.set(L, top, L + panelW, top + panelH);
                c.drawRoundRect(r, 10 * sc, 10 * sc, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xF412284A);
            r.set(L, top, L + panelW, top + rowH + 6 * sc);
            c.drawRoundRect(r, 10 * sc, 10 * sc, p);
            box(c, L, top + 4 * sc, 4 * sc, rowH - 2 * sc, 0xFFFF8A1E);
            text(c, "CONTROL PANEL", L + 12 * sc, top + 4 * sc, fs, 0xFFFFB733, true, panelW - 0.4f * ppi);
            text(c, h.lightsOn ? "[-]" : "[+]", L + panelW - 0.32f * ppi, top + 4 * sc, fs, 0xFFFFB733, true, 0);
            hit(L, top, L + panelW, top + rowH + 6 * sc, 6);
            if (!h.lightsOn) return;
            float y = top + rowH + 12 * sc;
            section(c, "HEADLIGHT", L, y, panelW, fsSmall);
            y += sec;
            toggleRow(c, "Headlights", h.light(0), 10, L, y, panelW, rh, fs, 0);
            y += step;
            toggleRow(c, "High beam", h.light(1), 17, L, y, panelW, rh, fs, 0);
            y += step;
            section(c, "MARKER LIGHTS", L, y, panelW, fsSmall);
            y += sec;
            float chipRoom = rh * 2.9f + 16 * sc;
            toggleRow(c, "Front", h.light(2), 12, L, y, panelW, rh, fs, chipRoom);
            colourChip(c, h.colour(0), 18, L, y, panelW, rh, fsSmall);
            y += step;
            toggleRow(c, "Back", h.light(3), 13, L, y, panelW, rh, fs, chipRoom);
            colourChip(c, h.colour(1), 19, L, y, panelW, rh, fsSmall);
            y += step;
            toggleRow(c, "Both ends", h.light(2) && h.light(3), 11, L, y, panelW, rh, fs, 0);
            y += step;
            section(c, "FLASHERS", L, y, panelW, fsSmall);
            y += sec;
            toggleRow(c, "Flasher front", h.light(4), 14, L, y, panelW, rh, fs, 0);
            y += step;
            toggleRow(c, "Flasher back", h.light(5), 15, L, y, panelW, rh, fs, 0);
            y += step;
            toggleRow(c, "Emergency flasher", h.light(6), 16, L, y, panelW, rh, fs, 0);
            y += step + 2 * sc;
            String[] lf = h.lights;
            text(c, lf != null && lf.length > 8 ? lf[8] : "", L + 8 * sc, y, fsSmall, 0xFF93A7C2, false, panelW - 16 * sc);
        }
    }

    // ================================================================ r126: CPU boost for the game's busiest threads
    /**
     * The lag is the emulated engine itself: one or two threads (the emulated CPU core, the GL thread) run at
     * 90-100 % of a core.  Every 2 s this looks at how busy every thread of the game is (/proc/self/task) and
     * gives the busiest ones (over 35 % of a core, at most 4) a higher scheduler priority; on Android 12+ they
     * also get a performance-hint session (ADPF) that asks the phone for higher CPU clocks while they are
     * saturated (over 85 %), and lets the clocks fall again when they are not.  Our own threads are left alone.
     * Stops while the game is in the background.
     */
    static final Boost boost = new Boost();

    static final class Boost implements Runnable {
        static final long TARGET_NS = 16666666L;
        Thread th;
        volatile boolean run;
        final HashMap<Integer, Long> lastTicks = new HashMap<Integer, Long>();
        long lastT;
        int[] picks = new int[0];
        float busy;
        final java.util.HashSet<Integer> raised = new java.util.HashSet<Integer>();
        Object session;
        Method report, close;
        boolean adpfFailed;
        int logged;

        synchronized void start() {
            run = true;
            if (th != null && th.isAlive()) return;
            th = new Thread(this, "TTSBoost");
            th.setDaemon(true);
            th.start();
        }

        void stop() {
            run = false;
        }

        public void run() {
            int n = 0;
            while (run) {
                try {
                    if (n % 20 == 0) scan();
                    hint();
                } catch (Throwable t) {
                    if (logged++ < 5) log("boost: " + t);
                }
                n++;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
            endSession();
        }

        /** CPU ticks (user + system) of a thread, or -1; skips our own TTS* threads */
        static long ticks(File task) {
            try {
                File stat = new File(task, "stat");
                FileInputStream in = new FileInputStream(stat);
                byte[] b = new byte[512];
                int len;
                try {
                    len = in.read(b);
                } finally {
                    in.close();
                }
                if (len <= 0) return -1;
                String s = new String(b, 0, len, "ISO-8859-1");
                int o = s.indexOf('('), c = s.lastIndexOf(')');
                if (o < 0 || c < 0) return -1;
                if (s.substring(o + 1, c).startsWith("TTS")) return -1;
                String[] f = s.substring(c + 2).trim().split(" ");
                if (f.length < 13) return -1;
                return Long.parseLong(f[11]) + Long.parseLong(f[12]);
            } catch (Throwable t) {
                return -1;
            }
        }

        void scan() {
            File[] tasks = new File("/proc/self/task").listFiles();
            if (tasks == null) return;
            long now = System.nanoTime();
            float dt = lastT == 0 ? 0f : (now - lastT) / 1e9f;
            lastT = now;
            HashMap<Integer, Long> cur = new HashMap<Integer, Long>();
            java.util.ArrayList<float[]> use = new java.util.ArrayList<float[]>();
            for (File t : tasks) {
                int tid;
                try {
                    tid = Integer.parseInt(t.getName());
                } catch (NumberFormatException e) {
                    continue;
                }
                long k = ticks(t);
                if (k < 0) continue;
                cur.put(tid, k);
                Long was = lastTicks.get(tid);
                if (was != null && dt > 0.5f) use.add(new float[]{tid, (k - was) / 100f / dt});
            }
            lastTicks.clear();
            lastTicks.putAll(cur);
            if (use.isEmpty()) return;
            java.util.Collections.sort(use, new java.util.Comparator<float[]>() {
                public int compare(float[] a, float[] b) {
                    return Float.compare(b[1], a[1]);
                }
            });
            java.util.ArrayList<Integer> pick = new java.util.ArrayList<Integer>();
            float top = 0f;
            for (float[] u : use) {
                if (pick.size() >= 4 || u[1] < 0.35f) break;
                pick.add((int) u[0]);
                top = Math.max(top, u[1]);
            }
            busy = top;
            for (int tid : pick) {
                if (raised.contains(tid)) continue;
                try {
                    android.os.Process.setThreadPriority(tid, -10);
                    raised.add(tid);
                    if (logged++ < 20) log("boost: thread " + tid + " priority -10");
                } catch (Throwable e) {
                    if (logged++ < 20) log("boost: priority " + tid + ": " + e);
                }
            }
            raised.retainAll(cur.keySet());
            int[] p = new int[pick.size()];
            for (int i = 0; i < p.length; i++) p[i] = pick.get(i);
            java.util.Arrays.sort(p);
            // r158: a new hint session at most every 30 s (r126 made a new one each time the busiest set changed,
            // every 2-4 s, which itself costs and kept the clocks jumping)
            long nowMs = System.currentTimeMillis();
            if (!java.util.Arrays.equals(p, picks) && (session == null || nowMs - sessionAt > 30000)) {
                picks = p;
                endSession();
                startSession();
                sessionAt = nowMs;
            }
        }

        void startSession() {
            if (adpfFailed || picks.length == 0 || android.os.Build.VERSION.SDK_INT < 31 || app == null) return;
            try {
                Object mgr = app.getSystemService("performance_hint");
                if (mgr == null) {
                    adpfFailed = true;
                    return;
                }
                Method create = mgr.getClass().getMethod("createHintSession", int[].class, long.class);
                session = create.invoke(mgr, picks, TARGET_NS);
                if (session == null) return;
                report = session.getClass().getMethod("reportActualWorkDuration", long.class);
                close = session.getClass().getMethod("close");
                if (logged++ < 20) log("boost: hint session for " + picks.length + " threads");
            } catch (Throwable e) {
                adpfFailed = true;
                session = null;
                log("boost: no hint session: " + e);
            }
        }

        void endSession() {
            Object s = session;
            session = null;
            if (s == null || close == null) return;
            try {
                close.invoke(s);
            } catch (Throwable e) {
                // gone already
            }
        }

        long sessionAt;
        int thermal;              // r158: PowerManager thermal status (0 none .. 6 shutdown)
        long thermalAt;

        int thermalStatus() {
            long now = System.currentTimeMillis();
            if (now - thermalAt < 5000) return thermal;
            thermalAt = now;
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29 && app != null) {
                    Object pm = app.getSystemService("power");
                    int st = (Integer) pm.getClass().getMethod("getCurrentThermalStatus").invoke(pm);
                    if (st != thermal) log("boost: thermal status " + thermal + " -> " + st);
                    thermal = st;
                }
            } catch (Throwable t) {
                // no thermal API
            }
            return thermal;
        }

        void hint() {
            Object s = session;
            if (s == null || report == null) return;
            try {
                // saturated: "the work took longer than the target" -> the phone raises the clocks.
                // r158: only when really saturated, and never once the phone is warm (thermal status MODERATE+):
                // then "the work fits" so the clocks can come down - pushing a hot phone only heats it more and
                // ends in heavier throttling (lag that grows with play time)
                int th = thermalStatus();
                long actual = (th < 2 && busy > 0.92f) ? TARGET_NS * 5 / 4 : TARGET_NS * 4 / 5;
                report.invoke(s, actual);
            } catch (Throwable e) {
                endSession();
            }
        }
    }

    // ================================================================ r164: phone screen recording = clean video
    /**
     * Android 15+ tells an app when its window is being screen-recorded (WindowManager.addScreenRecordingCallback,
     * permission DETECT_SCREEN_RECORDING in the manifest).  While the phone's recorder runs, the TTS overlays (HUD,
     * CONTROL PANEL, horn pad, TTS camera bar / knobs) are made fully transparent: they are not in the video, and a
     * finger on their place still works.  The TTS recorder (record knob) is not affected: it keeps the HUD (r127).
     * Reflection only, so the dex builds with any android.jar.
     */
    static Object recCb;
    static Activity recFor;
    static volatile boolean recOn;
    static final java.util.WeakHashMap<View, Float> recAlpha = new java.util.WeakHashMap<View, Float>();

    static void watchRecording(Activity a) {
        if (android.os.Build.VERSION.SDK_INT < 35 || a == null || a == recFor) return;
        try {
            Class<?> cc = Class.forName("java.util.function.Consumer");
            if (recCb != null && recFor != null) {
                try {
                    Object owm = recFor.getWindowManager();
                    owm.getClass().getMethod("removeScreenRecordingCallback", cc).invoke(owm, recCb);
                } catch (Throwable t) {
                    // old activity gone
                }
            }
            recCb = Proxy.newProxyInstance(cc.getClassLoader(), new Class<?>[]{cc}, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] args) {
                    String n = m.getName();
                    if ("accept".equals(n) && args != null && args.length == 1) {
                        final boolean on = Integer.valueOf(1).equals(args[0]);
                        H.post(new Runnable() {
                            public void run() {
                                setRecording(on);
                            }
                        });
                        return null;
                    }
                    if ("hashCode".equals(n)) return System.identityHashCode(p);
                    if ("equals".equals(n)) return args != null && args.length == 1 && p == args[0];
                    if ("toString".equals(n)) return "TTSRecWatch";
                    return null;
                }
            });
            Object wm = a.getWindowManager();
            Method add = wm.getClass().getMethod("addScreenRecordingCallback", java.util.concurrent.Executor.class, cc);
            Object st = add.invoke(wm, new java.util.concurrent.Executor() {
                public void execute(Runnable r) {
                    H.post(r);
                }
            }, recCb);
            recFor = a;
            log("rec watch: on, state " + st);
            setRecording(Integer.valueOf(1).equals(st));
        } catch (Throwable t) {
            log("rec watch: " + t);
        }
    }

    static final Runnable recTick = new Runnable() {
        public void run() {
            if (!recOn) return;
            applyRecording(true);                         // views added while recording (pad shown, menus ...)
            H.postDelayed(this, 500);
        }
    };

    static void setRecording(boolean on) {
        if (on == recOn) return;
        recOn = on;
        log("rec: phone screen recording " + (on ? "started - TTS overlays hidden" : "stopped - TTS overlays back"));
        applyRecording(on);
        H.removeCallbacks(recTick);
        if (on) H.postDelayed(recTick, 500);
    }

    static void applyRecording(boolean on) {
        try {
            if (!on) {
                for (java.util.Map.Entry<View, Float> e : recAlpha.entrySet()) {
                    View v = e.getKey();
                    if (v != null) v.setAlpha(e.getValue());
                }
                recAlpha.clear();
                return;
            }
            Activity a = owner;
            if (a == null) return;
            View c = a.findViewById(android.R.id.content);
            if (!(c instanceof ViewGroup)) return;
            ViewGroup g = (ViewGroup) c;
            HudView hv = hud.view;
            for (int i = 0; i < g.getChildCount(); i++) {
                View v = g.getChildAt(i);
                if (v instanceof android.view.SurfaceView || v instanceof android.view.TextureView) continue;
                boolean ours = v == hv || v == pad;
                // other TTS controls (camera bar, knobs) are small; anything as big as the screen may be the game
                if (!ours && (v.getWidth() > g.getWidth() * 0.9f && v.getHeight() > g.getHeight() * 0.7f)) continue;
                if (!recAlpha.containsKey(v)) recAlpha.put(v, v.getAlpha());
                if (v.getAlpha() != 0f) v.setAlpha(0f);
            }
        } catch (Throwable t) {
            log("rec hide: " + t);
        }
    }

    // ================================================================ r127: our views in TTS screenshots / recordings
    /**
     * The TTS screenshot (TTSZoom) and recorder (TTSRecorder) copy only the game's GL surface, so the Java HUD and
     * the horn pad were missing in them.  Both now call in here (classes2 patched): the screenshot gets the views
     * drawn onto its bitmap at full size (main thread), the recorder gets a snapshot of them that the main
     * thread refreshes every 100 ms while a recording asks for it (views must only be drawn on the main thread).
     */
    static volatile android.graphics.Bitmap ovl;
    static volatile long ovlWant;
    static volatile boolean ovlLoop;
    static final android.graphics.Bitmap[] ovlBuf = new android.graphics.Bitmap[3];
    static int ovlNext;

    static void drawViews(Canvas c, int targetW) {
        HudView hv = hud.view;
        View root = hv != null && hv.getParent() instanceof View ? (View) hv.getParent() : null;
        if (root == null || root.getWidth() <= 0) return;
        float s = targetW / (float) root.getWidth();
        int[] rl = new int[2];
        root.getLocationInWindow(rl);
        View[] vs = {hv, pad};
        for (View v : vs) {
            if (v == null || v.getVisibility() != View.VISIBLE || v.getWidth() <= 0) continue;
            int[] l = new int[2];
            v.getLocationInWindow(l);
            int save = c.save();
            c.scale(s, s);
            c.translate(l[0] - rl[0], l[1] - rl[1]);
            v.draw(c);
            c.restoreToCount(save);
        }
    }

    /** screenshot: called on the main thread with the copied game picture */
    public static void overlayBitmap(android.graphics.Bitmap bm) {
        try {
            if (bm != null && bm.isMutable()) drawViews(new Canvas(bm), bm.getWidth());
        } catch (Throwable t) {
            log("shot overlay: " + t);
        }
    }

    /** recorder: called on its own thread for every frame, after the game picture is drawn */
    public static void overlay(Canvas c, int w, int h) {
        try {
            ovlWant = System.currentTimeMillis();
            if (!ovlLoop) {
                ovlLoop = true;
                H.post(ovlTick);
            }
            android.graphics.Bitmap b = ovl;
            if (b != null) c.drawBitmap(b, null, new android.graphics.Rect(0, 0, w, h), null);
        } catch (Throwable t) {
            // the frame goes out without the HUD
        }
    }

    static final Runnable ovlTick = new Runnable() {
        public void run() {
            if (System.currentTimeMillis() - ovlWant > 1500) {
                ovlLoop = false;
                ovl = null;
                for (int i = 0; i < ovlBuf.length; i++) ovlBuf[i] = null;
                return;
            }
            try {
                HudView hv = hud.view;
                View root = hv != null && hv.getParent() instanceof View ? (View) hv.getParent() : null;
                if (root != null && root.getWidth() > 0 && root.getHeight() > 0) {
                    int rw = root.getWidth(), rh = root.getHeight();
                    int tw = Math.min(rw, 1280 * rw / Math.max(rw, rh));
                    int th = Math.max(1, tw * rh / rw);
                    android.graphics.Bitmap b = ovlBuf[ovlNext];
                    if (b == null || b.getWidth() != tw || b.getHeight() != th) {
                        b = android.graphics.Bitmap.createBitmap(tw, th, android.graphics.Bitmap.Config.ARGB_8888);
                        ovlBuf[ovlNext] = b;
                    }
                    b.eraseColor(0);
                    drawViews(new Canvas(b), tw);
                    ovl = b;
                    ovlNext = (ovlNext + 1) % ovlBuf.length;
                }
            } catch (Throwable t) {
                log("rec overlay: " + t);
            }
            H.postDelayed(this, 100);
        }
    };

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
        boolean hudTouch;          // r126: this gesture is a tap on the HUD
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
            // r126: HUD panels take a whole one-finger gesture (the game never sees it)
            HudView hv = hud.view;
            if (act == MotionEvent.ACTION_DOWN && padId < 0) {
                hudTouch = hv != null && hv.claim(ev.getX(), ev.getY());
                if (!hudTouch && hv != null && !HudView.onView(p, ev.getX(), ev.getY())) hv.gameDown(ev.getX(), ev.getY());
            }
            if (hudTouch) {
                if (act == MotionEvent.ACTION_UP) {
                    hudTouch = false;
                    if (hv != null) hv.tap(ev.getX(), ev.getY());
                } else if (act == MotionEvent.ACTION_CANCEL) {
                    hudTouch = false;
                }
                return true;
            }
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
    // r138: preset 2 = the horn / bell / sander of the .sms sound set (Open Rails events 8/9, 10/11, 4/5)
    static final String[] LABEL2 = {"", "HORN", "HORN", "BELL", "SAND"};
    static final int[] EV_ON = {0, 8, 8, 10, 4};
    static final int[] EV_OFF = {0, 9, 9, 11, 5};
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
        int preset = 1;                // r138: 1 = the pad's own sounds, 2 = the .sms sound set's horns
        boolean badgeTap;              // r138: the small preset button at the top-left corner
        boolean pressedSms;            // the side held now plays through the .sms
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
                preset = sp.getInt("preset", 1) == 2 ? 2 : 1;
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

        boolean smsOk() {
            return preset == 2 && TTSSms.active();
        }

        void pressSide(int k) {
            if (smsOk()) {
                pressedSms = true;
                TTSSms.post(EV_ON[k]);
            } else {
                pressedSms = false;
                mixer.press(k);
            }
        }

        void releaseSide(int k) {
            if (pressedSms) {
                pressedSms = false;
                TTSSms.post(EV_OFF[k]);
            } else {
                mixer.release(k);
            }
        }

        void dropTouch() {
            removeCallbacks(longPress);
            if (dir != 0) releaseSide(dir);
            dir = 0;
            held = false;
            editDrag = false;
            editAct = 0;
            miniTap = false;
            miniDrag = false;
            gearTap = false;
            badgeTap = false;
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
            boolean gear = false, badge = false;
            if (!hidden) {
                float g = getWidth() * 0.11f;
                float gx = wx - loc[0] - (getWidth() - g), gy = wy - loc[1] - g;
                gear = gx * gx + gy * gy < g * g * 2.0f;
                float bx = wx - loc[0] - g, by = wy - loc[1] - g;
                badge = !edit && bx * bx + by * by < g * g * 2.0f;
            }
            if (!gear && !badge && dx * dx + dy * dy > R * R) return false;   // outside the circle: the game gets it
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
            if (badge) {
                badgeTap = true;
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
            if (badgeTap) {
                if (far > 40 * 40) badgeTap = false;
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
            if (badgeTap) {
                badgeTap = false;
                if (!cancel) {
                    preset = preset == 1 ? 2 : 1;
                    savePrefs();
                    log("preset " + preset + (preset == 2 ? (TTSSms.active() ? " (.sms horns)" : " (no .sms loaded: pad sounds)") : ""));
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                invalidate();
                return;
            }
            if (gearTap) {
                gearTap = false;
                if (!cancel) {
                    edit = !edit;
                    log("settings " + (edit ? "open" : "closed"));
                    if (dir != 0) releaseSide(dir);
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
            if (dir != 0) releaseSide(dir);
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
                if (dir != 0) releaseSide(dir);
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
                if (dir != 0) releaseSide(dir);
                dir = nd;
                if (nd != 0) {
                    removeCallbacks(longPress);
                    pressSide(nd);
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
                        .putFloat("scale", scale).putBoolean("hidden", hidden).putInt("preset", preset).apply();
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
                boolean ok = preset == 2 && TTSSms.active() ? true : mixer.has(k);
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
                cv.drawText(preset == 2 && TTSSms.active() ? LABEL2[k] : LABEL[k], tx, ty, text);
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
            if (!edit) badge(cv);
        }

        /** r138: preset button at the top-left corner: 1 = pad sounds, 2 = .sms horns */
        void badge(Canvas cv) {
            float w = getWidth();
            float g = w * 0.11f;
            float gx = g, gy = g, r = g * 0.82f;
            boolean two = preset == 2;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(badgeTap ? 0xFF2EA8FF : (two ? 0xFF1E88E5 : 0xD80B1322));
            cv.drawCircle(gx, gy, r, fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Math.max(1.5f, r * 0.10f));
            fill.setColor(0xCC2EA8FF);
            cv.drawCircle(gx, gy, r * 0.97f, fill);
            fill.setStyle(Paint.Style.FILL);
            text.setColor(0xFFFFFFFF);
            text.setTextSize(Math.max(9f, r * 1.05f));
            cv.drawText(two ? "2" : "1", gx, gy + text.getTextSize() * 0.36f, text);
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
