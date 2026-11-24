
package com.offlinedraw.sdturbo;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Model pack storage. Small artifacts (manifest / tokenizer / constants / 3 tiny graphs) ship inside
 * the APK assets. The 21 big fp32 graphs (4.68 GiB) live on disk and can be either
 *  a) downloaded once from Hugging Face (resumable + SHA-256 verified), or
 *  b) sideloaded fully offline into /sdcard/OfflineDraw/bundle/graphs/ , or
 *  c) imported from any folder with the system folder picker.
 * After that, generation never touches the network again.
 */
public final class Pack {

    public static final String HF = "https://huggingface.co/frankdu/SD-Turbo-LiteRT/resolve/main/";

    public static final String[] BUNDLED = {
            "manifest.json", "tokenizer0/bpe.json", "constants/t17.bin",
            "LICENSE.md", "SOURCE_MODEL_CARD.md", "NOTICE", "reference.png",
            "graphs/initial-submod_0.tflite", "graphs/scale0-submod_0.tflite", "graphs/step0-submod_0.tflite"};

    public final File root, sideload;
    public final JSONObject manifest;
    public final LinkedHashMap<String, Long> sizes = new LinkedHashMap<>();
    public final HashMap<String, String> hashes = new HashMap<>();
    private final Context ctx;

    public Pack(Context c) throws Exception {
        ctx = c.getApplicationContext();
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        root = new File(base, "bundle");
        root.mkdirs();
        sideload = new File(Environment.getExternalStorageDirectory(), "OfflineDraw/bundle");
        for (String rel : BUNDLED) {
            File f = new File(root, rel);
            if (f.isFile()) continue;
            InputStream in = null;
            OutputStream out = null;
            try {
                in = ctx.getAssets().open("bundle/" + rel);
                f.getParentFile().mkdirs();
                out = new FileOutputStream(f);
                copy(in, out);
            } catch (IOException ignored) {
            } finally { close(in); close(out); }
        }
        manifest = new JSONObject(new String(readAll("manifest.json"), "UTF-8"));
        JSONObject fs = manifest.getJSONObject("files");
        Iterator<String> it = fs.keys();
        while (it.hasNext()) {
            String rel = it.next();
            JSONObject spec = fs.getJSONObject(rel);
            sizes.put(rel, spec.getLong("bytes"));
            hashes.put(rel, spec.getString("sha256"));
        }
    }

    public File resolve(String rel) {
        File a = new File(root, rel);
        if (a.isFile() && a.length() > 0) return a;
        File b = new File(sideload, rel);
        if (b.isFile() && b.length() > 0) return b;
        return null;
    }

    public File graph(String rel) {
        File f = resolve(rel);
        if (f == null) return null;
        Long want = sizes.get(rel);
        return (want == null || f.length() == want) ? f : null;
    }

