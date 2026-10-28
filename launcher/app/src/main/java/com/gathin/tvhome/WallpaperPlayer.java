package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final int backgroundColor;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;
    /** wallpaper_moods: the collection's groups by time of day; empty rotates through all of it. */
    private final Map<String, Set<String>> moods;
    /** config.xml latitude and longitude for the sun's times, and today's {sunrise, sunset} once worked out. */
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        backgroundColor = config.backgroundColor;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        latitude = moods.isEmpty() ? "" : context.getString(R.string.latitude);
        longitude = moods.isEmpty() ? "" : context.getString(R.string.longitude);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            playable = playable();
            first = WallpaperRotation.pick(playable, previous, order, random);
        }
    }

    /**
     * The next (+1) or previous (-1) picture of the collection, in name order, at once (this fork: the remote's
     * CH+/CH- on Home). The hourly rotation carries on from the picture chosen here.
     */
    void step(int direction) {
        if (closed || loading || files.size() < 2) return;
        List<File> sorted = new ArrayList<>(files);
        Collections.sort(sorted, (a, b) -> a.getName().compareTo(b.getName()));
        int at = 0;
        for (int i = 0; i < sorted.size(); i++) if (sorted.get(i).getName().equals(displayed)) at = i;
        File target = sorted.get(Math.floorMod(at + direction, sorted.size()));
        preferences.edit().putString("image", target.getName()).putLong("changed", System.currentTimeMillis()).commit();
        boolean was = active;
        active = true;
        update();
        active = was;
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    /** Drops the wallpaper texture when Home is hidden and Android asks the process to free memory. */
    void releaseForMemoryPressure() {
        if (current == null) return;
        image.setImageDrawable(null);
        // The renderer may still have a queued frame that references this hardware bitmap. Drop our references and
        // let Android reclaim its native buffer after rendering releases it instead of recycling it mid-frame.
        current = null;
        displayed = "";
        root.setBackgroundColor(backgroundColor);
    }

    /** The pictures the rotation picks from now: wallpaper_moods' group for the time of day, or the whole collection. */
    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalDate().equals(sunDay)) {
            sunDay = now.toLocalDate();
            sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
        }
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
        List<File> playable = playable();
        File selected = WallpaperRotation.find(playable, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(playable, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(bitmap);
                displayed = next.getName();
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** Puts a picture on screen and releases the previous one after a frame has drawn the new one. */
    private void show(Bitmap bitmap) {
        Bitmap old = current;
        current = bitmap;
        // The wallpaper covers the whole root; avoid drawing the fallback colour under it.
        root.setBackground(null);
        if (old != null && old != bitmap && !old.isRecycled()) {
            // A 450 ms cross-fade from the picture on screen, instead of a cut.
            android.graphics.drawable.TransitionDrawable fade = new android.graphics.drawable.TransitionDrawable(
                    new android.graphics.drawable.Drawable[]{
                            new android.graphics.drawable.BitmapDrawable(image.getResources(), old),
                            new android.graphics.drawable.BitmapDrawable(image.getResources(), bitmap)});
            fade.setCrossFadeEnabled(true);
            image.setImageDrawable(fade);
            fade.startTransition(450);
            image.postDelayed(() -> {
                if (current == bitmap) image.setImageBitmap(bitmap);
                old.recycle();
            }, 1000);
        } else {
            image.setImageBitmap(bitmap);
        }
        shownBitmap = bitmap;
        shownView = image;
        if (onClock != null) image.post(onClock);
        if (onGlass != null) image.post(onGlass);
        Bitmap oldDepth = depthShown;
        depthShown = nextDepth;
        if (onDepth != null) onDepth.accept(depthShown);
        if (oldDepth != null && oldDepth != depthShown) image.postDelayed(oldDepth::recycle, 1000);
    }

    /**
     * A heavily softened, screen-proportioned copy of the wallpaper on screen, for the dock's frosted glass
     * (DockDecoration). Made once per picture by scaling down twice with filtering: no live blur.
     */
    static volatile Bitmap glass;
    /** The picture on screen and its view, for the hero clock's glass digits (GlassClock); onClock after a change. */
    static volatile Bitmap shownBitmap;
    static android.widget.ImageView shownView;
    static Runnable onClock;
    /** Called on the UI thread after a new picture (and its glass) is on screen. */
    static Runnable onGlass;
    /**
     * Depth effect (this fork): a cut-out of the picture's foreground, depth/<picture name>.png beside the
     * wallpapers/ folder, drawn over the hero clock so the mountain stands in front of the time. Null when the
     * picture has none. Called with it on the UI thread after each picture change.
     */
    static java.util.function.Consumer<Bitmap> onDepth;
    private volatile Bitmap nextDepth;
    static Bitmap depthShown;

    static Bitmap softened(Bitmap src) {
        try {
            Bitmap tiny = Bitmap.createScaledBitmap(src, Math.max(1, src.getWidth() / 48), Math.max(1, src.getHeight() / 48), true);
            Bitmap soft = Bitmap.createScaledBitmap(tiny, Math.max(1, src.getWidth() / 12), Math.max(1, src.getHeight() / 12), true);
            if (soft != tiny) tiny.recycle();
            return soft;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** The cut-out for this picture, decoded at the same size and scale so it lines up pixel for pixel. */
    private Bitmap decodeDepth(File picture, int sample, int[] scale) {
        File dir = picture.getParentFile() != null ? picture.getParentFile().getParentFile() : null;
        if (dir == null) return null;
        String name = picture.getName();
        int dot = name.lastIndexOf('.');
        File cut = new File(new File(dir, "depth"), (dot > 0 ? name.substring(0, dot) : name) + ".png");
        if (!cut.isFile()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(cut.getPath(), o);
            if (o.outWidth <= 0) return null;
            // The cut-out may be smaller than the picture (1920 wide for a 3840 one): fit it to the screen instead.
            int s = WallpaperSize.sampleSize(o.outWidth, o.outHeight, width, height);
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            int[] sc = WallpaperSize.coverScale(o.outWidth / s, o.outHeight / s, width, height);
            if (sc != null) {
                d.inScaled = true;
                d.inDensity = sc[0];
                d.inTargetDensity = sc[1];
            }
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            d.inMutable = dimColor != 0;
            Bitmap b = BitmapFactory.decodeFile(cut.getPath(), d);
            if (b == null) return null;
            if (dimColor != 0) new Canvas(b).drawColor(dimColor, android.graphics.PorterDuff.Mode.SRC_ATOP);
            Bitmap gpu = b.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return b;
            b.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            glass = softened(decoded);
            nextDepth = decodeDepth(file, sample, scale);
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
