package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.widget.FrameLayout;

/**
 * A frosted material for this fork's Now Playing card and Control Center: the wallpaper behind the view, taken from
 * WallpaperPlayer's pre-softened copy (no live blur), under a dark tint that keeps white text legible over any photo
 * (HIG: a heavier material over imagery), a faint white veil and a lit top rim. Falls back to a plain dark slab
 * before the first wallpaper is decoded.
 */
class FrostedLayout extends FrameLayout {
    private final float dp;
    private final RectF rect = new RectF();
    private final int[] loc = new int[2];
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private Bitmap source;
    private BitmapShader shader;
    private float radius;

    FrostedLayout(Context context, float radiusDp, int tintArgb) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        radius = radiusDp * dp;
        tint.setColor(tintArgb);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, dp));
        setWillNotDraw(false);
        setClipToPadding(false);
    }

    /** The tint over the picture, e.g. lighter while focused. */
    void setTint(int argb) {
        tint.setColor(argb);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        veil.setShader(new LinearGradient(0, 0, 0, h, 0x1FFFFFFF, 0x08FFFFFF, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(0, 0, 0, h, 0x59FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        rect.set(0, 0, getWidth(), getHeight());
        Bitmap soft = WallpaperPlayer.glass;
        if (soft != null && !soft.isRecycled()) {
            if (soft != source) {
                source = soft;
                shader = new BitmapShader(soft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                glass.setShader(shader);
            }
            getLocationOnScreen(loc);
            float sx = getRootView().getWidth() / (float) soft.getWidth();
            float sy = getRootView().getHeight() / (float) soft.getHeight();
            matrix.setScale(sx, sy);
            // getLocationOnScreen includes this view's own translation; the picture must stay put while it slides.
            matrix.postTranslate(-loc[0], -loc[1]);
            shader.setLocalMatrix(matrix);
            c.drawRoundRect(rect, radius, radius, glass);
        }
        c.drawRoundRect(rect, radius, radius, tint);
        c.drawRoundRect(rect, radius, radius, veil);
        rect.inset(rim.getStrokeWidth() / 2, rim.getStrokeWidth() / 2);
        c.drawRoundRect(rect, radius, radius, rim);
    }
}
