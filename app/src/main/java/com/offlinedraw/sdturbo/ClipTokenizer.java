
package com.offlinedraw.sdturbo;

import android.util.JsonReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** CLIP byte-level BPE re-implemented in pure Java from tokenizer0/bpe.json (offline, no JNI). */
public final class ClipTokenizer {

    private final HashMap<String, Integer> vocab = new HashMap<>(70000);
    private final HashMap<String, Integer> ranks = new HashMap<>(70000);
    private final ArrayList<String[]> fixtures = new ArrayList<>();
    private int length = 77, pad = 0, bos = 49406, eos = 49407, unk = 49407;
    private String prefix = "", suffix = "";

    private static final Pattern SPLIT = Pattern.compile(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern WS = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final String[] B2C = byteEncoder();

    private static String[] byteEncoder() {
        boolean[] keep = new boolean[256];
        for (int b = 33; b <= 126; b++) keep[b] = true;
        for (int b = 161; b <= 172; b++) keep[b] = true;
        for (int b = 174; b <= 255; b++) keep[b] = true;
        String[] m = new String[256];
        int n = 0;
        for (int b = 0; b < 256; b++) if (keep[b]) m[b] = String.valueOf((char) b);
        for (int b = 0; b < 256; b++) if (!keep[b]) m[b] = String.valueOf((char) (256 + n++));
        return m;
    }

    public ClipTokenizer(InputStream in) throws Exception {
        JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if (k.equals("vocab")) {
                r.beginObject();
                while (r.hasNext()) vocab.put(r.nextName(), r.nextInt());
                r.endObject();
            } else if (k.equals("merges")) {
                r.beginArray();
                int i = 0;
                while (r.hasNext()) ranks.put(r.nextString(), i++);
                r.endArray();
            } else if (k.equals("length")) length = r.nextInt();
            else if (k.equals("pad")) pad = r.nextInt();
            else if (k.equals("bos")) bos = r.nextInt();
            else if (k.equals("eos")) { eos = r.nextInt(); unk = eos; }
            else if (k.equals("prefix")) prefix = r.nextString();
            else if (k.equals("suffix")) suffix = r.nextString();
            else if (k.equals("fixtures")) {
                r.beginArray();
                while (r.hasNext()) {
                    String p = null, ids = null, mask = null;
                    r.beginObject();
                    while (r.hasNext()) {
                        String f = r.nextName();
                        if (f.equals("prompt")) p = r.nextString();
                        else if (f.equals("ids") || f.equals("mask")) {
                            StringBuilder sb = new StringBuilder();
                            r.beginArray();
                            while (r.hasNext()) { if (sb.length() > 0) sb.append(','); sb.append(r.nextInt()); }
                            r.endArray();
                            if (f.equals("ids")) ids = sb.toString(); else mask = sb.toString();
                        } else r.skipValue();
                    }
                    r.endObject();
                    fixtures.add(new String[]{p, ids, mask});
                }
                r.endArray();
            } else r.skipValue();
        }
        r.endObject();
        r.close();
    }

    public int length() { return length; }
    public int vocabSize() { return vocab.size(); }

    /** Exactly mirrors tokenizer(prefix + text + suffix, padding=max_length, truncation=True). */
    public long[] encode(String text) {
        String s = Normalizer.normalize(prefix + text + suffix, Normalizer.Form.NFC);
        s = WS.matcher(s).replaceAll(" ").toLowerCase(Locale.ROOT);
        ArrayList<Integer> ids = new ArrayList<>();
        Matcher m = SPLIT.matcher(s);
        while (m.find()) {
            String piece = m.group();
            if (piece.equals("<|startoftext|>")) { ids.add(bos); continue; }
            if (piece.equals("<|endoftext|>")) { ids.add(eos); continue; }
            for (String sym : bpe(piece)) {
                Integer id = vocab.get(sym);
                ids.add(id == null ? unk : id);
            }
        }
        int keep = Math.min(ids.size(), length - 2);
        long[] out = new long[length];
        out[0] = bos;
        for (int i = 0; i < keep; i++) out[i + 1] = ids.get(i);
        out[keep + 1] = eos;
        for (int i = keep + 2; i < length; i++) out[i] = pad;
        return out;
    }

    private ArrayList<String> bpe(String token) {
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        ArrayList<String> w = new ArrayList<>(bytes.length + 2);
        for (int i = 0; i < bytes.length; i++) {
            String c = B2C[bytes[i] & 0xff];
            w.add(i == bytes.length - 1 ? c + "</w>" : c);
        }
        while (w.size() > 1) {
            int best = Integer.MAX_VALUE, bi = -1;
            for (int i = 0; i + 1 < w.size(); i++) {
                Integer rk = ranks.get(w.get(i) + " " + w.get(i + 1));
                if (rk != null && rk < best) { best = rk; bi = i; }
            }
            if (bi < 0) break;
            String a = w.get(bi), b = w.get(bi + 1), merged = a + b;
            ArrayList<String> nw = new ArrayList<>(w.size());
            for (int i = 0; i < w.size(); ) {
                if (i + 1 < w.size() && w.get(i).equals(a) && w.get(i + 1).equals(b)) { nw.add(merged); i += 2; }
                else { nw.add(w.get(i)); i++; }
            }
            w = nw;
        }
        return w;
    }

    /** Parity check against the 7 export fixtures shipped inside bpe.json. */
    public String selfTest() {
        int ok = 0;
        StringBuilder bad = new StringBuilder();
        for (String[] f : fixtures) {
            long[] got = encode(f[0]);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < got.length; i++) { if (i > 0) sb.append(','); sb.append(got[i]); }
            if (sb.toString().equals(f[1])) ok++;
            else if (bad.length() < 160) {
                String p = f[0].length() > 20 ? f[0].substring(0, 20) + "..." : f[0];
                bad.append('[').append(p).append("] ");
            }
        }
        return "tokenizer fixtures " + ok + "/" + fixtures.size()
                + (bad.length() == 0 ? " (exact parity)" : " mismatch: " + bad);
    }
}
