package com.clippingalex.clipcaptions;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Pantalla unica de ClipCaptions: todo corre dentro del telefono. */
@androidx.media3.common.util.UnstableApi
public class MainActivity extends Activity implements Pipeline.Listener {
    private static final int REQ_VIDEO = 1;
    static final int BG = 0xFF0B0B14, CARD = 0xFF14141F, LINE = 0xFF26263A, TXT = 0xFFF4F4FB,
            MUT = 0xFF9A9AB5, PINK = 0xFFFF2D95, LIME = 0xFFB6FF00, CYAN = 0xFF00E5FF;

    private static final String[][] STYLES = {
            {"auto", "Automático", "Mezcla todos los estilos sin repetir"},
            {"impacto", "Impacto", "Mayúsculas gruesas con pop"},
            {"flujo", "Flujo", "Cursiva que se desliza"},
            {"neon", "Neón", "Brillo de color alrededor"},
            {"mixto", "Mixto", "Palabras clave grandes y en cursiva"},
            {"aparece", "Aparece", "Las palabras entran al decirse"},
            {"grueso", "Grueso", "Letra gorda con borde fuerte"}};
    private static final String[][] LANGS = {
            {"", "Detectar automático"}, {"es", "Español"}, {"en", "English"}, {"pt", "Português"},
            {"fr", "Français"}, {"it", "Italiano"}, {"de", "Deutsch"}};

    private SharedPreferences prefs;
    private Uri video;
    private String style = "auto";
    private Pipeline pipeline;
    private File resultFile;

    private LinearLayout home, progressCard, resultCard;
    private TextView pickName, styleDesc, error, progMsg, resInfo;
    private EditText keyField;
    private Spinner langSpinner;
    private ProgressBar bar;
    private VideoView player;
    private TextView startBtn;
    private final List<TextView> chips = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("clipcaptions", MODE_PRIVATE);
        style = prefs.getString("style", "auto");
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout root = col();
        root.setPadding(dp(18), dp(28), dp(18), dp(40));
        sv.addView(root);
        setContentView(sv);

        // cabecera
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text("CC", 19, TXT, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(round(PINK, 14));
        head.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout titles = col();
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text("ClipCaptions", 22, TXT, true));
        titles.addView(text("Subtítulos dinámicos para tus clips", 14, MUT, false));
        head.addView(titles);
        root.addView(head);
        space(root, 16);

