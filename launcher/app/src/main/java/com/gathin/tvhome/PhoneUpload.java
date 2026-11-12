package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * "Choose from phone": photos sent from a phone on the same network become Home's wallpaper, with no account and no
 * cloud. While the sheet in TvosSettings is open, and only then, a small HTTP server listens on a random port. It
 * serves one page whose file picker opens the phone's photo picker (Google Photos included), and takes each photo as
 * a raw POST. Every request must carry the sheet's one-time token (in the QR code's address) and come from a private
 * address; a photo is at most 20 MB, a visit at most 30 photos, and the server stops when the sheet closes, after
 * Done, or after 10 minutes.
 *
 * Each photo is cropped to the panel's shape and scaled to its physical size (at most 3840x2160), then saved as JPEG
 * q85 in the theme's wallpapers/ folder as phone-*.jpg, where the rotation, the Settings preview and the screensaver
 * read it. A new visit's photos replace the last visit's.
 */
final class PhoneUpload {
    private static final String TAG = "PhoneUpload";
    static final String PREFIX = "phone-";
    static final long MAX_BYTES = 20L * 1024 * 1024;
    static final int MAX_PHOTOS = 30;
    private static final int MAX_HEADER = 8192;
    private static final long LIFETIME_MS = 10 * 60_000L;
    private static final int MAX_W = 3840, MAX_H = 2160;

    interface Listener {
        /** A photo was saved: {@code count} so far in this visit. */
        void received(int count);

        /** The phone said it is done, with {@code count} photos saved (main thread). */
        void done(int count);

        /** Ten minutes passed: the sheet should close. */
        void expired();
    }

    private final Context ctx;
    private final File folder;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String token;
    private final String stamp;
    private ServerSocket server;
    private Thread thread;
    private volatile boolean running;
    private int count;
    private String url;

    PhoneUpload(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        File dir = Config.dir(ctx);
        this.folder = dir == null ? null : new File(dir, ThemeFiles.WALLPAPERS);
        this.listener = listener;
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder t = new StringBuilder();
        for (byte b : random) t.append(String.format(Locale.ROOT, "%02x", b));
        token = t.toString();
        stamp = Long.toString(System.currentTimeMillis() / 1000, 36);
    }

    /** Starts listening; the address to show, or null when the TV has no network address or storage. */
    String start() {
        String host = lanAddress();
        if (host == null || folder == null) return null;
        try {
            server = new ServerSocket(0, 8);
        } catch (IOException e) {
            Log.w(TAG, "cannot listen: " + e.getMessage());
            return null;
        }
        running = true;
        url = "http://" + host + ":" + server.getLocalPort() + "/?t=" + token;
        Log.i(TAG, "listening on port " + server.getLocalPort());
        thread = new Thread(this::serve, "phone-upload");
        thread.start();
        main.postDelayed(expire, LIFETIME_MS);
        return url;
    }

    private final Runnable expire = this::expire;

    private void expire() {
        if (!running) return;
        stop();
        listener.expired();
    }

