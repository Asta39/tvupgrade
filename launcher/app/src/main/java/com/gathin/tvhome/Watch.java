package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * "Put on a show" from the phone page (this fork): where a show streams, from JustWatch's public GraphQL (no key),
 * and opening it in the app on the TV with JustWatch's Android TV link. The approach follows couch-tv's Finder
 * (github.com/Seagey/couch-tv, on-tv/.../Finder.kt and bridge/DEEPLINKS.md, MIT License, Copyright (c) 2026 Seagey).
 * <p>
 * JustWatch lists titles for every country but streaming offers only for some (none for Kenya), so the offers come
 * from the first country in a short chain that has any. A phone can only start links this class handed out in a
 * search (each gets a random id), never a link of its own.
 */
final class Watch {
    private static final String GQL = "https://apis.justwatch.com/graphql";
    private static final String OFFER = "monetizationType deeplinkURL(platform: ANDROID_TV) standardWebURL package { clearName technicalName }";
    private static final String SEARCH = "query($country: Country!, $language: Language!, $first: Int!, $filter: TitleFilter) {"
            + " popularTitles(country: $country, first: $first, filter: $filter) { edges { node { id objectType"
            + " content(country: $country, language: $language) { title originalReleaseYear posterUrl shortDescription }"
            + " offers(country: $country, platform: ANDROID_TV) { " + OFFER + " } } } } }";
    /** JustWatch's service names to the TV app that plays them, for "is it installed?". */
    private static final Map<String, String> APPS = new LinkedHashMap<>();
    /** Apps that can search by title when no link opens the show itself. */
    private static final Map<String, String> SEARCH_LINKS = new LinkedHashMap<>();

    static {
        APPS.put("netflix", "com.netflix.ninja");
        APPS.put("disneyplus", "com.disney.disneyplus");
        APPS.put("amazonprime", "com.amazon.amazonvideo.livingroom");
        APPS.put("amazonprimevideo", "com.amazon.amazonvideo.livingroom");
        APPS.put("primevideo", "com.amazon.amazonvideo.livingroom");
        APPS.put("youtube", "com.google.android.youtube.tv");
        APPS.put("youtubefree", "com.google.android.youtube.tv");
        APPS.put("appletvplus", "com.apple.atve.androidtv.appletv");
        APPS.put("itunes", "com.apple.atve.androidtv.appletv");
        APPS.put("showmax", "com.showmax.app");
        APPS.put("hbomax", "com.wbd.stream");
        APPS.put("max", "com.wbd.stream");
        APPS.put("crunchyroll", "com.crunchyroll.crunchyroid");
        APPS.put("spotify", "com.spotify.tv.android");
        SEARCH_LINKS.put("com.netflix.ninja", "https://www.netflix.com/search?q=");
        SEARCH_LINKS.put("com.google.android.youtube.tv", "https://www.youtube.com/results?search_query=");
    }

    /** TV app to JustWatch's short code, so the swipe deck only offers what this TV can play. */
    private static final Map<String, String> SHORT = new LinkedHashMap<>();

    static {
        SHORT.put("com.netflix.ninja", "nfx");
        SHORT.put("com.amazon.amazonvideo.livingroom", "amp");
        SHORT.put("com.disney.disneyplus", "dnp");
        SHORT.put("com.crunchyroll.crunchyroid", "cru");
        SHORT.put("com.apple.atve.androidtv.appletv", "atp");
        SHORT.put("com.google.android.youtube.tv", "yot");
        SHORT.put("com.showmax.app", "sma");
        SHORT.put("com.wbd.stream", "mxx");
    }

    /** JustWatch genres, as the phone shows them. */
    static final String[][] GENRES = {{"act", "Action"}, {"ani", "Animation"}, {"cmy", "Comedy"}, {"crm", "Crime"},
            {"doc", "Documentary"}, {"drm", "Drama"}, {"fnt", "Fantasy"}, {"hst", "History"}, {"hrr", "Horror"},
            {"fml", "Family"}, {"msc", "Music"}, {"trl", "Thriller"}, {"rma", "Romance"}, {"scf", "Sci-fi"},
            {"spt", "Sport"}, {"war", "War"}, {"rly", "Reality"}};

