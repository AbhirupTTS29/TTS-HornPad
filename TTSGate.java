package com.n3vgames.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TTS Trainz Simulator - leak-control gate (account login + owner approval + device binding).
 *
 *  - The game does not open until the player has logged in with a Firebase account (the same Firebase
 *    project as TTS Forge) AND the developer has approved that account (game_access/<uid>.status).
 *  - The first approved open on a phone binds that phone's id to the account.  The same account on any
 *    other phone is refused (a shared login does not work).
 *  - The developer's own account (game_access/<uid>.admin = true) always opens.
 *  - After one successful online open the phone may start the game offline for GRACE_DAYS.  Whenever the
 *    phone is online at start, the account is re-checked in the background: if the developer has blocked
 *    it (or the binding no longer matches) the game is shut again at once.

 *  - Login: "Sign in with Google" (system browser + loopback redirect + PKCE, exactly like TTS Forge) or
 *    email + password.  Both end in the same Firebase account (uid) -> the same game_access/<uid> record.
 *  - Plain HTTPS to the Firebase REST API - no Google Play Services.  The Firebase project values are NOT
 *    in this source: they are read from the APK asset tts_gate.cfg (two lines: api key, project id).
 *
 * Hooks (TTSHornPad): onActivityResumed(game) -> TTSGate.check(activity); returns false while the login
 * screen must stay in front.
 */
public final class TTSGate {

    static final int GRACE_DAYS = 7;
    static final byte[] SESS_SECRET = "tts-trainz-gate-v1-1078783-keep".getBytes();

    // overridable for the host test only
    static String AUTH_BASE = "https://identitytoolkit.googleapis.com";
    static String TOKEN_BASE = "https://securetoken.googleapis.com";
    static String FS_BASE = "https://firestore.googleapis.com";
    static String GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    static String GOOGLE_TOKEN = "https://oauth2.googleapis.com/token";
    static boolean PATCH_VIA_POST = false;      // host test: the JDK's HttpURLConnection has no PATCH

    static String fbKey = "", fbPid = "", gId = "", gSecret = "";   // from assets/tts_gate.cfg
    static volatile boolean passed = false;     // the gate has been cleared in this process
    static volatile boolean rechecked = false;
    static volatile boolean showing = false;

    // ---------------------------------------------------------------- hook
    /** true = the game may run; false = the login screen is (being) shown in front. */
    public static boolean check(Activity game) {
        if (passed) return true;
        Context app = game.getApplicationContext();
        loadConfig(app);
        if (offlineOk(app)) {
            passed = true;
            recheckAsync(game);
            return true;
        }
        show(game);
        return false;
    }

    static void show(Activity from) {
        try {
            Intent i = new Intent(from, GateActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            from.startActivity(i);          // singleTop + reorder: an open login screen is only brought forward
        } catch (Throwable t) {
            showing = false;
        }
    }

    static void loadConfig(Context c) {
        if (fbKey.length() > 0) return;
        try {
            InputStream in = c.getAssets().open("tts_gate.cfg");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String a = r.readLine(), b = r.readLine(), c2 = r.readLine(), d = r.readLine();
            r.close();
            if (a != null) fbKey = a.trim();
            if (b != null) fbPid = b.trim();
            if (c2 != null) gId = c2.trim();
            if (d != null) gSecret = d.trim();
        } catch (Throwable t) {
            // missing config -> the gate cannot verify anybody -> it stays shut (fail closed)
        }
    }

    static String deviceId(Context c) {
        String a = "";
        try { a = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID); } catch (Throwable t) { }
        if (a == null) a = "";
        return sha(("ttstrainz|" + a).getBytes()).substring(0, 32);
    }

    // ---------------------------------------------------------------- the decision (pure logic, testable)
    static final int OK = 0, PENDING = 1, BLOCKED = 2, OTHER_DEVICE = 3, BAD_LOGIN = 4, NET = 5, ERR = 6, EXISTS = 7, WEAK = 8, NOCFG = 9;