    /** Stops listening and refuses anything further; the token dies with it. */
    void stop() {
        running = false;
        main.removeCallbacks(expire);
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    // ---- The server, one connection at a time on its own thread -----------------------------------------------

    private void serve() {
        while (running) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(20_000);
                if (!privateAddress(s.getInetAddress())) continue;
                handle(s);
            } catch (IOException e) {
                if (running) Log.w(TAG, "request: " + e.getMessage());
            }
        }
    }

    private void handle(Socket s) throws IOException {
        InputStream in = new BufferedInputStream(s.getInputStream());
        OutputStream out = s.getOutputStream();
        String request = line(in);
        if (request == null) return;
        String[] parts = request.split(" ");
        if (parts.length < 2) return;
        String method = parts[0], target = parts[1];
        long length = -1;
        boolean expectContinue = false;
        for (int total = 0; ; ) {
            String h = line(in);
            if (h == null || h.isEmpty()) break;
            total += h.length();
            if (total > MAX_HEADER) return;
            int colon = h.indexOf(':');
            if (colon < 0) continue;
            String name = h.substring(0, colon).trim().toLowerCase(Locale.ROOT), value = h.substring(colon + 1).trim();
            if (name.equals("content-length")) {
                try {
                    length = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    length = -1;
                }
            } else if (name.equals("expect")) {
                expectContinue = value.equalsIgnoreCase("100-continue");
            }
        }
        int q = target.indexOf('?');
        String path = q < 0 ? target : target.substring(0, q);
        if (!tokenOk(q < 0 ? "" : target.substring(q + 1), token)) {
            reply(out, 403, "text/plain", "Forbidden");
            return;
        }
        if (method.equals("GET") && path.equals("/")) {
            reply(out, 200, "text/html; charset=utf-8", page());
        } else if (method.equals("POST") && path.equals("/photo")) {
            int refused = refusal(length, count);
            if (refused == 413) {
                reply(out, 413, "application/json", "{\"error\":\"too big\"}");
            } else if (refused == 429) {
                reply(out, 429, "application/json", "{\"error\":\"too many\"}");
            } else {
                if (expectContinue) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
                boolean saved = receive(in, length);
                reply(out, saved ? 200 : 415, "application/json",
                        saved ? "{\"count\":" + count + "}" : "{\"error\":\"not a JPEG, PNG or WebP photo\"}");
            }
        } else if (method.equals("POST") && path.equals("/done")) {
            reply(out, 200, "application/json", "{\"count\":" + count + "}");
            int n = count;
            stop();
            main.post(() -> listener.done(n));
        } else {
            reply(out, 404, "text/plain", "Not found");
        }
    }

    /** The query's t= token against the visit's, compared in constant time so it cannot be guessed a byte at a time. */
    static boolean tokenOk(String query, String token) {
        if (query == null || token == null || token.isEmpty()) return false;
        String given = null;
        for (String kv : query.split("&")) if (kv.startsWith("t=")) given = kv.substring(2);
        return given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.US_ASCII),
                token.getBytes(StandardCharsets.US_ASCII));
    }

    /** 413 for a body that is empty, unknown or over MAX_BYTES, 429 once MAX_PHOTOS have come; 0 to take it. */
    static int refusal(long length, int received) {
        if (length <= 0 || length > MAX_BYTES) return 413;
        if (received >= MAX_PHOTOS) return 429;
        return 0;
    }

    /** Loopback (adb forward), and the LAN's private, link-local and unique-local ranges; nothing routable. */
    static boolean privateAddress(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress();
            return (b[0] & 0xFE) == 0xFC;   // fc00::/7
        }
        return false;
    }

    /** The photo body to a temporary file, then into the wallpapers folder; false when it is not an image. */
    private boolean receive(InputStream in, long length) throws IOException {
        File tmp = new File(ctx.getCacheDir(), "phone-upload.part");
        try (OutputStream f = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            long left = length;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("short body");
                f.write(buf, 0, n);
                left -= n;
            }
        }
        try {
            if (!image(tmp)) return false;
            //noinspection ResultOfMethodCallIgnored
            folder.mkdirs();
            File to = new File(folder, String.format(Locale.ROOT, "%s%s-%02d.jpg", PREFIX, stamp, count + 1));
            if (!save(tmp, to, TvDesign.panel(ctx))) return false;
            // A new visit's first photo replaces the last visit's.
            if (count == 0) clear(ctx, PREFIX + stamp);
            int n = ++count;
            main.post(() -> listener.received(n));
            return true;
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "photo: " + e);
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** JPEG, PNG or WebP by their first bytes, whatever the phone called it. */
    private static boolean image(File f) throws IOException {
        byte[] h = new byte[12];
        int n;
        try (InputStream in = new java.io.FileInputStream(f)) {
            n = in.read(h);
        }
        return imageHeader(h, n);
    }

    /** True for the first bytes of a JPEG, a PNG or a WebP; at least 12 are needed. */
    static boolean imageHeader(byte[] h, int n) {
        if (h == null || n < 12 || h.length < 12) return false;
        boolean jpeg = (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8;
        boolean png = (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
        boolean webp = h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
        return jpeg || png || webp;
    }

    /**
     * Crops the photo to the panel's shape around its centre and scales it to the panel's pixels (never up), upright
     * by its EXIF orientation. Only the crop is decoded, sampled down to at most twice the size it ends at, in RGB_565
     * as Home draws wallpapers: a 48 MP photo for a 4K panel peaks near 35 MB, once.
     */
    static boolean save(File from, File to, Size panel) throws IOException {
        int pw = Math.min(MAX_W, panel.getWidth()), ph = Math.min(MAX_H, panel.getHeight());
        int rotation = 0;
        try {
            switch (new ExifInterface(from.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: case ExifInterface.ORIENTATION_TRANSPOSE: rotation = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: case ExifInterface.ORIENTATION_FLIP_VERTICAL: rotation = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: case ExifInterface.ORIENTATION_TRANSVERSE: rotation = 270; break;
                default: break;
            }
        } catch (IOException ignored) {
            // PNG and WebP without EXIF: as stored.
        }
        BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(from.getPath(), false);
        if (decoder == null) return false;
        Bitmap region, out;
        int tw;
        try {
            boolean sideways = rotation == 90 || rotation == 270;
            int w = sideways ? decoder.getHeight() : decoder.getWidth();
            int h = sideways ? decoder.getWidth() : decoder.getHeight();
            // The largest centred crop with the panel's shape, as the picture appears upright.
            int cw = w, ch = Math.round(w * ph / (float) pw);
            if (ch > h) {
                ch = h;
                cw = Math.round(h * pw / (float) ph);
            }
            tw = Math.min(pw, cw);
            int sw = sideways ? ch : cw, sh = sideways ? cw : ch;
            int left = (decoder.getWidth() - sw) / 2, top = (decoder.getHeight() - sh) / 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inSampleSize = 1;
            while (cw / (o.inSampleSize * 2) >= tw) o.inSampleSize *= 2;
            region = decoder.decodeRegion(new Rect(left, top, left + sw, top + sh), o);
        } finally {
            decoder.recycle();
        }
        if (region == null) return false;
        int rw = rotation == 90 || rotation == 270 ? region.getHeight() : region.getWidth();
        float scale = Math.min(1f, tw / (float) rw);
        Matrix m = new Matrix();
        m.postRotate(rotation);
        m.postScale(scale, scale);
        out = Bitmap.createBitmap(region, 0, 0, region.getWidth(), region.getHeight(), m, true);
        if (out != region) region.recycle();
        File tmp = new File(to.getPath() + ".part");
        try (OutputStream f = new FileOutputStream(tmp)) {
            if (!out.compress(Bitmap.CompressFormat.JPEG, 85, f)) return false;
        } finally {
            out.recycle();
        }
        return tmp.renameTo(to);
    }

    // ---- Phone photos on disk ---------------------------------------------------------------------------------

    static boolean isPhone(File f) {
        return f != null && f.getName().startsWith(PREFIX);
    }

    /** The phone photos in the wallpapers folder, oldest first. */
    static List<File> photos(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir == null) return Collections.emptyList();
        List<File> out = new ArrayList<>();
        for (File f : WallpaperRotation.images(new File(dir, ThemeFiles.WALLPAPERS))) if (isPhone(f)) out.add(f);
        return out;
    }

    /** Deletes every phone photo. */
    static void clear(Context ctx) {
        clear(ctx, null);
    }

    /** Deletes the phone photos, except those whose names start with {@code keep}. */
    private static void clear(Context ctx, String keep) {
        File dir = Config.dir(ctx);
        File[] files = dir == null ? null : new File(dir, ThemeFiles.WALLPAPERS).listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            if (isPhone(f) && (keep == null || !f.getName().startsWith(keep))) f.delete();
        }
    }

    // ---- HTTP plumbing -----------------------------------------------------------------------------------------

    private static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') return b.toString();
            if (c != '\r') b.append((char) c);
            if (b.length() > MAX_HEADER) return null;
        }
        return b.length() == 0 ? null : b.toString();
    }

    private static void reply(OutputStream out, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found"
                : code == 413 ? "Payload Too Large" : code == 415 ? "Unsupported Media Type" : "Too Many Requests";
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: "
                + bytes.length + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    /** The TV's address on the local network: Wi-Fi or Ethernet, IPv4 first. */
    private static String lanAddress() {
        try {
            String v6 = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!privateAddress(a) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    if (a instanceof Inet4Address) return a.getHostAddress();
                    if (v6 == null) v6 = "[" + a.getHostAddress().replaceAll("%.*", "") + "]";
                }
            }
            return v6;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The phone's page: pick photos, then each goes up on its own, then Done. In the TV's language (strings.xml). */
    private String page() {
        android.content.res.Resources r = ctx.getResources();
        return page(r.getString(R.string.phone_page_title), r.getString(R.string.phone_page_heading),
                r.getString(R.string.phone_page_body, MAX_PHOTOS), r.getString(R.string.phone_page_choose),
                r.getString(R.string.phone_page_sending, "{i}", "{n}"), r.getString(R.string.phone_page_sent, "{n}"),
                r.getString(R.string.phone_page_nothing));
    }

    /**
     * The page from its texts, escaped for HTML and for a JavaScript string. {@code sending} holds {i} and {n} and
     * {@code sent} {n}, filled in on the phone with the photo being sent, the total, and the count sent.
     */
    static String page(String title, String heading, String body, String choose, String sending, String sent,
                       String nothing) {
        return "<!doctype html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'><title>" + html(title) + "</title>"
                + "<style>body{margin:0;padding:32px 24px;font:17px -apple-system,system-ui,sans-serif;background:#111;"
                + "color:#f5f5f7}h1{font-size:28px;margin:0 0 8px}p{color:#a1a1a6;margin:0 0 28px}"
                + "label{display:block;padding:18px;border-radius:14px;background:#f5f5f7;color:#1c1c1e;"
                + "text-align:center;font-weight:600}input{display:none}#s{margin-top:24px;color:#f5f5f7}</style>"
                + "</head><body><h1>" + html(heading) + "</h1><p>" + html(body) + "</p><label>" + html(choose)
                + "<input id=f type=file accept='image/jpeg,image/png,image/webp' multiple></label><div id=s></div><script>"
                + "const T={sending:'" + js(sending) + "',sent:'" + js(sent) + "',nothing:'" + js(nothing) + "'};"
                + "const s=document.getElementById('s');document.getElementById('f').onchange=async e=>{"
                + "const files=[...e.target.files].slice(0," + MAX_PHOTOS + ");let ok=0;"
                + "for(let i=0;i<files.length;i++){s.textContent=T.sending.replace('{i}',i+1).replace('{n}',files.length);"
                + "try{const r=await fetch('/photo'+location.search,{method:'POST',body:files[i]});if(r.ok)ok++;}"
                + "catch(x){}}"
                + "await fetch('/done'+location.search,{method:'POST'}).catch(()=>{});"
                + "s.textContent=ok?T.sent.replace('{n}',ok):T.nothing;};</script></body></html>";
    }

    static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String js(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\u003c").replace("\n", "\\n");
    }
}
