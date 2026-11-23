
package com.offlinedraw.sdturbo;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Random;

public class MainActivity extends Activity {

    private static final int REQ_TREE = 1001;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile Pack pack;
    private volatile ClipTokenizer tok;
    private EditText prompt, seed;
    private Spinner threads;
    private TextView status, logview;
    private ProgressBar bar;
    private ImageView image;
    private Button generate;
    private int lastLog = -1;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String[] perms = Build.VERSION.SDK_INT >= 33
                ? new String[]{"android.permission.POST_NOTIFICATIONS", "android.permission.READ_MEDIA_IMAGES"}
                : new String[]{"android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"};
        try { requestPermissions(perms, 1); } catch (Throwable ignored) { }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(12);
        col.setPadding(p, p, p, p);
        ScrollView sc = new ScrollView(this);
        sc.addView(col);
        setContentView(sc);

        TextView title = new TextView(this);
        title.setText("OfflineDraw - SD-Turbo (LiteRT, 512x512, 1 step)");
        title.setTextSize(18);
        col.addView(title);

        status = new TextView(this);
        status.setText("starting");
        status.setPadding(0, dp(6), 0, dp(6));
        col.addView(status);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)));

        prompt = new EditText(this);
        prompt.setHint("prompt");
        prompt.setText("One ripe red apple with a green leaf, clear educational illustration, plain cream background.");
        prompt.setMinLines(3);
        col.addView(prompt);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        seed = new EditText(this);
        seed.setInputType(InputType.TYPE_CLASS_NUMBER);
        seed.setText("42");
        seed.setWidth(dp(120));
        row1.addView(seed);
        Button rnd = new Button(this);
        rnd.setText("random seed");
        rnd.setOnClickListener(v -> seed.setText(String.valueOf(new Random().nextInt(1000000))));
        row1.addView(rnd);
        threads = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"1", "2", "4", "6", "8"});
        threads.setAdapter(ad);
        threads.setSelection(2);
        row1.addView(threads);
        col.addView(row1);

        generate = new Button(this);
        generate.setText("GENERATE (offline)");
        generate.setOnClickListener(v -> generate());
        col.addView(generate);

        col.addView(row(
                button("Download pack", v -> download()),
                button("Import folder", v -> importFolder())));
        col.addView(row(
                button("Verify pack", v -> verify()),
                button("Self-test", v -> selfTest())));
        col.addView(row(
                button("All-files access", v -> allFiles()),
                button("Cancel", v -> Worker.I.cancel = true)));

        image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setBackgroundColor(Color.DKGRAY);
        col.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)));

        logview = new TextView(this);
        logview.setTextSize(10);
        logview.setTypeface(android.graphics.Typeface.MONOSPACE);
        col.addView(logview);

        Worker.I.start(this, () -> {
            Worker.I.progress("loading manifest + CLIP tokenizer", -1);
            pack = new Pack(getApplicationContext());
            tok = new ClipTokenizer(getAssets().open("bundle/tokenizer0/bpe.json"));
            Worker.I.log(tok.selfTest());
            Worker.I.log(pack.status());
            List<String> miss = pack.missing();
            Worker.I.note = miss.isEmpty()
                    ? "model pack complete - ready, fully offline"
                    : "model pack incomplete: " + miss.size() + " file(s) missing - Download or Import once";
            Worker.I.progress("ready", -1);
        });
        ui.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Worker w = Worker.I;
            String s = w.busy ? w.stage : (w.error != null ? "ERROR " + w.error : (w.note != null ? w.note : "idle"));
            status.setText(s);
            bar.setIndeterminate(w.busy && w.pct < 0);
            bar.setProgress(w.pct < 0 ? 0 : w.pct);
            String lg = w.log();
            if (lg.length() != lastLog) { lastLog = lg.length(); logview.setText(lg); }
            if (w.result != null && image.getTag() != w.result) {
                image.setImageBitmap(w.result);
                image.setTag(w.result);
            }
            generate.setEnabled(!w.busy && pack != null && tok != null);
            ui.postDelayed(this, 500);
        }
    };

    private void generate() {
        if (pack == null || tok == null) { toast("still loading"); return; }
        List<String> miss = pack.missing();
        if (!miss.isEmpty()) {
            toast("pack incomplete: " + miss.size() + " file(s). Download or Import first.");
            return;
        }
        final String text = prompt.getText().toString();
        final long sd = parse(seed.getText().toString(), 42);
        final int th = Integer.parseInt(threads.getSelectedItem().toString());
        Worker.I.start(this, () -> {
            long[] ids = tok.encode(text);
            Bitmap b = Engine.run(pack, ids, sd, th, Worker.I);
            Worker.I.result = b;
            String where = save(b);
            Worker.I.note = "saved " + where;
            Worker.I.log("saved " + where);
        });
    }

    private void download() {
        if (pack == null) { toast("still loading"); return; }
        Worker.I.start(this, () -> pack.download(Worker.I));
    }

    private void verify() {
        if (pack == null) { toast("still loading"); return; }
        Worker.I.start(this, () -> Worker.I.note = pack.verify(Worker.I));
    }

    private void selfTest() {
        if (pack == null || tok == null) { toast("still loading"); return; }
        Worker.I.start(this, () -> {
            Worker.I.log(tok.selfTest());
            Worker.I.log(Engine.runtimeTest(pack, 2));
            Worker.I.note = tok.selfTest();
        });
    }

    private void importFolder() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_TREE);
        } catch (Throwable t) { toast("no folder picker: " + t.getMessage()); }
    }

    private void allFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else toast("not needed on this Android version");
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_TREE || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) { }
        Worker.I.start(this, () -> {
            int n = pack.importTree(tree, Worker.I);
            Worker.I.note = "imported " + n + " file(s); " + pack.missing().size() + " still missing";
            Worker.I.log(Worker.I.note);
        });
    }

    private String save(Bitmap b) throws Exception {
        String name = "offlinedraw_" + System.currentTimeMillis() + ".png";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OfflineDraw");
                Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (u != null) {
                    OutputStream os = getContentResolver().openOutputStream(u);
                    b.compress(Bitmap.CompressFormat.PNG, 100, os);
                    Pack.close(os);
                    return "Pictures/OfflineDraw/" + name;
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "OfflineDraw");
                dir.mkdirs();
                File f = new File(dir, name);
                OutputStream os = new FileOutputStream(f);
                b.compress(Bitmap.CompressFormat.PNG, 100, os);
                Pack.close(os);
                android.media.MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()},
                        new String[]{"image/png"}, null);
                return f.getAbsolutePath();
            }
        } catch (Throwable ignored) { }
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "out");
        dir.mkdirs();
        File f = new File(dir, name);
        OutputStream os = new FileOutputStream(f);
        b.compress(Bitmap.CompressFormat.PNG, 100, os);
        Pack.close(os);
        return f.getAbsolutePath();
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            r.addView(v, lp);
        }
        return r;
    }

    private int dp(int v) { return (int) (getResources().getDisplayMetrics().density * v); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private static long parse(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Throwable t) { return def; }
    }
}
