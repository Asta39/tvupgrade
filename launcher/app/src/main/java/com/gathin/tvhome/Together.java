package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "What should we watch?" for a room (this fork): everyone swipes the same deck on their own phone, and when every
 * phone in the group has liked the same title the TV shows "It's a match!" with its poster and a Play button
 * (MatchActivity). One group per TV, kept in memory: a restart simply ends it.
 * <p>
 * The deck is built once on the TV from the swipe deck's source (Watch.discover), so every phone sees the same
 * cards in the same order and each card's play link is already remembered by Watch.
 */
final class Together {
    /** A group with nobody swiping for this long is over. */
    private static final long IDLE_MS = 3 * 3600_000L;
    private static final String[] MIX = {"cmy", "act", "drm", "fml", "trl", "scf", "ani", "crm", "rma", "fnt"};

    private static final Object LOCK = new Object();
    private static String id;
    private static long touched;
    private static final List<JSONObject> cards = new ArrayList<>();
    /** Member id to their name, in the order they joined. */
    private static final Map<String, String> members = new LinkedHashMap<>();
    /** Card key to the members who liked it. */
    private static final Map<String, Set<String>> likes = new HashMap<>();
    /** Member id to the card keys they have swiped either way. */
    private static final Map<String, Set<String>> swiped = new HashMap<>();
    private static JSONObject match;

    private Together() {
    }

    /** Starts a new group (ending any other) with a fresh shared deck, and joins whoever started it. */
    static JSONObject start(Context ctx, String name) throws Exception {
        List<JSONObject> deck = buildDeck(ctx);
        if (deck.isEmpty()) return new JSONObject().put("error", "Couldn’t find shows on this TV’s apps. Try again in a minute.");
        synchronized (LOCK) {
            id = token(6);
            cards.clear();
            cards.addAll(deck);
            members.clear();
            likes.clear();
            swiped.clear();
            match = null;
            touched = System.currentTimeMillis();
        }
        return join(name);
    }

    static JSONObject join(String name) throws Exception {
        synchronized (LOCK) {
            if (!active()) return new JSONObject().put("error", "No group yet. Start one.");
            String n = name == null ? "" : name.trim();
            if (n.length() > 24) n = n.substring(0, 24);
            if (n.isEmpty()) n = "Guest " + (members.size() + 1);
            String me = token(8);
            members.put(me, n);
            swiped.put(me, new HashSet<>());
            touched = System.currentTimeMillis();
            return state(me).put("me", me);
        }
    }

    static JSONObject leave(String me) throws Exception {
        synchronized (LOCK) {
            members.remove(me);
            swiped.remove(me);
            for (Set<String> s : likes.values()) s.remove(me);
            if (members.isEmpty()) id = null;
            return new JSONObject().put("ok", true);
        }
    }

    static void end() {
        synchronized (LOCK) {
            id = null;
            match = null;
        }
    }

    /**
     * One swipe. A like that makes the title liked by every member (two or more) is the match: the TV shows it at
     * once, and every phone sees it on its next look.
     */
    static JSONObject vote(Context ctx, String me, String key, boolean like) throws Exception {
        JSONObject found = null;
        synchronized (LOCK) {
            if (!active() || !members.containsKey(me)) return new JSONObject().put("error", "This group has ended.").put("gone", true);
            touched = System.currentTimeMillis();
            swiped.get(me).add(key);
            Set<String> who = likes.get(key);
            if (who == null) likes.put(key, who = new HashSet<>());
            if (like) who.add(me);
            else who.remove(me);
            if (match == null && like && members.size() >= 2 && who.containsAll(members.keySet())) {
                match = card(key);
                found = match;
            }
        }
        if (found != null) showOnTv(ctx, found);
        synchronized (LOCK) {
            return state(me);
        }
    }

    /** After a match the group can keep going for a second choice. */
    static JSONObject again(String me) throws Exception {
        synchronized (LOCK) {
            if (match != null) {
                Set<String> who = likes.get(match.optString("key"));
                if (who != null) who.clear();
                match = null;
            }
            return state(me);
        }
    }

