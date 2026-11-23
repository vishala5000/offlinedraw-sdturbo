
package com.offlinedraw.sdturbo;

import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Random;

/**
 * Manifest-driven replay of the word-image-litert-v1 plan (24 actions):
 * CLIP -> initial scale -> sigma scale -> 14 UNet shards -> Euler-ancestral step -> VAE decode.
 * Every tensor name / shape / dtype / signature comes from manifest.json, so no model-specific
 * sampler or scheduler is re-implemented here. One java.util.Random gaussian stream feeds all
 * "normal" externals in manifest order (identical to java_noise.py in the model repo).
 */
public final class Engine {

    public static Bitmap run(Pack pack, long[] tokens, long seed, int threads, Worker w) throws Exception {
        JSONObject man = pack.manifest;
        JSONArray actions = man.getJSONArray("actions");
        JSONArray externals = man.getJSONArray("externals");
        String outName = man.getString("output");

        HashMap<String, ByteBuffer> val = new HashMap<>();
        HashMap<String, int[]> shp = new HashMap<>();
        HashMap<String, Integer> use = new HashMap<>();
        for (int i = 0; i < actions.length(); i++) {
            JSONArray ins = actions.getJSONObject(i).getJSONArray("inputs");
            for (int j = 0; j < ins.length(); j++) bump(use, ins.getJSONObject(j).getString("tensor"), 1);
        }
        bump(use, outName, 1);

        Random rng = new Random(seed);
        for (int i = 0; i < externals.length(); i++) {
            JSONObject e = externals.getJSONObject(i);
            String t = e.getString("tensor"), kind = e.getString("kind"), dt = e.getString("dtype");
            int[] shape = ints(e.getJSONArray("shape"));
            int n = prod(shape);
            ByteBuffer b = buf(n, dt);
            if (kind.equals("normal")) {
                for (int k = 0; k < n; k++) b.putFloat((float) rng.nextGaussian());
            } else if (kind.equals("constant")) {
                b.put(pack.readAll(e.getString("path")));
            } else if (kind.equals("tokens")) {
                for (int k = 0; k < n; k++) b.putLong(tokens[k]);
            } else if (kind.equals("mask")) {
                for (int k = 0; k < n; k++) b.putLong(k < tokens.length ? 1L : 0L);
            } else throw new IllegalStateException("unknown external kind " + kind);
            b.rewind();
            val.put(t, b);
            shp.put(t, shape);
        }
        w.log("seed " + seed + ", threads " + threads + ", " + actions.length() + " actions");

        int total = actions.length();
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < total; i++) {
            if (w.cancel) throw new InterruptedException("cancelled by user");
            JSONObject a = actions.getJSONObject(i);
            String model = a.getString("model"), sig = a.getString("signature");
            String shortName = new File(model).getName();
            w.progress(a.optString("phase", "run") + " " + (i + 1) + "/" + total + "  " + shortName, i * 100 / total);
            File f = pack.graph(model);
            if (f == null) throw new IllegalStateException("missing graph file: " + model);

            JSONArray ins = a.getJSONArray("inputs");
            JSONArray outs = a.getJSONArray("outputs");
            Interpreter.Options opt = new Interpreter.Options();
            opt.setNumThreads(threads);
            Interpreter it = new Interpreter(f, opt);
            try {
                it.allocateTensors();
                HashMap<String, Object> in = new HashMap<>();
                for (int j = 0; j < ins.length(); j++) {
                    JSONObject o = ins.getJSONObject(j);
                    ByteBuffer b = val.get(o.getString("tensor"));
                    if (b == null) throw new IllegalStateException("tensor not ready: " + o.getString("tensor"));
                    b.rewind();
                    in.put(o.getString("name"), b);
                }
                HashMap<String, Object> out = new HashMap<>();
                LinkedHashMap<String, ByteBuffer> got = new LinkedHashMap<>();
                for (int j = 0; j < outs.length(); j++) {
                    JSONObject o = outs.getJSONObject(j);
                    int[] s = ints(o.getJSONArray("shape"));
                    ByteBuffer b = buf(prod(s), o.getString("dtype"));
                    got.put(o.getString("tensor"), b);
                    out.put(o.getString("name"), b);
                    shp.put(o.getString("tensor"), s);
                }
                long ts = System.currentTimeMillis();
                it.runSignature(in, out, sig);
                for (int j = 0; j < outs.length(); j++) {
                    JSONObject o = outs.getJSONObject(j);
                    ByteBuffer b = got.get(o.getString("tensor"));
                    b.rewind();
                    if (o.getString("dtype").equals("float32")) {
                        FloatBuffer fb = b.asFloatBuffer();
                        for (int k = 0; k < fb.capacity(); k++) {
                            float v = fb.get(k);
                            if (Float.isNaN(v) || Float.isInfinite(v))
                                throw new IllegalStateException("non-finite output from " + shortName);
                        }
                    }
                    val.put(o.getString("tensor"), b);
                }
                w.log("  " + shortName + "  " + (System.currentTimeMillis() - ts) + " ms");
            } finally {
                it.close();
            }
            for (int j = 0; j < ins.length(); j++) {
                String t = ins.getJSONObject(j).getString("tensor");
                if (bump(use, t, -1) <= 0) val.remove(t);
            }
            if ((i & 3) == 3) System.gc();
        }