    public byte[] readAll(String rel) throws IOException {
        File f = resolve(rel);
        InputStream in = (f != null) ? new FileInputStream(f) : ctx.getAssets().open("bundle/" + rel);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            copy(in, bo);
            return bo.toByteArray();
        } finally { close(in); }
    }

    public List<String> missing() {
        List<String> m = new ArrayList<>();
        for (String rel : sizes.keySet()) {
            File f = resolve(rel);
            if (f == null || f.length() != sizes.get(rel)) m.add(rel);
        }
        return m;
    }

    public String status() {
        long have = 0, total = 0;
        int ok = 0;
        for (String rel : sizes.keySet()) {
            long want = sizes.get(rel);
            total += want;
            File f = resolve(rel);
            if (f != null && f.length() == want) { have += want; ok++; }
        }
        return "pack: " + ok + "/" + sizes.size() + " files, " + mb(have) + " / " + mb(total)
                + "\nAPK-private: " + root.getAbsolutePath()
                + "\nSideload   : " + sideload.getAbsolutePath();
    }

    public void download(Worker w) throws Exception {
        List<String> miss = missing();
        long total = 0;
        for (String rel : miss) total += sizes.get(rel);
        if (total == 0) { w.progress("pack already complete", 100); return; }
        long done = 0;
        for (int i = 0; i < miss.size(); i++) {
            String rel = miss.get(i);
            w.progress("download " + (i + 1) + "/" + miss.size() + " " + rel, (int) (done * 100 / total));
            done += fetch(rel, done, total, w);
        }
        w.progress("model pack complete", 100);
    }

    private long fetch(String rel, long doneBase, long total, Worker w) throws Exception {
        long want = sizes.get(rel);
        File dst = new File(root, rel);
        dst.getParentFile().mkdirs();
        if (dst.isFile() && dst.length() == want) return want;
        File part = new File(dst.getAbsolutePath() + ".part");
        long have = part.isFile() ? part.length() : 0;
        if (have > want) { part.delete(); have = 0; }
        HttpURLConnection c = open(rel, have);
        int code = c.getResponseCode();
        if (code != 200 && code != 206) throw new IOException("HTTP " + code + " for " + rel);
        RandomAccessFile raf = new RandomAccessFile(part, "rw");
        if (code == 200) { raf.setLength(0); have = 0; }
        raf.seek(have);
        InputStream in = c.getInputStream();
        byte[] b = new byte[1 << 20];
        long got = have, mark = have;
        int n;
        while ((n = in.read(b)) > 0) {
            if (w.cancel) { close(in); raf.close(); throw new IOException("cancelled"); }
            raf.write(b, 0, n);
            got += n;
            if (got - mark > (4L << 20)) {
                mark = got;
                w.progress("download " + rel + "  " + mb(got) + " / " + mb(want),
                        (int) ((doneBase + got) * 100 / total));
            }
        }
        close(in);
        raf.close();
        c.disconnect();
        if (part.length() != want) throw new IOException("size mismatch " + rel);
        if (!sha256(part).equals(hashes.get(rel))) { part.delete(); throw new IOException("SHA-256 mismatch " + rel); }
        if (dst.exists()) dst.delete();
        if (!part.renameTo(dst)) throw new IOException("rename failed " + rel);
        w.log("verified " + rel + " (" + mb(want) + ")");
        return want;
    }

    public static final String MIRROR = "https://github.com/vishala5000/offlinedraw-sdturbo/releases/download/model-pack/";

    private HttpURLConnection open(String rel, long have) throws IOException {
        String[] us = {MIRROR + new File(rel).getName(), HF + rel};
        IOException last = null;
        for (String u : us) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(30000);
                c.setReadTimeout(60000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "OfflineDraw/1.0");
                if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                int code = c.getResponseCode();
                if (code == 200 || code == 206) return c;
                last = new IOException("HTTP " + code + " " + u);
                c.disconnect();
            } catch (IOException e) {
                last = e;
                if (c != null) c.disconnect();
            }
        }
        throw (last == null) ? new IOException("no source for " + rel) : last;
    }

    public String verify(Worker w) throws Exception {
        int ok = 0, bad = 0, miss = 0, i = 0;
        for (String rel : sizes.keySet()) {
            if (w.cancel) break;
            i++;
            w.progress("verify " + i + "/" + sizes.size() + " " + rel, i * 100 / sizes.size());
            File f = resolve(rel);
            if (f == null) { miss++; w.log("MISSING " + rel); continue; }
            if (f.length() != sizes.get(rel)) { bad++; w.log("BAD SIZE " + rel); continue; }
            if (sha256(f).equals(hashes.get(rel))) ok++;
            else { bad++; w.log("BAD HASH " + rel); }
        }
        return "verify: " + ok + " ok / " + bad + " bad / " + miss + " missing (" + sizes.size() + " total)";
    }

    public int importTree(Uri tree, Worker w) throws Exception {
        HashMap<String, String> byName = new HashMap<>();
        for (String rel : sizes.keySet()) byName.put(new File(rel).getName(), rel);
        int[] n = {0};
        walk(tree, DocumentsContract.getTreeDocumentId(tree), byName, w, n, 0);
        return n[0];
    }

    private void walk(Uri tree, String parentId, HashMap<String, String> byName,
                      Worker w, int[] n, int depth) throws Exception {
        if (depth > 3) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                         DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                         DocumentsContract.Document.COLUMN_MIME_TYPE};
        Cursor c = ctx.getContentResolver().query(children, cols, null, null, null);
        if (c == null) return;
        try {
            while (c.moveToNext()) {
                if (w.cancel) return;
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(tree, id, byName, w, n, depth + 1);
                    continue;
                }
                String rel = byName.get(name);
                if (rel == null) continue;
                File dst = new File(root, rel);
                if (dst.isFile() && dst.length() == sizes.get(rel)) continue;
                dst.getParentFile().mkdirs();
                w.progress("importing " + name, -1);
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                InputStream in = ctx.getContentResolver().openInputStream(doc);
                OutputStream out = new FileOutputStream(dst);
                copy(in, out);
                close(in);
                close(out);
                if (dst.length() != sizes.get(rel) || !sha256(dst).equals(hashes.get(rel))) {
                    dst.delete();
                    throw new IOException("corrupt copy of " + name);
                }
                n[0]++;
                w.log("imported " + rel);
            }
        } finally {
            c.close();
        }
    }

    public static String mb(long b) {
        if (b >= (1L << 30)) return String.format(java.util.Locale.US, "%.2f GiB", b / 1073741824.0);
        return String.format(java.util.Locale.US, "%.1f MiB", b / 1048576.0);
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[1 << 16];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.flush();
    }

    public static void close(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (IOException ignored) { }
    }

    public static String sha256(File f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[1 << 20];
            int n;
            while ((n = in.read(b)) > 0) d.update(b, 0, n);
        } finally { close(in); }
        StringBuilder sb = new StringBuilder();
        for (byte x : d.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
