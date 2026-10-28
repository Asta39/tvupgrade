package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.ImageView;

import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The hero clock's digits as liquid glass (this fork): clear lenses over the wallpaper rather than frosted panes.
 * Each minute (and each picture change) the digits are rendered on a worker thread: their shape is softened into a
 * height field, whose slope bends the picture behind each stroke towards the edges (red and blue a little apart,
 * like real glass), lights the edges that face the top-left, and shades the ones facing away.
 */
final class GlassClock extends View {
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    /** Software copy of the picture on screen, made once per picture: the shown one may be a GPU bitmap. */
    private static Bitmap source;
    private static Bitmap sourceOf;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float dp;
    private Bitmap frame;
    private String shown = "";
    private int generation;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            render();
        }
    };

    GlassClock(Context ctx, Typeface face, float sizePx, float scaleX) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        text.setTypeface(face);
        text.setTextSize(sizePx);
        text.setTextScaleX(scaleX);
        text.setLetterSpacing(-0.02f);
        text.setColor(Color.WHITE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot || l != ol || t != ot) render();
        });
    }

    private String now() {
        return DateFormat.format(DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm", new Date()).toString();
    }

    private int pad() {
        return Math.round(24 * dp);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        Rect b = new Rect();
        text.getTextBounds("0", 0, 1, b);
        float w = text.measureText(now());
        setMeasuredDimension(Math.round(w) + 2 * pad(), b.height() + 2 * pad());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        getContext().registerReceiver(tick, f);
        render();
    }

    @Override
    protected void onDetachedFromWindow() {
        getContext().unregisterReceiver(tick);
        super.onDetachedFromWindow();
    }

    /** Re-render for the current minute and picture; also called by Home after a picture change. */
    void render() {
        String t = now();
        if (!t.equals(shown) && Math.round(text.measureText(t)) + 2 * pad() != getWidth()) {
            shown = t;
            requestLayout();   // a new width: the layout pass calls back here
            return;
        }
        shown = t;
        int w = getWidth(), h = getHeight();
        ImageView wall = WallpaperPlayer.shownView;
        Bitmap pic = WallpaperPlayer.shownBitmap;
        if (w <= 0 || h <= 0 || wall == null || pic == null || pic.isRecycled()) return;
        // Where this view sits in the picture's own pixels.
        int[] me = new int[2], it = new int[2];
        getLocationOnScreen(me);
        wall.getLocationOnScreen(it);
        Matrix inv = new Matrix();
        wall.getImageMatrix().invert(inv);
        float[] corners = {me[0] - it[0], me[1] - it[1], me[0] - it[0] + w, me[1] - it[1] + h};
        inv.mapPoints(corners);
        Rect b = new Rect();
        text.getTextBounds("0", 0, 1, b);
        float baseline = pad() - b.top;
        int gen = ++generation;
        Paint p = new Paint(text);
        WORK.execute(() -> {
            Bitmap out = draw(t, p, w, h, baseline, pic, corners);
            main.post(() -> {
                if (gen != generation || out == null) return;
                Bitmap old = frame;
                frame = out;
                invalidate();
                if (old != null) post(old::recycle);
            });
        });
    }

    @Override
    protected void onDraw(Canvas c) {
        if (frame != null && !frame.isRecycled()) c.drawBitmap(frame, 0, 0, null);
    }

    private static synchronized Bitmap software(Bitmap pic) {
        if (sourceOf != pic || source == null) {
            if (source != null) source.recycle();
            source = pic.getConfig() == Bitmap.Config.HARDWARE || pic.getConfig() != Bitmap.Config.ARGB_8888
                    ? pic.copy(Bitmap.Config.ARGB_8888, false) : pic;
            sourceOf = pic;
        }
        return source;
    }

    private Bitmap draw(String t, Paint p, int w, int h, float baseline, Bitmap pic, float[] map) {
        try {
            // 1. The digits' shape.
            Bitmap maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            new Canvas(maskBmp).drawText(t, pad(), baseline, p);
            int n = w * h;
            int[] px = new int[n];
            maskBmp.getPixels(px, 0, w, 0, 0, w, h);
            maskBmp.recycle();
            int[] mask = new int[n];
            for (int i = 0; i < n; i++) mask[i] = px[i] >>> 24;

            // 2. A rounded height field: the shape blurred twice (about a Gaussian), only inside the strokes.
            int r = Math.max(2, Math.round(2.5f * dp));
            int[] height = boxBlur(boxBlur(mask, w, h, r), w, h, r);

            // 3. The picture behind, at this view's pixels.
            Bitmap src = software(pic);
            if (src == null) return null;
            int sw = src.getWidth(), sh = src.getHeight();
            float sx0 = map[0], sy0 = map[1], kx = (map[2] - map[0]) / w, ky = (map[3] - map[1]) / h;

            float steep = 255f / (4 * r + 2);   // the steepest slope the double blur makes
            float bend = 20 * dp / 2f / steep;  // so the edges bend the picture by up to about 10dp
            float lx = -0.55f, ly = -0.83f;   // light from the top left
            int[] out = new int[n];
            int[] row = new int[sw];
            int cachedRow = -1;
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int i = y * w + x, a = mask[i];
                    if (a == 0) continue;
                    // Slope of the surface; the outward normal is its negative.
                    float gx = (height[i + 1] - height[i - 1]) / 2f, gy = (height[i + w] - height[i - w]) / 2f;
                    float nx = -gx, ny = -gy, m = (float) Math.sqrt(nx * nx + ny * ny);
                    float ox = gx * bend, oy = gy * bend;
                    // Inside the flat middle of a stroke a slight magnification, like a thick lens.
                    float cx = x - w / 2f, cy = y - h / 2f;
                    float fx = x - cx * 0.06f + ox, fy = y - cy * 0.06f + oy;
                    int cr = sample(src, sw, sh, sx0 + (fx + ox * 0.22f) * kx, sy0 + (fy + oy * 0.22f) * ky) >> 16 & 255;
                    int cg = sample(src, sw, sh, sx0 + fx * kx, sy0 + fy * ky) >> 8 & 255;
                    int cb = sample(src, sw, sh, sx0 + (fx - ox * 0.22f) * kx, sy0 + (fy - oy * 0.22f) * ky) & 255;
                    // Glass lifts what it shows a little, and more towards the top.
                    float lift = 0.05f + 0.07f * (1f - (float) y / h);
                    float s = m > 0 ? (nx * lx + ny * ly) / m : 0f;
                    float edge = Math.min(1f, m / steep);
                    // A bright rim on the edges facing the light, a fainter one on the far side (light passing
                    // through and catching the back), and a little shade between them.
                    float facing = (float) Math.pow(Math.max(0f, s), 1.4) * (float) Math.pow(edge, 1.6) * 0.95f;
                    float back = (float) Math.pow(Math.max(0f, -s), 2) * edge * edge * 0.4f;
                    float spec = facing + back + (float) Math.pow(edge, 4) * 0.25f;
                    float shade = (1f - Math.abs(s)) * edge * 0.12f;
                    cr = glass(cr, lift, spec, shade);
                    cg = glass(cg, lift, spec, shade);
                    cb = glass(cb, lift, spec, shade);
                    out[i] = a << 24 | cr << 16 | cg << 8 | cb;
                }
            }
            // Premultiply for drawing.
            for (int i = 0; i < n; i++) {
                int a = out[i] >>> 24;
                if (a == 0 || a == 255) continue;
                int c = out[i];
                out[i] = a << 24 | ((c >> 16 & 255) * a / 255) << 16 | ((c >> 8 & 255) * a / 255) << 8 | (c & 255) * a / 255;
            }
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.setPixels(out, 0, w, 0, 0, w, h);
            return b;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static int glass(int c, float lift, float spec, float shade) {
        float v = c + (255 - c) * lift;
        v = v * (1f - shade) + (255 - v) * Math.min(1f, spec);
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    private static int sample(Bitmap src, int sw, int sh, float x, float y) {
        int ix = Math.max(0, Math.min(sw - 1, Math.round(x)));
        int iy = Math.max(0, Math.min(sh - 1, Math.round(y)));
        return src.getPixel(ix, iy);
    }

    private static int[] boxBlur(int[] in, int w, int h, int r) {
        int[] tmp = new int[in.length], out = new int[in.length];
        int span = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int sum = 0, o = y * w;
            for (int x = -r; x <= r; x++) sum += in[o + Math.max(0, Math.min(w - 1, x))];
            for (int x = 0; x < w; x++) {
                tmp[o + x] = sum / span;
                sum += in[o + Math.min(w - 1, x + r + 1)] - in[o + Math.max(0, x - r)];
            }
        }
        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int y = -r; y <= r; y++) sum += tmp[Math.max(0, Math.min(h - 1, y)) * w + x];
            for (int y = 0; y < h; y++) {
                out[y * w + x] = sum / span;
                sum += tmp[Math.min(h - 1, y + r + 1) * w + x] - tmp[Math.max(0, y - r) * w + x];
            }
        }
        return out;
    }
}