        int[] shape = shp.get(outName);
        int h = shape[2], wd = shape[3], plane = h * wd;
        FloatBuffer fb = val.get(outName).asFloatBuffer();
        int[] px = new int[plane];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < wd; x++) {
                int idx = y * wd + x;
                px[idx] = 0xff000000 | (q(fb.get(idx)) << 16) | (q(fb.get(plane + idx)) << 8) | q(fb.get(2 * plane + idx));
            }
        }
        Bitmap bmp = Bitmap.createBitmap(wd, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(px, 0, wd, 0, 0, wd, h);
        w.progress("done in " + ((System.currentTimeMillis() - t0) / 1000.0) + " s", 100);
        return bmp;
    }

    /** Loads + runs the 3 tiny graphs that ship inside the APK: proves LiteRT works on this phone. */
    public static String runtimeTest(Pack pack, int threads) throws Exception {
        int n = 4 * 64 * 64;
        Random rng = new Random(42);
        ByteBuffer x = buf(n, "float32"), noise = buf(n, "float32");
        for (int i = 0; i < n; i++) x.putFloat((float) rng.nextGaussian());
        for (int i = 0; i < n; i++) noise.putFloat((float) rng.nextGaussian());
        x.rewind();
        noise.rewind();
        long t0 = System.currentTimeMillis();
        ByteBuffer[] a = invoke(pack, "graphs/initial-submod_0.tflite", threads,
                new String[]{"args_0"}, new ByteBuffer[]{x}, new int[]{n});
        ByteBuffer[] b = invoke(pack, "graphs/scale0-submod_0.tflite", threads,
                new String[]{"args_0"}, a, new int[]{n});
        ByteBuffer[] c = invoke(pack, "graphs/step0-submod_0.tflite", threads,
                new String[]{"args_0", "args_1", "args_2"}, new ByteBuffer[]{b[0], a[0], noise}, new int[]{n});
        FloatBuffer fb = c[0].asFloatBuffer();
        double mx = 0;
        for (int i = 0; i < fb.capacity(); i++) {
            float v = fb.get(i);
            if (Float.isNaN(v) || Float.isInfinite(v)) return "LiteRT runtime test FAILED (non-finite)";
            mx = Math.max(mx, Math.abs(v));
        }
        return "LiteRT runtime OK: 3 bundled graphs ran in " + (System.currentTimeMillis() - t0)
                + " ms, max|latent|=" + String.format(java.util.Locale.US, "%.3f", mx);
    }

    private static ByteBuffer[] invoke(Pack pack, String rel, int threads, String[] names,
                                       ByteBuffer[] ins, int[] outElems) throws Exception {
        File f = pack.graph(rel);
        if (f == null) throw new IllegalStateException("missing " + rel);
        Interpreter.Options o = new Interpreter.Options();
        o.setNumThreads(threads);
        Interpreter it = new Interpreter(f, o);
        try {
            it.allocateTensors();
            HashMap<String, Object> in = new HashMap<>(), out = new HashMap<>();
            for (int i = 0; i < names.length; i++) { ins[i].rewind(); in.put(names[i], ins[i]); }
            ByteBuffer[] outs = new ByteBuffer[outElems.length];
            for (int i = 0; i < outElems.length; i++) {
                outs[i] = buf(outElems[i], "float32");
                out.put("output_" + i, outs[i]);
            }
            it.runSignature(in, out, "serving_default");
            for (ByteBuffer b : outs) b.rewind();
            return outs;
        } finally { it.close(); }
    }

    private static int q(float v) {
        int i = (int) Math.floor(v * 255f + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    private static int bump(HashMap<String, Integer> m, String k, int d) {
        int v = (m.containsKey(k) ? m.get(k) : 0) + d;
        m.put(k, v);
        return v;
    }

    private static int[] ints(JSONArray a) throws Exception {
        int[] r = new int[a.length()];
        for (int i = 0; i < r.length; i++) r[i] = a.getInt(i);
        return r;
    }

    private static int prod(int[] s) {
        int n = 1;
        for (int v : s) n *= v;
        return n;
    }

    private static ByteBuffer buf(int elems, String dtype) {
        int width = dtype.equals("int64") ? 8 : 4;
        return ByteBuffer.allocateDirect(elems * width).order(ByteOrder.nativeOrder());
    }
}