    static final class Res {
        int code; String uid = "", refresh = "", msg = "";
        Res(int c, String m) { code = c; msg = m; }
    }

    static Res login(String email, String pass, boolean signup, String dev) {
        if (fbKey.length() == 0 || fbPid.length() == 0) return new Res(NOCFG, "This build is not configured.");
        String idToken, uid, refresh;
        try {
            JSONObject body = new JSONObject();
            body.put("email", email); body.put("password", pass); body.put("returnSecureToken", true);
            String ep = signup ? "accounts:signUp" : "accounts:signInWithPassword";
            JSONObject r = new JSONObject(http("POST", AUTH_BASE + "/v1/" + ep + "?key=" + fbKey, body.toString(), null));
            idToken = r.optString("idToken", ""); uid = r.optString("localId", ""); refresh = r.optString("refreshToken", "");
            if (idToken.length() == 0 || uid.length() == 0) return new Res(ERR, "Login failed.");
        } catch (HttpErr he) {
            if (he.code >= 500 || he.code == 429) return new Res(NET, "Server busy. Try again in a moment.");
            return authErr(he.body);
        } catch (Throwable t) {
            return new Res(NET, "No connection. Check your internet.");
        }
        Res r = access(uid, idToken, email, dev);
        r.uid = uid; r.refresh = refresh;
        return r;
    }

    // ---------------------------------------------------------------- Google sign-in (browser + loopback + PKCE)
    static final class GFlow {
        final String verifier, challenge, state;
        final ServerSocket ss;
        final int port;
        volatile boolean cancelled = false;
        String error = "";