        buildHome(root);
        buildProgress(root);
        buildResult(root);
        show(home);
    }

    // ---------------------------------------------------------------- pantallas

    private void buildHome(LinearLayout root) {
        home = card();
        root.addView(home);

        home.addView(label("Clave de Groq (solo para transcribir)"));
        keyField = new EditText(this);
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyField.setHint("gsk_...");
        keyField.setHintTextColor(MUT);
        keyField.setTextColor(TXT);
        keyField.setSingleLine(true);
        keyField.setBackground(round(0xFF1B1B2B, 12));
        keyField.setPadding(dp(14), dp(12), dp(14), dp(12));
        keyField.setText(prefs.getString("key", ""));
        home.addView(keyField);
        TextView link = text("Consigue una gratis en console.groq.com/keys", 13, CYAN, false);
        link.setPadding(0, dp(6), 0, 0);
        link.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")));
            }
        });
        home.addView(link);

        home.addView(label("Video"));
        LinearLayout pick = new LinearLayout(this);
        pick.setGravity(Gravity.CENTER_VERTICAL);
        pick.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable dash = round(0xFF121220, 16);
        dash.setStroke(dp(2), 0xFF3A3A58, dp(8), dp(5));
        pick.setBackground(dash);
        TextView plus = text("+", 26, CYAN, true);
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(round(0xFF22223A, 12));
        pick.addView(plus, new LinearLayout.LayoutParams(dp(44), dp(44)));
        pickName = text("Elige tu video (vertical u horizontal)", 16, TXT, true);
        pickName.setPadding(dp(14), 0, 0, 0);
        pick.addView(pickName);
        pick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("video/*");
                startActivityForResult(i, REQ_VIDEO);
            }
        });
        home.addView(pick);

        home.addView(label("Estilo"));
        LinearLayout row = null;
        for (int i = 0; i < STYLES.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                home.addView(row);
            }
            final String[] s = STYLES[i];
            TextView chip = text(s[1], 15, TXT, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(8), dp(11), dp(8), dp(11));
            chip.setTag(s[0]);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { setStyle(s[0]); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            row.addView(chip, lp);
            chips.add(chip);
        }
        styleDesc = text("", 13, MUT, false);
        styleDesc.setPadding(dp(4), dp(6), 0, 0);
        home.addView(styleDesc);
        setStyle(style);

        home.addView(label("Idioma del audio"));
        langSpinner = new Spinner(this);
        List<String> names = new ArrayList<>();
        for (String[] l : LANGS) names.add(l[1]);
        langSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        langSpinner.setBackground(round(0xFF1B1B2B, 12));
        String saved = prefs.getString("lang", "");
        for (int i = 0; i < LANGS.length; i++) if (LANGS[i][0].equals(saved)) langSpinner.setSelection(i);
        home.addView(langSpinner);

        startBtn = button("Crear subtítulos", true);
        startBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { start(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(20);
        home.addView(startBtn, blp);

        error = text("", 15, 0xFFFF6B81, true);
        error.setPadding(0, dp(12), 0, 0);
        error.setVisibility(View.GONE);
        home.addView(error);
    }

    private void buildProgress(LinearLayout root) {
        progressCard = card();
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.getProgressDrawable().setColorFilter(LIME, android.graphics.PorterDuff.Mode.SRC_IN);
        progressCard.addView(bar, new LinearLayout.LayoutParams(-1, dp(14)));
        progMsg = text("Empezando…", 17, TXT, true);
        progMsg.setPadding(0, dp(14), 0, 0);
        progressCard.addView(progMsg);
        TextView hint = text("Mantén la app abierta hasta que termine. Puede tardar un poco según el largo del video.", 13, MUT, false);
        hint.setPadding(0, dp(8), 0, 0);
        progressCard.addView(hint);
        TextView cancel = button("Cancelar", false);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (pipeline != null) pipeline.cancel(); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(16);
        progressCard.addView(cancel, clp);
        root.addView(progressCard);
    }

    private void buildResult(LinearLayout root) {
        resultCard = card();
        player = new VideoView(this);
        resultCard.addView(player, new LinearLayout.LayoutParams(-1, dp(420)));
        resInfo = text("", 13, MUT, false);
        resInfo.setPadding(0, dp(8), 0, 0);
        resultCard.addView(resInfo);
        TextView save = button("Guardar en la galería", true);
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveToGallery(); } });
        TextView share = button("Compartir", false);
        share.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { share(); } });
        TextView again = button("Hacer otro video", false);
        again.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                player.stopPlayback();
                video = null;
                pickName.setText("Elige tu video (vertical u horizontal)");
                show(home);
            }
        });
        for (TextView t : new TextView[]{save, share, again}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(12);
            resultCard.addView(t, lp);
        }
        root.addView(resultCard);
    }

    private void show(View card) {
        home.setVisibility(card == home ? View.VISIBLE : View.GONE);
        progressCard.setVisibility(card == progressCard ? View.VISIBLE : View.GONE);
        resultCard.setVisibility(card == resultCard ? View.VISIBLE : View.GONE);
        if (card == progressCard) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ---------------------------------------------------------------- acciones

    private void setStyle(String id) {
        style = id;
        for (TextView c : chips) {
            boolean on = id.equals(c.getTag());
            c.setBackground(round(on ? PINK : 0xFF1B1B2B, 999));
        }
        for (String[] s : STYLES) if (s[0].equals(id)) styleDesc.setText(s[2]);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VIDEO && res == RESULT_OK && data != null && data.getData() != null) {
            video = data.getData();
            String name = "video";
            try (Cursor c = getContentResolver().query(video, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (i >= 0) name = c.getString(i);
                }
            } catch (Exception ignored) { }
            pickName.setText(name + "\nToca para cambiar");
            error.setVisibility(View.GONE);
        }
    }

    private void start() {
        String key = keyField.getText().toString().trim();
        if (video == null) { fail("Elige un video primero."); return; }
        if (key.isEmpty()) { fail("Escribe tu clave de Groq (es gratis)."); return; }
        String lang = LANGS[langSpinner.getSelectedItemPosition()][0];
        prefs.edit().putString("key", key).putString("lang", lang).putString("style", style).apply();
        error.setVisibility(View.GONE);
        bar.setProgress(2);
        progMsg.setText("Empezando…");
        show(progressCard);
        pipeline = new Pipeline(this, this);
        pipeline.start(video, style, lang, key);
    }

    private void fail(String m) {
        error.setText(m);
        error.setVisibility(View.VISIBLE);
    }

    @Override
    public void onProgress(int pct, String msg) {
        bar.setProgress(pct);
        progMsg.setText(msg + "…");
    }

    @Override
    public void onDone(File out, int phrases, List<String> styles) {
        resultFile = out;
        show(resultCard);
        resInfo.setText(phrases + " frases · estilos: " + android.text.TextUtils.join(", ", styles));
        player.setVideoURI(Uri.fromFile(out));
        MediaController mc = new MediaController(this);
        mc.setAnchorView(player);
        player.setMediaController(mc);
        player.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
            public void onPrepared(android.media.MediaPlayer mp) { mp.setLooping(true); }
        });
        player.start();
    }

    @Override
    public void onError(String msg) {
        show(home);
        if (!"Cancelado".equals(msg)) fail(msg);
    }

    private void saveToGallery() {
        if (resultFile == null) return;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, "ClipCaptions_" + System.currentTimeMillis() + ".mp4");
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipCaptions");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            try (InputStream in = new FileInputStream(resultFile);
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            v.clear();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(uri, v, null, null);
            Toast.makeText(this, "Guardado en Películas/ClipCaptions", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo guardar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void share() {
        if (resultFile == null) return;
        Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", resultFile);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("video/mp4");
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Compartir video"));
    }

    @Override
    protected void onDestroy() {
        if (pipeline != null && isFinishing()) pipeline.cancel();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- ayudas de UI

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout l = col();
        l.setPadding(dp(16), dp(10), dp(16), dp(16));
        GradientDrawable g = round(CARD, 20);
        g.setStroke(dp(1), LINE);
        l.setBackground(g);
        return l;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s.toUpperCase(), 12, MUT, true);
        t.setLetterSpacing(0.05f);
        t.setPadding(0, dp(16), 0, dp(8));
        return t;
    }

    private TextView button(String s, boolean primary) {
        TextView t = text(s, 17, primary ? 0xFF0B0B14 : TXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(15), dp(12), dp(15));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                primary ? new int[]{LIME, CYAN} : new int[]{0xFF1F1F31, 0xFF1F1F31});
        g.setCornerRadius(dp(14));
        if (!primary) g.setStroke(dp(1), LINE);
        t.setBackground(g);
        return t;
    }

    private void space(LinearLayout l, int dp) {
        l.addView(new View(this), new LinearLayout.LayoutParams(1, dp(dp)));
    }
}
