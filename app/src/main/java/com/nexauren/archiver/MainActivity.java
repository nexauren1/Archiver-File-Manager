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
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.documentfile.provider.DocumentFile;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
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
    private final List<DocumentFile> clipboard = new ArrayList<>();
    private boolean clipboardIsCut = false;
    private boolean showHidden = false, showSize = true, showDate = true, descending = false;
    private String sortBy = "Nome";
    private int compressionLevel = 2;
    private LinearLayout page, rows;
    private TextView pathView, selectionView;
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        showHidden = prefs.getBoolean("hidden", false);
        showSize = prefs.getBoolean("size", true);
        showDate = prefs.getBoolean("date", true);
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
        TextView row = new TextView(this);
        StringBuilder label = new StringBuilder(file.isDirectory() ? "📁  " : iconFor(name) + "  ").append(name);
        if (showSize) label.append("\n").append(file.isDirectory() ? "Pasta" : formatSize(file.length()));
        if (showDate && file.lastModified() > 0)
            label.append(showSize ? "   •   " : "\n").append(DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(file.lastModified())));
        boolean isSelected = selected.contains(file.getUri().toString());
        if (isSelected) label.insert(0, "✓  ");
        row.setText(label.toString());
        row.setTextSize(16);
        row.setTextColor(isSelected ? 0xFFBDB8FF : 0xFFF2F2F2);
        row.setPadding(dp(8), dp(12), dp(8), dp(12));
        row.setMinHeight(dp(58));
        row.setOnClickListener(v -> {
            String id = file.getUri().toString();
            if (!selected.isEmpty()) {
                if (!selected.add(id)) selected.remove(id);
                refresh();
            } else if (file.isDirectory()) { stack.add(file); refresh(); }
            else showFileActions(file);
        });
        row.setOnLongClickListener(v -> {
            selected.add(file.getUri().toString());
            refresh();
            showFileActions(file);
            return true;
        });
        rows.addView(row);
        View divider = new View(this);
        divider.setBackgroundColor(0xFF333333);
        rows.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
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
        String[] options = {"Selecionar tudo", "Limpar seleção", "Copiar seleção", "Recortar seleção", "Excluir seleção", "Ordenar", "Mostrar/ocultar ficheiros ocultos", "Mostrar/ocultar tamanho", "Mostrar/ocultar data", "Compressão ZIP", "Atualizar"};
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
        new AlertDialog.Builder(this).setTitle("Criar").setItems(new String[]{"Nova pasta", "Novo ficheiro vazio", "Criar arquivo ZIP"}, (d, which) -> {
            if (which == 0) promptNewItem(true); else if (which == 1) promptNewItem(false); else promptZip();
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

    private void showFileActions(DocumentFile file) {
        String[] options;
        if (file.isDirectory()) options = new String[]{"Abrir pasta", "Comprimir em ZIP", "Copiar", "Recortar", "Renomear", "Excluir", "Informações"};
        else if ("zip".equals(extension(file.getName()))) options = new String[]{"Abrir", "Extrair aqui", "Extrair para nova pasta", "Copiar", "Recortar", "Renomear", "Excluir", "Informações"};
        else options = new String[]{"Abrir", "Copiar", "Recortar", "Renomear", "Excluir", "Informações"};
        new AlertDialog.Builder(this).setTitle(file.getName()).setItems(options, (d,which) -> {
            String op = options[which];
            if ("Abrir pasta".equals(op)) { stack.add(file); selected.clear(); refresh(); }
            else if ("Abrir".equals(op)) openFile(file);
            else if ("Comprimir em ZIP".equals(op)) promptZip(Collections.singletonList(file));
            else if ("Extrair aqui".equals(op)) extract(file, current(), false);
            else if ("Extrair para nova pasta".equals(op)) extract(file, current(), true);
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
            .setMessage("Eliminar "" + file.getName() + ""? Esta ação não pode ser anulada.")
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

    private void promptZip() { promptZip(chosen()); }
    private void promptZip(List<DocumentFile> files) {
        if (current() == null || files == null || files.isEmpty()) { toast("Selecione pelo menos um ficheiro ou pasta."); return; }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        String suggested = files.size() == 1 ? files.get(0).getName() : "Arquivo";
        if (suggested == null) suggested = "Arquivo";
        if (!suggested.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) suggested += ".zip";
        input.setText(suggested);
        new AlertDialog.Builder(this).setTitle("Criar arquivo ZIP")
            .setMessage("Formato ZIP • Compressão: " + compressionName()).setView(input)
            .setNegativeButton("Cancelar", null).setPositiveButton("Criar", (d,w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) { toast("Indique um nome."); return; }
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) name += ".zip";
                DocumentFile out = current().createFile("application/zip", name);
                if (out == null) { toast("Não foi possível criar o ZIP. Confirme o nome e as permissões."); return; }
                final List<DocumentFile> sources = new ArrayList<>(files);
                final DocumentFile target = out;
                selected.clear();
                runWork(() -> {
                    try (OutputStream stream = getContentResolver().openOutputStream(target.getUri());
                         ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(stream))) {
                        if (stream == null) throw new IOException("Não foi possível abrir o destino.");
                        if (compressionLevel == 0) zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
                        else if (compressionLevel == 1) zip.setLevel(java.util.zip.Deflater.BEST_SPEED);
                        else if (compressionLevel == 3) zip.setLevel(java.util.zip.Deflater.BEST_COMPRESSION);
                        else zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);
                        for (DocumentFile src : sources) addToZip(zip, src, src.getName() == null ? "item" : src.getName(), target.getUri());
                    } catch (Exception e) {
                        deleteRecursively(target); postResult("Erro ao comprimir: " + safeMessage(e), false); return;
                    }
                    postResult("ZIP criado: " + target.getName(), true);
                });
            }).show();
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
        for (int i=0; i<parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            boolean last = i == parts.length - 1;
            DocumentFile found = null;
            DocumentFile[] children = parent.listFiles();
            if (children != null) for (DocumentFile child : children) if (parts[i].equals(child.getName())) { found = child; break; }
            if (found == null) found = (last && !directory)
                ? parent.createFile("application/octet-stream", parts[i]) : parent.createDirectory(parts[i]);
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
        runWork(() -> {
            int done = 0;
            for (DocumentFile src : sources) {
                try {
                    if (!cut) copyRecursively(src, target, src.getName());
                    else {
                        if (src.getUri().equals(target.getUri())) continue;
                        if (src.isDirectory()) {
                            DocumentFile moved = target.createDirectory(src.getName());
                            if (moved == null) throw new IOException("Já existe uma pasta com esse nome.");
                            copyFolderChildren(src, moved);
                            if (!deleteRecursively(src)) throw new IOException("A pasta foi copiada, mas a origem não foi eliminada.");
                        } else {
                            String mime = getContentResolver().getType(src.getUri());
                            DocumentFile moved = target.createFile(mime == null ? "application/octet-stream" : mime, src.getName());
                            copyBytes(src, moved);
                            if (!src.delete()) throw new IOException("Ficheiro copiado, mas a origem não foi eliminada.");
                        }
                    }
                    done++;
                } catch (Exception e) { postResult("Erro ao copiar/mover " + src.getName() + ": " + safeMessage(e), false); return; }
            }
            clipboard.clear(); clipboardIsCut = false;
            postResult((cut ? "Movidos " : "Copiados ") + done + " item(ns).", true);
        });
    }
    private void copyRecursively(DocumentFile source, DocumentFile destination, String name) throws IOException {
        if (source.isDirectory()) {
            DocumentFile dir = destination.createDirectory(name);
            if (dir == null) throw new IOException("Já existe uma pasta com o nome " + name);
            copyFolderChildren(source, dir);
        } else {
            String mime = getContentResolver().getType(source.getUri());
            DocumentFile out = destination.createFile(mime == null ? "application/octet-stream" : mime, name);
            if (out == null) throw new IOException("Já existe um item com o nome " + name);
            copyBytes(source, out);
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
        OutputStream outRaw = getContentResolver().openOutputStream(target.getUri());
        if (raw == null || outRaw == null) throw new IOException("Não foi possível abrir o ficheiro.");
        try (InputStream in = new BufferedInputStream(raw); OutputStream out = new BufferedOutputStream(outRaw)) {
            byte[] buffer = new byte[32768]; int n;
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
