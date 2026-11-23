package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import org.json.JSONObject;

import java.util.Random;

/**
 * The screensaver's weather (this fork): what it's doing outside, drawn over StandBy. Rain falls when it's raining
 * where the TV is, snow when it snows, slow fog banks on foggy days, and a rare flash in a thunderstorm. Clear and
 * cloudy skies draw nothing. The reading is Weather's cached Open-Meteo answer, so it costs no extra request.
 * Kept light for low-memory TVs: a few hundred short lines at most, about 30 frames a second.
 */
final class WeatherFx extends View {
    static final int NONE = 0, RAIN = 1, SNOW = 2, FOG = 3, STORM = 4;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG),
            fog = new Paint(Paint.ANTI_ALIAS_FLAG), flash = new Paint();
    private final Random random = new Random();
    private final int kind;
    private float[] x, y, speed, size;
    private long last, nextFlash, flashUntil;

    WeatherFx(Context ctx) {
        super(ctx);
        kind = current(ctx);
        line.setColor(0x66BFD8FF);
        line.setStrokeCap(Paint.Cap.ROUND);
        dot.setColor(0xCCFFFFFF);
        flash.setColor(Color.WHITE);
        setWillNotDraw(kind == NONE);
    }

    /** The cached weather code, as one of the kinds above. */
    static int current(Context ctx) {
        try {
            String json = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE).getString("json", null);
            if (json == null) return NONE;
            long at = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE).getLong("at", 0);
            // An answer more than three hours old says nothing about now.
            if (System.currentTimeMillis() - at > 3 * 60 * 60 * 1000L) return NONE;
            return kindOf(new JSONObject(json).optJSONObject("current").optInt("weather_code"));
        } catch (Exception e) {
            return NONE;
        }
    }

    static int kindOf(int code) {
        if (code >= 95) return STORM;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return SNOW;
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return RAIN;
        if (code == 45 || code == 48) return FOG;
        return NONE;
    }

    int kind() {
        return kind;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        int n = kind == RAIN || kind == STORM ? 160 : kind == SNOW ? 120 : kind == FOG ? 5 : 0;
        x = new float[n];
        y = new float[n];
        speed = new float[n];
        size = new float[n];
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < n; i++) {
            x[i] = random.nextFloat() * w;
            y[i] = random.nextFloat() * h;
            if (kind == SNOW) {
                speed[i] = (20 + random.nextFloat() * 40) * dp;
                size[i] = (1.2f + random.nextFloat() * 2.4f) * dp;
            } else if (kind == FOG) {
                speed[i] = (6 + random.nextFloat() * 10) * dp;
                size[i] = (260 + random.nextFloat() * 260) * dp;
            } else {
                speed[i] = (700 + random.nextFloat() * 500) * dp;
                size[i] = (14 + random.nextFloat() * 18) * dp;
            }
        }
        line.setStrokeWidth(1.4f * dp);
        last = 0;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (kind == NONE || x == null) return;
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0 : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        int w = getWidth(), h = getHeight();
        for (int i = 0; i < x.length; i++) {
            if (kind == FOG) {
                x[i] += speed[i] * dt;
                if (x[i] - size[i] > w) x[i] = -size[i];
                fog.setShader(new RadialGradient(x[i], y[i], size[i], 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawCircle(x[i], y[i], size[i], fog);
            } else if (kind == SNOW) {
                y[i] += speed[i] * dt;
                x[i] += (float) Math.sin((now / 1000.0) + i) * 12 * dt;
                if (y[i] > h) {
                    y[i] = -size[i];
                    x[i] = random.nextFloat() * w;
                }
                c.drawCircle(x[i], y[i], size[i], dot);
            } else {
                // Rain, slanted a little, as if there's a breeze.
                float dy = speed[i] * dt;
                y[i] += dy;
                x[i] += dy * 0.12f;
                if (y[i] > h) {
                    y[i] = -size[i];
                    x[i] = random.nextFloat() * w * 1.1f - w * 0.1f;
                }
                c.drawLine(x[i], y[i], x[i] - size[i] * 0.12f, y[i] - size[i], line);
            }
        }
        if (kind == STORM) {
            if (nextFlash == 0) nextFlash = now + 8000 + random.nextInt(20000);
            if (now >= nextFlash) {
                flashUntil = now + 120;
                nextFlash = now + 10000 + random.nextInt(25000);
            }
            if (now < flashUntil) {
                flash.setAlpha(46);
                c.drawRect(0, 0, w, h, flash);
            }
        }
        // About 30 frames a second is plenty for weather.
        postInvalidateDelayed(33);
    }
}
