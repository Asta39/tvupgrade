package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.text.Layout;
import android.text.TextPaint;
import android.widget.TextView;

/**
 * One lyric line for StandBy (this fork), lit like Apple Music: the sung part in the cover's colour sweeping
 * left to right, line by line when it wraps, with a soft leading edge. The sweep is drawn by clipping the text a
 * second time, so moving it every frame never re-lays out the text: it glides instead of stepping word by word.
 */
final class LyricLine extends TextView {
    private float progress = -1f;   // -1 = not the current line: drawn plainly
    private int lit = 0xFFFFFFFF, unlit = 0x80FFFFFF;
    private final float edge;

    LyricLine(Context ctx) {
        super(ctx);
        edge = 28 * ctx.getResources().getDisplayMetrics().density;
    }

    /** 0..1 through the line, or -1 to draw it as an ordinary (past or coming) line. */
    void setProgress(float p) {
        if (p == progress) return;
        // Sung lines are drawn over an unsung base; ordinary lines are plain white.
        if (p >= 0 && progress < 0) setTextColor(unlit);
        else if (p < 0 && progress >= 0) setTextColor(0xFFFFFFFF);
        progress = p;
        invalidate();
    }

    void setColors(int litColor, int unlitColor) {
        lit = litColor;
        unlit = unlitColor;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        Layout layout = getLayout();
        if (progress < 0 || layout == null) {
            super.onDraw(c);
            return;
        }
        TextPaint paint = getPaint();
        int left = getCompoundPaddingLeft(), top = getExtendedPaddingTop();
        // Unsung text first, then the sung part on top of it, clipped to how far the sweep has come.
        super.onDraw(c);
        float total = 0;
        for (int i = 0; i < layout.getLineCount(); i++) total += layout.getLineWidth(i);
        float reach = progress * (total + edge);
        paint.setColor(lit);
        for (int i = 0; i < layout.getLineCount() && reach > 0; i++) {
            float lineLeft = layout.getLineLeft(i), w = layout.getLineWidth(i);
            float x = lineLeft + Math.min(reach, w + edge);
            paint.setShader(new LinearGradient(left + x - edge, 0, left + x, 0, lit, lit & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.save();
            c.clipRect(left + lineLeft, top + layout.getLineTop(i), left + x, top + layout.getLineBottom(i));
            c.translate(left, top);
            layout.draw(c);
            c.restore();
            reach -= w;
        }
        paint.setShader(null);
    }
}