    /** The catalogue the swipe deck reads (the TV's country, or the nearest with streaming data), found once. */
    private static volatile String deckCountry;

    /** Links handed out by search, by their random id, so a phone can only start what a search found. */
    private static final Map<String, String[]> LINKS = Collections.synchronizedMap(new LinkedHashMap<String, String[]>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String[]> e) {
            return size() > 400;
        }
    });

    private Watch() {
    }

    /** Titles for a query ("put on Bluey" works too), each with where it streams and whether that app is here. */
    static JSONObject search(Context ctx, String query) throws Exception {
        String q = query.trim().replaceAll("(?i)^(please\\s+)?(put on|play|watch|open|find)\\s+", "").replaceAll("[.?!]+$", "").trim();
        if (q.isEmpty() || q.length() > 80) return new JSONObject().put("error", "Type the name of a show or film.");
        String home = country(ctx);
        JSONArray titles = searchIn(home, q);
        // No offers where the TV is: borrow them from the nearest catalogue that has some.
        boolean anyOffers = false;
        for (int i = 0; i < titles.length(); i++) if (titles.getJSONObject(i).getJSONArray("offers").length() > 0) anyOffers = true;
        String from = home;
        if (!anyOffers) {
            for (String c : new String[]{"ZA", "GB", "US"}) {
                if (c.equals(home)) continue;
                JSONArray alt = searchIn(c, q);
                boolean has = false;
                for (int i = 0; i < alt.length(); i++) if (alt.getJSONObject(i).getJSONArray("offers").length() > 0) has = true;
                if (has) {
                    titles = alt;
                    from = c;
                    break;
                }
            }
        }
        JSONArray out = new JSONArray();
        PackageManager pm = ctx.getPackageManager();
        for (int i = 0; i < titles.length() && out.length() < 6; i++) {
            JSONObject t = titles.getJSONObject(i);
            JSONArray where = new JSONArray();
            Map<String, JSONObject> best = new LinkedHashMap<>();
            JSONArray offers = t.getJSONArray("offers");
            for (int k = 0; k < offers.length(); k++) {
                JSONObject o = offers.getJSONObject(k);
                String tech = o.getJSONObject("package").optString("technicalName");
                String kind = o.optString("monetizationType");
                String pkg = APPS.get(tech);
                String link = o.optString("deeplinkURL", "");
                if ("null".equals(link)) link = "";
                JSONObject cur = best.get(tech);
                // Free and subscription beat rent and buy.
                if (cur != null && rank(cur.optString("kind")) <= rank(kind)) continue;
                best.put(tech, new JSONObject().put("name", o.getJSONObject("package").optString("clearName"))
                        .put("kind", kind).put("pkg", pkg == null ? "" : pkg).put("link", link));
            }
            for (JSONObject b : best.values()) {
                String pkg = b.optString("pkg");
                boolean here = !pkg.isEmpty() && installed(pm, pkg);
                String link = b.optString("link");
                JSONObject w = new JSONObject().put("name", b.optString("name")).put("kind", kind(b.optString("kind"))).put("installed", here);
                if (here && !link.isEmpty()) w.put("id", remember(pkg, link));
                where.put(w);
            }
            // Not on any app here with a link: offer a search inside the apps that have one.
            for (Map.Entry<String, String> s : SEARCH_LINKS.entrySet()) {
                if (!installed(pm, s.getKey())) continue;
                where.put(new JSONObject().put("name", label(pm, s.getKey())).put("kind", "search").put("installed", true)
                        .put("id", remember(s.getKey(), s.getValue() + Uri.encode(t.optString("title")))));
            }
            out.put(new JSONObject().put("title", t.optString("title")).put("year", t.optInt("year"))
                    .put("type", t.optString("type")).put("poster", t.optString("poster")).put("overview", t.optString("overview"))
                    .put("where", where));
        }
        return new JSONObject().put("results", out).put("country", from);
    }

    /**
     * A page of the swipe deck: popular titles in one genre, only on apps this TV has, each with where to play it.
     * The phone picks the genre (its own like-learning) and the offset (so cards don't repeat).
     */
    static JSONObject discover(Context ctx, String genre, int offset) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        JSONArray packages = new JSONArray();
        for (Map.Entry<String, String> e : SHORT.entrySet()) if (installed(pm, e.getKey())) packages.put(e.getValue());
        if (packages.length() == 0) return new JSONObject().put("cards", new JSONArray()).put("error", "No streaming apps found on the TV.");
        JSONObject filter = new JSONObject().put("packages", packages);
        boolean known = false;
        for (String[] g : GENRES) if (g[0].equals(genre)) known = true;
        if (known) filter.put("genres", new JSONArray().put(genre));
        String q = "query($country: Country!, $language: Language!, $first: Int!, $offset: Int, $filter: TitleFilter) {"
                + " popularTitles(country: $country, first: $first, offset: $offset, filter: $filter) { edges { node { id objectType"
                + " content(country: $country, language: $language) { title originalReleaseYear posterUrl shortDescription genres { shortName } }"
                + " offers(country: $country, platform: ANDROID_TV) { " + OFFER + " } } } } }";
        String[] chain = deckCountry != null ? new String[]{deckCountry} : new String[]{country(ctx), "ZA", "GB", "US"};
        JSONArray edges = new JSONArray();
        for (String c : chain) {
            JSONObject vars = new JSONObject().put("country", c).put("language", "en").put("first", 12)
                    .put("offset", Math.max(0, Math.min(offset, 500))).put("filter", filter);
            edges = post(new JSONObject().put("query", q).put("variables", vars)).getJSONObject("popularTitles").getJSONArray("edges");
            if (edges.length() > 0) {
                deckCountry = c;
                break;
            }
        }
        JSONArray cards = new JSONArray();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject n = edges.getJSONObject(i).getJSONObject("node");
            JSONObject c = n.getJSONObject("content");
            JSONArray genres = new JSONArray();
            JSONArray gs = c.optJSONArray("genres");
            if (gs != null) for (int k = 0; k < gs.length(); k++) genres.put(gs.getJSONObject(k).optString("shortName"));
            JSONArray where = new JSONArray();
            java.util.Set<String> seen = new java.util.HashSet<>();
            JSONArray offers = n.optJSONArray("offers");
            if (offers != null) for (int k = 0; k < offers.length(); k++) {
                JSONObject o = offers.getJSONObject(k);
                String pkg = APPS.get(o.getJSONObject("package").optString("technicalName"));
                String link = o.optString("deeplinkURL", "");
                if (pkg == null || "null".equals(link) || link.isEmpty() || !installed(pm, pkg) || !seen.add(pkg)) continue;
                where.put(new JSONObject().put("name", o.getJSONObject("package").optString("clearName"))
                        .put("kind", kind(o.optString("monetizationType"))).put("id", remember(pkg, link)));
            }
            String poster = c.optString("posterUrl", "");
            if (where.length() == 0 || poster.isEmpty() || "null".equals(poster)) continue;
            cards.put(new JSONObject().put("key", n.optString("id")).put("title", c.optString("title"))
                    .put("year", c.optInt("originalReleaseYear")).put("type", "SHOW".equals(n.optString("objectType")) ? "Show" : "Film")
                    .put("poster", "https://images.justwatch.com" + poster.replace("{profile}", "s592").replace("{format}", "jpg"))
                    .put("overview", "null".equals(c.optString("shortDescription")) ? "" : c.optString("shortDescription"))
                    .put("genres", genres).put("where", where));
        }
        JSONArray names = new JSONArray();
        for (String[] g : GENRES) names.put(new JSONArray().put(g[0]).put(g[1]));
        return new JSONObject().put("cards", cards).put("genres", names);
    }

    /** The app and link behind a search result's id, or null. */
    static String[] link(String id) {
        return LINKS.get(id == null ? "" : id);
    }

    /** Opens a saved show (Kids mode's posters on the TV): its app with its link, or just the app. */
    static void open(Context ctx, String pkg, String link) {
        String id = remember(pkg, link);
        try {
            play(ctx, id);
        } catch (Exception ignored) {
        }
    }

    /** Starts a link a search handed out. */
    static JSONObject play(Context ctx, String id) throws Exception {
        String[] l = LINKS.get(id == null ? "" : id);
        if (l == null) return new JSONObject().put("error", "Search again, then choose where to watch.");
        String pkg = l[0], link = l[1];
        Intent go;
        if (link.startsWith("intent:")) {
            go = Intent.parseUri(link, Intent.URI_INTENT_SCHEME);
            go.setComponent(null);
            go.setSelector(null);
            if (go.getPackage() == null) go.setPackage(pkg);
        } else {
            go = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg);
        }
        go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            ctx.startActivity(go);
        } catch (Exception e) {
            // The app ignores this kind of link: at least open it.
            Intent open = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (open == null) open = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (open == null) return new JSONObject().put("error", "That app isn't on this TV.");
            ctx.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return new JSONObject().put("ok", true).put("note", "Opened the app. Find the show there.");
        }
        return new JSONObject().put("ok", true);
    }

    // ---- JustWatch -------------------------------------------------------------------------------------

    private static JSONArray searchIn(String country, String q) throws Exception {
        JSONObject vars = new JSONObject().put("country", country).put("language", "en").put("first", 8)
                .put("filter", new JSONObject().put("searchQuery", q));
        JSONObject data = post(new JSONObject().put("query", SEARCH).put("variables", vars));
        JSONArray edges = data.getJSONObject("popularTitles").getJSONArray("edges");
        JSONArray out = new JSONArray();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject n = edges.getJSONObject(i).getJSONObject("node");
            JSONObject c = n.getJSONObject("content");
            String poster = c.optString("posterUrl", "");
            out.put(new JSONObject().put("title", c.optString("title")).put("year", c.optInt("originalReleaseYear"))
                    .put("type", "SHOW".equals(n.optString("objectType")) ? "Show" : "Film")
                    .put("poster", poster.isEmpty() || "null".equals(poster) ? ""
                            : "https://images.justwatch.com" + poster.replace("{profile}", "s332").replace("{format}", "jpg"))
                    .put("overview", "null".equals(c.optString("shortDescription")) ? "" : c.optString("shortDescription"))
                    .put("offers", n.optJSONArray("offers") == null ? new JSONArray() : n.getJSONArray("offers")));
        }
        return out;
    }

    private static JSONObject post(JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(GQL).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        c.setRequestProperty("user-agent", "tvhome/1");
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) > 0) b.write(buf, 0, n);
        }
        JSONObject j = new JSONObject(b.toString("UTF-8"));
        if (j.has("errors")) throw new java.io.IOException("JustWatch: " + j.getJSONArray("errors").getJSONObject(0).optString("message"));
        return j.getJSONObject("data");
    }

    /** The theme's watch_country, else the TV's own country, else Kenya's neighbours fall to the chain in search. */
    private static String country(Context ctx) {
        String c = Stay.json(ctx).optString("watch_country", "").trim().toUpperCase(Locale.ROOT);
        if (c.matches("[A-Z]{2}")) return c;
        c = Locale.getDefault().getCountry();
        return c.matches("[A-Z]{2}") ? c : "US";
    }

    private static int rank(String kind) {
        switch (kind) {
            case "FREE": case "ADS": case "FAST": return 0;
            case "FLATRATE": return 1;
            case "RENT": return 2;
            case "BUY": return 3;
            default: return 4;
        }
    }

    private static String kind(String k) {
        switch (k) {
            case "FREE": case "ADS": case "FAST": return "free";
            case "FLATRATE": return "subscription";
            case "RENT": return "rent";
            case "BUY": return "buy";
            default: return "other";
        }
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return "Search " + pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return "Search";
        }
    }

    private static String remember(String pkg, String link) {
        byte[] b = new byte[9];
        new SecureRandom().nextBytes(b);
        StringBuilder id = new StringBuilder();
        for (byte x : b) id.append(String.format("%02x", x));
        LINKS.put(id.toString(), new String[]{pkg, link});
        return id.toString();
    }
}