        GFlow() throws Exception {
            SecureRandom rnd = new SecureRandom();
            byte[] v = new byte[64]; rnd.nextBytes(v);
            verifier = b64url(v);
            challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII")));
            byte[] st = new byte[18]; rnd.nextBytes(st);
            state = b64url(st);
            ss = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));   // loopback only
            port = ss.getLocalPort();
        }

        String redirect() { return "http://127.0.0.1:" + port; }

        String url() throws Exception {
            return GOOGLE_AUTH + "?client_id=" + URLEncoder.encode(gId, "UTF-8")
                + "&redirect_uri=" + URLEncoder.encode(redirect(), "UTF-8")
                + "&response_type=code&scope=" + URLEncoder.encode("openid email profile", "UTF-8")
                + "&code_challenge=" + challenge + "&code_challenge_method=S256"
                + "&state=" + state + "&prompt=select_account";
        }

        void cancel() { cancelled = true; try { ss.close(); } catch (Throwable t) { } }

        /** blocks until the browser comes back with the code (or timeout / cancel); null = no code, see error */
        String waitCode(int seconds) {
            long end = System.currentTimeMillis() + seconds * 1000L;
            try {
                ss.setSoTimeout(1000);
                while (!cancelled && System.currentTimeMillis() < end) {
                    Socket c;
                    try { c = ss.accept(); } catch (java.net.SocketTimeoutException e) { continue; }
                    try {
                        c.setSoTimeout(3000);
                        String line = readLine(c.getInputStream());
                        // drain the headers
                        for (int i = 0; i < 64; i++) { String h = readLine(c.getInputStream()); if (h == null || h.length() == 0) break; }
                        String code = null, st = null, err = null;
                        if (line != null && line.startsWith("GET ")) {
                            int sp = line.indexOf(' ', 4);
                            String target = line.substring(4, sp < 0 ? line.length() : sp);
                            int q = target.indexOf('?');
                            if (q >= 0) {
                                for (String kv : target.substring(q + 1).split("&")) {
                                    int e = kv.indexOf('=');
                                    if (e < 0) continue;
                                    String k = kv.substring(0, e), v = URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                                    if (k.equals("code")) code = v; else if (k.equals("state")) st = v; else if (k.equals("error")) err = v;
                                }
                            }
                        }
                        boolean mine = st != null && st.equals(state);     // anything else (favicon, strangers) is ignored
                        String page = mine ? (code != null ? "Sign-in complete. Go back to TTS Trainz Simulator."
                                                           : "Sign-in was cancelled. Go back to the game.")
                                           : "";
                        byte[] body = page.getBytes("UTF-8");
                        OutputStream o = c.getOutputStream();
                        o.write(("HTTP/1.1 " + (mine ? "200 OK" : "404 Not Found") + "\r\nContent-Type: text/plain; charset=utf-8\r\n"
                            + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                        o.write(body); o.flush();
                        if (mine) {
                            if (code != null) return code;
                            error = err != null ? err : "cancelled";
                            return null;
                        }
                    } catch (Throwable t) {
                        // a broken connection: keep waiting
                    } finally {
                        try { c.close(); } catch (Throwable t) { }
                    }
                }
            } catch (Throwable t) {
                if (!cancelled) error = "server";
            } finally {
                try { ss.close(); } catch (Throwable t) { }
            }
            if (error.length() == 0) error = cancelled ? "cancelled" : "timeout";
            return null;
        }
    }

    static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') break;
            if (ch != '\r') sb.append((char) ch);
            if (sb.length() > 4096) break;
        }
        return (ch < 0 && sb.length() == 0) ? null : sb.toString();
    }

    static String b64url(byte[] b) {
        final String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (; i + 2 < b.length; i += 3) {
            int n = ((b[i] & 255) << 16) | ((b[i + 1] & 255) << 8) | (b[i + 2] & 255);
            sb.append(A.charAt(n >> 18 & 63)).append(A.charAt(n >> 12 & 63)).append(A.charAt(n >> 6 & 63)).append(A.charAt(n & 63));
        }
        if (b.length - i == 1) {
            int n = (b[i] & 255) << 16;
            sb.append(A.charAt(n >> 18 & 63)).append(A.charAt(n >> 12 & 63));
        } else if (b.length - i == 2) {
            int n = ((b[i] & 255) << 16) | ((b[i + 1] & 255) << 8);
            sb.append(A.charAt(n >> 18 & 63)).append(A.charAt(n >> 12 & 63)).append(A.charAt(n >> 6 & 63));
        }
        return sb.toString();
    }

    /** the Google code -> Google id_token -> Firebase session -> the same access check as email login */
    static Res googleFinish(String code, GFlow f, String dev) {
        if (fbKey.length() == 0 || fbPid.length() == 0 || gId.length() == 0) return new Res(NOCFG, "Google sign-in is not configured.");
        try {
            String form = "code=" + URLEncoder.encode(code, "UTF-8") + "&client_id=" + URLEncoder.encode(gId, "UTF-8")
                + "&redirect_uri=" + URLEncoder.encode(f.redirect(), "UTF-8") + "&grant_type=authorization_code"
                + "&code_verifier=" + URLEncoder.encode(f.verifier, "UTF-8");
            if (gSecret.length() > 0) form += "&client_secret=" + URLEncoder.encode(gSecret, "UTF-8");
            JSONObject g = new JSONObject(httpForm(GOOGLE_TOKEN, form));
            String gid = g.optString("id_token", "");
            if (gid.length() == 0) return new Res(ERR, "Google did not return an id token.");
            JSONObject body = new JSONObject();
            body.put("postBody", "id_token=" + gid + "&providerId=google.com");
            body.put("requestUri", "http://localhost");
            body.put("returnSecureToken", true);
            body.put("returnIdpCredential", true);
            JSONObject r = new JSONObject(http("POST", AUTH_BASE + "/v1/accounts:signInWithIdp?key=" + fbKey, body.toString(), null));
            String idToken = r.optString("idToken", ""), uid = r.optString("localId", ""), refresh = r.optString("refreshToken", "");
            if (idToken.length() == 0 || uid.length() == 0) return new Res(ERR, "Firebase sign-in failed.");
            Res a = access(uid, idToken, r.optString("email", ""), dev);
            a.uid = uid; a.refresh = refresh;
            return a;
        } catch (HttpErr he) {
            if (he.code >= 500 || he.code == 429) return new Res(NET, "Server busy. Try again in a moment.");
            return new Res(ERR, "Google sign-in failed (" + he.code + ").");
        } catch (Throwable t) {
            return new Res(NET, "No connection. Check your internet.");
        }
    }

    /** background re-check with the saved refresh token; NET = could not tell (keep the grace period) */
    static Res recheck(String refresh, String dev) {
        try {
            String form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8");
            JSONObject r = new JSONObject(httpForm(TOKEN_BASE + "/v1/token?key=" + fbKey, form));
            String idToken = r.optString("id_token", ""), uid = r.optString("user_id", "");
            if (idToken.length() == 0 || uid.length() == 0) return new Res(ERR, "");
            Res a = access(uid, idToken, "", dev);
            a.uid = uid; a.refresh = r.optString("refresh_token", refresh);
            return a;
        } catch (HttpErr he) {
            // a revoked / deleted account answers 400 from the token endpoint
            if (he.code == 400) return new Res(BLOCKED, "Account no longer valid.");
            return new Res(NET, "");
        } catch (Throwable t) {
            return new Res(NET, "");
        }
    }

    static Res access(String uid, String idToken, String email, String dev) {
        try {
            String url = FS_BASE + "/v1/projects/" + fbPid + "/databases/(default)/documents/game_access/" + uid;
            JSONObject doc;
            try {
                doc = new JSONObject(http("GET", url, null, idToken));
            } catch (HttpErr he) {
                if (he.code == 404) {
                    if (email.length() > 0) register(url, idToken, email);
                    return new Res(PENDING, "Request sent. Waiting for the developer to approve your access.");
                }
                throw he;
            }
            JSONObject f = doc.optJSONObject("fields");
            String status = fsStr(f, "status"), bound = fsStr(f, "device");
            boolean admin = fsBool(f, "admin");
            if (admin) {
                if (bound.length() == 0) bind(url, idToken, dev);     // owner: works on any phone, binding is only informative
                return new Res(OK, "");
            }
            if ("blocked".equals(status)) return new Res(BLOCKED, "This account is blocked.");
            if (!"approved".equals(status)) return new Res(PENDING, "Waiting for the developer to approve your access.");
            if (bound.length() == 0) {
                bind(url, idToken, dev);
                // two phones can race the first bind: only the first PATCH wins, so READ IT BACK and compare
                JSONObject again = new JSONObject(http("GET", url, null, idToken));
                String now = fsStr(again.optJSONObject("fields"), "device");
                if (!dev.equals(now)) return new Res(OTHER_DEVICE, "This account is already used on another device.");
            } else if (!bound.equals(dev)) {
                return new Res(OTHER_DEVICE, "This account is already used on another device.");
            }
            return new Res(OK, "");
        } catch (HttpErr he) {
            if (he.code == 401 || he.code == 403) return new Res(ERR, "Access check was refused.");
            return new Res(NET, "No connection. Check your internet.");
        } catch (Throwable t) {
            return new Res(NET, "No connection. Check your internet.");
        }
    }

    static void register(String docUrl, String idToken, String email) {
        try {
            JSONObject fields = new JSONObject();
            fields.put("status", strVal("pending"));
            fields.put("email", strVal(email));
            fields.put("at", intVal(System.currentTimeMillis() / 1000));
            String url = docUrl + "?currentDocument.exists=false&updateMask.fieldPaths=status"
                + "&updateMask.fieldPaths=email&updateMask.fieldPaths=at";
            http("PATCH", url, new JSONObject().put("fields", fields).toString(), idToken);
        } catch (Throwable ignore) { }
    }

    static void bind(String docUrl, String idToken, String dev) {
        try {
            JSONObject fields = new JSONObject().put("device", strVal(dev));
            http("PATCH", docUrl + "?updateMask.fieldPaths=device", new JSONObject().put("fields", fields).toString(), idToken);
        } catch (Throwable ignore) { }
    }

    // ---------------------------------------------------------------- offline session (device-bound, signed)
    static void saveSession(Context c, String uid, String dev, String refresh) {
        try {
            long exp = System.currentTimeMillis() / 1000 + (long) GRACE_DAYS * 86400;
            String payload = uid + "|" + dev + "|" + exp;
            SharedPreferences.Editor e = c.getSharedPreferences("tts_gate", Context.MODE_PRIVATE).edit();
            e.putString("sess", payload + "|" + hmac(payload));
            if (refresh != null && refresh.length() > 0) e.putString("rt", refresh);
            e.apply();
        } catch (Throwable ignore) { }
    }

    static void clearSession(Context c) {
        try {
            c.getSharedPreferences("tts_gate", Context.MODE_PRIVATE).edit().putString("sess", "").putString("rt", "").apply();
        } catch (Throwable ignore) { }
    }

    static boolean offlineOk(Context c) {
        try {
            String s = c.getSharedPreferences("tts_gate", Context.MODE_PRIVATE).getString("sess", "");
            if (s == null || s.length() == 0) return false;
            int k = s.lastIndexOf('|');
            if (k < 0) return false;
            String payload = s.substring(0, k), mac = s.substring(k + 1);
            if (!hmac(payload).equals(mac)) return false;
            String[] p = payload.split("\\|");
            if (p.length != 3) return false;
            if (!p[1].equals(deviceId(c))) return false;
            return System.currentTimeMillis() / 1000 < Long.parseLong(p[2]);
        } catch (Throwable t) { return false; }
    }

    static void recheckAsync(final Activity game) {
        if (rechecked) return;
        rechecked = true;
        final Context app = game.getApplicationContext();
        new Thread(new Runnable() { public void run() {
            try {
                String rt = app.getSharedPreferences("tts_gate", Context.MODE_PRIVATE).getString("rt", "");
                if (rt == null || rt.length() == 0) return;
                Res r = recheck(rt, deviceId(app));
                if (r.code == OK) {
                    saveSession(app, r.uid, deviceId(app), r.refresh);          // renew the grace period
                } else if (r.code == BLOCKED || r.code == PENDING || r.code == OTHER_DEVICE) {
                    clearSession(app);                                            // revoked: shut the game again
                    passed = false;
                    game.runOnUiThread(new Runnable() { public void run() { show(game); } });
                }
            } catch (Throwable ignore) { }
        } }).start();
    }

    // ---------------------------------------------------------------- login screen
    public static final class GateActivity extends Activity {
        EditText email, pass;
        TextView status;
        Button loginBtn, signupBtn, googleBtn;
        String dev;
        GFlow flow;

        @Override protected void onCreate(Bundle b) {
            super.onCreate(b);
            dev = deviceId(this);
            loadConfig(getApplicationContext());
            try { getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); } catch (Throwable t) { }

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(48, 48, 48, 48);
            root.setGravity(Gravity.CENTER_HORIZONTAL);

            TextView t = new TextView(this);
            t.setText("TTS Trainz Simulator");
            t.setTextColor(0xFFFFD060); t.setTextSize(22f); t.setGravity(Gravity.CENTER);
            root.addView(t);

            TextView sub = new TextView(this);
            sub.setText("Sign in to play");
            sub.setTextColor(0xFFAAB4C0); sub.setTextSize(14f); sub.setGravity(Gravity.CENTER);
            sub.setPadding(0, 6, 0, 24);
            root.addView(sub);

            googleBtn = new Button(this); googleBtn.setText("Sign in with Google");
            root.addView(googleBtn);
            TextView or = new TextView(this);
            or.setText("or use email");
            or.setTextColor(0xFF66707A); or.setTextSize(12f); or.setGravity(Gravity.CENTER);
            or.setPadding(0, 12, 0, 4);
            root.addView(or);

            email = new EditText(this);
            email.setHint("Email");
            email.setSingleLine(true);
            email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            email.setTextColor(Color.WHITE); email.setHintTextColor(0xFF66707A);
            root.addView(email);

            pass = new EditText(this);
            pass.setHint("Password");
            pass.setSingleLine(true);
            pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            pass.setTextColor(Color.WHITE); pass.setHintTextColor(0xFF66707A);
            root.addView(pass);

            loginBtn = new Button(this); loginBtn.setText("Login");
            root.addView(loginBtn);
            signupBtn = new Button(this); signupBtn.setText("Create account");
            root.addView(signupBtn);

            status = new TextView(this);
            status.setTextColor(0xFFAAB4C0); status.setTextSize(13f); status.setGravity(Gravity.CENTER);
            status.setPadding(0, 20, 0, 0);
            root.addView(status);

            TextView d = new TextView(this);
            d.setText("Device ID: " + dev);
            d.setTextColor(0xFF66707A); d.setTextSize(11f); d.setGravity(Gravity.CENTER);
            d.setPadding(0, 28, 0, 0);
            root.addView(d);

            ScrollView sv = new ScrollView(this);
            sv.setBackgroundColor(0xFF0B0F14);
            sv.setFillViewport(true);
            sv.addView(root);
            setContentView(sv);

            loginBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { go(false); } });
            signupBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { go(true); } });
            googleBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { goGoogle(); } });
        }

        @Override public void onBackPressed() { finishAffinity(); }   // no way past the gate

        @Override protected void onDestroy() { if (flow != null) flow.cancel(); showing = false; super.onDestroy(); }

        void setStatus(final String s, final boolean enable) {
            runOnUiThread(new Runnable() { public void run() {
                status.setText(s); loginBtn.setEnabled(enable); signupBtn.setEnabled(enable); googleBtn.setEnabled(true);
            } });
        }

        void goGoogle() {
            loadConfig(getApplicationContext());
            if (gId.length() == 0) { setStatus("Google sign-in is not configured in this build.", true); return; }
            try {
                if (flow != null) flow.cancel();                 // a second tap restarts cleanly
                final GFlow f = new GFlow();
                flow = f;
                setStatus("Opening Google... pick your account, then come back to the game.", true);
                final String u = f.url();
                new Thread(new Runnable() { public void run() {
                    String code = f.waitCode(300);
                    if (code == null) {
                        if (!"cancelled".equals(f.error) || flow == f) setStatus(googleMsg(f.error), true);
                        return;
                    }
                    try {                                         // pull the game back over the browser
                        Intent back = new Intent(GateActivity.this, GateActivity.class);
                        back.addFlags(0x10000000 | 0x20000 | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(back);
                    } catch (Throwable ignore) { }
                    setStatus("Checking your access...", false);
                    Res r = googleFinish(code, f, dev);
                    if (r.code == OK) {
                        saveSession(getApplicationContext(), r.uid, dev, r.refresh);
                        passed = true;
                        runOnUiThread(new Runnable() { public void run() { finish(); } });
                    } else {
                        setStatus(r.msg, true);
                    }
                } }).start();
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
            } catch (Throwable t) {
                setStatus("Could not open the browser for Google sign-in.", true);
            }
        }

        String googleMsg(String e) {
            if ("timeout".equals(e)) return "Google sign-in timed out. Try again.";
            if ("access_denied".equals(e) || "cancelled".equals(e)) return "Google sign-in was cancelled.";
            return "Google sign-in failed.";
        }

        void go(final boolean signup) {
            final String em = email.getText().toString().trim();
            final String pw = pass.getText().toString();
            if (em.length() < 3 || pw.length() < 6) { setStatus("Enter your email and a password (6+ characters).", true); return; }
            setStatus(signup ? "Creating account..." : "Signing in...", false);
            new Thread(new Runnable() { public void run() {
                Res r = login(em, pw, signup, dev);
                if (r.code == OK) {
                    saveSession(getApplicationContext(), r.uid, dev, r.refresh);
                    passed = true;
                    runOnUiThread(new Runnable() { public void run() { finish(); } });   // the game behind resumes
                } else {
                    setStatus(r.msg, true);
                }
            } }).start();
        }
    }

    // ---------------------------------------------------------------- tiny HTTP + JSON helpers
    static final class HttpErr extends Exception { int code; String body; HttpErr(int c, String b) { code = c; body = b; } }

    static String httpForm(String url, String form) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000); con.setReadTimeout(20000);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        con.setDoOutput(true);
        OutputStream os = con.getOutputStream(); os.write(form.getBytes("UTF-8")); os.close();
        return finish(con);
    }

    static String http(String method, String url, String body, String bearer) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000); con.setReadTimeout(20000);
        if ("PATCH".equals(method) && PATCH_VIA_POST) {
            con.setRequestMethod("POST");
            con.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        } else {
            con.setRequestMethod(method);
        }
        con.setRequestProperty("Content-Type", "application/json");
        if (bearer != null) con.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            con.setDoOutput(true);
            OutputStream os = con.getOutputStream(); os.write(body.getBytes("UTF-8")); os.close();
        }
        return finish(con);
    }

    static String finish(HttpURLConnection con) throws Exception {
        int code = con.getResponseCode();
        InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
        String out = readAll(in);
        con.disconnect();
        if (code >= 400) throw new HttpErr(code, out);
        return out;
    }

    static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    static Res authErr(String body) {
        String m = "";
        try { m = new JSONObject(body).optJSONObject("error").optString("message", ""); } catch (Throwable t) { }
        if (m.contains("EMAIL_NOT_FOUND") || m.contains("INVALID_PASSWORD") || m.contains("INVALID_LOGIN"))
            return new Res(BAD_LOGIN, "Wrong email or password.");
        if (m.contains("EMAIL_EXISTS")) return new Res(EXISTS, "That email already has an account. Use Login.");
        if (m.contains("WEAK_PASSWORD")) return new Res(WEAK, "Password too weak (6+ characters).");
        if (m.contains("TOO_MANY_ATTEMPTS")) return new Res(ERR, "Too many tries. Wait a little.");
        if (m.contains("OPERATION_NOT_ALLOWED")) return new Res(ERR, "Email login is not enabled for this app.");
        return new Res(ERR, "Login failed.");
    }

    static JSONObject strVal(String s) { try { return new JSONObject().put("stringValue", s); } catch (Exception e) { return new JSONObject(); } }
    static JSONObject intVal(long v) { try { return new JSONObject().put("integerValue", String.valueOf(v)); } catch (Exception e) { return new JSONObject(); } }
    static String fsStr(JSONObject fields, String k) {
        if (fields == null) return "";
        JSONObject v = fields.optJSONObject(k);
        return v == null ? "" : v.optString("stringValue", "");
    }
    static boolean fsBool(JSONObject fields, String k) {
        if (fields == null) return false;
        JSONObject v = fields.optJSONObject(k);
        return v != null && v.optBoolean("booleanValue", false);
    }

    static String hex(byte[] h) {
        StringBuilder sb = new StringBuilder();
        for (byte x : h) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }
    static String sha(byte[] b) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (Throwable t) { return "00000000000000000000000000000000"; }
    }
    static String hmac(String msg) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(SESS_SECRET, "HmacSHA256"));
            return hex(m.doFinal(msg.getBytes("UTF-8")));
        } catch (Throwable t) { return ""; }
    }
}
