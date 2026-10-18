package com.nexauren.archiver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.GridLayout;
import android.util.LruCache;
import android.os.ParcelFileDescriptor;
import android.os.Build;
import android.media.MediaMetadataRetriever;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.graphics.Canvas;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.content.pm.PackageInfo;
import android.content.pm.ApplicationInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.documentfile.provider.DocumentFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_TREE = 101;
    private static final String PREFS = "archiver_preferences";
    private static final String KEY_TREE = "tree_uri";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<DocumentFile> stack = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final Set<String> favorites = new HashSet<>();
    private final List<DocumentFile> clipboard = new ArrayList<>();
    private boolean clipboardIsCut = false;
    private boolean showHidden = false, showSize = true, showDate = true, showTime = false, descending = false;
    private boolean showThumbnails = true, showPdfThumbnails = false, showVideoThumbnails = false;
    private boolean showAlbumCovers = true, showApkIcons = true, squareThumbnails = false;
    private String sortBy = "Nome", viewMode = "Detalhado";
    private int compressionLevel = 2;
    private LinearLayout page, rows;
    private GridLayout gridRows;
    private TextView pathView, selectionView;
    private SharedPreferences prefs;
    private final LruCache<String, Bitmap> thumbnailCache = new LruCache<String, Bitmap>(1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> savedFavorites = prefs.getStringSet("favorites", null);
        if (savedFavorites != null) favorites.addAll(savedFavorites);
        showHidden = prefs.getBoolean("hidden", false);
        showSize = prefs.getBoolean("size", true);
        showDate = prefs.getBoolean("date", true);
        showTime = prefs.getBoolean("time", false);
        showThumbnails = prefs.getBoolean("thumbnails", true);
        showPdfThumbnails = prefs.getBoolean("thumbnails_pdf", false);
        showVideoThumbnails = prefs.getBoolean("thumbnails_video", false);
        showAlbumCovers = prefs.getBoolean("album_covers", true);
        showApkIcons = prefs.getBoolean("apk_icons", true);
        squareThumbnails = prefs.getBoolean("square_thumbnails", false);
        viewMode = prefs.getString("view_mode", "Detalhado");
        sortBy = prefs.getString("sort", "Nome");
        descending = prefs.getBoolean("descending", false);
        compressionLevel = prefs.getInt("compression", 2);
        buildScreen();
        String saved = prefs.getString(KEY_TREE, null);
        if (saved != null) {
            try {
                Uri uri = Uri.parse(saved);
                DocumentFile root = DocumentFile.fromTreeUri(this, uri);
                if (root != null && root.canRead()) {
                    stack.add(root);
                    refresh();
                    return;
                }
            } catch (Exception ignored) { }
        }
        promptForFolder();
    }

    private void buildScreen() {
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(8), dp(12), dp(8));
        page.setBackgroundColor(0xFF171717);
        setContentView(page);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(button("‹", v -> goUp()), new LinearLayout.LayoutParams(dp(46), dp(48)));
        pathView = new TextView(this);
        pathView.setTextColor(0xFFFFFFFF);
        pathView.setTextSize(18);
        pathView.setPadding(dp(8), 0, dp(8), 0);
        top.addView(pathView, new LinearLayout.LayoutParams(0, dp(48), 1));
        top.addView(button("⋮", v -> showMainMenu()), new LinearLayout.LayoutParams(dp(46), dp(48)));
        page.addView(top);
        selectionView = new TextView(this);
        selectionView.setTextColor(0xFFBDB8FF);
        selectionView.setTextSize(14);
        selectionView.setPadding(dp(8), dp(4), dp(8), dp(4));
        page.addView(selectionView);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(button("Pasta", v -> promptForFolder()), new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(button("+ Criar", v -> showCreateMenu()), new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(button("Colar", v -> paste()), new LinearLayout.LayoutParams(0, dp(44), 1));
        page.addView(actions);
        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(button("Comprimir seleção em ZIP", v -> promptZip()), new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }
    private int dp(float amount) { return (int)(amount * getResources().getDisplayMetrics().density + 0.5f); }
    private DocumentFile current() { return stack.isEmpty() ? null : stack.get(stack.size() - 1); }

    private void promptForFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_TREE); }
        catch (ActivityNotFoundException e) { toast("O seletor de pastas não está disponível neste dispositivo."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_TREE && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, data.getFlags() &
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            } catch (SecurityException e) { toast("Não foi possível guardar a permissão para esta pasta."); return; }
            prefs.edit().putString(KEY_TREE, uri.toString()).apply();
            DocumentFile root = DocumentFile.fromTreeUri(this, uri);
            stack.clear(); selected.clear();
            if (root != null) stack.add(root);
            refresh();
        }
    }

    private void refresh() {
        rows.removeAllViews();
        DocumentFile dir = current();
        if (dir == null) {
            pathView.setText("Escolha uma pasta");
            selectionView.setText("Selecione uma pasta para começar.");
            return;
        }
        pathView.setText(dir.getName() == null ? "Ficheiros" : dir.getName());
        gridRows = null;
        if ("Grelha".equals(viewMode)) {
            gridRows = new GridLayout(this);
            gridRows.setColumnCount(3);
            rows.addView(gridRows, new LinearLayout.LayoutParams(-1, -2));
        }
        List<DocumentFile> files = new ArrayList<>();
        DocumentFile[] children = dir.listFiles();
        if (children != null) for (DocumentFile f : children) {
            String name = f.getName() == null ? "" : f.getName();
            if (showHidden || !name.startsWith(".")) files.add(f);
        }
        Comparator<DocumentFile> cmp;
        if ("Tamanho".equals(sortBy)) cmp = Comparator.comparingLong(f -> f.isDirectory() ? -1L : f.length());
        else if ("Data".equals(sortBy)) cmp = Comparator.comparingLong(DocumentFile::lastModified);
        else if ("Tipo".equals(sortBy)) cmp = Comparator.comparing(f -> extension(f.getName()));
        else cmp = Comparator.comparing(f -> f.getName() == null ? "" : f.getName(), String.CASE_INSENSITIVE_ORDER);
        final Comparator<DocumentFile> sort = cmp;
        files.sort((a,b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            int c = sort.compare(a,b);
            return descending ? -c : c;
        });
        for (DocumentFile file : files) addRow(file);
        selectionView.setText(files.size() + " itens" + (selected.isEmpty() ? "" : " • " + selected.size() + " selecionado(s)"));
    }

    private void addRow(DocumentFile file) {
        String name = file.getName() == null ? "(sem nome)" : file.getName();
        String uriKey = file.getUri().toString();
        boolean isSelected = selected.contains(uriKey);
        boolean grid = gridRows != null;
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setOrientation(grid ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        item.setPadding(dp(grid ? 6 : 8), dp(grid ? 8 : ("Compacto".equals(viewMode) ? 5 : 10)),
                dp(grid ? 6 : 8), dp(grid ? 8 : ("Compacto".equals(viewMode) ? 5 : 10)));
        if (isSelected) item.setBackgroundColor(0xFF323044);

        if (grid) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(file.isDirectory() ? android.R.drawable.ic_menu_agenda : android.R.drawable.ic_menu_report_image);
            icon.setScaleType(squareThumbnails ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            int imageSize = dp(72);
            item.addView(icon, new LinearLayout.LayoutParams(imageSize, imageSize));
            if (shouldLoadThumbnail(file)) loadThumbnailAsync(file, icon);
        } else if (shouldLoadThumbnail(file)) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(android.R.drawable.ic_menu_report_image);
            icon.setScaleType(squareThumbnails ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            imageParams.rightMargin = dp(10);
            item.addView(icon, imageParams);
            loadThumbnailAsync(file, icon);
        }

        StringBuilder label = new StringBuilder(grid ? name : (file.isDirectory() ? "📁  " : iconFor(name) + "  ") + name);
        if (!grid && "Detalhado".equals(viewMode) && showSize)
            label.append("\n").append(file.isDirectory() ? "Pasta" : formatSize(file.length()));
        else if ("Compacto".equals(viewMode) && showSize && !file.isDirectory())
            label.append("  •  ").append(formatSize(file.length()));
        if (!grid && showDate && file.lastModified() > 0 && "Detalhado".equals(viewMode)) {
            Date modified = new Date(file.lastModified());
            label.append(showSize ? "   •   " : "\n");
            label.append(DateFormat.getDateInstance(DateFormat.SHORT).format(modified));
            if (showTime) label.append(" ").append(DateFormat.getTimeInstance(DateFormat.SHORT).format(modified));
        }
        if (isSelected) label.insert(0, "✓  ");
        TextView text = new TextView(this);
        text.setText(label.toString());
        text.setTextSize(grid ? 13 : ("Compacto".equals(viewMode) ? 14 : 16));
        text.setTextColor(isSelected ? 0xFFBDB8FF : 0xFFF2F2F2);
        text.setGravity(grid ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        text.setMaxLines(grid ? 2 : ("Detalhado".equals(viewMode) ? 3 : 1));
        if (grid) {
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-1, -2);
            textParams.topMargin = dp(5);
            item.addView(text, textParams);
        } else {
            item.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        }
        View.OnClickListener click = v -> {
            if (!selected.isEmpty()) {
                if (!selected.add(uriKey)) selected.remove(uriKey);
                refresh();
            } else if (file.isDirectory()) {
                stack.add(file);
                refresh();
            } else showFileActions(file);
        };
        item.setOnClickListener(click);
        item.setOnLongClickListener(v -> {
            selected.add(uriKey);
            refresh();
            showFileActions(file);
            return true;
        });
        if (grid) {
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            params.width = 0;
            params.height = -2;
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            gridRows.addView(item, params);
        } else {
            rows.addView(item, new LinearLayout.LayoutParams(-1, -2));
            if (!"Compacto".equals(viewMode)) {
                View divider = new View(this);
                divider.setBackgroundColor(0xFF333333);
                rows.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            }
        }
    }

    private boolean shouldLoadThumbnail(DocumentFile file) {
        if (!showThumbnails || file.isDirectory()) return false;
        String ext = extension(file.getName());
        String mime = getContentResolver().getType(file.getUri());
        if (isImageExtension(ext)) return true;
        if ("pdf".equals(ext)) return showPdfThumbnails;
        if (isVideoExtension(ext) || (mime != null && mime.startsWith("video/"))) return showVideoThumbnails;
        if (isAudioExtension(ext) || (mime != null && mime.startsWith("audio/"))) return showAlbumCovers;
        if ("apk".equals(ext)) return showApkIcons;
        return false;
    }

    private boolean isImageExtension(String ext) {
        return "jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext) ||
                "webp".equals(ext) || "gif".equals(ext) || "bmp".equals(ext) || "heic".equals(ext);
    }
    private boolean isVideoExtension(String ext) {
        return "mp4".equals(ext) || "mkv".equals(ext) || "webm".equals(ext) ||
                "3gp".equals(ext) || "avi".equals(ext) || "mov".equals(ext) || "m4v".equals(ext);
    }
    private boolean isAudioExtension(String ext) {
        return "mp3".equals(ext) || "wav".equals(ext) || "ogg".equals(ext) ||
                "m4a".equals(ext) || "flac".equals(ext) || "aac".equals(ext) || "opus".equals(ext);
    }

    private String thumbnailKey(DocumentFile file) {
        return file.getUri().toString() + ":" + file.lastModified() + ":" + file.length();
    }

    private void loadThumbnailAsync(DocumentFile file, ImageView view) {
        String key = thumbnailKey(file);
        view.setTag(key);
        Bitmap cached = thumbnailCache.get(key);
        if (cached != null) { view.setImageBitmap(cached); return; }
        worker.execute(() -> {
            Bitmap bitmap = loadThumbnail(file);
            if (bitmap != null) thumbnailCache.put(key, bitmap);
            runOnUiThread(() -> {
                if (!isFinishing() && key.equals(view.getTag()) && bitmap != null) view.setImageBitmap(bitmap);
            });
        });
    }

    private Bitmap loadThumbnail(DocumentFile file) {
        String ext = extension(file.getName());
        try {
            if (isImageExtension(ext)) return decodeScaledImage(file);
            if ("pdf".equals(ext) && showPdfThumbnails) return pdfThumbnail(file);
            if ((isVideoExtension(ext) || isMime(file, "video/")) && showVideoThumbnails) return videoThumbnail(file);
            if ((isAudioExtension(ext) || isMime(file, "audio/")) && showAlbumCovers) return albumThumbnail(file);
            if ("apk".equals(ext) && showApkIcons) return apkThumbnail(file);
        } catch (Exception ignored) { }
        return null;
    }

    private boolean isMime(DocumentFile file, String prefix) {
        String mime = getContentResolver().getType(file.getUri());
        return mime != null && mime.startsWith(prefix);
    }

    private Bitmap decodeScaledImage(DocumentFile file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream first = getContentResolver().openInputStream(file.getUri());
        if (first == null) return null;
        try { BitmapFactory.decodeStream(first, null, bounds); } finally { first.close(); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / opts.inSampleSize > 256 || bounds.outHeight / opts.inSampleSize > 256)
            opts.inSampleSize *= 2;
        InputStream second = getContentResolver().openInputStream(file.getUri());
        if (second == null) return null;
        Bitmap decoded;
        try { decoded = BitmapFactory.decodeStream(second, null, opts); } finally { second.close(); }
        return scaleThumbnail(decoded);
    }

    private Bitmap scaleThumbnail(Bitmap source) {
        if (source == null) return null;
        int max = dp(96);
        float ratio = Math.min(1f, Math.min(max / (float) source.getWidth(), max / (float) source.getHeight()));
        if (ratio >= 1f) return source;
        int w = Math.max(1, Math.round(source.getWidth() * ratio));
        int h = Math.max(1, Math.round(source.getHeight() * ratio));
        Bitmap scaled = Bitmap.createScaledBitmap(source, w, h, true);
        if (scaled != source) source.recycle();
        return scaled;
    }

    private Bitmap videoThumbnail(DocumentFile file) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, file.getUri());
            if (Build.VERSION.SDK_INT >= 27)
                return scaleThumbnail(retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 128, 128));
            return scaleThumbnail(retriever.getFrameAtTime(-1));
        } finally { retriever.release(); }
    }

    private Bitmap albumThumbnail(DocumentFile file) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, file.getUri());
            byte[] bytes = retriever.getEmbeddedPicture();
            if (bytes == null || bytes.length == 0) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / opts.inSampleSize > 256 || bounds.outHeight / opts.inSampleSize > 256)
                opts.inSampleSize *= 2;
            return scaleThumbnail(BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts));
        } finally { retriever.release(); }
    }

    private Bitmap pdfThumbnail(DocumentFile file) throws Exception {
        ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(file.getUri(), "r");
        if (descriptor == null) return null;
        try (ParcelFileDescriptor pfd = descriptor; PdfRenderer renderer = new PdfRenderer(pfd)) {
            if (renderer.getPageCount() < 1) return null;
            try (PdfRenderer.Page page = renderer.openPage(0)) {
                float ratio = Math.min(1f, 128f / Math.max(page.getWidth(), page.getHeight()));
                int width = Math.max(1, (int)(page.getWidth() * ratio));
                int height = Math.max(1, (int)(page.getHeight() * ratio));
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            }
        }
    }

    private Bitmap apkThumbnail(DocumentFile file) throws Exception {
        File temporary = File.createTempFile("archiver-app-icon-", ".apk", getCacheDir());
        try {
            InputStream raw = getContentResolver().openInputStream(file.getUri());
            if (raw == null) return null;
            try (InputStream in = new BufferedInputStream(raw);
                 OutputStream out = new BufferedOutputStream(new java.io.FileOutputStream(temporary))) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            PackageInfo info = getPackageManager().getPackageArchiveInfo(temporary.getAbsolutePath(), 0);
            if (info == null || info.applicationInfo == null) return null;
            ApplicationInfo appInfo = info.applicationInfo;
            appInfo.sourceDir = temporary.getAbsolutePath();
            appInfo.publicSourceDir = temporary.getAbsolutePath();
            Drawable icon = getPackageManager().getApplicationIcon(appInfo);
            Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            icon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            icon.draw(canvas);
            return bitmap;
        } finally { temporary.delete(); }
    }

    private String iconFor(String name) {
        String ext = extension(name);
        if ("zip".equals(ext) || "7z".equals(ext) || "rar".equals(ext) || "tar".equals(ext)) return "🗜";
        if ("jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext) || "webp".equals(ext)) return "🖼";
        if ("mp3".equals(ext) || "wav".equals(ext) || "ogg".equals(ext)) return "♫";
        if ("apk".equals(ext)) return "▣";
        return "📄";
    }
    private String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f MB", size / (1024.0 * 1024));
        return String.format(java.util.Locale.ROOT, "%.2f GB", size / (1024.0 * 1024 * 1024));
    }

    private void goUp() {
        if (stack.size() > 1) { stack.remove(stack.size() - 1); selected.clear(); refresh(); }
        else if (current() == null) promptForFolder();
        else toast("Já está na pasta escolhida.");
    }
    @Override public void onBackPressed() {
        if (stack.size() > 1) goUp(); else super.onBackPressed();
    }

    private List<DocumentFile> chosen() {
        List<DocumentFile> result = new ArrayList<>();
        DocumentFile dir = current();
        if (dir == null) return result;
        DocumentFile[] children = dir.listFiles();
        if (children != null) for (DocumentFile file : children)
            if (selected.contains(file.getUri().toString())) result.add(file);
        return result;
    }

    private void showMainMenu() {
        String[] options = {"Selecionar tudo", "Limpar seleção", "Copiar seleção", "Recortar seleção", "Excluir seleção", "Ordenar", "Mostrar/ocultar ficheiros ocultos", "Mostrar/ocultar tamanho", "Mostrar/ocultar data", "Compressão ZIP", "Atualizar", "Pesquisar em subpastas", "Definições do explorador", "Favoritos"};
        new AlertDialog.Builder(this).setTitle("Opções").setItems(options, (d, which) -> {
            switch (which) {
                case 0:
                    selected.clear();
                    if (current() != null) { DocumentFile[] all = current().listFiles(); if (all != null) for (DocumentFile f : all) selected.add(f.getUri().toString()); }
                    refresh(); break;
                case 1: selected.clear(); refresh(); break;
                case 2: stageClipboard(false); break;
                case 3: stageClipboard(true); break;
                case 4: confirmDeleteSelection(); break;
                case 5: showSortMenu(); break;
                case 6: showHidden = !showHidden; prefs.edit().putBoolean("hidden", showHidden).apply(); refresh(); break;
                case 7: showSize = !showSize; prefs.edit().putBoolean("size", showSize).apply(); refresh(); break;
                case 8: showDate = !showDate; prefs.edit().putBoolean("date", showDate).apply(); refresh(); break;
                case 9: showCompressionMenu(); break;
                case 10: refresh(); break;
                case 11: promptSearch(); break;
                case 12: showExplorerSettings(); break;
                case 13: showFavorites(); break;
            }
        }).show();
    }

    private void stageClipboard(boolean cut) {
        List<DocumentFile> items = chosen();
        if (items.isEmpty()) { toast("Selecione pelo menos um item primeiro."); return; }
        clipboard.clear();
        clipboard.addAll(items);
        clipboardIsCut = cut;
        selected.clear();
        refresh();
        toast(cut ? items.size() + " item(ns) preparados para mover. Abra o destino e toque em Colar."
                  : items.size() + " item(ns) preparados para copiar. Abra o destino e toque em Colar.");
    }

    private void confirmDeleteSelection() {
        List<DocumentFile> items = chosen();
        if (items.isEmpty()) { toast("Selecione pelo menos um item primeiro."); return; }
        new AlertDialog.Builder(this).setTitle("Excluir seleção")
            .setMessage("Eliminar " + items.size() + " item(ns)? Esta ação não pode ser anulada.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Excluir", (d, w) -> {
                selected.clear();
                runWork(() -> {
                    for (DocumentFile item : items) {
                        if (!deleteRecursively(item)) {
                            postResult("A eliminação parou em: " + item.getName(), false);
                            return;
                        }
                    }
                    postResult("Seleção eliminada.", true);
                });
            }).show();
    }

    private static final class SearchResult {
        final DocumentFile file;
        final String path;
        final List<DocumentFile> parents;
        SearchResult(DocumentFile file, String path, List<DocumentFile> parents) {
            this.file = file;
            this.path = path;
            this.parents = parents;
        }
    }

    private void promptSearch() {
        if (current() == null) { toast("Escolha uma pasta primeiro."); return; }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Nome ou padrão, por exemplo *.zip");
        new AlertDialog.Builder(this).setTitle("Pesquisar ficheiros")
            .setMessage("Pesquisa dentro da pasta atual e das respetivas subpastas.")
            .setView(input).setNegativeButton("Cancelar", null).setPositiveButton("Pesquisar", (d,w) -> {
                String query = input.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                if (query.isEmpty()) { toast("Escreva um nome ou padrão."); return; }
                final DocumentFile root = current();
                final List<DocumentFile> baseStack = new ArrayList<>(stack);
                toast("A pesquisar…");
                worker.execute(() -> {
                    List<SearchResult> hits = new ArrayList<>();
                    searchTree(root, query, "", new ArrayList<>(), hits, 0);
                    runOnUiThread(() -> {
                        if (hits.isEmpty()) { toast("Nenhum resultado encontrado."); return; }
                        String[] labels = new String[hits.size()];
                        for (int i = 0; i < hits.size(); i++) {
                            SearchResult hit = hits.get(i);
                            labels[i] = (hit.file.isDirectory() ? "[Pasta] " : "[Ficheiro] ") + hit.path;
                        }
                        new AlertDialog.Builder(this).setTitle("Resultados (" + hits.size() + ")")
                            .setItems(labels, (dialog, which) -> {
                                SearchResult hit = hits.get(which);
                                stack.clear();
                                stack.addAll(baseStack);
                                stack.addAll(hit.parents);
                                selected.clear();
                                refresh();
                                showFileActions(hit.file);
                            }).setPositiveButton("Fechar", null).show();
                    });
                });
            }).show();
    }

    private void searchTree(DocumentFile directory, String query, String prefix,
                            List<DocumentFile> parentPath, List<SearchResult> hits, int depth) {
        if (depth > 60 || hits.size() >= 300) return;
        DocumentFile[] children;
        try { children = directory.listFiles(); }
        catch (Exception e) { return; }
        if (children == null) return;
        for (DocumentFile child : children) {
            if (hits.size() >= 300) return;
            String name = child.getName() == null ? "" : child.getName();
            if (!showHidden && name.startsWith(".")) continue;
            String fullPath = prefix.isEmpty() ? name : prefix + "/" + name;
            if (matchesSearch(name.toLowerCase(java.util.Locale.ROOT), query)) {
                hits.add(new SearchResult(child, fullPath, new ArrayList<>(parentPath)));
            }
            if (child.isDirectory()) {
                List<DocumentFile> parents = new ArrayList<>(parentPath);
                parents.add(child);
                searchTree(child, query, fullPath, parents, hits, depth + 1);
            }
        }
    }

    private boolean matchesSearch(String name, String query) {
        String wildcard = query.indexOf('*') >= 0 ? query : "*" + query + "*";
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < wildcard.length(); i++) {
            char ch = wildcard.charAt(i);
            if (ch == '*') regex.append(".*");
            else regex.append(Pattern.quote(String.valueOf(ch)));
        }
        regex.append("$");
        return Pattern.matches(regex.toString(), name);
    }

    private void showExplorerSettings() {
        String[] options = {
            "Modo de exibição: " + viewMode,
            "Miniaturas: " + onOff(showThumbnails),
            "Miniaturas de PDF: " + onOff(showPdfThumbnails),
            "Miniaturas de vídeo: " + onOff(showVideoThumbnails),
            "Capa do álbum: " + onOff(showAlbumCovers),
            "Ícones do APK: " + onOff(showApkIcons),
            "Miniaturas quadradas: " + onOff(squareThumbnails),
            "Mostrar hora: " + onOff(showTime) + (showDate ? "" : " (ative Mostrar data primeiro)")
        };
        new AlertDialog.Builder(this).setTitle("Definições do explorador")
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: showViewModeMenu(); return;
                    case 1: showThumbnails = !showThumbnails; prefs.edit().putBoolean("thumbnails", showThumbnails).apply(); break;
                    case 2: showPdfThumbnails = !showPdfThumbnails; prefs.edit().putBoolean("thumbnails_pdf", showPdfThumbnails).apply(); break;
                    case 3: showVideoThumbnails = !showVideoThumbnails; prefs.edit().putBoolean("thumbnails_video", showVideoThumbnails).apply(); break;
                    case 4: showAlbumCovers = !showAlbumCovers; prefs.edit().putBoolean("album_covers", showAlbumCovers).apply(); break;
                    case 5: showApkIcons = !showApkIcons; prefs.edit().putBoolean("apk_icons", showApkIcons).apply(); break;
                    case 6: squareThumbnails = !squareThumbnails; prefs.edit().putBoolean("square_thumbnails", squareThumbnails).apply(); break;
                    case 7:
                        if (!showDate) { toast("Ative primeiro a opção Mostrar data no menu principal."); return; }
                        showTime = !showTime; prefs.edit().putBoolean("time", showTime).apply(); break;
                }
                refresh();
                showExplorerSettings();
            }).setNegativeButton("Fechar", null).show();
    }

    private String onOff(boolean value) { return value ? "Ligado" : "Desligado"; }

    private void showViewModeMenu() {
        String[] modes = {"Detalhado", "Compacto", "Grelha"};
        int checked = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(viewMode)) checked = i;
        new AlertDialog.Builder(this).setTitle("Modo de exibição")
            .setSingleChoiceItems(modes, checked, (dialog, which) -> {
                viewMode = modes[which];
                prefs.edit().putString("view_mode", viewMode).apply();
                dialog.dismiss();
                refresh();
                showExplorerSettings();
            }).setNegativeButton("Cancelar", null).show();
    }

    private void showSortMenu() {
        String[] options = {"Nome", "Tamanho", "Data", "Tipo", descending ? "Ordem crescente" : "Ordem decrescente"};
        new AlertDialog.Builder(this).setTitle("Ordenar ficheiros").setItems(options, (d, which) -> {
            if (which < 4) sortBy = options[which]; else descending = !descending;
            prefs.edit().putString("sort", sortBy).putBoolean("descending", descending).apply();
            refresh();
        }).show();
    }
    private void showCompressionMenu() {
        String[] options = {"Sem compressão (mais rápido)", "Rápido", "Normal", "Ultra (mais lento)"};
        new AlertDialog.Builder(this).setTitle("Compressão ZIP").setSingleChoiceItems(options, compressionLevel, (dialog, which) -> {
            compressionLevel = which; prefs.edit().putInt("compression", which).apply();
            dialog.dismiss(); toast("Nível de compressão guardado.");
        }).setNegativeButton("Cancelar", null).show();
    }

    private void showCreateMenu() {
        new AlertDialog.Builder(this).setTitle("Criar").setItems(new String[]{"Nova pasta", "Novo ficheiro vazio", "Criar arquivo ZIP", "Criar arquivo 7Z"}, (d, which) -> {
            if (which == 0) promptNewItem(true);
            else if (which == 1) promptNewItem(false);
            else if (which == 2) promptZip();
            else promptSevenZip(chosen());
        }).show();
    }
    private void promptNewItem(boolean folder) {
        if (current() == null) { toast("Escolha uma pasta primeiro."); return; }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(folder ? "Nome da pasta" : "Nome do ficheiro");
        new AlertDialog.Builder(this).setTitle(folder ? "Nova pasta" : "Novo ficheiro").setView(input)
            .setNegativeButton("Cancelar", null).setPositiveButton("Criar", (d,w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty() || name.contains("/") || name.equals(".") || name.equals("..")) { toast("Nome inválido."); return; }
                try {
                    DocumentFile dir = current();
                    DocumentFile made = folder ? dir.createDirectory(name) : dir.createFile("application/octet-stream", name);
                    if (made == null) toast("Não foi possível criar. Verifique permissões, espaço e se o nome já existe.");
                    refresh();
                } catch (Exception e) { toast("Erro ao criar: " + safeMessage(e)); }
            }).show();
    }

    private String relativeFavoritePath(DocumentFile file) {
        StringBuilder path = new StringBuilder();
        for (int i = 1; i < stack.size(); i++) {
            String segment = stack.get(i).getName();
            if (segment != null && !segment.isEmpty()) {
                if (path.length() > 0) path.append('/');
                path.append(segment);
            }
        }
        if (file != null && file.getName() != null) {
            if (path.length() > 0) path.append('/');
            path.append(file.getName());
        }
        return path.toString();
    }

    private String favoriteKey(DocumentFile file) {
        String tree = prefs.getString(KEY_TREE, "");
        return tree + "\n" + relativeFavoritePath(file);
    }

    private void addFavorite(DocumentFile file) {
        favorites.add(favoriteKey(file));
        prefs.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
        toast("Adicionado aos Favoritos.");
    }

    private void removeFavorite(DocumentFile file) {
        favorites.remove(favoriteKey(file));
        prefs.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
        toast("Removido dos Favoritos.");
    }

    private void showFavorites() {
        if (favorites.isEmpty()) { toast("Ainda não existem Favoritos."); return; }
        List<String> items = new ArrayList<>(favorites);
        Collections.sort(items, String.CASE_INSENSITIVE_ORDER);
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            String[] parts = items.get(i).split("\\n", 2);
            String path = parts.length > 1 ? parts[1] : parts[0];
            String rootName = "Armazenamento";
            if (parts.length > 1) {
                try {
                    DocumentFile root = DocumentFile.fromTreeUri(this, Uri.parse(parts[0]));
                    if (root != null && root.getName() != null) rootName = root.getName();
                } catch (Exception ignored) { }
            }
            labels[i] = rootName + (path.isEmpty() ? "" : "/" + path);
        }
        new AlertDialog.Builder(this).setTitle("Favoritos").setItems(labels, (dialog, which) -> openFavorite(items.get(which)))
            .setNegativeButton("Fechar", null).show();
    }

    private void openFavorite(String key) {
        String[] parts = key.split("\\n", 2);
        if (parts.length != 2 || parts[0].isEmpty()) { toast("Este Favorito já não está disponível."); return; }
        DocumentFile root;
        try { root = DocumentFile.fromTreeUri(this, Uri.parse(parts[0])); }
        catch (Exception e) { root = null; }
        if (root == null || !root.canRead()) { toast("A pasta guardada já não está acessível."); return; }
        stack.clear();
        stack.add(root);
        selected.clear();
        if (parts[1].isEmpty()) { refresh(); return; }
        String[] segments = parts[1].split("/");
        for (int i = 0; i < segments.length; i++) {
            DocumentFile parent = current();
            DocumentFile found = null;
            DocumentFile[] children = parent.listFiles();
            if (children != null) for (DocumentFile child : children) {
                if (segments[i].equals(child.getName())) { found = child; break; }
            }
            if (found == null) { toast("Não foi encontrado: " + segments[i]); refresh(); return; }
            boolean last = i == segments.length - 1;
            if (last && !found.isDirectory()) {
                refresh();
                showFileActions(found);
                return;
            }
            if (!found.isDirectory()) { toast("O percurso guardado já não corresponde a uma pasta."); refresh(); return; }
            stack.add(found);
        }
        refresh();
    }

    private static final class ArchiveListItem {
        final int index;
        final String name;
        final boolean directory;
        final long size;
        ArchiveListItem(int index, String name, boolean directory, long size) {
            this.index = index;
            this.name = name;
            this.directory = directory;
            this.size = size;
        }
    }

    private void browseArchive(DocumentFile archive) {
        String ext = extension(archive.getName());
        if (!"zip".equals(ext) && !"7z".equals(ext)) {
            toast("A visualização interna está disponível para ZIP e 7Z."); return;
        }
        runWork(() -> {
            File temporary = null;
            List<ArchiveListItem> entries = new ArrayList<>();
            try {
                temporary = copyUriToTemp(archive.getUri(), "." + ext);
                if ("zip".equals(ext)) {
                    try (ZipFile zip = new ZipFile(temporary)) {
                        java.util.Enumeration<? extends ZipEntry> enumeration = zip.entries();
                        int index = 0;
                        while (enumeration.hasMoreElements() && entries.size() < 300) {
                            ZipEntry entry = enumeration.nextElement();
                            entries.add(new ArchiveListItem(index++, entry.getName(), entry.isDirectory(), entry.getSize()));
                        }
                    }
                } else {
                    try (SevenZFile seven = new SevenZFile(temporary)) {
                        int index = 0;
                        for (SevenZArchiveEntry entry : seven.getEntries()) {
                            if (entries.size() >= 300) break;
                            entries.add(new ArchiveListItem(index++, entry.getName(), entry.isDirectory(), entry.getSize()));
                        }
                    }
                }
            } catch (Exception e) {
                postResult("Não foi possível ler o arquivo: " + safeMessage(e), false);
                return;
            } finally {
                if (temporary != null) temporary.delete();
            }
            final List<ArchiveListItem> found = entries;
            runOnUiThread(() -> {
                if (found.isEmpty()) {
                    new AlertDialog.Builder(this).setTitle(archive.getName())
                        .setMessage("O arquivo está vazio ou não contém entradas legíveis.")
                        .setPositiveButton("OK", null).show();
                    return;
                }
                String[] labels = new String[found.size()];
                for (int i = 0; i < found.size(); i++) {
                    ArchiveListItem item = found.get(i);
                    labels[i] = (item.directory ? "📁  " : "📄  ") + item.name +
                        (!item.directory && item.size >= 0 ? "  •  " + formatSize(item.size) : "");
                }
                String title = archive.getName() + (found.size() == 300 ? " (primeiras 300 entradas)" : "");
                new AlertDialog.Builder(this).setTitle(title).setItems(labels, (dialog, which) -> {
                    ArchiveListItem item = found.get(which);
                    if (item.directory) {
                        new AlertDialog.Builder(this).setTitle("Pasta do arquivo")
                            .setMessage("Para extrair uma pasta e todo o seu conteúdo, use «Extrair aqui» ou «Extrair para nova pasta».")
                            .setPositiveButton("OK", null).show();
                    } else {
                        new AlertDialog.Builder(this).setTitle("Extrair item")
                            .setMessage("Extrair " + item.name + " para a pasta atual?")
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Extrair", (d, w) -> extractArchiveEntry(archive, item.index))
                            .show();
                    }
                }).setPositiveButton("Fechar", null).show();
            });
        });
    }

    private void extractArchiveEntry(DocumentFile archive, int wantedIndex) {
        final DocumentFile target = current();
        if (target == null) { toast("Escolha a pasta de destino."); return; }
        String ext = extension(archive.getName());
        runWork(() -> {
            File temporary = null;
            DocumentFile output = null;
            try {
                temporary = copyUriToTemp(archive.getUri(), "." + ext);
                if ("zip".equals(ext)) {
                    try (ZipFile zip = new ZipFile(temporary)) {
                        java.util.Enumeration<? extends ZipEntry> enumeration = zip.entries();
                        int index = 0;
                        while (enumeration.hasMoreElements()) {
                            ZipEntry entry = enumeration.nextElement();
                            if (index++ != wantedIndex) continue;
                            String path = safeZipPath(entry.getName());
                            if (path == null) throw new IOException("O arquivo contém um caminho inseguro.");
                            output = createTree(target, path, entry.isDirectory());
                            if (!entry.isDirectory()) {
                                InputStream raw = zip.getInputStream(entry);
                                OutputStream outRaw = getContentResolver().openOutputStream(output.getUri());
                                if (outRaw == null) { raw.close(); throw new IOException("Não foi possível criar o destino."); }
                                try (InputStream in = new BufferedInputStream(raw);
                                     OutputStream out = new BufferedOutputStream(outRaw)) {
                                    byte[] buffer = new byte[32768];
                                    int n;
                                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                                }
                            }
                            postResult("Item extraído: " + path, true);
                            return;
                        }
                    }
                } else if ("7z".equals(ext)) {
                    try (SevenZFile seven = new SevenZFile(temporary)) {
                        SevenZArchiveEntry entry;
                        int index = 0;
                        byte[] buffer = new byte[32768];
                        while ((entry = seven.getNextEntry()) != null) {
                            if (index++ != wantedIndex) {
                                while (seven.read(buffer, 0, buffer.length) != -1) { }
                                continue;
                            }
                            String path = safeZipPath(entry.getName());
                            if (path == null) throw new IOException("O 7Z contém um caminho inseguro.");
                            output = createTree(target, path, entry.isDirectory());
                            if (!entry.isDirectory()) {
                                OutputStream outRaw = getContentResolver().openOutputStream(output.getUri());
                                if (outRaw == null) throw new IOException("Não foi possível criar o destino.");
                                try (OutputStream out = new BufferedOutputStream(outRaw)) {
                                    int n;
                                    while ((n = seven.read(buffer, 0, buffer.length)) != -1) out.write(buffer, 0, n);
                                }
                            }
                            postResult("Item extraído: " + path, true);
                            return;
                        }
                    }
                } else {
                    throw new IOException("Formato não suportado.");
                }
                throw new IOException("A entrada selecionada já não existe.");
            } catch (Exception e) {
                if (output != null && !output.isDirectory()) deleteRecursively(output);
                postResult("Falha ao extrair o item: " + safeMessage(e), false);
            } finally {
                if (temporary != null) temporary.delete();
            }
        });
    }

    private void showFileActions(DocumentFile file) {
        String[] options;
        if (file.isDirectory()) options = new String[]{"Abrir pasta", "Comprimir em ZIP", "Comprimir em 7Z", "Copiar", "Recortar", "Renomear", "Excluir", "Informações"};
        else if ("zip".equals(extension(file.getName())) || "7z".equals(extension(file.getName()))) options = new String[]{"Abrir", "Ver conteúdo", "Testar arquivo", "Extrair aqui", "Extrair para nova pasta", "Partilhar", "Copiar", "Recortar", "Renomear", "Excluir", "Informações"};
        else options = new String[]{"Abrir", "Partilhar", "Copiar", "Recortar", "Renomear", "Excluir", "Informações"};
        List<String> allOptions = new ArrayList<>();
        Collections.addAll(allOptions, options);
        String favoriteKey = favoriteKey(file);
        allOptions.add(favorites.contains(favoriteKey) ? "Remover dos Favoritos" : "Adicionar aos Favoritos");
        final String[] actionOptions = allOptions.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(file.getName()).setItems(actionOptions, (d,which) -> {
            String op = actionOptions[which];
            if ("Abrir pasta".equals(op)) { stack.add(file); selected.clear(); refresh(); }
            else if ("Abrir".equals(op)) openFile(file);
            else if ("Ver conteúdo".equals(op)) browseArchive(file);
            else if ("Comprimir em ZIP".equals(op)) promptZip(chosenOrSingle(file));
            else if ("Comprimir em 7Z".equals(op)) promptSevenZip(chosenOrSingle(file));
            else if ("Testar arquivo".equals(op)) { if ("7z".equals(extension(file.getName()))) testSevenZip(file); else testZip(file); }
            else if ("Extrair aqui".equals(op)) { if ("7z".equals(extension(file.getName()))) extractSevenZip(file, current(), false); else extract(file, current(), false); }
            else if ("Extrair para nova pasta".equals(op)) { if ("7z".equals(extension(file.getName()))) extractSevenZip(file, current(), true); else extract(file, current(), true); }
            else if ("Partilhar".equals(op)) shareFile(file);
            else if ("Adicionar aos Favoritos".equals(op)) addFavorite(file);
            else if ("Remover dos Favoritos".equals(op)) removeFavorite(file);
            else if ("Copiar".equals(op) || "Recortar".equals(op)) {
                List<DocumentFile> items = chosen();
                if (items.isEmpty()) items.add(file);
                clipboard.clear(); clipboard.addAll(items); clipboardIsCut = "Recortar".equals(op);
                selected.clear(); refresh();
                toast(clipboardIsCut ? items.size() + " item(ns) preparados para mover." : items.size() + " item(ns) preparados para copiar.");
            } else if ("Renomear".equals(op)) promptRename(file);
            else if ("Excluir".equals(op)) confirmDelete(file);
            else showInfo(file);
        }).show();
    }

    private void openFile(DocumentFile file) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            String type = getContentResolver().getType(file.getUri());
            intent.setDataAndType(file.getUri(), type == null ? "*/*" : type);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (ActivityNotFoundException e) { toast("Não existe uma aplicação para abrir este ficheiro."); }
        catch (Exception e) { toast("Não foi possível abrir: " + safeMessage(e)); }
    }

    private void promptRename(DocumentFile file) {
        EditText input = new EditText(this);
        input.setSingleLine(true); input.setText(file.getName());
        new AlertDialog.Builder(this).setTitle("Renomear").setView(input)
            .setNegativeButton("Cancelar", null).setPositiveButton("Guardar", (d,w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty() || name.contains("/") || name.equals(".") || name.equals("..")) { toast("Nome inválido."); return; }
                if (!file.renameTo(name)) toast("Não foi possível renomear este item.");
                refresh();
            }).show();
    }
    private void confirmDelete(DocumentFile file) {
        new AlertDialog.Builder(this).setTitle("Excluir")
            .setMessage("Eliminar o item " + file.getName() + "? Esta ação não pode ser anulada.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Excluir", (d,w) -> runWork(() -> {
                boolean ok = deleteRecursively(file);
                postResult(ok ? "Item eliminado." : "Não foi possível eliminar o item.", ok);
            })).show();
    }
    private boolean deleteRecursively(DocumentFile file) {
        try {
            if (file.isDirectory()) {
                DocumentFile[] children = file.listFiles();
                if (children != null) for (DocumentFile child : children) if (!deleteRecursively(child)) return false;
            }
            return file.delete();
        } catch (Exception e) { return false; }
    }
    private void showInfo(DocumentFile file) {
        String msg = "Nome: " + file.getName() + "\nTipo: " + (file.isDirectory() ? "Pasta" : "Ficheiro") +
            "\nTamanho: " + (file.isDirectory() ? "—" : formatSize(file.length())) +
            "\nModificado: " + (file.lastModified() > 0 ? DateFormat.getDateTimeInstance().format(new Date(file.lastModified())) : "desconhecido") +
            "\nPode ler: " + file.canRead() + "\nPode escrever: " + file.canWrite();
        new AlertDialog.Builder(this).setTitle("Informações").setMessage(msg).setPositiveButton("OK", null).show();
    }

    private List<DocumentFile> chosenOrSingle(DocumentFile fallback) {
        List<DocumentFile> items = chosen();
        if (items.isEmpty()) items.add(fallback);
        return items;
    }

    private void promptZip() { promptZip(chosen()); }
    private void promptZip(List<DocumentFile> files) {
        if (current() == null || files == null || files.isEmpty()) { toast("Selecione pelo menos um ficheiro ou pasta."); return; }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        String suggested = files.size() == 1 ? files.get(0).getName() : "Arquivo";
        if (suggested == null) suggested = "Arquivo";
        if (!suggested.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) suggested += ".zip";
        input.setText(suggested);
        CheckBox deleteOriginals = new CheckBox(this);
        deleteOriginals.setText("Excluir originais depois de comprimir com sucesso");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.addView(input);
        form.addView(deleteOriginals);
        new AlertDialog.Builder(this).setTitle("Criar arquivo ZIP")
            .setMessage("Formato ZIP • Compressão: " + compressionName()).setView(form)
            .setNegativeButton("Cancelar", null).setPositiveButton("Criar", (d,w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) { toast("Indique um nome."); return; }
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) name += ".zip";
                try { name = uniqueName(current(), name); }
                catch (IOException e) { toast("Não foi possível verificar o nome de destino."); return; }
                DocumentFile out = current().createFile("application/zip", name);
                if (out == null) { toast("Não foi possível criar o ZIP. Confirme o nome e as permissões."); return; }
                final List<DocumentFile> sources = new ArrayList<>(files);
                final DocumentFile target = out;
                final boolean removeOriginals = deleteOriginals.isChecked();
                selected.clear();
                runWork(() -> {
                    try {
                        OutputStream stream = getContentResolver().openOutputStream(target.getUri());
                        if (stream == null) throw new IOException("Não foi possível abrir o destino.");
                        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(stream))) {
                            if (compressionLevel == 0) zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
                            else if (compressionLevel == 1) zip.setLevel(java.util.zip.Deflater.BEST_SPEED);
                            else if (compressionLevel == 3) zip.setLevel(java.util.zip.Deflater.BEST_COMPRESSION);
                            else zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);
                            for (DocumentFile src : sources) addToZip(zip, src, src.getName() == null ? "item" : src.getName(), target.getUri());
                        }
                    } catch (Exception e) {
                        deleteRecursively(target);
                        postResult("Erro ao comprimir: " + safeMessage(e), false);
                        return;
                    }
                    if (removeOriginals) {
                        for (DocumentFile src : sources) {
                            if (!deleteRecursively(src)) {
                                postResult("ZIP criado, mas não foi possível eliminar a origem " + src.getName() + ".", false);
                                return;
                            }
                        }
                        postResult("ZIP criado e originais eliminados.", true);
                    } else {
                        postResult("ZIP criado: " + target.getName(), true);
                    }
                });
            }).show();
    }

    private void promptSevenZip(List<DocumentFile> files) {
        if (current() == null || files == null || files.isEmpty()) {
            toast("Selecione pelo menos um ficheiro ou pasta."); return;
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        String suggested = files.size() == 1 ? files.get(0).getName() : "Arquivo";
        if (suggested == null) suggested = "Arquivo";
        if (!suggested.toLowerCase(java.util.Locale.ROOT).endsWith(".7z")) suggested += ".7z";
        input.setText(suggested);
        CheckBox deleteOriginals = new CheckBox(this);
        deleteOriginals.setText("Excluir originais depois de comprimir com sucesso");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.addView(input);
        form.addView(deleteOriginals);
        new AlertDialog.Builder(this).setTitle("Criar arquivo 7Z")
            .setMessage("Formato 7Z • Compressão: " + compressionName()).setView(form)
            .setNegativeButton("Cancelar", null).setPositiveButton("Criar", (d,w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) { toast("Indique um nome."); return; }
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".7z")) name += ".7z";
                try { name = uniqueName(current(), name); }
                catch (IOException e) { toast("Não foi possível verificar o nome de destino."); return; }
                DocumentFile out = current().createFile("application/x-7z-compressed", name);
                if (out == null) { toast("Não foi possível criar o 7Z neste local."); return; }
                final DocumentFile target = out;
                final List<DocumentFile> sources = new ArrayList<>(files);
                final boolean removeOriginals = deleteOriginals.isChecked();
                selected.clear();
                runWork(() -> {
                    File temporary = null;
                    try {
                        temporary = File.createTempFile("archiver-create-", ".7z", getCacheDir());
                        try (SevenZOutputFile seven = new SevenZOutputFile(temporary)) {
                            if (compressionLevel == 0) seven.setContentCompression(SevenZMethod.COPY);
                            else if (compressionLevel == 1) seven.setContentCompression(SevenZMethod.DEFLATE);
                            else if (compressionLevel == 3) seven.setContentCompression(SevenZMethod.BZIP2);
                            else seven.setContentCompression(SevenZMethod.LZMA2);
                            for (DocumentFile source : sources) {
                                String entryName = source.getName() == null ? "item" : source.getName();
                                addToSevenZip(seven, source, entryName, target.getUri());
                            }
                        }
                        InputStream inputStream = new BufferedInputStream(new FileInputStream(temporary));
                        OutputStream rawOut = getContentResolver().openOutputStream(target.getUri());
                        if (rawOut == null) { inputStream.close(); throw new IOException("Não foi possível escrever o arquivo no armazenamento."); }
                        try (InputStream in = inputStream; OutputStream outStream = new BufferedOutputStream(rawOut)) {
                            byte[] buffer = new byte[32768];
                            int n;
                            while ((n = in.read(buffer)) != -1) outStream.write(buffer, 0, n);
                        }
                    } catch (Exception e) {
                        deleteRecursively(target);
                        postResult("Erro ao criar 7Z: " + safeMessage(e), false);
                        return;
                    } finally {
                        if (temporary != null) temporary.delete();
                    }
                    if (removeOriginals) {
                        for (DocumentFile source : sources) {
                            if (!deleteRecursively(source)) {
                                postResult("7Z criado, mas não foi possível eliminar " + source.getName() + ".", false);
                                return;
                            }
                        }
                        postResult("7Z criado e originais eliminados.", true);
                    } else postResult("Arquivo 7Z criado: " + target.getName(), true);
                });
            }).show();
    }

    private void addToSevenZip(SevenZOutputFile seven, DocumentFile file, String entryName, Uri archiveUri) throws IOException {
        if (file.getUri().equals(archiveUri)) return;
        SevenZArchiveEntry entry = new SevenZArchiveEntry();
        entry.setName(entryName);
        entry.setLastModifiedDate(new Date(Math.max(0L, file.lastModified())));
        if (file.isDirectory()) {
            entry.setDirectory(true);
            entry.setHasStream(false);
            seven.putArchiveEntry(entry);
            seven.closeArchiveEntry();
            DocumentFile[] children = file.listFiles();
            if (children != null) for (DocumentFile child : children) {
                String name = child.getName() == null ? "item" : child.getName();
                addToSevenZip(seven, child, entryName + "/" + name, archiveUri);
            }
        } else {
            entry.setDirectory(false);
            entry.setHasStream(true);
            entry.setSize(file.length());
            seven.putArchiveEntry(entry);
            InputStream raw = getContentResolver().openInputStream(file.getUri());
            if (raw == null) throw new IOException("Não foi possível ler " + file.getName());
            try (InputStream in = new BufferedInputStream(raw)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) seven.write(buffer, 0, n);
            }
            seven.closeArchiveEntry();
        }
    }

    private File copyUriToTemp(Uri uri, String suffix) throws IOException {
        File temporary = File.createTempFile("archiver-read-", suffix, getCacheDir());
        boolean ok = false;
        try {
            InputStream raw = getContentResolver().openInputStream(uri);
            if (raw == null) throw new IOException("Não foi possível ler o arquivo.");
            try (InputStream in = new BufferedInputStream(raw);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(temporary))) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            ok = true;
            return temporary;
        } finally {
            if (!ok) temporary.delete();
        }
    }

    private void testSevenZip(DocumentFile archive) {
        runWork(() -> {
            File temporary = null;
            int count = 0;
            try {
                temporary = copyUriToTemp(archive.getUri(), ".7z");
                try (SevenZFile seven = new SevenZFile(temporary)) {
                    SevenZArchiveEntry entry;
                    byte[] buffer = new byte[32768];
                    while ((entry = seven.getNextEntry()) != null) {
                        int n;
                        while ((n = seven.read(buffer, 0, buffer.length)) != -1) { }
                        count++;
                    }
                }
            } catch (Exception e) {
                postResult("O 7Z falhou no teste: " + safeMessage(e), false);
                return;
            } finally {
                if (temporary != null) temporary.delete();
            }
            postResult("Teste 7Z concluído. Entradas verificadas: " + count + ".", true);
        });
    }

    private void extractSevenZip(DocumentFile archive, DocumentFile targetDir, boolean makeSubfolder) {
        if (targetDir == null || !"7z".equals(extension(archive.getName()))) {
            toast("Selecione um arquivo .7z e uma pasta de destino."); return;
        }
        DocumentFile destination = targetDir;
        if (makeSubfolder) {
            String name = archive.getName() == null ? "Arquivo" : archive.getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            try { name = uniqueName(targetDir, name); }
            catch (IOException e) { toast("Não foi possível verificar a pasta de destino."); return; }
            destination = targetDir.createDirectory(name);
            if (destination == null) { toast("Não foi possível criar a pasta de destino."); return; }
        }
        final DocumentFile target = destination;
        runWork(() -> {
            File temporary = null;
            int count = 0;
            try {
                temporary = copyUriToTemp(archive.getUri(), ".7z");
                try (SevenZFile seven = new SevenZFile(temporary)) {
                    SevenZArchiveEntry entry;
                    byte[] buffer = new byte[32768];
                    while ((entry = seven.getNextEntry()) != null) {
                        String path = safeZipPath(entry.getName());
                        if (path == null) throw new IOException("O 7Z contém um caminho inseguro.");
                        DocumentFile out = createTree(target, path, entry.isDirectory());
                        if (!entry.isDirectory()) {
                            OutputStream raw = getContentResolver().openOutputStream(out.getUri());
                            if (raw == null) throw new IOException("Não foi possível escrever: " + path);
                            try (OutputStream outStream = new BufferedOutputStream(raw)) {
                                int n;
                                while ((n = seven.read(buffer, 0, buffer.length)) != -1) outStream.write(buffer, 0, n);
                            }
                        } else {
                            // Directories are created by createTree; no entry data to write.
                        }
                        count++;
                    }
                }
            } catch (Exception e) {
                postResult("Erro na extração 7Z: " + safeMessage(e) + " (" + count + " entradas processadas).", false);
                return;
            } finally {
                if (temporary != null) temporary.delete();
            }
            postResult("Extração 7Z concluída: " + count + " entradas.", true);
        });
    }

    private void testZip(DocumentFile archive) {
        runWork(() -> {
            int count = 0;
            try {
                InputStream raw = getContentResolver().openInputStream(archive.getUri());
                if (raw == null) throw new IOException("Não foi possível abrir o arquivo.");
                try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
                    ZipEntry entry;
                    byte[] buffer = new byte[32768];
                    while ((entry = zip.getNextEntry()) != null) {
                        int n;
                        while ((n = zip.read(buffer)) != -1) { }
                        zip.closeEntry();
                        count++;
                    }
                }
            } catch (Exception e) {
                postResult("O ZIP falhou no teste: " + safeMessage(e), false);
                return;
            }
            postResult("Teste ZIP concluído. Entradas verificadas: " + count + ".", true);
        });
    }

    private void shareFile(DocumentFile file) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            String mime = getContentResolver().getType(file.getUri());
            send.setType(mime == null ? "*/*" : mime);
            send.putExtra(Intent.EXTRA_STREAM, file.getUri());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Partilhar ficheiro"));
        } catch (Exception e) {
            toast("Não foi possível partilhar: " + safeMessage(e));
        }
    }

    private String compressionName() {
        if (compressionLevel == 0) return "sem compressão";
        if (compressionLevel == 1) return "rápida";
        if (compressionLevel == 3) return "ultra";
        return "normal";
    }
    private void addToZip(ZipOutputStream zip, DocumentFile file, String entryName, Uri archiveUri) throws IOException {
        if (file.getUri().equals(archiveUri)) return;
        if (file.isDirectory()) {
            String dirName = entryName.endsWith("/") ? entryName : entryName + "/";
            zip.putNextEntry(new ZipEntry(dirName)); zip.closeEntry();
            DocumentFile[] children = file.listFiles();
            if (children != null) for (DocumentFile child : children)
                addToZip(zip, child, dirName + (child.getName() == null ? "item" : child.getName()), archiveUri);
        } else {
            zip.putNextEntry(new ZipEntry(entryName));
            InputStream raw = getContentResolver().openInputStream(file.getUri());
            if (raw == null) throw new IOException("Não foi possível ler " + file.getName());
            try (InputStream in = new BufferedInputStream(raw)) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = in.read(buffer)) != -1) zip.write(buffer, 0, count);
            }
            zip.closeEntry();
        }
    }

    private void extract(DocumentFile archive, DocumentFile targetDir, boolean makeSubfolder) {
        if (targetDir == null || !"zip".equals(extension(archive.getName()))) {
            toast("Nesta versão, a extração é suportada para ficheiros .zip."); return;
        }
        DocumentFile destination = targetDir;
        if (makeSubfolder) {
            String name = archive.getName() == null ? "Arquivo" : archive.getName();
            int dot = name.lastIndexOf('.'); if (dot > 0) name = name.substring(0,dot);
            try { name = uniqueName(targetDir, name); }
            catch (IOException e) { toast("Não foi possível verificar o destino."); return; }
            destination = targetDir.createDirectory(name);
            if (destination == null) { toast("Não foi possível criar a pasta de destino."); return; }
        }
        final DocumentFile target = destination;
        runWork(() -> {
            int count = 0;
            try {
                InputStream raw = getContentResolver().openInputStream(archive.getUri());
                if (raw == null) throw new IOException("Não foi possível ler o ZIP.");
                try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
                    ZipEntry entry; byte[] buffer = new byte[32768];
                    while ((entry = zip.getNextEntry()) != null) {
                        String path = safeZipPath(entry.getName());
                        if (path == null) throw new IOException("O arquivo contém um caminho inseguro.");
                        DocumentFile out = createTree(target, path, entry.isDirectory());
                        if (out == null) throw new IOException("Não foi possível criar: " + path);
                        if (!entry.isDirectory()) {
                            OutputStream outRaw = getContentResolver().openOutputStream(out.getUri());
                            if (outRaw == null) throw new IOException("Não foi possível escrever: " + path);
                            try (OutputStream outStream = new BufferedOutputStream(outRaw)) {
                                int len; while ((len = zip.read(buffer)) > 0) outStream.write(buffer, 0, len);
                            }
                        }
                        zip.closeEntry(); count++;
                    }
                }
            } catch (Exception e) {
                postResult("Erro na extração: " + safeMessage(e) + (count > 0 ? " (" + count + " itens processados)" : ""), false); return;
            }
            postResult("Extração concluída: " + count + " itens.", true);
        });
    }
    private String safeZipPath(String raw) {
        if (raw == null || raw.isEmpty() || raw.startsWith("/") || raw.startsWith("\\")) return null;
        String path = raw.replace('\\','/');
        for (String part : path.split("/")) if ("..".equals(part) || ".".equals(part)) return null;
        return path;
    }
    private DocumentFile createTree(DocumentFile root, String path, boolean directory) throws IOException {
        String[] parts = path.split("/");
        DocumentFile parent = root;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            boolean last = i == parts.length - 1;
            DocumentFile found = null;
            DocumentFile[] children = parent.listFiles();
            if (children != null) {
                for (DocumentFile child : children) {
                    if (parts[i].equals(child.getName())) { found = child; break; }
                }
            }
            if (last && !directory) {
                String outName = parts[i];
                if (found != null) {
                    int dot = outName.lastIndexOf('.');
                    String stem = dot > 0 ? outName.substring(0, dot) : outName;
                    String ext = dot > 0 ? outName.substring(dot) : "";
                    int suffix = 2;
                    boolean collision;
                    do {
                        String candidate = stem + " (" + suffix++ + ")" + ext;
                        collision = false;
                        DocumentFile[] existing = parent.listFiles();
                        if (existing != null) {
                            for (DocumentFile child : existing) {
                                if (candidate.equals(child.getName())) { collision = true; break; }
                            }
                        }
                        if (!collision) outName = candidate;
                    } while (collision);
                }
                found = parent.createFile("application/octet-stream", outName);
            } else if (found == null) {
                found = parent.createDirectory(parts[i]);
            }
            if (found == null) throw new IOException("Falha ao criar " + parts[i]);
            parent = found;
        }
        return parent;
    }

    private void paste() {
        if (current() == null || clipboard.isEmpty()) { toast("Nada para colar."); return; }
        List<DocumentFile> sources = new ArrayList<>(clipboard);
        DocumentFile target = current();
        boolean cut = clipboardIsCut;
        for (DocumentFile src : sources) {
            DocumentFile parent = src.getParentFile();
            if (parent != null && parent.getUri().equals(target.getUri())) {
                toast("O item selecionado já está nesta pasta.");
                return;
            }
            if (src.isDirectory()) {
                for (DocumentFile ancestor : stack) {
                    if (ancestor.getUri().equals(src.getUri())) {
                        toast("Não é possível copiar ou mover uma pasta para dentro de si mesma ou de uma subpasta.");
                        return;
                    }
                }
            }
        }
        runWork(() -> {
            int done = 0;
            for (DocumentFile src : sources) {
                try {
                    if (!cut) {
                        copyRecursively(src, target, src.getName() == null ? "item" : src.getName());
                    } else {
                        if (src.isDirectory()) {
                            String name = uniqueName(target, src.getName() == null ? "Pasta" : src.getName());
                            DocumentFile moved = target.createDirectory(name);
                            if (moved == null) throw new IOException("Não foi possível criar a pasta de destino.");
                            try {
                                copyFolderChildren(src, moved);
                            } catch (IOException e) {
                                deleteRecursively(moved);
                                throw e;
                            }
                            if (!deleteRecursively(src)) throw new IOException("A pasta foi copiada, mas a origem não foi eliminada.");
                        } else {
                            String name = uniqueName(target, src.getName() == null ? "ficheiro" : src.getName());
                            String mime = getContentResolver().getType(src.getUri());
                            DocumentFile moved = target.createFile(mime == null ? "application/octet-stream" : mime, name);
                            try {
                                copyBytes(src, moved);
                            } catch (IOException e) {
                                if (moved != null) deleteRecursively(moved);
                                throw e;
                            }
                            if (!src.delete()) throw new IOException("Ficheiro copiado, mas a origem não foi eliminada.");
                        }
                    }
                    done++;
                } catch (Exception e) {
                    postResult("Erro ao copiar/mover " + src.getName() + ": " + safeMessage(e), false);
                    return;
                }
            }
            clipboard.clear();
            clipboardIsCut = false;
            postResult((cut ? "Movidos " : "Copiados ") + done + " item(ns).", true);
        });
    }

    private String uniqueName(DocumentFile destination, String originalName) throws IOException {
        DocumentFile[] children = destination.listFiles();
        if (children == null) throw new IOException("Não foi possível listar a pasta de destino.");
        for (DocumentFile child : children) if (originalName.equals(child.getName())) {
            int dot = originalName.lastIndexOf('.');
            String stem = dot > 0 ? originalName.substring(0, dot) : originalName;
            String ext = dot > 0 ? originalName.substring(dot) : "";
            for (int suffix = 2; suffix < 100000; suffix++) {
                String candidate = stem + " (" + suffix + ")" + ext;
                boolean used = false;
                for (DocumentFile existing : children) {
                    if (candidate.equals(existing.getName())) { used = true; break; }
                }
                if (!used) return candidate;
            }
            throw new IOException("Não foi possível gerar um nome único.");
        }
        return originalName;
    }

    private void copyRecursively(DocumentFile source, DocumentFile destination, String name) throws IOException {
        String safeName = uniqueName(destination, name);
        if (source.isDirectory()) {
            DocumentFile dir = destination.createDirectory(safeName);
            if (dir == null) throw new IOException("Não foi possível criar a pasta " + safeName);
            try {
                copyFolderChildren(source, dir);
            } catch (IOException e) {
                deleteRecursively(dir);
                throw e;
            }
        } else {
            String mime = getContentResolver().getType(source.getUri());
            DocumentFile out = destination.createFile(mime == null ? "application/octet-stream" : mime, safeName);
            if (out == null) throw new IOException("Não foi possível criar o ficheiro " + safeName);
            try {
                copyBytes(source, out);
            } catch (IOException e) {
                deleteRecursively(out);
                throw e;
            }
        }
    }

    private void copyFolderChildren(DocumentFile source, DocumentFile out) throws IOException {
        DocumentFile[] children = source.listFiles();
        if (children != null) for (DocumentFile child : children)
            copyRecursively(child, out, child.getName() == null ? "item" : child.getName());
    }

    private void copyBytes(DocumentFile source, DocumentFile target) throws IOException {
        if (target == null) throw new IOException("Não foi possível criar o destino.");
        InputStream raw = getContentResolver().openInputStream(source.getUri());
        if (raw == null) throw new IOException("Não foi possível ler o ficheiro de origem.");
        OutputStream outRaw = getContentResolver().openOutputStream(target.getUri());
        if (outRaw == null) {
            raw.close();
            throw new IOException("Não foi possível abrir o ficheiro de destino.");
        }
        try (InputStream in = new BufferedInputStream(raw); OutputStream out = new BufferedOutputStream(outRaw)) {
            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
    }

    private void runWork(Runnable action) { toast("A processar…"); worker.execute(action); }
    private void postResult(String message, boolean success) {
        runOnUiThread(() -> { toast(message); refresh(); });
    }
    private String safeMessage(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.trim().isEmpty() ? e.getClass().getSimpleName() : msg;
    }
    private void toast(String message) { runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show()); }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
