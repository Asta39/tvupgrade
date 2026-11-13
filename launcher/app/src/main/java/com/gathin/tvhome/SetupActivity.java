package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The welcome steps a new TV sees before Home (this fork): who watches, which apps go on each person's home row,
 * then switching on debugging so the app can finish its own setup (Setup, LocalAdb) and, on a tested model, turn
 * off the preinstalled ad and tracking apps. Every step can be skipped; nothing here needs a computer.
 */
public class SetupActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Setup.Person> people = new ArrayList<>();
    private List<Setup.App> apps = new ArrayList<>();
    private List<String> cleanup = new ArrayList<>();
    private float dp;
    private FrameLayout root;
    private LinearLayout page;
    /**
     * 0 welcome, 1 Home or Guest, 2 people (Home) or the place (Guest), 3.. one app step per person (Guest: the
     * apps guests may open), then debugging, clean-up (when there is a list), done.
     */
    private int step;
    private boolean granted, busy;
    /** Guest mode (an Airbnb or rental TV) instead of a personal Home: chosen on the second step. */
    private boolean guest;
    private String placeName = "", wifiName = "", wifiPassword = "";
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        people.add(new Setup.Person());
        apps = Setup.apps(this);
        cleanup = Setup.cleanup(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B0F);
        ImageView picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setImageBitmap(backdrop());
        root.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackgroundColor(0xB3000000);
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(96), px(56), px(96), px(40));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        show();
    }

    @Override
    public void onBackPressed() {
        if (busy) return;
        if (step > 0) {
            step--;
            show();
        }
        // On the first step Back does nothing: there is no Home behind this yet.
    }

    // ---- steps -----------------------------------------------------------------------------------------

    private int appSteps() {
        return guest ? 1 : people.size();
    }

    private int debugStep() {
        return 3 + appSteps();
    }

    private int cleanupStep() {
        return cleanup.isEmpty() ? -1 : debugStep() + 1;
    }

    private int doneStep() {
        return debugStep() + (cleanup.isEmpty() ? 1 : 2);
    }

    private void show() {
        page.removeAllViews();
        status = null;
        if (step == 0) welcome();
        else if (step == 1) mode();
        else if (step == 2) {
            if (guest) place();
            else who();
        } else if (step < debugStep()) pick(people.get(step - 3));
        else if (step == debugStep()) debugging();
        else if (step == cleanupStep()) cleanUp();
        else done();
    }

    private void next() {
        step++;
        // Clean-up only makes sense once debugging is on.
        if (step == cleanupStep() && !granted) step++;
        show();
    }

    private void welcome() {
        header("Welcome", "A calm home screen for this TV: a big clock, your apps, nothing else.\n"
                + "Setting it up takes about two minutes.");
        space();
        buttons(button("Get started", this::next, true));
    }

    private void mode() {
        header("How will this TV be used?", "You can change this later in Control Center.");
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        View home = choice("Home", "Your own TV: the clock, your apps, profiles for the people who live here.", () -> {
            guest = false;
            next();
        });
        View rental = choice("Guest", "For an Airbnb or rental: welcomes guests by name, shows the Wi-Fi, house rules and "
                + "check-out, and only the apps you allow.", () -> {
            guest = true;
            while (people.size() > 1) people.remove(people.size() - 1);
            next();
        });
        row.addView(home);
        row.addView(rental);
        page.addView(row);
        space();
        (guest ? rental : home).requestFocus();
    }

    /** A large selectable card for the mode step. */
    private View choice(String name, String about, Runnable pick) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(26), px(22), px(26), px(22));
        c.setBackground(pill(0x26FFFFFF, 22));
        c.setFocusable(true);
        c.setClickable(true);
        TextView n = text(name, 26, Color.WHITE, R.string.font_medium);
        TextView a = text(about, 16, 0xCCFFFFFF, R.string.font_regular);
        a.setLineSpacing(px(3), 1f);
        c.addView(n);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = px(8);
        c.addView(a, al);
        c.setOnFocusChangeListener((v, f) -> {
            v.setBackground(pill(f ? Color.WHITE : 0x26FFFFFF, 22));
            n.setTextColor(f ? Color.BLACK : Color.WHITE);
            a.setTextColor(f ? 0xCC000000 : 0xCCFFFFFF);
            v.animate().scaleX(f ? 1.03f : 1f).scaleY(f ? 1.03f : 1f).setDuration(140).start();
        });
        c.setOnClickListener(v -> pick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(380), -2);
        lp.rightMargin = px(20);
        c.setLayoutParams(lp);
        return c;
    }

    /** Guest mode: the place's name and the Wi-Fi guests join. Android keeps saved Wi-Fi passwords from apps. */
    private void place() {
        header("Your place", "Guests see this on the welcome screen. House rules, door codes and stays come next, "
                + "from your phone.");
        if (wifiName.isEmpty()) wifiName = currentWifi();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(field("Name of your place (e.g. The Ridge)", placeName, false, v -> placeName = v));
        list.addView(field("Wi-Fi name", wifiName, false, v -> wifiName = v));
        list.addView(field("Wi-Fi password", wifiPassword, false, v -> wifiPassword = v));
        page.addView(list);
        space();
        buttons(button("Continue", this::next, true));
    }

    private View field(String hint, String value, boolean secret, java.util.function.Consumer<String> save) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_CAP_WORDS));
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0x80FFFFFF);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        e.setTypeface(font(R.string.font_regular));
        e.setPadding(px(20), px(12), px(20), px(12));
        e.setBackground(pill(0x26FFFFFF, 14));
        e.setOnFocusChangeListener((v, f) -> v.setBackground(pill(f ? 0x59FFFFFF : 0x26FFFFFF, 14)));
        e.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                save.accept(s.toString());
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(460), -2);
        lp.bottomMargin = px(12);
        e.setLayoutParams(lp);
        return e;
    }

    /** The Wi-Fi this TV is on, when Android will say (it needs the location grant); empty otherwise. */
    private String currentWifi() {
        try {
            android.net.wifi.WifiManager wm = getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
            String ssid = wm.getConnectionInfo().getSSID();
            if (ssid == null || ssid.contains("unknown ssid")) return "";
            return ssid.replace("\"", "");
        } catch (Exception e) {
            return "";
        }
    }

    private void who() {
        header("Who watches this TV?", "Each person gets their own home row of apps. One person is fine.");
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < people.size(); i++) {
            Setup.Person p = people.get(i);
            EditText name = new EditText(this);
            name.setHint(people.size() == 1 ? "Your name (optional)" : "Name " + (i + 1));
            name.setText(p.name);
            name.setSingleLine(true);
            name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            name.setImeOptions(EditorInfo.IME_ACTION_DONE);
            name.setTextColor(Color.WHITE);
            name.setHintTextColor(0x80FFFFFF);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            name.setTypeface(font(R.string.font_regular));
            name.setPadding(px(20), px(12), px(20), px(12));
            name.setBackground(pill(0x26FFFFFF, 14));
            name.setOnFocusChangeListener((v, f) -> v.setBackground(pill(f ? 0x59FFFFFF : 0x26FFFFFF, 14)));
            name.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(android.text.Editable s) {
                    p.name = s.toString();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(420), -2);
            lp.bottomMargin = px(12);
            list.addView(name, lp);
        }
        page.addView(list);
        space();
        TextView more = people.size() < Setup.MAX_PEOPLE ? button("Add another person", () -> {
            people.add(new Setup.Person());
            show();
        }, false) : null;
        TextView fewer = people.size() > 1 ? button("Remove last", () -> {
            people.remove(people.size() - 1);
            show();
        }, false) : null;
        buttons(button("Continue", this::next, true), more, fewer);
    }

    private void pick(Setup.Person p) {
        String whose = people.size() == 1 ? "your" : (p.name.trim().isEmpty() ? "this person's" : p.name.trim() + "'s");
        if (guest) header("Apps guests can open", "Up to " + Setup.MAX_APPS + ", in order. Guests see only these on the "
                + "welcome screen.");
        else header("Pick " + whose + " main apps", "Up to " + Setup.MAX_APPS + ", in the order you want them on the home row. "
                + "Every other app stays one press below.");
        // Continue sits above the grid, one press Up from the first row, not below every app on the TV.
        TextView count = text("", 16, 0xCCFFFFFF, R.string.font_medium);
        TextView go = button("Continue", () -> {
            if (!p.apps.isEmpty()) next();
        }, false);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setClipChildren(false);
        top.addView(go);
        count.setPadding(px(18), 0, 0, 0);
        top.addView(count);
        page.addView(top);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(7);
        List<View> tiles = new ArrayList<>();
        Runnable refresh = () -> {
            count.setText(p.apps.size() + " of " + Setup.MAX_APPS + " picked");
            for (View t : tiles) {
                int at = p.apps.indexOf((String) t.getTag());
                TextView badge = t.findViewWithTag("badge");
                badge.setVisibility(at >= 0 ? View.VISIBLE : View.INVISIBLE);
                badge.setText(String.valueOf(at + 1));
                t.setAlpha(at >= 0 || p.apps.size() < Setup.MAX_APPS ? 1f : 0.45f);
            }
        };
        for (Setup.App app : apps) {
            View tile = tile(app, () -> {
                if (p.apps.remove(app.pkg)) return;
                if (p.apps.size() < Setup.MAX_APPS) p.apps.add(app.pkg);
            }, refresh);
            tiles.add(tile);
            grid.addView(tile);
        }
        refresh.run();
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, px(10), 0, px(10));
        scroll.addView(grid);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = px(8);
        page.addView(scroll, lp);
        if (!tiles.isEmpty()) tiles.get(0).requestFocus();
    }

    private void debugging() {
        header("Let Home finish its own setup", "Switching on debugging lets this app give itself what it needs: the Home "
                + "button, Now Playing, Sleep, Night Shift and the StandBy screensaver. No computer needed.");
        page.addView(text("1.  Open Settings, then System, then About.\n"
                + "2.  Press OK on \"Android TV OS build\" seven times, until it says you are a developer.\n"
                + "3.  Go back to System, open Developer options, and turn on USB debugging\n"
                + "     (and Network debugging, if it is listed).\n"
                + "4.  Come back here and choose Check. When the TV asks, tick \"Always allow\", then choose Allow.",
                17, 0xE6FFFFFF, R.string.font_regular));
        status = text(granted ? "Done. Everything is switched on." : "", 17, 0xFFFFD27A, R.string.font_medium);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = px(18);
        page.addView(status, lp);
        space();
        buttons(button(granted ? "Continue" : "Check", () -> {
            if (granted) next();
            else check();
        }, true), button("Open Settings", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception e) {
                say("Settings could not be opened from here. Open it with the remote.");
            }
        }, false), button("Skip for now", this::next, false));
    }

    private void check() {
        if (busy) return;
        busy = true;
        say("Checking…");
        new Thread(() -> {
            String result;
            boolean ok = false;
            if (!LocalAdb.portOpen()) {
                result = "Debugging is still off. Follow the steps above, then choose Check again.";
            } else {
                say("Look at the TV: tick \"Always allow from this computer\", then choose Allow.");
                try (LocalAdb adb = LocalAdb.connect(this, 90_000)) {
                    List<String> failed = Setup.grant(this, adb);
                    ok = true;
                    result = failed.isEmpty() ? "Done. Everything is switched on."
                            : "Done, except: " + String.join(", ", failed) + ". Home still works without them.";
                } catch (Exception e) {
                    result = "The TV did not allow it. Choose Check and accept the question on screen.";
                }
            }
            boolean done = ok;
            String message = result;
            main.post(() -> {
                busy = false;
                granted = done;
                if (done) show();
                say(message);
            });
        }, "setup-adb").start();
    }

    private void cleanUp() {
        header("Turn off the clutter?", "This TV model has a tested list of " + cleanup.size() + " preinstalled ad, tracking "
                + "and promo apps. They are only turned off, never removed, and each can be turned back on in Settings.");
        status = text("", 17, 0xFFFFD27A, R.string.font_medium);
        page.addView(status);
        space();
        buttons(button("Turn them off", () -> {
            if (busy) return;
            busy = true;
            say("Turning them off…");
            new Thread(() -> {
                String message;
                try (LocalAdb adb = LocalAdb.connect(this, 30_000)) {
                    int n = Setup.disable(adb, cleanup);
                    message = n + " apps turned off.";
                } catch (Exception e) {
                    message = "That did not work. You can skip this step.";
                }
                String m = message;
                main.post(() -> {
                    busy = false;
                    say(m);
                    main.postDelayed(this::next, 1200);
                });
            }, "setup-cleanup").start();
        }, true), button("Keep everything", this::next, false));
    }

    private void done() {
        header("All set", guest
                ? "The welcome screen is ready. Next, open the host page on your phone to add house rules, door codes "
                + "and your guests' stays. Press Menu on the welcome screen to switch back to Home mode."
                : granted
                ? "Press the Home button any time to come back here. The gear at the top right opens Control Center."
                : "Home is ready. Without debugging, the Home button may still open the old home screen: open this "
                + "app from your apps list instead.");
        status = text("", 17, 0xFFFFD27A, R.string.font_medium);
        page.addView(status);
        space();
        buttons(button("Go to Home", () -> {
            if (busy) return;
            busy = true;
            say("Getting your wallpapers ready…");
            new Thread(() -> {
                try {
                    Setup.write(this, people);
                    if (guest) {
                        org.json.JSONObject j = Stay.json(this);
                        j.put("mode", "guest").put("place", placeName.trim()).put("wifi_name", wifiName.trim())
                                .put("wifi_password", wifiPassword.trim())
                                .put("apps", new org.json.JSONArray(people.get(0).apps));
                        Stay.write(this, j);
                    }
                    main.post(() -> {
                        startActivity(new Intent(this, MainActivity.class).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                        finish();
                    });
                } catch (Exception e) {
                    main.post(() -> {
                        busy = false;
                        say("The theme could not be saved: " + e.getMessage());
                    });
                }
            }, "setup-write").start();
        }, true));
    }

    // ---- pieces ----------------------------------------------------------------------------------------

    private void header(String title, String body) {
        int total = doneStep() + 1;
        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < total; i++) {
            View dot = new View(this);
            dot.setBackground(pill(i == step ? Color.WHITE : (i < step ? 0x99FFFFFF : 0x40FFFFFF), 4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(i == step ? 28 : 8), px(8));
            lp.rightMargin = px(8);
            dots.addView(dot, lp);
        }
        TextView counter = text("Step " + (step + 1) + " of " + total, 14, 0xB3FFFFFF, R.string.font_medium);
        counter.setLetterSpacing(0.06f);
        counter.setPadding(px(8), 0, 0, 0);
        dots.addView(counter);
        page.addView(dots);
        TextView t = text(title, 44, Color.WHITE, R.string.font_medium);
        t.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = px(22);
        page.addView(t, tlp);
        TextView b = text(body, 19, 0xCCFFFFFF, R.string.font_regular);
        b.setLineSpacing(px(4), 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(720), -2);
        blp.topMargin = px(10);
        blp.bottomMargin = px(22);
        page.addView(b, blp);
    }

    private void space() {
        page.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
    }

    private void buttons(TextView... all) {
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        TextView first = null;
        for (TextView b : all) {
            if (b == null) continue;
            if (first == null) first = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = px(14);
            row.addView(b, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = px(14);
        page.addView(row, lp);
        if (first != null && first.getTag() != null) first.requestFocus();
    }

    /** A pill button: glass at rest, white when selected. Tagged when it should take focus first. */
    private TextView button(String label, Runnable action, boolean focusFirst) {
        TextView b = text(label, 18, Color.WHITE, R.string.font_medium);
        b.setPadding(px(28), px(13), px(28), px(13));
        b.setBackground(pill(0x33FFFFFF, 26));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClickable(true);
        if (focusFirst) b.setTag("first");
        b.setOnFocusChangeListener((v, f) -> {
            v.setBackground(pill(f ? Color.WHITE : 0x33FFFFFF, 26));
            ((TextView) v).setTextColor(f ? Color.BLACK : Color.WHITE);
            v.animate().scaleX(f ? 1.05f : 1f).scaleY(f ? 1.05f : 1f).setDuration(120).start();
        });
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private View tile(Setup.App app, Runnable toggle, Runnable refresh) {
        FrameLayout box = new FrameLayout(this);
        box.setTag(app.pkg);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setClickable(true);
        box.setPadding(px(8), px(8), px(8), px(8));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        col.addView(icon, new LinearLayout.LayoutParams(px(64), px(64)));
        TextView name = text(app.label, 12, 0xE6FFFFFF, R.string.font_regular);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(px(92), -2);
        nlp.topMargin = px(6);
        col.addView(name, nlp);
        box.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        TextView badge = text("", 12, Color.BLACK, R.string.font_medium);
        badge.setTag("badge");
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(pill(Color.WHITE, 11));
        box.addView(badge, new FrameLayout.LayoutParams(px(22), px(22), Gravity.TOP | Gravity.END));
        box.setOnFocusChangeListener((v, f) -> {
            v.setBackground(f ? pill(0x40FFFFFF, 16) : null);
            v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(120).start();
        });
        box.setOnClickListener(v -> {
            toggle.run();
            refresh.run();
        });
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = px(108);
        lp.height = px(112);
        lp.setMargins(0, 0, px(6), px(6));
        box.setLayoutParams(lp);
        return box;
    }

    private void say(String message) {
        main.post(() -> {
            if (status != null) status.setText(message);
        });
    }

    private TextView text(String s, int sp, int color, int fontString) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        Typeface face = font(fontString);
        if (face != null) t.setTypeface(face);
        return t;
    }

    private Typeface font(int fontString) {
        try {
            return UiFont.get(this, fontString);
        } catch (Exception e) {
            return null;
        }
    }

    private GradientDrawable pill(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(radiusDp));
        return d;
    }

    private int px(int dps) {
        return Math.round(dps * dp);
    }

    /** One of the bundled wallpapers, decoded small, behind the steps. */
    private Bitmap backdrop() {
        try (InputStream in = getAssets().open("wallpapers/03-golden-peak.jpg")) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        }
    }
}