    static JSONObject state(String me) throws Exception {
        synchronized (LOCK) {
            if (!active()) return new JSONObject().put("active", false);
            JSONArray who = new JSONArray();
            for (Map.Entry<String, String> m : members.entrySet()) {
                Set<String> s = swiped.get(m.getKey());
                who.put(new JSONObject().put("name", m.getValue()).put("swiped", s == null ? 0 : s.size()).put("you", m.getKey().equals(me)));
            }
            JSONObject o = new JSONObject().put("active", true).put("id", id).put("members", who).put("total", cards.size())
                    .put("joined", me != null && members.containsKey(me));
            if (match != null) o.put("match", match);
            // The cards this phone hasn't swiped yet, in the group's order.
            Set<String> mine = me == null ? null : swiped.get(me);
            if (mine != null) {
                JSONArray left = new JSONArray();
                for (JSONObject c : cards) if (!mine.contains(c.optString("key"))) left.put(c);
                o.put("cards", left);
            }
            // No match yet: the titles most of the room likes, best first, so a stalemate still ends with a choice.
            List<Map.Entry<String, Set<String>>> best = new ArrayList<>();
            for (Map.Entry<String, Set<String>> e : likes.entrySet()) if (e.getValue().size() >= 1) best.add(e);
            best.sort((a, b) -> b.getValue().size() - a.getValue().size());
            JSONArray top = new JSONArray();
            for (int i = 0; i < best.size() && top.length() < 3; i++) {
                JSONObject c = card(best.get(i).getKey());
                if (c != null) top.put(new JSONObject(c.toString()).put("likes", best.get(i).getValue().size()));
            }
            o.put("top", top);
            return o;
        }
    }

    /** Plays the match (or a top title) from the TV's remote or a phone. */
    static JSONObject play(Context ctx, String key) throws Exception {
        JSONObject c;
        synchronized (LOCK) {
            c = card(key);
        }
        if (c == null) return new JSONObject().put("error", "That title isn’t in this group.");
        JSONArray where = c.optJSONArray("where");
        if (where == null || where.length() == 0) return new JSONObject().put("error", "Not on this TV’s apps.");
        return Watch.play(ctx, where.getJSONObject(0).optString("id"));
    }

    // ---- inside -------------------------------------------------------------------------------------------

    private static boolean active() {
        if (id != null && System.currentTimeMillis() - touched > IDLE_MS) id = null;
        return id != null;
    }

    private static JSONObject card(String key) {
        for (JSONObject c : cards) if (c.optString("key").equals(key)) return c;
        return null;
    }

    /** About 36 titles from a spread of genres, shuffled once so every phone shares the same order. */
    private static List<JSONObject> buildDeck(Context ctx) {
        List<JSONObject> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        List<String> genres = new ArrayList<>();
        java.util.Collections.addAll(genres, MIX);
        java.util.Collections.shuffle(genres);
        SecureRandom r = new SecureRandom();
        for (String g : genres) {
            if (out.size() >= 36) break;
            try {
                JSONArray got = Watch.discover(ctx, g, r.nextInt(3) * 12).optJSONArray("cards");
                if (got == null) continue;
                for (int i = 0; i < got.length() && i < 6; i++) {
                    JSONObject c = got.getJSONObject(i);
                    if (keys.add(c.optString("key"))) out.add(c.put("from", g));
                }
            } catch (Exception e) {
                android.util.Log.w("Together", "deck: " + g, e);
            }
        }
        java.util.Collections.shuffle(out);
        return out;
    }

    private static void showOnTv(Context ctx, JSONObject c) {
        Intent i = new Intent(ctx, MatchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("key", c.optString("key")).putExtra("title", c.optString("title"))
                .putExtra("poster", c.optString("poster")).putExtra("sub", c.optString("type")
                        + (c.optInt("year") > 0 ? " · " + c.optInt("year") : "") + " · " + names(c));
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                ctx.startActivity(i);
            } catch (Exception e) {
                android.util.Log.w("Together", "match screen", e);
            }
        });
    }

    private static String names(JSONObject c) {
        JSONArray w = c.optJSONArray("where");
        List<String> n = new ArrayList<>();
        if (w != null) for (int i = 0; i < w.length(); i++) n.add(w.optJSONObject(i).optString("name"));
        return String.join(", ", n);
    }

    private static String token(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder t = new StringBuilder();
        for (byte x : b) t.append(String.format("%02x", x));
        return t.toString();
    }
}
