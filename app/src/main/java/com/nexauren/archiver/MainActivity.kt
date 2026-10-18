package com.nexauren.archiver

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.DateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var rootDir: File
    private lateinit var currentDir: File
    private lateinit var page: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var pathView: TextView
    private lateinit var selectionView: TextView
    private var gridRows: GridLayout? = null
    private var browserRendered = false

    private val selected = linkedSetOf<String>()
    private val favorites = linkedSetOf<String>()
    private var clipboard = emptyList<File>()
    private var clipboardIsCut = false
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val workActive = AtomicBoolean(false)
    private val thumbnailCache = object : LruCache<String, Bitmap>(2 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = maxOf(1, value.byteCount / 1024)
    }

    private var showHidden = false
    private var showSize = true
    private var showDate = true
    private var showTime = false
    private var showThumbnails = true
    private var showPdfThumbnails = false
    private var showVideoThumbnails = false
    private var showAlbumCovers = true
    private var showApkIcons = true
    private var squareThumbnails = false
    private var useParentEntry = true
    private var descending = false
    private var sortBy = "Nome"
    private var viewMode = "Detalhado"
    private var compressionLevel = 2

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = 0xFF292D40.toInt()
        window.navigationBarColor = 0xFF101014.toInt()
        prefs = getSharedPreferences("archiver_preferences", MODE_PRIVATE)
        loadPreferences()
        rootDir = Environment.getExternalStorageDirectory().canonicalFile
        currentDir = rootDir
        if (hasStorageAccess()) renderBrowser() else showPermissionScreen()
    }

    override fun onResume() {
        super.onResume()
        if (!::prefs.isInitialized) return
        if (hasStorageAccess() && !browserRendered) renderBrowser()
        else if (!hasStorageAccess() && !browserRendered && !::page.isInitialized) showPermissionScreen()
    }

    private fun loadPreferences() {
        showHidden = prefs.getBoolean("hidden", false)
        showSize = prefs.getBoolean("size", true)
        showDate = prefs.getBoolean("date", true)
        showTime = prefs.getBoolean("time", false)
        showThumbnails = prefs.getBoolean("thumbnails", true)
        showPdfThumbnails = prefs.getBoolean("thumbnails_pdf", false)
        showVideoThumbnails = prefs.getBoolean("thumbnails_video", false)
        showAlbumCovers = prefs.getBoolean("album_covers", true)
        showApkIcons = prefs.getBoolean("apk_icons", true)
        squareThumbnails = prefs.getBoolean("square_thumbnails", false)
        useParentEntry = prefs.getBoolean("use_parent_entry", true)
        descending = prefs.getBoolean("descending", false)
        sortBy = prefs.getString("sort", "Nome") ?: "Nome"
        viewMode = prefs.getString("view_mode", "Detalhado") ?: "Detalhado"
        compressionLevel = prefs.getInt("compression", 2)
        favorites.clear()
        favorites.addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
    }

    private fun savePreferences() {
        prefs.edit()
            .putBoolean("hidden", showHidden)
            .putBoolean("size", showSize)
            .putBoolean("date", showDate)
            .putBoolean("time", showTime)
            .putBoolean("thumbnails", showThumbnails)
            .putBoolean("thumbnails_pdf", showPdfThumbnails)
            .putBoolean("thumbnails_video", showVideoThumbnails)
            .putBoolean("album_covers", showAlbumCovers)
            .putBoolean("apk_icons", showApkIcons)
            .putBoolean("square_thumbnails", squareThumbnails)
            .putBoolean("use_parent_entry", useParentEntry)
            .putBoolean("descending", descending)
            .putString("sort", sortBy)
            .putString("view_mode", viewMode)
            .putInt("compression", compressionLevel)
            .putStringSet("favorites", HashSet(favorites))
            .apply()
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionScreen() {
        browserRendered = false
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(0xFF17171B.toInt())
        }
        val icon = ImageView(this).apply {
            setImageResource(com.nexauren.archiver.R.drawable.ic_archiver)
            contentDescription = "Archiver"
        }
        page.addView(icon, LinearLayout.LayoutParams(dp(96), dp(96)).apply { bottomMargin = dp(20) })
        page.addView(label("Archiver", 28, 0xFFFFFFFF.toInt(), true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2))
        page.addView(label("Gestor de ficheiros para Android", 16, 0xFFB7B7C2.toInt(), false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        }, LinearLayout.LayoutParams(-1, -2))
        page.addView(label(
            "Para explorar, copiar, mover, renomear, eliminar e comprimir ficheiros em diferentes pastas, o Archiver precisa de acesso aos ficheiros partilhados do dispositivo.",
            16, 0xFFE3E3E8.toInt(), false
        ).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(24)) }, LinearLayout.LayoutParams(-1, -2))
        page.addView(actionButton("Conceder acesso aos ficheiros") { requestStorageAccess() },
            LinearLayout.LayoutParams(-1, dp(54)))
        page.addView(label(
            "O Android abre uma página especial de definições para confirmar este acesso. O sistema continua a bloquear algumas pastas privadas das aplicações.",
            13, 0xFF9999A4.toInt(), false
        ).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        setContentView(page)
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + packageName)))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                    toast("Não foi possível abrir as definições de acesso aos ficheiros.")
                }
            }
        } else {
            requestPermissions(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ), 501)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 501) {
            if (hasStorageAccess()) renderBrowser() else showPermissionScreen()
        }
    }

    private fun renderBrowser() {
        if (!hasStorageAccess()) {
            showPermissionScreen()
            return
        }
        browserRendered = true
        if (!::rootDir.isInitialized) rootDir = Environment.getExternalStorageDirectory().canonicalFile
        if (!::currentDir.isInitialized) currentDir = rootDir
        if (!currentDir.exists() || !currentDir.isDirectory) currentDir = rootDir

        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF19191D.toInt())
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(0xFF292D40.toInt())
        }
        toolbar.addView(iconButton("‹", "Voltar") { goUp() }, LinearLayout.LayoutParams(dp(48), dp(54)))
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pathView = label(displayPath(), 17, 0xFFFFFFFF.toInt(), true)
        pathView.maxLines = 1
        pathView.ellipsize = android.text.TextUtils.TruncateAt.START
        titleColumn.addView(pathView, LinearLayout.LayoutParams(-1, -2))
        selectionView = label(if (selected.isEmpty()) "Armazenamento interno" else selected.size.toString() + " selecionado(s)", 12, 0xFFD0D0DF.toInt(), false)
        titleColumn.addView(selectionView, LinearLayout.LayoutParams(-1, -2))
        toolbar.addView(titleColumn, LinearLayout.LayoutParams(0, dp(54), 1f))
        toolbar.addView(iconButton("⌕", "Pesquisar") { promptSearch() }, LinearLayout.LayoutParams(dp(44), dp(54)))
        toolbar.addView(iconButton(if (viewMode == "Grelha") "☰" else "▦", "Alterar modo de visualização") { cycleViewMode() },
            LinearLayout.LayoutParams(dp(44), dp(54)))
        toolbar.addView(iconButton("⋮", "Mais opções") { showMainMenu() }, LinearLayout.LayoutParams(dp(44), dp(54)))
        page.addView(toolbar, LinearLayout.LayoutParams(-1, dp(54)))

        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(0xFF202025.toInt())
        }
        strip.addView(actionButton("Nova pasta") { promptNewItem(true) }, LinearLayout.LayoutParams(0, dp(42), 1f))
        strip.addView(actionButton("Colar") { paste() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        strip.addView(actionButton("Compactar") { promptArchive(chosenOrVisible(), "zip") }, LinearLayout.LayoutParams(0, dp(42), 1f))
        page.addView(strip, LinearLayout.LayoutParams(-1, dp(50)))

        val scroll = ScrollView(this)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        refreshFiles()
    }

    private fun refreshFiles() {
        if (!browserRendered || !hasStorageAccess()) return
        rows.removeAllViews()
        gridRows = null
        pathView.text = displayPath()
        selectionView.text = if (selected.isEmpty()) {
            val count = safeList(currentDir).size
            count.toString() + if (count == 1) " item" else " itens"
        } else selected.size.toString() + " selecionado(s)"
        if (useParentEntry && currentDir.canonicalFile != rootDir.canonicalFile) addParentRow()

        if (viewMode == "Grelha") {
            gridRows = GridLayout(this).apply { columnCount = 3 }
            rows.addView(gridRows, LinearLayout.LayoutParams(-1, -2))
        }
        val files = safeList(currentDir).filter { showHidden || !it.name.startsWith(".") }.toMutableList()
        val comparator = when (sortBy) {
            "Tamanho" -> compareBy<File> { if (it.isDirectory) -1L else it.length() }
            "Data" -> compareBy<File> { it.lastModified() }
            "Tipo" -> compareBy<File> { extension(it.name) }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        files.sortWith { a, b ->
            val dirOrder = when {
                a.isDirectory && !b.isDirectory -> -1
                !a.isDirectory && b.isDirectory -> 1
                else -> 0
            }
            if (dirOrder != 0) dirOrder else {
                val order = comparator.compare(a, b)
                if (descending) -order else order
            }
        }
        files.forEach { addFileRow(it) }
    }

    private fun safeList(dir: File): List<File> {
        return try { dir.listFiles()?.toList() ?: emptyList() } catch (_: SecurityException) { emptyList() }
    }

    private fun addParentRow() {
        val row = TextView(this).apply {
            text = "📁   ..\nSubir um diretório"
            textSize = 15f
            setTextColor(0xFFF2F2F2.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { goUp() }
        }
        rows.addView(row, LinearLayout.LayoutParams(-1, -2))
        addDivider()
    }

    private fun addFileRow(file: File) {
        val key = pathKey(file)
        val isSelected = selected.contains(key)
        val inGrid = gridRows != null
        val item = LinearLayout(this).apply {
            orientation = if (inGrid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (inGrid) 6 else 10), dp(if (inGrid) 8 else if (viewMode == "Compacto") 4 else 9),
                dp(if (inGrid) 6 else 10), dp(if (inGrid) 8 else if (viewMode == "Compacto") 4 else 9))
            if (isSelected) setBackgroundColor(0xFF353348.toInt())
        }
        if (shouldLoadThumbnail(file)) {
            val preview = ImageView(this).apply {
                setImageResource(if (file.isDirectory) android.R.drawable.ic_menu_agenda else android.R.drawable.ic_menu_report_image)
                scaleType = if (squareThumbnails) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            }
            val size = dp(if (inGrid) 72 else 48)
            if (inGrid) item.addView(preview, LinearLayout.LayoutParams(size, size))
            else item.addView(preview, LinearLayout.LayoutParams(size, size).apply { rightMargin = dp(10) })
            loadThumbnailAsync(file, preview)
        } else if (inGrid) {
            val glyph = TextView(this).apply {
                text = fileGlyph(file)
                textSize = 36f
                gravity = Gravity.CENTER
                setTextColor(if (file.isDirectory) 0xFFFFA726.toInt() else 0xFFE4E4EA.toInt())
            }
            item.addView(glyph, LinearLayout.LayoutParams(dp(72), dp(72)))
        } else {
            val glyph = TextView(this).apply {
                text = fileGlyph(file)
                textSize = 31f
                gravity = Gravity.CENTER
                setTextColor(if (file.isDirectory) 0xFFFFA726.toInt() else 0xFFE4E4EA.toInt())
            }
            item.addView(glyph, LinearLayout.LayoutParams(dp(52), dp(54)))
        }

        val title = if (inGrid) file.name else (if (file.isDirectory) "" else "") + file.name
        val details = if (file.isDirectory) {
            val count = safeList(file).size
            count.toString() + if (count == 1) " item" else " itens"
        } else formatSize(file.length())
        val labelText = TextView(this).apply {
            text = when {
                inGrid -> title
                viewMode == "Compacto" -> title + if (showSize && !file.isDirectory) "  •  " + details else ""
                else -> buildString {
                    append(title)
                    if (showSize) append("\n").append(details)
                    if (showDate && file.lastModified() > 0L) {
                        if (showSize) append("   •   ") else append("\n")
                        append(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.lastModified())))
                        if (showTime) append(" ").append(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(file.lastModified())))
                    }
                }
            }
            textSize = if (inGrid) 13f else if (viewMode == "Compacto") 14f else 16f
            setTextColor(if (isSelected) 0xFFD0CBFF.toInt() else 0xFFF3F3F5.toInt())
            maxLines = if (inGrid) 2 else if (viewMode == "Detalhado") 3 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = if (inGrid) Gravity.CENTER else Gravity.CENTER_VERTICAL
        }
        if (inGrid) {
            item.addView(labelText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            val params = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = -2
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            gridRows?.addView(item, params)
        } else {
            item.addView(labelText, LinearLayout.LayoutParams(0, -2, 1f))
            rows.addView(item, LinearLayout.LayoutParams(-1, -2))
            if (viewMode != "Compacto") addDivider()
        }
        item.setOnClickListener {
            if (selected.isNotEmpty()) toggleSelection(file)
            else if (file.isDirectory) {
                currentDir = file
                selected.clear()
                refreshFiles()
            } else showFileActions(file)
        }
        item.setOnLongClickListener {
            toggleSelection(file)
            true
        }
    }

    private fun fileGlyph(file: File): String {
        if (file.isDirectory) return "📁"
        return when (extension(file.name)) {
            "zip", "7z", "rar", "tar", "gz", "bz2" -> "🗜"
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> "🖼"
            "mp3", "wav", "ogg", "m4a", "flac", "aac", "opus" -> "♫"
            "mp4", "mkv", "webm", "avi", "mov", "3gp" -> "▶"
            "apk" -> "▣"
            "pdf" -> "PDF"
            else -> "📄"
        }
    }

    private fun addDivider() {
        rows.addView(View(this).apply { setBackgroundColor(0xFF333339.toInt()) },
            LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun toggleSelection(file: File) {
        val key = pathKey(file)
        if (!selected.add(key)) selected.remove(key)
        refreshFiles()
    }

    private fun goUp() {
        if (!hasStorageAccess()) { showPermissionScreen(); return }
        val parent = currentDir.parentFile
        if (parent != null && isWithinRoot(parent)) {
            currentDir = parent
            selected.clear()
            refreshFiles()
        } else {
            toast("Já estás na raiz do armazenamento partilhado.")
        }
    }

    private fun isWithinRoot(file: File): Boolean {
        val root = rootDir.canonicalPath
        val path = try { file.canonicalPath } catch (_: Exception) { return false }
        return path == root || path.startsWith(root + File.separator)
    }

    private fun displayPath(): String {
        val path = try { currentDir.canonicalPath } catch (_: Exception) { currentDir.absolutePath }
        val root = rootDir.absolutePath
        val relative = if (path == root) "Armazenamento interno" else path.removePrefix(root).trimStart(File.separatorChar)
        return relative.ifBlank { "Armazenamento interno" }
    }

    private fun pathKey(file: File): String = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }

    private fun showMainMenu() {
        val options = arrayOf(
            "Selecionar tudo", "Limpar seleção", "Copiar seleção", "Recortar seleção", "Colar",
            "Comprimir em ZIP", "Comprimir em 7Z", "Pesquisar em subpastas", "Favoritos",
            "Ordenar", "Definições do explorador", "Atualizar", "Pasta de início"
        )
        AlertDialog.Builder(this).setTitle("Opções").setItems(options) { _, which ->
            when (which) {
                0 -> {
                    selected.clear()
                    safeList(currentDir).forEach { selected.add(pathKey(it)) }
                    refreshFiles()
                }
                1 -> { selected.clear(); refreshFiles() }
                2 -> stageClipboard(false)
                3 -> stageClipboard(true)
                4 -> paste()
                5 -> promptArchive(chosenOrVisible(), "zip")
                6 -> promptArchive(chosenOrVisible(), "7z")
                7 -> promptSearch()
                8 -> showFavorites()
                9 -> showSortMenu()
                10 -> showExplorerSettings()
                11 -> refreshFiles()
                12 -> { currentDir = rootDir; selected.clear(); refreshFiles() }
            }
        }.show()
    }

    private fun showSortMenu() {
        val items = arrayOf("Nome", "Tamanho", "Data", "Tipo", if (descending) "Ordem crescente" else "Ordem decrescente")
        AlertDialog.Builder(this).setTitle("Ordenar ficheiros").setItems(items) { _, which ->
            if (which < 4) sortBy = items[which] else descending = !descending
            savePreferences()
            refreshFiles()
        }.show()
    }

    private fun showExplorerSettings() {
        val options = arrayOf(
            "Modo de exibição: " + viewMode,
            "Miniaturas: " + onOff(showThumbnails),
            "Miniaturas de PDF: " + onOff(showPdfThumbnails),
            "Miniaturas de vídeo: " + onOff(showVideoThumbnails),
            "Capa do álbum: " + onOff(showAlbumCovers),
            "Ícones do APK: " + onOff(showApkIcons),
            "Miniaturas quadradas: " + onOff(squareThumbnails),
            "Mostrar tamanho: " + onOff(showSize),
            "Mostrar data: " + onOff(showDate),
            "Mostrar hora: " + onOff(showTime),
            "Usar item .. para subir: " + onOff(useParentEntry),
            "Nível de compressão: " + compressionName()
        )
        AlertDialog.Builder(this).setTitle("Definições do explorador").setItems(options) { _, which ->
            when (which) {
                0 -> showViewModeMenu()
                1 -> showThumbnails = !showThumbnails
                2 -> showPdfThumbnails = !showPdfThumbnails
                3 -> showVideoThumbnails = !showVideoThumbnails
                4 -> showAlbumCovers = !showAlbumCovers
                5 -> showApkIcons = !showApkIcons
                6 -> squareThumbnails = !squareThumbnails
                7 -> showSize = !showSize
                8 -> showDate = !showDate
                9 -> showTime = !showTime
                10 -> useParentEntry = !useParentEntry
                11 -> showCompressionMenu()
            }
            savePreferences()
            refreshFiles()
            if (which != 0 && which != 11) showExplorerSettings()
        }.setNegativeButton("Fechar", null).show()
    }

    private fun showViewModeMenu() {
        val modes = arrayOf("Detalhado", "Compacto", "Grelha")
        val checked = modes.indexOf(viewMode).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Modo de exibição").setSingleChoiceItems(modes, checked) { dialog, which ->
            viewMode = modes[which]
            savePreferences()
            dialog.dismiss()
            refreshFiles()
            showExplorerSettings()
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun cycleViewMode() {
        viewMode = when (viewMode) { "Detalhado" -> "Compacto"; "Compacto" -> "Grelha"; else -> "Detalhado" }
        savePreferences()
        refreshFiles()
        if (::pathView.isInitialized) pathView.text = displayPath()
    }

    private fun showCompressionMenu() {
        val levels = arrayOf("Sem compressão (mais rápido)", "Rápido", "Normal", "Ultra (mais lento)")
        AlertDialog.Builder(this).setTitle("Nível de compressão").setSingleChoiceItems(levels, compressionLevel.coerceIn(0, 3)) { dialog, which ->
            compressionLevel = which
            savePreferences()
            dialog.dismiss()
            toast("Nível guardado.")
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun promptNewItem(folder: Boolean) {
        val input = EditText(this).apply {
            hint = if (folder) "Nome da pasta" else "Nome do ficheiro"
            setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle(if (folder) "Nova pasta" else "Novo ficheiro")
            .setView(input).setNegativeButton("Cancelar", null).setPositiveButton("Criar") { _, _ ->
                val name = input.text.toString().trim()
                if (!validName(name)) { toast("Nome inválido."); return@setPositiveButton }
                val target = File(currentDir, name)
                if (target.exists()) { toast("Já existe um ficheiro com esse nome."); return@setPositiveButton }
                val ok = try { if (folder) target.mkdir() else target.createNewFile() } catch (_: Exception) { false }
                if (!ok) toast("Não foi possível criar. Verifica as permissões e o espaço livre.")
                refreshFiles()
            }.show()
    }

    private fun validName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && !name.contains("/") && !name.contains("\\") && !name.contains('\u0000')

    private fun showFileActions(file: File) {
        val ext = extension(file.name)
        val options = mutableListOf<String>()
        if (file.isDirectory) options += "Abrir pasta" else options += "Abrir"
        if (ext == "zip" || ext == "7z") {
            options += "Ver conteúdo"
            options += "Testar arquivo"
            options += "Extrair aqui"
            options += "Extrair para nova pasta"
        }
        options += listOf("Partilhar", "Copiar", "Recortar", "Renomear", "Eliminar", "Informações")
        options += if (favorites.contains(pathKey(file))) "Remover dos Favoritos" else "Adicionar aos Favoritos"
        AlertDialog.Builder(this).setTitle(file.name).setItems(options.toTypedArray()) { _, which ->
            when (options[which]) {
                "Abrir pasta" -> { currentDir = file; selected.clear(); refreshFiles() }
                "Abrir" -> openFile(file)
                "Ver conteúdo" -> browseArchive(file)
                "Testar arquivo" -> if (ext == "7z") testSevenZip(file) else testZip(file)
                "Extrair aqui" -> if (ext == "7z") extractSevenZip(file, currentDir, false) else extractZip(file, currentDir, false)
                "Extrair para nova pasta" -> if (ext == "7z") extractSevenZip(file, currentDir, true) else extractZip(file, currentDir, true)
                "Partilhar" -> shareFile(file)
                "Copiar" -> { clipboard = chosenOrSingle(file); clipboardIsCut = false; selected.clear(); toast("Preparado para copiar."); refreshFiles() }
                "Recortar" -> { clipboard = chosenOrSingle(file); clipboardIsCut = true; selected.clear(); toast("Preparado para mover."); refreshFiles() }
                "Renomear" -> promptRename(file)
                "Eliminar" -> confirmDelete(listOf(file))
                "Informações" -> showInfo(file)
                "Adicionar aos Favoritos" -> { favorites.add(pathKey(file)); savePreferences(); toast("Adicionado aos Favoritos.") }
                "Remover dos Favoritos" -> { favorites.remove(pathKey(file)); savePreferences(); toast("Removido dos Favoritos.") }
            }
        }.show()
    }

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(file.name)) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("Não existe uma aplicação para abrir este ficheiro.")
        } catch (e: Exception) {
            toast("Não foi possível abrir: " + (e.message ?: "erro desconhecido"))
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(file.name)) ?: "*/*"
            val intent = Intent(Intent.ACTION_SEND).setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Partilhar ficheiro"))
        } catch (e: Exception) { toast("Não foi possível partilhar: " + (e.message ?: "erro")) }
    }

    private fun promptRename(file: File) {
        val input = EditText(this).apply { setSingleLine(true); setText(file.name) }
        AlertDialog.Builder(this).setTitle("Renomear").setView(input)
            .setNegativeButton("Cancelar", null).setPositiveButton("Guardar") { _, _ ->
                val name = input.text.toString().trim()
                if (!validName(name)) { toast("Nome inválido."); return@setPositiveButton }
                val dest = File(file.parentFile, name)
                if (dest.exists() && dest.canonicalFile != file.canonicalFile) {
                    toast("Já existe um item com esse nome."); return@setPositiveButton
                }
                if (!file.renameTo(dest)) toast("Não foi possível renomear o item.")
                selected.clear()
                refreshFiles()
            }.show()
    }

    private fun confirmDelete(files: List<File>) {
        if (files.isEmpty()) { toast("Seleciona pelo menos um item."); return }
        AlertDialog.Builder(this).setTitle("Eliminar")
            .setMessage("Eliminar " + files.size + if (files.size == 1) " item? Esta ação não pode ser anulada." else " itens? Esta ação não pode ser anulada.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Eliminar") { _, _ ->
                selected.clear()
                runTask("A eliminar ficheiros…") {
                    files.forEach { file ->
                        if (!deleteRecursively(file)) throw IOException("Não foi possível eliminar " + file.name)
                    }
                    "Eliminação concluída."
                }
            }.show()
    }

    private fun deleteRecursively(file: File): Boolean {
        return try {
            if (file.isDirectory) {
                val children = file.listFiles() ?: return false
                for (child in children) if (!deleteRecursively(child)) return false
            }
            file.delete()
        } catch (_: Exception) { false }
    }

    private fun showInfo(file: File) {
        val type = if (file.isDirectory) "Pasta" else "Ficheiro"
        val message = "Nome: " + file.name +
            "\nTipo: " + type +
            "\nTamanho: " + if (file.isDirectory) "—" else formatSize(file.length()) +
            "\nModificado: " + DateFormat.getDateTimeInstance().format(Date(file.lastModified())) +
            "\nPode ler: " + file.canRead() + "\nPode escrever: " + file.canWrite()
        AlertDialog.Builder(this).setTitle("Informações").setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun chosen(): List<File> {
        return safeList(currentDir).filter { selected.contains(pathKey(it)) }
    }

    private fun chosenOrSingle(file: File): List<File> = chosen().ifEmpty { listOf(file) }

    private fun chosenOrVisible(): List<File> = chosen().ifEmpty { emptyList<File>() }

    private fun stageClipboard(cut: Boolean) {
        val items = chosen()
        if (items.isEmpty()) { toast("Seleciona primeiro os ficheiros ou pastas."); return }
        clipboard = items
        clipboardIsCut = cut
        selected.clear()
        toast(if (cut) "Preparado para mover. Abre o destino e toca em Colar." else "Preparado para copiar. Abre o destino e toca em Colar.")
        refreshFiles()
    }

    private fun paste() {
        if (clipboard.isEmpty()) { toast("Nada para colar."); return }
        val sources = clipboard.toList()
        val target = currentDir
        val cut = clipboardIsCut
        for (source in sources) {
            if (pathKey(source) == pathKey(target) || (source.isDirectory && isDescendant(target, source))) {
                toast("Não podes copiar ou mover uma pasta para dentro de si própria.")
                return
            }
        }
        runTask(if (cut) "A mover…" else "A copiar…") {
            var count = 0
            for (source in sources) {
                val targetName = uniqueName(target, source.name)
                val destination = File(target, targetName)
                if (cut && source.parentFile?.canonicalFile == target.canonicalFile) continue
                copyFileTree(source, destination)
                if (cut && !deleteRecursively(source)) throw IOException("Copiado, mas não foi possível remover a origem: " + source.name)
                count++
            }
            clipboard = emptyList()
            clipboardIsCut = false
            selected.clear()
            (if (cut) "Movidos " else "Copiados ") + count + " item(ns)."
        }
    }

    private fun isDescendant(child: File, parent: File): Boolean {
        val p = pathKey(parent)
        val c = pathKey(child)
        return c.startsWith(p + File.separator)
    }

    private fun uniqueName(directory: File, original: String): String {
        if (!File(directory, original).exists()) return original
        val dot = original.lastIndexOf('.')
        val stem = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ""
        var index = 2
        while (index < 100000) {
            val name = stem + " (" + index + ")" + ext
            if (!File(directory, name).exists()) return name
            index++
        }
        throw IOException("Não foi possível gerar um nome único.")
    }

    private fun copyFileTree(source: File, dest: File) {
        if (source.isDirectory) {
            if (!dest.mkdirs()) throw IOException("Não foi possível criar " + dest.name)
            source.listFiles()?.forEach { child ->
                copyFileTree(child, File(dest, child.name))
            } ?: throw IOException("Não foi possível ler a pasta " + source.name)
        } else {
            if (dest.exists()) throw IOException("O destino já existe: " + dest.name)
            val input = BufferedInputStream(FileInputStream(source))
            try {
                val output = BufferedOutputStream(FileOutputStream(dest))
                try {
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                } finally { output.close() }
            } finally { input.close() }
        }
    }

    private fun promptSearch() {
        val input = EditText(this).apply { hint = "Nome ou padrão, por exemplo *.zip"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Pesquisar ficheiros").setView(input)
            .setNegativeButton("Cancelar", null).setPositiveButton("Pesquisar") { _, _ ->
                val query = input.text.toString().trim().lowercase(Locale.ROOT)
                if (query.isBlank()) { toast("Escreve um nome para pesquisar."); return@setPositiveButton }
                val start = currentDir
                runTask("A pesquisar…") { 
                    val hits = mutableListOf<File>()
                    searchTree(start, query, hits, 0)
                    runOnUiThread { showSearchResults(hits) }
                    "Pesquisa concluída: " + hits.size + " resultado(s)."
                }
            }.show()
    }

    private fun searchTree(directory: File, query: String, hits: MutableList<File>, depth: Int) {
        if (depth > 60 || hits.size >= 300) return
        val children = try { directory.listFiles() } catch (_: Exception) { null } ?: return
        for (child in children) {
            if (hits.size >= 300) return
            if (!showHidden && child.name.startsWith(".")) continue
            val name = child.name.lowercase(Locale.ROOT)
            val matches = if (query.contains('*') || query.contains('?')) {
                val regex = query.replace(".", "\\\\.").replace("*", ".*").replace("?", ".")
                name.matches(Regex("^" + regex + "$"))
            } else name.contains(query)
            if (matches) hits.add(child)
            if (child.isDirectory) searchTree(child, query, hits, depth + 1)
        }
    }

    private fun showSearchResults(hits: List<File>) {
        if (isFinishing) return
        if (hits.isEmpty()) { toast("Nenhum resultado encontrado."); return }
        val labels = hits.map { it.absolutePath.removePrefix(rootDir.absolutePath).trimStart(File.separatorChar) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Resultados (" + hits.size + ")").setItems(labels) { _, which ->
            val found = hits[which]
            currentDir = if (found.isDirectory) found else found.parentFile ?: rootDir
            refreshFiles()
            if (!found.isDirectory) showFileActions(found)
        }.setPositiveButton("Fechar", null).show()
    }

    private fun showFavorites() {
        if (favorites.isEmpty()) { toast("Ainda não existem Favoritos."); return }
        val files = favorites.map { File(it) }.filter { it.exists() }
        if (files.isEmpty()) { toast("Os Favoritos guardados já não existem."); return }
        val labels = files.map { it.absolutePath.removePrefix(rootDir.absolutePath).trimStart(File.separatorChar) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Favoritos").setItems(labels) { _, which ->
            val file = files[which]
            currentDir = if (file.isDirectory) file else file.parentFile ?: rootDir
            refreshFiles()
            if (!file.isDirectory) showFileActions(file)
        }.setPositiveButton("Fechar", null).show()
    }

    private fun promptArchive(files: List<File>, format: String) {
        if (files.isEmpty()) { toast("Seleciona pelo menos um ficheiro ou pasta."); return }
        val suffix = if (format == "7z") ".7z" else ".zip"
        val input = EditText(this).apply {
            setSingleLine(true)
            val suggestion = if (files.size == 1) files[0].name else "Arquivo"
            setText(if (suggestion.lowercase(Locale.ROOT).endsWith(suffix)) suggestion else suggestion + suffix)
        }
        val deleteOriginals = CheckBox(this).apply { text = "Eliminar originais depois de comprimir com sucesso" }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(input); addView(deleteOriginals) }
        AlertDialog.Builder(this).setTitle(if (format == "7z") "Criar arquivo 7Z" else "Criar arquivo ZIP")
            .setMessage("Compressão: " + compressionName()).setView(form)
            .setNegativeButton("Cancelar", null).setPositiveButton("Criar") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isBlank() || !validName(name)) { toast("Nome inválido."); return@setPositiveButton }
                if (!name.lowercase(Locale.ROOT).endsWith(suffix)) name += suffix
                try { name = uniqueName(currentDir, name) } catch (e: Exception) { toast("Nome de destino inválido."); return@setPositiveButton }
                val destination = File(currentDir, name)
                val removeSources = deleteOriginals.isChecked
                val sources = files.toList()
                runTask("A criar " + suffix + "…") {
                    if (format == "7z") createSevenZip(sources, destination) else createZip(sources, destination)
                    if (removeSources) sources.forEach { if (!deleteRecursively(it)) throw IOException("Arquivo criado, mas não foi possível eliminar " + it.name) }
                    selected.clear()
                    if (removeSources) "Arquivo criado; originais eliminados." else "Arquivo criado: " + destination.name
                }
            }.show()
    }

    private fun compressionName(): String = when (compressionLevel) {
        0 -> "sem compressão"
        1 -> "rápida"
        3 -> "ultra"
        else -> "normal"
    }

    private fun createZip(sources: List<File>, destination: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { zip ->
            zip.setLevel(when (compressionLevel) {
                0 -> java.util.zip.Deflater.NO_COMPRESSION
                1 -> java.util.zip.Deflater.BEST_SPEED
                3 -> java.util.zip.Deflater.BEST_COMPRESSION
                else -> java.util.zip.Deflater.DEFAULT_COMPRESSION
            })
            sources.forEach { source ->
                if (source.canonicalFile != destination.canonicalFile) addToZip(zip, source, source.name, destination)
            }
        }
    }

    private fun addToZip(zip: ZipOutputStream, source: File, entryName: String, destination: File) {
        if (source.canonicalFile == destination.canonicalFile) return
        if (source.isDirectory) {
            val name = if (entryName.endsWith("/")) entryName else entryName + "/"
            zip.putNextEntry(ZipEntry(name))
            zip.closeEntry()
            val children = source.listFiles() ?: throw IOException("Não foi possível ler " + source.name)
            children.forEach { addToZip(zip, it, name + it.name, destination) }
        } else {
            zip.putNextEntry(ZipEntry(entryName))
            BufferedInputStream(FileInputStream(source)).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    zip.write(buffer, 0, n)
                }
            }
            zip.closeEntry()
        }
    }

    private fun createSevenZip(sources: List<File>, destination: File) {
        SevenZOutputFile(destination).use { seven ->
            when (compressionLevel) {
                0 -> seven.setContentCompression(SevenZMethod.COPY)
                1 -> seven.setContentCompression(SevenZMethod.DEFLATE)
                3 -> seven.setContentCompression(SevenZMethod.BZIP2)
                else -> seven.setContentCompression(SevenZMethod.LZMA2)
            }
            sources.forEach { source ->
                if (source.canonicalFile != destination.canonicalFile) addToSevenZip(seven, source, source.name, destination)
            }
        }
    }

    private fun addToSevenZip(seven: SevenZOutputFile, source: File, entryName: String, destination: File) {
        if (source.canonicalFile == destination.canonicalFile) return
        val entry = SevenZArchiveEntry()
        entry.name = entryName
        entry.lastModifiedDate = Date(maxOf(0L, source.lastModified()))
        if (source.isDirectory) {
            entry.isDirectory = true
            entry.setHasStream(false)
            seven.putArchiveEntry(entry)
            seven.closeArchiveEntry()
            val children = source.listFiles() ?: throw IOException("Não foi possível ler " + source.name)
            children.forEach { addToSevenZip(seven, it, entryName + "/" + it.name, destination) }
        } else {
            entry.isDirectory = false
            entry.setHasStream(true)
            entry.size = source.length()
            seven.putArchiveEntry(entry)
            BufferedInputStream(FileInputStream(source)).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    seven.write(buffer, 0, n)
                }
            }
            seven.closeArchiveEntry()
        }
    }

    private fun extractZip(archive: File, destinationDir: File, makeSubfolder: Boolean) {
        runTask("A extrair ZIP…") {
            val destination = if (makeSubfolder) File(destinationDir, uniqueName(destinationDir, archive.nameWithoutExtension)) else destinationDir
            if (makeSubfolder && !destination.mkdirs()) throw IOException("Não foi possível criar a pasta de destino.")
            var count = 0
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val output = safeArchiveTarget(destination, entry.name, entry.isDirectory)
                    if (entry.isDirectory) {
                        if (!output.exists() && !output.mkdirs()) throw IOException("Não foi possível criar " + output.name)
                    } else {
                        output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível criar uma pasta de destino.") }
                        val finalOut = if (output.exists()) File(output.parentFile, uniqueName(output.parentFile!!, output.name)) else output
                        BufferedOutputStream(FileOutputStream(finalOut)).use { stream ->
                            while (true) {
                                val n = zip.read(buffer)
                                if (n <= 0) break
                                stream.write(buffer, 0, n)
                            }
                        }
                    }
                    zip.closeEntry()
                    count++
                }
            }
            "Extração ZIP concluída: " + count + " entradas."
        }
    }

    private fun extractSevenZip(archive: File, destinationDir: File, makeSubfolder: Boolean) {
        runTask("A extrair 7Z…") {
            val destination = if (makeSubfolder) File(destinationDir, uniqueName(destinationDir, archive.nameWithoutExtension)) else destinationDir
            if (makeSubfolder && !destination.mkdirs()) throw IOException("Não foi possível criar a pasta de destino.")
            var count = 0
            SevenZFile(archive).use { seven ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = seven.nextEntry ?: break
                    val output = safeArchiveTarget(destination, entry.name, entry.isDirectory)
                    if (entry.isDirectory) {
                        if (!output.exists() && !output.mkdirs()) throw IOException("Não foi possível criar " + output.name)
                    } else {
                        output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível criar a pasta de destino.") }
                        val finalOut = if (output.exists()) File(output.parentFile, uniqueName(output.parentFile!!, output.name)) else output
                        BufferedOutputStream(FileOutputStream(finalOut)).use { stream ->
                            while (true) {
                                val n = seven.read(buffer, 0, buffer.size)
                                if (n < 0) break
                                stream.write(buffer, 0, n)
                            }
                        }
                    }
                    count++
                }
            }
            "Extração 7Z concluída: " + count + " entradas."
        }
    }

    private fun safeArchiveTarget(destination: File, entryName: String, directory: Boolean): File {
        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.matches(Regex("^[A-Za-z]:.*")) ||
            normalized.split('/').any { it == ".." || it == "." }) {
            throw IOException("O arquivo contém um caminho inseguro.")
        }
        val base = destination.canonicalFile
        val target = File(base, normalized).canonicalFile
        if (target.path != base.path && !target.path.startsWith(base.path + File.separator)) {
            throw IOException("O arquivo tenta escrever fora da pasta de destino.")
        }
        return target
    }

    private fun testZip(archive: File) {
        runTask("A testar ZIP…") {
            var count = 0
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    while (zip.read(buffer) >= 0) { }
                    zip.closeEntry()
                    count++
                }
            }
            "Teste ZIP concluído: " + count + " entradas."
        }
    }

    private fun testSevenZip(archive: File) {
        runTask("A testar 7Z…") {
            var count = 0
            SevenZFile(archive).use { seven ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = seven.nextEntry ?: break
                    while (seven.read(buffer, 0, buffer.size) >= 0) { }
                    count++
                }
            }
            "Teste 7Z concluído: " + count + " entradas."
        }
    }

    private data class ArchiveItem(val index: Int, val name: String, val directory: Boolean, val size: Long)

    private fun browseArchive(archive: File) {
        val ext = extension(archive.name)
        runTask("A ler arquivo…") {
            val entries = mutableListOf<ArchiveItem>()
            if (ext == "zip") {
                ZipFile(archive).use { zip ->
                    val en = zip.entries()
                    var index = 0
                    while (en.hasMoreElements() && entries.size < 300) {
                        val e = en.nextElement()
                        entries += ArchiveItem(index++, e.name, e.isDirectory, e.size)
                    }
                }
            } else if (ext == "7z") {
                SevenZFile(archive).use { seven ->
                    var index = 0
                    for (e in seven.entries) {
                        if (entries.size >= 300) break
                        entries += ArchiveItem(index++, e.name, e.isDirectory, e.size)
                    }
                }
            } else throw IOException("Formato não suportado.")
            runOnUiThread { showArchiveItems(archive, entries) }
            "Conteúdo lido: " + entries.size + " entradas."
        }
    }

    private fun showArchiveItems(archive: File, entries: List<ArchiveItem>) {
        if (entries.isEmpty()) { toast("O arquivo está vazio."); return }
        val labels = entries.map { (if (it.directory) "📁  " else "📄  ") + it.name + if (!it.directory && it.size >= 0) "  •  " + formatSize(it.size) else "" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(archive.name + if (entries.size == 300) " (primeiras 300 entradas)" else "")
            .setItems(labels) { _, which ->
                val item = entries[which]
                if (item.directory) {
                    AlertDialog.Builder(this).setTitle("Pasta do arquivo")
                        .setMessage("Usa «Extrair aqui» para extrair esta pasta juntamente com o restante conteúdo.")
                        .setPositiveButton("OK", null).show()
                } else {
                    AlertDialog.Builder(this).setTitle("Extrair item")
                        .setMessage("Extrair " + item.name + " para a pasta atual?")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Extrair") { _, _ -> extractArchiveEntry(archive, item) }.show()
                }
            }.setPositiveButton("Fechar", null).show()
    }

    private fun extractArchiveEntry(archive: File, item: ArchiveItem) {
        runTask("A extrair item…") {
            val destination = safeArchiveTarget(currentDir, item.name, item.directory)
            if (item.directory) {
                if (!destination.exists() && !destination.mkdirs()) throw IOException("Não foi possível criar a pasta.")
            } else {
                destination.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível criar a pasta de destino.") }
                val target = if (destination.exists()) File(destination.parentFile, uniqueName(destination.parentFile!!, destination.name)) else destination
                when (extension(archive.name)) {
                    "zip" -> ZipFile(archive).use { zip ->
                        val en = zip.entries()
                        var index = 0
                        var entry: ZipEntry? = null
                        while (en.hasMoreElements()) {
                            val candidate = en.nextElement()
                            if (index++ == item.index) { entry = candidate; break }
                        }
                        val chosenEntry = entry ?: throw IOException("A entrada já não existe.")
                        zip.getInputStream(chosenEntry).use { input -> BufferedOutputStream(FileOutputStream(target)).use { input.copyTo(it) } }
                    }
                    "7z" -> SevenZFile(archive).use { seven ->
                        val buffer = ByteArray(32 * 1024)
                        var index = 0
                        var found = false
                        while (true) {
                            val e = seven.nextEntry ?: break
                            if (index++ != item.index) {
                                while (seven.read(buffer, 0, buffer.size) >= 0) { }
                                continue
                            }
                            found = true
                            BufferedOutputStream(FileOutputStream(target)).use { out ->
                                while (true) {
                                    val n = seven.read(buffer, 0, buffer.size)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                }
                            }
                            break
                        }
                        if (!found) throw IOException("A entrada já não existe.")
                    }
                    else -> throw IOException("Formato não suportado.")
                }
            }
            "Item extraído: " + item.name
        }
    }

    private fun showInfoPlaceholder() {}

    private fun shouldLoadThumbnail(file: File): Boolean {
        if (file.isDirectory || !showThumbnails) return false
        return when (extension(file.name)) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> true
            "pdf" -> showPdfThumbnails
            "mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v" -> showVideoThumbnails
            "mp3", "wav", "ogg", "m4a", "flac", "aac", "opus" -> showAlbumCovers
            "apk" -> showApkIcons
            else -> false
        }
    }

    private fun thumbnailKey(file: File): String = file.absolutePath + ":" + file.lastModified() + ":" + file.length()

    private fun loadThumbnailAsync(file: File, view: ImageView) {
        val key = thumbnailKey(file)
        view.tag = key
        thumbnailCache.get(key)?.let { view.setImageBitmap(it); return }
        worker.execute {
            val bitmap = try { loadThumbnail(file) } catch (_: Exception) { null }
            if (bitmap != null) thumbnailCache.put(key, bitmap)
            runOnUiThread { if (!isFinishing && view.tag == key && bitmap != null) view.setImageBitmap(bitmap) }
        }
    }

    private fun loadThumbnail(file: File): Bitmap? {
        return when (extension(file.name)) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                    val options = BitmapFactory.Options()
                    var sample = 1
                    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
                    options.inSampleSize = sample
                    scaleThumbnail(BitmapFactory.decodeFile(file.absolutePath, options))
                }
            }
            "pdf" -> if (showPdfThumbnails) pdfThumbnail(file) else null
            "mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v" -> if (showVideoThumbnails) videoThumbnail(file) else null
            "mp3", "wav", "ogg", "m4a", "flac", "aac", "opus" -> if (showAlbumCovers) albumThumbnail(file) else null
            "apk" -> if (showApkIcons) apkThumbnail(file) else null
            else -> null
        }
    }

    private fun scaleThumbnail(source: Bitmap?): Bitmap? {
        if (source == null) return null
        val max = dp(96)
        val ratio = minOf(1f, minOf(max / source.width.toFloat(), max / source.height.toFloat()))
        if (ratio >= 1f) return source
        val scaled = Bitmap.createScaledBitmap(source, maxOf(1, (source.width * ratio).toInt()), maxOf(1, (source.height * ratio).toInt()), true)
        if (scaled != source) source.recycle()
        return scaled
    }

    private fun videoThumbnail(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            scaleThumbnail(if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 128, 128) else retriever.frameAtTime)
        } finally { retriever.release() }
    }

    private fun albumThumbnail(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val bytes = retriever.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
            scaleThumbnail(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }))
        } finally { retriever.release() }
    }

    private fun pdfThumbnail(file: File): Bitmap? {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { page ->
                val ratio = minOf(1f, 128f / maxOf(page.width, page.height))
                val bitmap = Bitmap.createBitmap(maxOf(1, (page.width * ratio).toInt()), maxOf(1, (page.height * ratio).toInt()), Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }

    private fun apkThumbnail(file: File): Bitmap? {
        val info: PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        } ?: return null
        val appInfo: ApplicationInfo = info.applicationInfo ?: return null
        appInfo.sourceDir = file.absolutePath
        appInfo.publicSourceDir = file.absolutePath
        val icon: Drawable = packageManager.getApplicationIcon(appInfo)
        val bitmap = Bitmap.createBitmap(dp(64), dp(64), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        icon.setBounds(0, 0, canvas.width, canvas.height)
        icon.draw(canvas)
        return bitmap
    }

    private fun runTask(message: String, action: () -> String) {
        if (!workActive.compareAndSet(false, true)) {
            toast("Já existe uma operação em curso. Aguarda que termine.")
            return
        }
        val progress = ProgressDialog(this).apply {
            setMessage(message)
            setIndeterminate(true)
            setCancelable(false)
            show()
        }
        worker.execute {
            var result: String
            try {
                result = action()
            } catch (e: Exception) {
                result = "Erro: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                workActive.set(false)
            }
            runOnUiThread {
                if (!isFinishing) {
                    if (progress.isShowing) progress.dismiss()
                    toast(result)
                    if (hasStorageAccess() && browserRendered) refreshFiles()
                }
            }
        }
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun iconButton(symbol: String, description: String, click: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            contentDescription = description
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }

    private fun actionButton(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        setOnClickListener { click() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> bytes.toString() + " B"
        bytes < 1024L * 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun onOff(value: Boolean): String = if (value) "Ligado" else "Desligado"

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    override fun onBackPressed() {
        if (selected.isNotEmpty()) {
            selected.clear()
            refreshFiles()
        } else if (::currentDir.isInitialized && currentDir.canonicalFile != rootDir.canonicalFile) {
            goUp()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
