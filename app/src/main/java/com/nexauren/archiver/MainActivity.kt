package com.nexauren.archiver

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.Switch
import android.widget.ImageButton
import android.widget.FrameLayout
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import net.lingala.zip4j.ZipFile as Zip4jFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.progress.ProgressMonitor
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
    private lateinit var countView: TextView
    private lateinit var breadcrumbRow: LinearLayout
    private var selectionBar: LinearLayout? = null
    private var addFab: ImageButton? = null
    private var gridRows: GridLayout? = null
    private var browserRendered = false
    private var settingsOpen = false
    private var settingsCategory: String? = null
    private var editorOpen = false
    private var editorDirty = false
    private var editorSavedText = ""
    private var editorFile: File? = null
    private var editorInput: EditText? = null
    private var closeEditorAfterSave = false
    private var appLanguage = "pt"
    private var uiTheme = "Escuro"
    private var accentName = "Lavanda"
    private val folderColors = linkedMapOf<String, String>()

    private val selected = linkedSetOf<String>()
    private val favorites = linkedSetOf<String>()
    private var clipboard = emptyList<File>()
    private var clipboardIsCut = false
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val thumbnailWorker: ExecutorService = Executors.newFixedThreadPool(2)
    private val workActive = AtomicBoolean(false)
    private val notificationChannelId = "archiver_file_operations"
    private val operationNotificationId = 7410
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
        appLanguage = prefs.getString("app_language", "pt") ?: "pt"
        uiTheme = prefs.getString("ui_theme", "Escuro") ?: "Escuro"
        accentName = prefs.getString("accent_color", "Lavanda") ?: "Lavanda"
        folderColors.clear()
        (prefs.getStringSet("folder_colors", emptySet()) ?: emptySet()).forEach { entry ->
            val index = entry.lastIndexOf('=')
            if (index > 0) folderColors[entry.substring(0, index)] = entry.substring(index + 1)
        }
        favorites.clear()
        favorites.addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
        applyPaletteToWindow()
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
            .putString("app_language", appLanguage)
            .putString("ui_theme", uiTheme)
            .putString("accent_color", accentName)
            .putStringSet("folder_colors", folderColors.map { (path, color) -> "$path=$color" }.toSet())
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
        settingsOpen = false
        browserRendered = true
        if (!::rootDir.isInitialized) rootDir = Environment.getExternalStorageDirectory().canonicalFile
        if (!::currentDir.isInitialized) currentDir = rootDir
        if (!currentDir.exists() || !currentDir.isDirectory) currentDir = rootDir

        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBackgroundColor())
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(toolbarBackgroundColor())
        }
        toolbar.addView(toolbarIcon(R.drawable.ic_arrow_back, "Pasta anterior") { goUp() },
            LinearLayout.LayoutParams(dp(48), dp(58)))
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pathView = label(currentDir.name?.ifBlank { "Armazenamento interno" } ?: "Armazenamento interno",
            19, 0xFFFFFFFF.toInt(), true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        selectionView = label(currentDir.absolutePath, 12, 0xFFD0D0DF.toInt(), false).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        titleColumn.addView(pathView, LinearLayout.LayoutParams(-1, -2))
        titleColumn.addView(selectionView, LinearLayout.LayoutParams(-1, -2))
        toolbar.addView(titleColumn, LinearLayout.LayoutParams(0, dp(58), 1f))
        toolbar.addView(toolbarIcon(R.drawable.ic_search, "Pesquisar") { promptSearch() },
            LinearLayout.LayoutParams(dp(44), dp(58)))
        toolbar.addView(toolbarIcon(if (viewMode == "Grelha") R.drawable.ic_list else R.drawable.ic_grid,
            "Alterar modo de visualização") { cycleViewMode() }, LinearLayout.LayoutParams(dp(44), dp(58)))
        toolbar.addView(toolbarIcon(R.drawable.ic_more_vertical, "Mais opções") { showMainMenu() },
            LinearLayout.LayoutParams(dp(44), dp(58)))
        page.addView(toolbar, LinearLayout.LayoutParams(-1, dp(58)))
        page.addView(View(this).apply { setBackgroundColor(accentColor()) },
            LinearLayout.LayoutParams(-1, dp(2)))

        val breadcrumbScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(panelBackgroundColor())
        }
        breadcrumbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        breadcrumbScroll.addView(breadcrumbRow, FrameLayout.LayoutParams(-2, -1))
        page.addView(breadcrumbScroll, LinearLayout.LayoutParams(-1, dp(38)))

        countView = label("", 13, 0xFFB9B9C3.toInt(), false).apply {
            setPadding(dp(16), dp(7), dp(16), dp(7))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(panelBackgroundColor())
        }
        page.addView(countView, LinearLayout.LayoutParams(-1, dp(34)))

        val body = FrameLayout(this)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(104))
        }
        scroll.addView(rows)
        body.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val fab = ImageButton(this).apply {
            setImageResource(R.drawable.ic_add)
            contentDescription = "Criar ficheiro ou arquivo"
            background = roundedBackground(0xFFB8B5FF.toInt(), 28)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            elevation = dp(8).toFloat()
            setOnClickListener { showCreateMenu() }
        }
        val fabParams = FrameLayout.LayoutParams(dp(58), dp(58), Gravity.END or Gravity.BOTTOM)
        fabParams.setMargins(0, 0, dp(20), dp(20))
        body.addView(fab, fabParams)
        addFab = fab

        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setBackgroundColor(toolbarBackgroundColor())
            elevation = dp(10).toFloat()
            visibility = View.GONE
        }
        selectionBar?.addView(selectionAction("▢", "Copiar") { stageClipboard(false) },
            LinearLayout.LayoutParams(0, dp(66), 1f))
        selectionBar?.addView(selectionAction("⇄", "Mover") { stageClipboard(true) },
            LinearLayout.LayoutParams(0, dp(66), 1f))
        selectionBar?.addView(selectionAction("ZIP", "Comprimir") { promptArchive(chosen(), "zip") },
            LinearLayout.LayoutParams(0, dp(66), 1f))
        selectionBar?.addView(selectionAction("⌫", "Eliminar") { confirmDelete(chosen()) },
            LinearLayout.LayoutParams(0, dp(66), 1f))
        selectionBar?.addView(selectionAction("⋮", "Mais") { showSelectionActions() },
            LinearLayout.LayoutParams(0, dp(66), 1f))
        body.addView(selectionBar, FrameLayout.LayoutParams(-1, dp(70), Gravity.BOTTOM))
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        refreshFiles()
    }

    private fun refreshFiles() {
        if (!browserRendered || settingsOpen || editorOpen || !hasStorageAccess()) return
        rows.removeAllViews()
        gridRows = null
        pathView.text = currentDir.name?.ifBlank { "Armazenamento interno" } ?: "Armazenamento interno"
        selectionView.text = currentDir.absolutePath
        renderBreadcrumbs()
        if (useParentEntry && currentDir.canonicalFile != rootDir.canonicalFile) addParentRow()

        if (viewMode == "Grelha") {
            gridRows = GridLayout(this).apply { columnCount = 3; useDefaultMargins = false }
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
        countView.text = when {
            selected.isNotEmpty() -> selected.size.toString() + " selecionado(s) • " + files.size + " itens"
            files.isEmpty() -> "Pasta vazia"
            else -> files.size.toString() + if (files.size == 1) " item" else " itens"
        }
        files.forEach { addFileRow(it) }
        updateSelectionBar()
    }

    private fun renderBreadcrumbs() {
        if (!::breadcrumbRow.isInitialized || !::rootDir.isInitialized || !::currentDir.isInitialized) return
        breadcrumbRow.removeAllViews()
        val root = rootDir.canonicalFile
        val current = currentDir.canonicalFile
        val rootIsCurrent = pathKey(root) == pathKey(current)
        val rootSegment = TextView(this).apply {
            text = "Armazenamento interno"
            textSize = 12f
            setTextColor(if (rootIsCurrent) 0xFFD8D3FF.toInt() else 0xFFE2E2E8.toInt())
            setPadding(dp(8), dp(5), dp(8), dp(5))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                currentDir = rootDir
                selected.clear()
                refreshFiles()
            }
        }
        breadcrumbRow.addView(rootSegment, LinearLayout.LayoutParams(-2, -2))

        val relative = if (pathKey(current) == pathKey(root)) "" else
            current.path.removePrefix(root.path).trimStart(File.separatorChar)
        var cursor = root
        relative.split(File.separatorChar).filter { it.isNotBlank() }.forEach { part ->
            breadcrumbRow.addView(TextView(this).apply {
                text = "›"
                textSize = 18f
                setTextColor(0xFF858596.toInt())
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-2, -2))
            cursor = File(cursor, part)
            val target = cursor
            val isCurrent = pathKey(target) == pathKey(current)
            val segment = TextView(this).apply {
                text = part
                textSize = 12f
                setTextColor(if (isCurrent) 0xFFD8D3FF.toInt() else 0xFFE2E2E8.toInt())
                setPadding(dp(6), dp(5), dp(8), dp(5))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (target.exists() && target.isDirectory && isWithinRoot(target)) {
                        currentDir = target
                        selected.clear()
                        refreshFiles()
                    }
                }
            }
            breadcrumbRow.addView(segment, LinearLayout.LayoutParams(-2, -2))
        }
    }

    private fun updateSelectionBar() {
        val bar = selectionBar ?: return
        val hasSelection = selected.isNotEmpty()
        bar.visibility = if (hasSelection) View.VISIBLE else View.GONE
        addFab?.visibility = if (hasSelection) View.GONE else View.VISIBLE
    }

    private fun safeList(dir: File): List<File> {
        return try { dir.listFiles()?.toList() ?: emptyList() } catch (_: SecurityException) { emptyList() }
    }

    private fun addParentRow() {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(12), dp(8))
            minimumHeight = dp(76)
            background = roundedBackground(0xFF24242A.toInt(), 12)
            setOnClickListener { goUp() }
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_folder_up)
            contentDescription = "Subir um diretório"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        item.addView(icon, LinearLayout.LayoutParams(dp(64), dp(54)))
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        textColumn.addView(label("..", 17, 0xFFF4F4F6.toInt(), true))
        textColumn.addView(label("Subir um diretório", 12, 0xFFACACB6.toInt(), false).apply {
            setPadding(0, dp(3), 0, 0)
        })
        item.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        rows.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
    }

    private fun addFileRow(file: File) {
        val key = pathKey(file)
        val isSelected = selected.contains(key)
        val inGrid = gridRows != null
        val item = LinearLayout(this).apply {
            orientation = if (inGrid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (inGrid) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            setPadding(dp(if (inGrid) 8 else 4), dp(if (inGrid) 12 else 6),
                dp(if (inGrid) 8 else 6), dp(if (inGrid) 12 else 6))
            background = roundedBackground(
                if (isSelected) selectedRowColor() else if (inGrid) rowSurfaceColor() else pageBackgroundColor(),
                if (inGrid) 10 else 4
            )
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(this).apply {
            setImageResource(iconForFile(file))
            contentDescription = if (file.isDirectory) "Pasta " + file.name else file.name
            scaleType = if (squareThumbnails) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            if (file.isDirectory) folderColorValue(file)?.let { setColorFilter(it, android.graphics.PorterDuff.Mode.SRC_IN) }
        }
        val iconWidth = dp(if (inGrid) 72 else 48)
        val iconHeight = dp(if (inGrid) 66 else 48)
        if (inGrid) item.addView(icon, LinearLayout.LayoutParams(iconWidth, iconHeight).apply { gravity = Gravity.CENTER_HORIZONTAL })
        else item.addView(icon, LinearLayout.LayoutParams(iconWidth, iconHeight).apply { rightMargin = dp(8) })
        if (shouldLoadThumbnail(file)) loadThumbnailAsync(file, icon)

        val details = if (file.isDirectory) {
            val count = safeList(file).size
            count.toString() + if (count == 1) " item" else " itens"
        } else formatSize(file.length())
        val modified = if (file.lastModified() > 0L) DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.lastModified())) else ""
        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (inGrid) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            setPadding(if (inGrid) 0 else dp(4), 0, if (inGrid) 0 else dp(4), 0)
        }
        val name = TextView(this).apply {
            text = file.name.ifBlank { "(sem nome)" }
            textSize = if (inGrid) 13f else if (viewMode == "Compacto") 13f else 15f
            setTextColor(if (isSelected) accentColor() else primaryTextColor())
            typeface = if (isSelected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            maxLines = if (inGrid) 2 else if (viewMode == "Detalhado") 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = if (inGrid) Gravity.CENTER else Gravity.START
        }
        val metaText = buildString {
            if (showSize) append(details)
            if (showTime && modified.isNotEmpty() && !inGrid) {
                if (isNotEmpty()) append("   •   ")
                append(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(file.lastModified())))
            }
        }
        middle.addView(name, LinearLayout.LayoutParams(-1, -2))
        if (metaText.isNotEmpty()) middle.addView(TextView(this).apply {
            text = metaText
            textSize = 12f
            setTextColor(mutedTextColor())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = if (inGrid) Gravity.CENTER else Gravity.START
            setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        if (inGrid) {
            item.addView(middle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            val params = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0; height = -2
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            gridRows?.addView(item, params)
        } else {
            item.addView(middle, LinearLayout.LayoutParams(0, -2, 1f))
            if (showDate && modified.isNotEmpty()) item.addView(TextView(this).apply {
                text = modified
                textSize = 11f
                gravity = Gravity.END or Gravity.BOTTOM
                setTextColor(0xFF9999A3.toInt())
                maxLines = 1
            }, LinearLayout.LayoutParams(dp(72), -2).apply { leftMargin = dp(4) })
            rows.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
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
        item.setOnLongClickListener { toggleSelection(file); true }
    }

    private fun iconForFile(file: File): Int {
        if (file.isDirectory) {
            return when (file.name.lowercase(Locale.ROOT)) {
                "android" -> R.drawable.ic_folder_android
                "dcim", "pictures", "picture", "collage", "myalbums" -> R.drawable.ic_folder_camera
                "download", "downloads" -> R.drawable.ic_folder_download
                "music", "musicas" -> R.drawable.ic_folder_music
                "movies", "videos" -> R.drawable.ic_folder_video
                "documents", "documentos" -> R.drawable.ic_folder_documents
                else -> R.drawable.ic_folder
            }
        }
        return when (extension(file.name)) {
            "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "lz4", "zst", "tgz", "tbz", "iso", "cab", "arj" -> R.drawable.ic_archive
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif", "tif", "tiff", "svg" -> R.drawable.ic_image
            "mp3", "wav", "ogg", "m4a", "flac", "aac", "opus", "wma", "mid", "midi" -> R.drawable.ic_audio
            "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "mpeg", "mpg" -> R.drawable.ic_video
            "apk", "aab", "apks", "xapk" -> R.drawable.ic_android_file
            "pdf" -> R.drawable.ic_pdf
            "txt", "md", "markdown", "log", "readme", "nfo" -> R.drawable.ic_text_file
            "json", "xml", "yaml", "yml", "properties", "ini", "cfg", "conf", "toml",
            "java", "kt", "kts", "js", "ts", "tsx", "jsx", "py", "html", "htm", "css",
            "scss", "sql", "sh", "bat", "c", "cpp", "h", "hpp", "gradle", "gitignore" -> R.drawable.ic_code_file
            "doc", "docx", "odt", "rtf", "pages", "epub" -> R.drawable.ic_document_file
            "xls", "xlsx", "ods", "csv", "tsv" -> R.drawable.ic_spreadsheet_file
            "glb", "gltf", "obj", "stl", "fbx", "3mf", "dae", "ply", "blend" -> R.drawable.ic_model3d_file
            else -> R.drawable.ic_file
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
            "Criar novo…", "Comprimir em ZIP", "Comprimir em 7Z", "Pesquisar em subpastas", "Favoritos",
            "Ordenar", "Definições do explorador", "Atualizar", "Pasta de início"
        )
        AlertDialog.Builder(this).setTitle(tr("Opções")).setItems(options.map { tr(it) }.toTypedArray()) { _, which ->
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
                5 -> showCreateMenu()
                6 -> promptArchive(chosenOrVisible(), "zip")
                7 -> promptArchive(chosenOrVisible(), "7z")
                8 -> promptSearch()
                9 -> showFavorites()
                10 -> showSortMenu()
                11 -> showExplorerSettings()
                12 -> refreshFiles()
                13 -> { currentDir = rootDir; selected.clear(); refreshFiles() }
            }
        }.show()
    }

    private fun showSelectionActions() {
        val options = arrayOf("Comprimir em ZIP", "Comprimir em 7Z", "Renomear", "Informações", "Limpar seleção")
        AlertDialog.Builder(this)
            .setTitle("${selected.size} selecionado(s)")
            .setItems(options.map { tr(it) }.toTypedArray()) { _, which ->
                val items = chosen()
                when (which) {
                    0 -> promptArchive(items, "zip")
                    1 -> promptArchive(items, "7z")
                    2 -> if (items.size == 1) promptRename(items[0]) else toast("Para renomear, seleciona apenas um item.")
                    3 -> if (items.size == 1) showInfo(items[0]) else toast("Seleciona apenas um item para ver as informações.")
                    4 -> { selected.clear(); refreshFiles() }
                }
            }
            .show()
    }

    private fun showSortMenu() {
        val items = arrayOf("Nome", "Tamanho", "Data", "Tipo", if (descending) "Ordem crescente" else "Ordem decrescente")
        AlertDialog.Builder(this).setTitle(tr("Ordenar ficheiros")).setItems(items.map { tr(it) }.toTypedArray()) { _, which ->
            if (which < 4) sortBy = items[which] else descending = !descending
            savePreferences()
            if (settingsOpen) renderSettingsScreen() else refreshFiles()
        }.show()
    }

    private fun showExplorerSettings() {
        settingsOpen = true
        settingsCategory = null
        renderSettingsScreen()
    }

    private fun renderSettingsScreen() {
        val category = settingsCategory
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBackgroundColor())
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(12), 0)
            setBackgroundColor(toolbarBackgroundColor())
        }
        toolbar.addView(toolbarIcon(R.drawable.ic_arrow_back, tr("Voltar")) {
            if (settingsCategory != null) {
                settingsCategory = null
                renderSettingsScreen()
            } else {
                settingsOpen = false
                renderBrowser()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(58)))
        toolbar.addView(label(tr(category ?: "Configurações"), 22, primaryTextColor(), true),
            LinearLayout.LayoutParams(0, dp(58), 1f))
        page.addView(toolbar, LinearLayout.LayoutParams(-1, dp(58)))
        page.addView(View(this).apply { setBackgroundColor(accentColor()) },
            LinearLayout.LayoutParams(-1, dp(2)))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(18))
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        when (category) {
            null -> {
                addSettingChoice(content, "Geral", "2 idiomas", "Idioma, notificações e informações") {
                    settingsCategory = "Geral"; renderSettingsScreen()
                }
                addSettingChoice(content, "Interface", uiTheme, "Tema, cor de destaque e aparência") {
                    settingsCategory = "Interface"; renderSettingsScreen()
                }
                addSettingChoice(content, "Explorador", tr(viewMode), "Ordenação, ficheiros ocultos e miniaturas") {
                    settingsCategory = "Explorador"; renderSettingsScreen()
                }
                addSettingChoice(content, "Compressão", compressionName(), "Formato, velocidade e opções de arquivos") {
                    settingsCategory = "Compressão"; renderSettingsScreen()
                }
                addSettingChoice(content, "ROOT", "Não configurado", "O acesso root não está ativo no Archiver") {
                    settingsCategory = "ROOT"; renderSettingsScreen()
                }
                addSettingChoice(content, "Plugins", "Nenhum", "O sistema de plugins ainda não está disponível") {
                    settingsCategory = "Plugins"; renderSettingsScreen()
                }
            }
            "Geral" -> {
                addSettingChoice(content, "Idioma", if (appLanguage == "en") "English" else "Português",
                    "Escolher o idioma da interface") { showLanguageMenu() }
                val notificationsEnabled = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                addSettingChoice(content, "Notificações", if (notificationsEnabled) "Ligado" else "Desligado",
                    "Progresso e conclusão das operações de ficheiros") {
                    if (Build.VERSION.SDK_INT >= 33 && !notificationsEnabled) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 742)
                    } else {
                        toast(if (notificationsEnabled) "As notificações estão disponíveis." else "Ativa as notificações nas definições do Android.")
                    }
                }
                addSettingChoice(content, "Sobre o Archiver", "0.4.0", "Gestor de ficheiros Android nativo em Kotlin") {
                    AlertDialog.Builder(this).setTitle("Archiver 0.4.0")
                        .setMessage("Gestor de ficheiros Android independente, inspirado em exploradores clássicos. ZIP/7Z, editor de texto, miniaturas e operações com progresso.")
                        .setPositiveButton(tr("Fechar"), null).show()
                }
            }
            "Interface" -> {
                addSettingChoice(content, "Tema da interface", tr(uiTheme), "Escuro, preto OLED ou claro") { showThemeMenu() }
                addSettingChoice(content, "Cor de destaque", tr(accentName), "Personaliza os controlos e realces") { showAccentMenu() }
                addSettingChoice(content, "Cores das pastas", "Por pasta", "Escolhe uma cor no menu de cada pasta") {
                    toast("Abre o menu de uma pasta e escolhe Cor da pasta.")
                }
            }
            "Explorador" -> {
                addSettingChoice(content, "Ordenar arquivos", tr(sortBy), "Escolher a ordem dos ficheiros") { showSortMenu() }
                addSettingSwitch(content, "Decrescente", "Inverte a ordem de ordenação dos arquivos", descending) { descending = it }
                addSettingSwitch(content, "Usar item \"..\" ", "Permite subir para o diretório anterior a partir da lista", useParentEntry) { useParentEntry = it }
                addSettingSwitch(content, "Exibir arquivos ocultos", "Mostra ficheiros e diretórios cujo nome começa por ponto", showHidden) { showHidden = it }
                addSettingChoice(content, "Modo de exibição", tr(viewMode), "Detalhado, compacto ou grelha") { showViewModeMenu() }
                addSettingSwitch(content, "Exibir tamanho", "Mostra o tamanho dos ficheiros na lista", showSize) { showSize = it }
                addSettingSwitch(content, "Exibir data", "Mostra a data da última modificação", showDate) { showDate = it }
                addSettingSwitch(content, "Exibir hora", "Mostra a hora da última modificação", showTime, showDate) { showTime = it }
                addSettingSwitch(content, "Miniaturas", "Permite a exibição de miniaturas", showThumbnails) { showThumbnails = it }
                addSettingSwitch(content, "Miniaturas do PDF", "Mostrar miniaturas de documentos PDF", showPdfThumbnails) { showPdfThumbnails = it }
                addSettingSwitch(content, "Miniaturas de vídeo", "Mostrar miniaturas de vídeo", showVideoThumbnails) { showVideoThumbnails = it }
                addSettingSwitch(content, "Capa do álbum", "Mostrar a capa do álbum nos ficheiros de música", showAlbumCovers) { showAlbumCovers = it }
                addSettingSwitch(content, "Ícones do APK", "Exibir os ícones das aplicações APK", showApkIcons) { showApkIcons = it }
                addSettingSwitch(content, "Miniaturas quadradas", "Recorta as miniaturas para formato quadrado", squareThumbnails) { squareThumbnails = it }
            }
            "Compressão" -> {
                addSettingChoice(content, "Nível de compressão", compressionName(), "Sem compressão, rápida, normal ou ultra") { showCompressionMenu() }
                content.addView(label(
                    "ZIP: suporta ZipCrypto, AES-256 e divisão em volumes. 7Z: criação e extração com níveis de compressão; palavra-passe e volumes não estão ligados nesta versão.",
                    14, mutedTextColor(), false
                ).apply { setPadding(dp(8), dp(16), dp(8), dp(16)) }, LinearLayout.LayoutParams(-1, -2))
            }
            "ROOT" -> {
                content.addView(label(
                    "O Archiver não implementa acesso ROOT nem Shizuku nesta versão. A permissão Android «Gerir todos os ficheiros» permite gerir o armazenamento partilhado, mas não desbloqueia as pastas privadas de outras aplicações nem concede root.",
                    15, mutedTextColor(), false
                ).apply { setPadding(dp(8), dp(20), dp(8), dp(20)) }, LinearLayout.LayoutParams(-1, -2))
            }
            "Plugins" -> {
                content.addView(label(
                    "Ainda não existe um sistema de plugins nesta versão. Os formatos e leitores disponíveis são os integrados na aplicação ou nas aplicações instaladas no dispositivo.",
                    15, mutedTextColor(), false
                ).apply { setPadding(dp(8), dp(20), dp(8), dp(20)) }, LinearLayout.LayoutParams(-1, -2))
            }
        }
        setContentView(page)
    }

    private fun addSettingChoice(container: LinearLayout, title: String, value: String, summary: String, click: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(15), dp(4), dp(15))
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        copy.addView(label(tr(title), 16, primaryTextColor(), false))
        copy.addView(label(tr(value), 14, accentColor(), false).apply { setPadding(0, dp(4), 0, 0) })
        copy.addView(label(tr(summary), 12, mutedTextColor(), false).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(accentColor())
        }, LinearLayout.LayoutParams(dp(32), dp(44)))
        container.addView(row, LinearLayout.LayoutParams(-1, -2))
        container.addView(View(this).apply { setBackgroundColor(0xFF333339.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun addSettingSwitch(
        container: LinearLayout,
        title: String,
        summary: String,
        checked: Boolean,
        enabled: Boolean = true,
        change: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(14), dp(2), dp(14))
            isClickable = true
            isFocusable = true
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        copy.addView(label(tr(title), 16, if (enabled) primaryTextColor() else 0xFF777780.toInt(), false))
        copy.addView(label(tr(summary), 13, if (enabled) mutedTextColor() else 0xFF777780.toInt(), false).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(this).apply {
            isChecked = checked
            isEnabled = enabled
            contentDescription = title
            setOnCheckedChangeListener { _, value ->
                change(value)
                savePreferences()
                renderSettingsScreen()
            }
        }
        row.addView(toggle, LinearLayout.LayoutParams(dp(58), -2).apply { leftMargin = dp(10) })
        row.setOnClickListener { if (enabled) toggle.isChecked = !toggle.isChecked }
        container.addView(row, LinearLayout.LayoutParams(-1, -2))
        container.addView(View(this).apply { setBackgroundColor(0xFF333339.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun showViewModeMenu() {
        val modes = arrayOf("Detalhado", "Compacto", "Grelha")
        val checked = modes.indexOf(viewMode).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Modo de exibição").setSingleChoiceItems(modes, checked) { dialog, which ->
            viewMode = modes[which]
            savePreferences()
            dialog.dismiss()
            if (settingsOpen) renderSettingsScreen() else refreshFiles()
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
            if (settingsOpen) renderSettingsScreen()
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun showCreateMenu() {
        val options = arrayOf("Nova pasta", "Novo ficheiro vazio", "Criar arquivo ZIP", "Criar arquivo 7Z")
        AlertDialog.Builder(this).setTitle("Criar").setItems(options) { _, which ->
            when (which) {
                0 -> promptNewItem(true)
                1 -> promptNewItem(false)
                2 -> promptArchive(chosen(), "zip")
                3 -> promptArchive(chosen(), "7z")
            }
        }.show()
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
        if (file.isDirectory) options += "Cor da pasta"
        AlertDialog.Builder(this).setTitle(file.name).setItems(options.map { tr(it) }.toTypedArray()) { _, which ->
            when (options[which]) {
                "Abrir pasta" -> { currentDir = file; selected.clear(); refreshFiles() }
                "Cor da pasta" -> showFolderColorMenu(file)
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
        if (isEditableTextFile(file) && file.length() <= 4L * 1024L * 1024L) {
            showTextEditor(file)
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(file.name)) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(if (isEditableTextFile(file)) "O ficheiro é demasiado grande para o editor integrado e não existe outra aplicação para o abrir." else "Não existe uma aplicação para abrir este ficheiro.")
        } catch (e: Exception) {
            toast("Não foi possível abrir: " + (e.message ?: "erro desconhecido"))
        }
    }

    private fun isEditableTextFile(file: File): Boolean = extension(file.name) in setOf(
        "txt", "md", "markdown", "log", "nfo", "readme", "json", "xml", "yaml", "yml",
        "properties", "ini", "cfg", "conf", "toml", "java", "kt", "kts", "js", "ts", "tsx",
        "jsx", "py", "html", "htm", "css", "scss", "sql", "sh", "bat", "c", "cpp", "h",
        "hpp", "gradle", "gitignore", "csv", "tsv"
    )

    private fun showTextEditor(file: File) {
        val sourceText = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            toast("Não foi possível ler o ficheiro: " + (e.message ?: "erro"))
            return
        }
        editorOpen = true
        editorDirty = false
        editorSavedText = sourceText
        editorFile = file
        closeEditorAfterSave = false

        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBackgroundColor())
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
            setBackgroundColor(toolbarBackgroundColor())
        }
        toolbar.addView(toolbarIcon(R.drawable.ic_arrow_back, "Voltar ao gestor") { requestLeaveTextEditor() },
            LinearLayout.LayoutParams(dp(48), dp(58)))
        toolbar.addView(label(file.name, 16, 0xFFFFFFFF.toInt(), true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, dp(58), 1f))
        toolbar.addView(actionButton("Guardar") { saveTextEditor() }, LinearLayout.LayoutParams(-2, dp(44)))
        page.addView(toolbar, LinearLayout.LayoutParams(-1, dp(58)))
        page.addView(View(this).apply { setBackgroundColor(accentColor()) }, LinearLayout.LayoutParams(-1, dp(2)))

        val status = label("UTF-8  •  " + sourceText.count { it == '\n' }.plus(1) + " linhas", 11, 0xFFAAAAB4.toInt(), false)
            .apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }
        val input = EditText(this).apply {
            setText(sourceText)
            setTextColor(primaryTextColor())
            setHintTextColor(0xFF8F8F99.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(12), dp(14), dp(24))
            setBackgroundColor(pageBackgroundColor())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setHorizontallyScrolling(false)
        }
        editorInput = input
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                editorDirty = text?.toString() != editorSavedText
                status.text = "UTF-8  •  " + (text?.count { it == '\n' } ?: 0).plus(1) + " linhas" +
                    if (editorDirty) "  •  alterações por guardar" else ""
            }
            override fun afterTextChanged(text: android.text.Editable?) = Unit
        })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(input, FrameLayout.LayoutParams(-1, -1))
        }
        page.addView(status, LinearLayout.LayoutParams(-1, -2))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        input.requestFocus()
    }

    private fun saveTextEditor() {
        val target = editorFile ?: return
        val content = editorInput?.text?.toString() ?: return
        val bytes = content.toByteArray(Charsets.UTF_8)
        runTask("A guardar texto…", bytes.size.toLong(), 1L) { meter ->
            meter.show(target)
            val parent = target.parentFile ?: throw IOException("A pasta do ficheiro não está disponível.")
            val temp = File.createTempFile(".archiver-", ".tmp", parent)
            val backup = File.createTempFile(".archiver-backup-", ".bak", parent)
            backup.delete()
            try {
                FileOutputStream(temp).use { output ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val amount = minOf(32 * 1024, bytes.size - offset)
                        output.write(bytes, offset, amount)
                        offset += amount
                        meter.bytesRead(amount.toLong(), target)
                    }
                }
                if (!target.renameTo(backup)) throw IOException("Não foi possível proteger a versão anterior do ficheiro.")
                if (!temp.renameTo(target)) {
                    backup.renameTo(target)
                    throw IOException("Não foi possível substituir o ficheiro original.")
                }
                backup.delete()
                meter.itemDone(target)
                "Ficheiro guardado: " + target.name
            } finally {
                temp.delete()
                if (backup.exists() && target.exists()) backup.delete()
            }
        }
    }

    private fun requestLeaveTextEditor() {
        if (!editorDirty) {
            closeTextEditor()
            return
        }
        AlertDialog.Builder(this).setTitle("Alterações por guardar")
            .setMessage("Queres guardar as alterações antes de sair do editor?")
            .setPositiveButton("Sair sem guardar") { _, _ -> closeTextEditor() }
            .setNegativeButton("Continuar a editar", null)
            .setNeutralButton("Guardar") { _, _ ->
                closeEditorAfterSave = true
                saveTextEditor()
            }.show()
    }

    private fun closeTextEditor() {
        editorOpen = false
        editorDirty = false
        editorSavedText = ""
        editorFile = null
        editorInput = null
        closeEditorAfterSave = false
        renderBrowser()
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
                runTask("A eliminar ficheiros…", 0L, 1L) { meter ->
                    val totals = measureFiles(files) { meter.show(it) }
                    meter.setTotals(0L, totals.entries)
                    files.forEach { file ->
                        meter.show(file)
                        if (!deleteRecursively(file) { deleted -> meter.itemDone(deleted) }) {
                            throw IOException("Não foi possível eliminar " + file.name)
                        }
                    }
                    "Eliminação concluída: " + totals.entries + " itens."
                }
            }.show()
    }

    private fun deleteRecursively(file: File, onDeleted: ((File) -> Unit)? = null): Boolean {
        return try {
            if (file.isDirectory) {
                val children = file.listFiles() ?: return false
                for (child in children) if (!deleteRecursively(child, onDeleted)) return false
            }
            val deleted = file.delete()
            if (deleted) onDeleted?.invoke(file)
            deleted
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
        val effectiveSources = sources.filterNot { cut && it.parentFile?.canonicalFile == target.canonicalFile }
        if (effectiveSources.isEmpty()) { toast("Os itens já estão nesta pasta."); return }
        runTask(if (cut) "A mover…" else "A copiar…", 0L, 1L) { meter ->
            val totals = measureFiles(effectiveSources) { meter.show(it) }
            meter.setTotals(totals.bytes, totals.entries * if (cut) 2L else 1L)
            var count = 0
            for (source in effectiveSources) {
                val destination = File(target, uniqueName(target, source.name))
                meter.show(source)
                copyFileTree(source, destination, meter)
                if (cut && !deleteRecursively(source) { deleted -> meter.itemDone(deleted) }) {
                    throw IOException("Copiado, mas não foi possível remover a origem: " + source.name)
                }
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

    private fun copyFileTree(source: File, dest: File, meter: TaskProgress? = null) {
        if (source.isDirectory) {
            if (!dest.mkdirs()) throw IOException("Não foi possível criar " + dest.name)
            meter?.show(source)
            meter?.itemDone(source)
            source.listFiles()?.forEach { child ->
                copyFileTree(child, File(dest, child.name), meter)
            } ?: throw IOException("Não foi possível ler a pasta " + source.name)
        } else {
            if (dest.exists()) throw IOException("O destino já existe: " + dest.name)
            BufferedInputStream(FileInputStream(source)).use { input ->
                BufferedOutputStream(FileOutputStream(dest)).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        meter?.bytesRead(count.toLong(), source)
                    }
                }
            }
            meter?.itemDone(source)
        }
    }

    private fun promptSearch() {
        val input = EditText(this).apply { hint = "Nome ou padrão, por exemplo *.zip"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Pesquisar ficheiros").setView(input)
            .setNegativeButton("Cancelar", null).setPositiveButton("Pesquisar") { _, _ ->
                val query = input.text.toString().trim().lowercase(Locale.ROOT)
                if (query.isBlank()) { toast("Escreve um nome para pesquisar."); return@setPositiveButton }
                val start = currentDir
                runTask("A pesquisar…", 0L, 1L) { meter ->
                    val entries = countFilesForSearch(start) { meter.show(it) }
                    meter.setTotals(0L, entries)
                    val hits = mutableListOf<File>()
                    searchTree(start, query, hits, 0, meter)
                    runOnUiThread { showSearchResults(hits) }
                    "Pesquisa concluída: " + hits.size + " resultado(s)."
                }
            }.show()
    }

    private fun searchTree(directory: File, query: String, hits: MutableList<File>, depth: Int, meter: TaskProgress? = null) {
        if (depth > 60 || hits.size >= 300) return
        val children = try { directory.listFiles() } catch (_: Exception) { null } ?: return
        for (child in children) {
            if (hits.size >= 300) return
            if (!showHidden && child.name.startsWith(".")) continue
            val name = child.name.lowercase(Locale.ROOT)
            val matches = if (query.contains('*') || query.contains('?')) {
                val regex = query.replace(".", "\\.").replace("*", ".*").replace("?", ".")
                name.matches(Regex("^" + regex + "$"))
            } else name.contains(query)
            if (matches) hits.add(child)
            if (child.isDirectory) searchTree(child, query, hits, depth + 1, meter)
            meter?.itemDone(child)
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

    private fun promptArchive(files: List<File>, initialFormat: String) {
        if (files.isEmpty()) { toast("Seleciona pelo menos um ficheiro ou pasta."); return }

        val formatNames = arrayOf("ZIP", "7Z")
        val formatPicker = makeSpinner(formatNames, if (initialFormat == "7z") 1 else 0)
        val levelNames = arrayOf("Sem compressão", "Rápida", "Normal", "Ultra")
        val levelPicker = makeSpinner(levelNames, compressionLevel.coerceIn(0, 3))
        val encryptionNames = arrayOf("Nenhuma", "ZipCrypto", "AES-256")
        val encryptionPicker = makeSpinner(encryptionNames)
        val volumeNames = arrayOf("Não", "64 MB", "100 MB", "250 MB", "500 MB", "1 GB")
        val volumeBytes = longArrayOf(
            0L,
            64L * 1024L * 1024L,
            100L * 1024L * 1024L,
            250L * 1024L * 1024L,
            500L * 1024L * 1024L,
            1024L * 1024L * 1024L
        )
        val volumePicker = makeSpinner(volumeNames)
        val passwordInput = EditText(this).apply {
            hint = "Palavra-passe (opcional)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val suffix = if (initialFormat == "7z") ".7z" else ".zip"
        val input = EditText(this).apply {
            setSingleLine(true)
            val suggestion = if (files.size == 1) files[0].name else "Arquivo"
            setText(if (suggestion.lowercase(Locale.ROOT).endsWith(suffix)) suggestion else suggestion + suffix)
        }
        val deleteOriginals = CheckBox(this).apply { text = "Eliminar originais depois de comprimir com sucesso" }
        val separateArchives = CheckBox(this).apply { text = "Criar um arquivo separado por item selecionado" }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(6))
        }
        fun addField(title: String, view: View) {
            form.addView(label(title, 13, 0xFFAAAAB4.toInt(), false).apply {
                setPadding(0, dp(9), 0, dp(3))
            }, LinearLayout.LayoutParams(-1, -2))
            form.addView(view, LinearLayout.LayoutParams(-1, dp(46)))
        }
        addField("Arquivo", input)
        addField("Formato do arquivo", formatPicker)
        addField("Nível de compressão", levelPicker)
        addField("Criptografia (ZIP)", encryptionPicker)
        form.addView(passwordInput, LinearLayout.LayoutParams(-1, dp(48)))
        addField("Dividir em volumes (ZIP)", volumePicker)
        form.addView(deleteOriginals, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        form.addView(separateArchives, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        fun updateZipOptions() {
            val zipSelected = formatPicker.selectedItemPosition == 0
            encryptionPicker.isEnabled = zipSelected
            volumePicker.isEnabled = zipSelected
            passwordInput.isEnabled = zipSelected && encryptionPicker.selectedItemPosition > 0
            if (!zipSelected) {
                encryptionPicker.setSelection(0)
                volumePicker.setSelection(0)
                passwordInput.setText("")
            }
        }
        formatPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateZipOptions()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        encryptionPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                passwordInput.isEnabled = formatPicker.selectedItemPosition == 0 && position > 0
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        updateZipOptions()

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(form, FrameLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this).setTitle("Criação de arquivo")
            .setMessage("Escolhe o formato, a compressão e, para ZIP, opções de segurança e volumes.")
            .setView(scroll)
            .setNegativeButton(tr("Cancelar"), null)
            .setPositiveButton(tr("Criar"), null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val archiveFormat = if (formatPicker.selectedItemPosition == 1) "7z" else "zip"
                        val chosenSuffix = if (archiveFormat == "7z") ".7z" else ".zip"
                        val encryption = encryptionNames[encryptionPicker.selectedItemPosition]
                        val password = passwordInput.text.toString()
                        val splitSize = if (archiveFormat == "zip") volumeBytes[volumePicker.selectedItemPosition] else 0L
                        if (archiveFormat == "7z" && (encryption != "Nenhuma" || splitSize > 0L)) {
                            toast("Senha e volumes divididos estão disponíveis apenas para ZIP nesta versão.")
                            return@setOnClickListener
                        }
                        if (encryption != "Nenhuma" && password.isBlank()) {
                            passwordInput.error = "Escreve uma palavra-passe."
                            passwordInput.requestFocus()
                            return@setOnClickListener
                        }

                        var name = input.text.toString().trim()
                        if (name.isBlank() || !validName(name)) { toast("Nome inválido."); return@setOnClickListener }
                        name = name.removeSuffix(".zip").removeSuffix(".7z") + chosenSuffix
                        val sources = files.toList()
                        val removeSources = deleteOriginals.isChecked
                        val separate = separateArchives.isChecked
                        compressionLevel = levelPicker.selectedItemPosition
                        savePreferences()

                        val outputFiles = if (separate) {
                            val reserved = linkedSetOf<String>()
                            sources.map { source ->
                                val stem = source.nameWithoutExtension.ifBlank { source.name }
                                var candidate = stem + chosenSuffix
                                var index = 2
                                while (File(currentDir, candidate).exists() || !reserved.add(candidate)) {
                                    candidate = "$stem ($index)$chosenSuffix"
                                    index++
                                }
                                File(currentDir, candidate)
                            }
                        } else {
                            val unique = try { uniqueName(currentDir, name) } catch (_: Exception) {
                                toast("Nome de destino inválido.")
                                return@setOnClickListener
                            }
                            listOf(File(currentDir, unique))
                        }

                        dialog.dismiss()
                        runTask(if (separate) "A criar arquivos separados…" else "A criar $chosenSuffix…", 0L, 1L) { meter ->
                            val totals = measureFiles(sources) { meter.show(it) }
                            meter.setTotals(totals.bytes, totals.entries * if (removeSources) 2L else 1L)
                            try {
                                if (separate) {
                                    sources.zip(outputFiles).forEach { (source, destination) ->
                                        meter.show(source)
                                        if (archiveFormat == "7z") createSevenZip(listOf(source), destination, meter)
                                        else createZip(
                                            listOf(source), destination, meter,
                                            password.takeIf { encryption != "Nenhuma" }, encryption, splitSize
                                        )
                                    }
                                } else {
                                    val destination = outputFiles.single()
                                    if (archiveFormat == "7z") createSevenZip(sources, destination, meter)
                                    else createZip(
                                        sources, destination, meter,
                                        password.takeIf { encryption != "Nenhuma" }, encryption, splitSize
                                    )
                                }
                            } catch (e: Exception) {
                                outputFiles.forEach { deleteArchiveOutputs(it) }
                                throw e
                            }

                            if (removeSources) sources.forEach { source ->
                                meter.show(source)
                                if (!deleteRecursively(source) { deleted -> meter.itemDone(deleted) }) {
                                    throw IOException("Arquivo criado, mas não foi possível eliminar " + source.name)
                                }
                            }
                            selected.clear()
                            val preview = outputFiles.take(3).joinToString(", ") { it.name }
                            val more = if (outputFiles.size > 3) " e mais ${outputFiles.size - 3}" else ""
                            if (removeSources) "Criado(s) ${outputFiles.size} arquivo(s); originais eliminados."
                            else "Criado(s) ${outputFiles.size} arquivo(s): $preview$more"
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun makeSpinner(options: Array<String>, selectedIndex: Int = 0): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter<String>(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                options.toList()
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(selectedIndex.coerceIn(0, options.lastIndex))
        }
    private fun compressionName(): String = when (compressionLevel) {
        0 -> "sem compressão"
        1 -> "rápida"
        3 -> "ultra"
        else -> "normal"
    }

    private fun createZip(
        sources: List<File>,
        destination: File,
        meter: TaskProgress,
        password: String? = null,
        encryption: String = "Nenhuma",
        splitBytes: Long = 0L
    ) {
        if (!password.isNullOrBlank() || splitBytes > 0L) {
            createZipWithZip4j(sources, destination, meter, password, encryption, splitBytes)
            return
        }
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { zip ->
            zip.setLevel(when (compressionLevel) {
                0 -> java.util.zip.Deflater.NO_COMPRESSION
                1 -> java.util.zip.Deflater.BEST_SPEED
                3 -> java.util.zip.Deflater.BEST_COMPRESSION
                else -> java.util.zip.Deflater.DEFAULT_COMPRESSION
            })
            sources.forEach { source ->
                if (source.canonicalFile != destination.canonicalFile) addToZip(zip, source, source.name, destination, meter)
            }
        }
    }

    private data class ZipFileSource(val file: File, val entryName: String)
    private data class ZipFolderSource(val file: File, val entryName: String, val empty: Boolean)

    private fun zip4jParameters(entryName: String? = null, encryption: String = "Nenhuma"): ZipParameters =
        ZipParameters().apply {
            setCompressionMethod(
                if (this@MainActivity.compressionLevel == 0) net.lingala.zip4j.model.enums.CompressionMethod.STORE
                else net.lingala.zip4j.model.enums.CompressionMethod.DEFLATE
            )
            if (this@MainActivity.compressionLevel != 0) {
                setCompressionLevel(when (this@MainActivity.compressionLevel) {
                    1 -> net.lingala.zip4j.model.enums.CompressionLevel.FASTEST
                    3 -> net.lingala.zip4j.model.enums.CompressionLevel.ULTRA
                    else -> net.lingala.zip4j.model.enums.CompressionLevel.NORMAL
                })
            }
            if (entryName != null) setFileNameInZip(entryName)
            if (encryption != "Nenhuma") {
                setEncryptFiles(true)
                setEncryptionMethod(
                    if (encryption == "ZipCrypto") net.lingala.zip4j.model.enums.EncryptionMethod.ZIP_STANDARD
                    else net.lingala.zip4j.model.enums.EncryptionMethod.AES
                )
                if (encryption == "AES-256") {
                    setAesKeyStrength(net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256)
                }
            }
        }

    private fun createZipWithZip4j(
        sources: List<File>,
        destination: File,
        meter: TaskProgress,
        password: String?,
        encryption: String,
        splitBytes: Long
    ) {
        val archive = Zip4jFile(destination, password?.toCharArray())
        archive.setRunInThread(true)
        val parameters = zip4jParameters(encryption = encryption)

        if (splitBytes > 0L) {
            when {
                sources.all { it.isFile } ->
                    archive.createSplitZipFile(sources, parameters, true, splitBytes)
                sources.size == 1 && sources[0].isDirectory ->
                    archive.createSplitZipFileFromFolder(sources[0], parameters, true, splitBytes)
                else -> throw IOException("Para dividir o ZIP, seleciona apenas ficheiros ou uma única pasta por arquivo.")
            }
            val monitor = archive.getProgressMonitor()
            while (monitor.getState() == ProgressMonitor.State.BUSY) {
                val current = monitor.getFileName()?.takeIf { it.isNotBlank() } ?: destination.name
                meter.report(monitor.getPercentDone(), current)
                Thread.sleep(120L)
            }
            if (monitor.getResult() == ProgressMonitor.Result.ERROR) {
                throw IOException(monitor.getException()?.message ?: "Falha ao criar ZIP dividido.")
            }
            val visited = mutableListOf<File>()
            measureFiles(sources) { visited.add(it) }
            val totalBytes = visited.filter { it.isFile }.sumOf { it.length().coerceAtLeast(0L) }
            if (totalBytes > 0L) meter.bytesRead(totalBytes, destination)
            visited.forEach { meter.itemDone(it) }
            return
        }

        val files = mutableListOf<ZipFileSource>()
        val directories = mutableListOf<ZipFolderSource>()
        val visited = HashSet<String>()
        fun collect(file: File, entryName: String, depth: Int) {
            if (depth > 80 || visited.size >= 200000) return
            val key = pathKey(file)
            if (!visited.add(key) || file.canonicalFile == destination.canonicalFile) return
            if (file.isDirectory) {
                val children = safeList(file)
                directories.add(ZipFolderSource(file, entryName, children.isEmpty()))
                children.forEach { child -> collect(child, entryName.trimEnd('/') + "/" + child.name, depth + 1) }
            } else {
                files.add(ZipFileSource(file, entryName))
            }
        }
        sources.forEach { source -> collect(source, source.name, 0) }

        val totalBytes = files.sumOf { it.file.length().coerceAtLeast(0L) }
        var completedBytes = 0L
        files.forEach { entry ->
            val entryParameters = zip4jParameters(entry.entryName, encryption)
            archive.addFile(entry.file, entryParameters)
            val monitor = archive.getProgressMonitor()
            var lastReported = 0L
            while (monitor.getState() == ProgressMonitor.State.BUSY) {
                val percent = monitor.getPercentDone().coerceIn(0, 100)
                val estimated = (entry.file.length().toDouble() * percent / 100.0).toLong()
                val delta = (estimated - lastReported).coerceAtLeast(0L)
                if (delta > 0L) {
                    meter.bytesRead(delta, entry.file)
                    lastReported = estimated
                } else {
                    meter.show(entry.file)
                }
                Thread.sleep(100L)
            }
            if (monitor.getResult() == ProgressMonitor.Result.ERROR) {
                throw IOException(monitor.getException()?.message ?: "Falha ao comprimir " + entry.file.name)
            }
            val remaining = (entry.file.length() - lastReported).coerceAtLeast(0L)
            if (remaining > 0L) meter.bytesRead(remaining, entry.file)
            completedBytes += entry.file.length().coerceAtLeast(0L)
            meter.itemDone(entry.file)
        }

        directories.filter { it.empty }.forEach { directory ->
            val folderParams = zip4jParameters(directory.entryName.trimEnd('/') + "/", "Nenhuma").apply {
                setCompressionMethod(net.lingala.zip4j.model.enums.CompressionMethod.STORE)
                setSourceExternalStream(true)
            }
            archive.addFolder(directory.file, folderParams)
            meter.itemDone(directory.file)
        }
        directories.filterNot { it.empty }.forEach { meter.itemDone(it.file) }
        if (files.isEmpty() && directories.isEmpty()) throw IOException("Não existem ficheiros para adicionar ao ZIP.")
        if (completedBytes < totalBytes) meter.bytesRead(totalBytes - completedBytes, destination)
    }

    private fun deleteArchiveOutputs(destination: File) {
        destination.delete()
        val parent = destination.parentFile ?: return
        val stem = destination.nameWithoutExtension
        val splitPattern = Regex(Regex.escape(stem) + "\\.z[0-9]{2,3}")
        safeList(parent).filter { candidate ->
            candidate.name == destination.name || splitPattern.matches(candidate.name)
        }.forEach { it.delete() }
    }

    private fun addToZip(zip: ZipOutputStream, source: File, entryName: String, destination: File, meter: TaskProgress) {
        if (source.canonicalFile == destination.canonicalFile) return
        meter.show(source)
        if (source.isDirectory) {
            val name = if (entryName.endsWith("/")) entryName else entryName + "/"
            zip.putNextEntry(ZipEntry(name))
            zip.closeEntry()
            meter.itemDone(source)
            val children = source.listFiles() ?: throw IOException("Não foi possível ler " + source.name)
            children.forEach { addToZip(zip, it, name + it.name, destination, meter) }
        } else {
            zip.putNextEntry(ZipEntry(entryName))
            BufferedInputStream(FileInputStream(source)).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    zip.write(buffer, 0, n)
                    meter.bytesRead(n.toLong(), source)
                }
            }
            zip.closeEntry()
            meter.itemDone(source)
        }
    }

    private fun createSevenZip(sources: List<File>, destination: File, meter: TaskProgress) {
        SevenZOutputFile(destination).use { seven ->
            when (compressionLevel) {
                0 -> seven.setContentCompression(SevenZMethod.COPY)
                1 -> seven.setContentCompression(SevenZMethod.DEFLATE)
                3 -> seven.setContentCompression(SevenZMethod.BZIP2)
                else -> seven.setContentCompression(SevenZMethod.LZMA2)
            }
            sources.forEach { source ->
                if (source.canonicalFile != destination.canonicalFile) addToSevenZip(seven, source, source.name, destination, meter)
            }
        }
    }

    private fun addToSevenZip(seven: SevenZOutputFile, source: File, entryName: String, destination: File, meter: TaskProgress) {
        if (source.canonicalFile == destination.canonicalFile) return
        meter.show(source)
        val entry = SevenZArchiveEntry()
        entry.name = entryName
        entry.lastModifiedDate = Date(maxOf(0L, source.lastModified()))
        if (source.isDirectory) {
            entry.isDirectory = true
            entry.setHasStream(false)
            seven.putArchiveEntry(entry)
            seven.closeArchiveEntry()
            meter.itemDone(source)
            val children = source.listFiles() ?: throw IOException("Não foi possível ler " + source.name)
            children.forEach { addToSevenZip(seven, it, entryName + "/" + it.name, destination, meter) }
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
                    meter.bytesRead(n.toLong(), source)
                }
            }
            seven.closeArchiveEntry()
            meter.itemDone(source)
        }
    }

    private fun extractZip(archive: File, destinationDir: File, makeSubfolder: Boolean) {
        runTask("A extrair ZIP…", 0L, 1L) { meter ->
            val totals = try { measureZipArchive(archive) } catch (_: Exception) { TaskTotals(0L, 1L) }
            meter.setTotals(totals.bytes, totals.entries)
            val destination = if (makeSubfolder) File(destinationDir, uniqueName(destinationDir, archive.nameWithoutExtension)) else destinationDir
            if (makeSubfolder && !destination.mkdirs()) throw IOException("Não foi possível criar a pasta de destino.")
            var count = 0
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val output = safeArchiveTarget(destination, entry.name, entry.isDirectory)
                    meter.show(output)
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
                                meter.bytesRead(n.toLong(), finalOut)
                            }
                        }
                    }
                    zip.closeEntry()
                    meter.itemDone(output)
                    count++
                }
            }
            "Extração ZIP concluída: " + count + " entradas."
        }
    }

    private fun extractSevenZip(archive: File, destinationDir: File, makeSubfolder: Boolean) {
        runTask("A extrair 7Z…", 0L, 1L) { meter ->
            val totals = try { measureSevenZipArchive(archive) } catch (_: Exception) { TaskTotals(0L, 1L) }
            meter.setTotals(totals.bytes, totals.entries)
            val destination = if (makeSubfolder) File(destinationDir, uniqueName(destinationDir, archive.nameWithoutExtension)) else destinationDir
            if (makeSubfolder && !destination.mkdirs()) throw IOException("Não foi possível criar a pasta de destino.")
            var count = 0
            SevenZFile(archive).use { seven ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = seven.nextEntry ?: break
                    val output = safeArchiveTarget(destination, entry.name, entry.isDirectory)
                    meter.show(output)
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
                                meter.bytesRead(n.toLong(), finalOut)
                            }
                        }
                    }
                    meter.itemDone(output)
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
        runTask("A testar ZIP…", 0L, 1L) { meter ->
            val totals = try { measureZipArchive(archive) } catch (_: Exception) { TaskTotals(0L, 1L) }
            meter.setTotals(totals.bytes, totals.entries)
            var count = 0
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val current = File(archive.parentFile, entry.name)
                    meter.show(current)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        if (n > 0) meter.bytesRead(n.toLong(), current)
                    }
                    zip.closeEntry()
                    meter.itemDone(current)
                    count++
                }
            }
            "Teste ZIP concluído: " + count + " entradas."
        }
    }

    private fun testSevenZip(archive: File) {
        runTask("A testar 7Z…", 0L, 1L) { meter ->
            val totals = try { measureSevenZipArchive(archive) } catch (_: Exception) { TaskTotals(0L, 1L) }
            meter.setTotals(totals.bytes, totals.entries)
            var count = 0
            SevenZFile(archive).use { seven ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = seven.nextEntry ?: break
                    val current = File(archive.parentFile, entry.name)
                    meter.show(current)
                    while (true) {
                        val n = seven.read(buffer, 0, buffer.size)
                        if (n < 0) break
                        if (n > 0) meter.bytesRead(n.toLong(), current)
                    }
                    meter.itemDone(current)
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
        runTrackedTask("A extrair item…", item.size.coerceAtLeast(0L), 1L) { meter ->
            val destination = safeArchiveTarget(currentDir, item.name, item.directory)
            if (item.directory) {
                if (!destination.exists() && !destination.mkdirs()) throw IOException("Não foi possível criar a pasta.")
            } else {
                when (extension(archive.name)) {
                    "zip" -> ZipFile(archive).use { zip ->
                        val entry = zip.getEntry(item.name) ?: throw IOException("A entrada já não existe.")
                        val target = if (destination.exists()) File(destination.parentFile, uniqueName(destination.parentFile!!, destination.name)) else destination
                        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível criar a pasta.") }
                        BufferedInputStream(zip.getInputStream(entry)).use { input ->
                            BufferedOutputStream(FileOutputStream(target)).use { output ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    meter.bytesRead(n.toLong(), target)
                                }
                            }
                        }
                    }
                    "7z" -> {
                        var found = false
                        SevenZFile(archive).use { seven ->
                            var index = 0
                            while (true) {
                                val entry = seven.nextEntry ?: break
                                if (index++ != item.index) {
                                    if (!entry.isDirectory) {
                                        val buffer = ByteArray(32 * 1024)
                                        while (seven.read(buffer, 0, buffer.size) >= 0) { }
                                    }
                                    continue
                                }
                                found = true
                                val target = if (destination.exists()) File(destination.parentFile, uniqueName(destination.parentFile!!, destination.name)) else destination
                                target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível criar a pasta.") }
                                BufferedOutputStream(FileOutputStream(target)).use { out ->
                                    val buffer = ByteArray(32 * 1024)
                                    while (true) {
                                        val n = seven.read(buffer, 0, buffer.size)
                                        if (n < 0) break
                                        out.write(buffer, 0, n)
                                        meter.bytesRead(n.toLong(), target)
                                    }
                                }
                                break
                            }
                        }
                        if (!found) throw IOException("A entrada já não existe.")
                    }
                    else -> throw IOException("Formato não suportado.")
                }
            }
            meter.itemDone(destination)
            "Item extraído: " + item.name
        }
    }

    private fun showInfoPlaceholder() {}

    private fun shouldLoadThumbnail(file: File): Boolean {
        if (!showThumbnails) return false
        if (file.isDirectory) {
            return safeList(file).any { child ->
                !child.isDirectory && extension(child.name) in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif")
            }
        }
        return when (extension(file.name)) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif" -> true
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
        thumbnailWorker.execute {
            val bitmap = try { loadThumbnail(file) } catch (_: Exception) { null }
            if (bitmap != null) thumbnailCache.put(key, bitmap)
            runOnUiThread { if (!isFinishing && view.tag == key && bitmap != null) view.setImageBitmap(bitmap) }
        }
    }

    private fun loadThumbnail(file: File): Bitmap? {
        if (file.isDirectory) return folderThumbnail(file)
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

    private fun folderThumbnail(directory: File): Bitmap? {
        val sample = safeList(directory).firstOrNull { child ->
            !child.isDirectory && extension(child.name) in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif")
        } ?: return null
        val preview = loadThumbnail(sample) ?: return null
        val size = dp(64)
        val folderBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(folderBitmap)
        val folderDrawable: Drawable = getDrawable(R.drawable.ic_folder) ?: return null
        folderDrawable.setBounds(0, 0, size, size)
        folderDrawable.draw(canvas)
        val target = android.graphics.Rect(size / 3, size / 3, size * 3 / 4, size * 3 / 4)
        val framePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF24242A.toInt() }
        val targetRect = android.graphics.RectF(target)
        canvas.drawRoundRect(targetRect, dp(3).toFloat(), dp(3).toFloat(), framePaint)
        canvas.save()
        canvas.clipRect(target)
        canvas.drawBitmap(preview, null, target, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        framePaint.style = android.graphics.Paint.Style.STROKE
        framePaint.strokeWidth = dp(1).toFloat()
        framePaint.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(targetRect, dp(3).toFloat(), dp(3).toFloat(), framePaint)
        preview.recycle()
        return folderBitmap
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

    private data class TaskTotals(val bytes: Long, val entries: Long)

    private inner class TaskProgress(
        totalBytes: Long,
        totalEntries: Long,
        private val publish: (Int, String) -> Unit
    ) {
        private var totalBytes: Long = totalBytes.coerceAtLeast(0L)
        private var totalEntries: Long = totalEntries.coerceAtLeast(1L)
        private var completedBytes = 0L
        private var completedEntries = 0L
        private var totalUnits = (this.totalBytes + this.totalEntries).coerceAtLeast(1L)

        @Synchronized
        fun setTotals(bytes: Long, entries: Long) {
            totalBytes = bytes.coerceAtLeast(0L)
            totalEntries = entries.coerceAtLeast(1L)
            completedBytes = 0L
            completedEntries = 0L
            totalUnits = (totalBytes + totalEntries).coerceAtLeast(1L)
            publish(0, "A preparar operação…")
        }

        private fun detailFor(file: File): String =
            "${completedEntries.coerceAtMost(totalEntries)}/${totalEntries}  •  ${file.absolutePath}"

        @Synchronized
        fun show(file: File) = publish(progressPercent(), detailFor(file))

        @Synchronized
        fun bytesRead(count: Long, file: File) {
            if (count > 0L) completedBytes = (completedBytes + count).coerceAtMost(totalBytes.coerceAtLeast(0L))
            publish(progressPercent(), detailFor(file))
        }

        @Synchronized
        fun itemDone(file: File) {
            completedEntries = (completedEntries + 1L).coerceAtMost(totalEntries.coerceAtLeast(1L))
            publish(progressPercent(), detailFor(file))
        }

        private fun progressPercent(): Int {
            val done = completedBytes + completedEntries
            return ((done.toDouble() / totalUnits.toDouble()) * 100.0).toInt().coerceIn(0, 99)
        }

        @Synchronized
        fun report(percent: Int, detail: String) =
            publish(percent.coerceIn(0, 99), "${completedEntries.coerceAtMost(totalEntries)}/${totalEntries}  •  $detail")

        fun finish(detail: String) = publish(100, detail)
    }

    private fun measureFiles(files: List<File>, onVisit: ((File) -> Unit)? = null): TaskTotals {
        var bytes = 0L
        var entries = 0L
        val visited = HashSet<String>()
        fun walk(file: File, depth: Int) {
            if (depth > 80 || entries >= 200000L) return
            val key = pathKey(file)
            if (!visited.add(key)) return
            onVisit?.invoke(file)
            entries++
            if (file.isFile) {
                bytes += file.length().coerceAtLeast(0L)
            } else if (file.isDirectory) {
                safeList(file).forEach { walk(it, depth + 1) }
            }
        }
        files.forEach { walk(it, 0) }
        return TaskTotals(bytes, entries.coerceAtLeast(1L))
    }

    private fun countFilesForSearch(directory: File, onVisit: ((File) -> Unit)? = null): Long = measureFiles(listOf(directory), onVisit).entries

    private fun measureZipArchive(archive: File): TaskTotals {
        var bytes = 0L
        var entries = 0L
        ZipFile(archive).use { zip ->
            val all = zip.entries()
            while (all.hasMoreElements()) {
                val entry = all.nextElement()
                entries++
                if (!entry.isDirectory && entry.size > 0L) bytes += entry.size
            }
        }
        return TaskTotals(bytes, entries.coerceAtLeast(1L))
    }

    private fun measureSevenZipArchive(archive: File): TaskTotals {
        var bytes = 0L
        var entries = 0L
        SevenZFile(archive).use { seven ->
            while (true) {
                val entry = seven.nextEntry ?: break
                entries++
                if (!entry.isDirectory && entry.size > 0L) bytes += entry.size
            }
        }
        return TaskTotals(bytes, entries.coerceAtLeast(1L))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId,
                "Operações de ficheiros",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Progresso e conclusão das operações do Archiver." }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showOperationNotification(title: String, detail: String, percent: Int?, finished: Boolean = false, failed: Boolean = false) {
        try {
            ensureNotificationChannel()
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pendingIntent = PendingIntent.getActivity(
                this, operationNotificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(this, notificationChannelId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setContentTitle(if (finished) if (failed) "Archiver — erro" else "Archiver — concluído" else title)
                .setContentText(detail)
                .setOnlyAlertOnce(true)
                .setOngoing(!finished)
                .setAutoCancel(finished)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            if (finished || percent == null) builder.setProgress(0, 0, false)
            else builder.setProgress(100, percent.coerceIn(0, 100), false)
            getSystemService(NotificationManager::class.java).notify(operationNotificationId, builder.build())
        } catch (_: SecurityException) {
            // Android 13+: notifications may be denied; in-app progress still works.
        } catch (_: Exception) { }
    }

    private fun runTask(message: String, action: () -> String) {
        runTrackedTask(message, 0L, 1L) { meter ->
            meter.show(currentDir)
            val result = action()
            meter.itemDone(currentDir)
            result
        }
    }

    private fun runTask(message: String, totalBytes: Long, totalEntries: Long, action: (TaskProgress) -> String) {
        runTrackedTask(message, totalBytes, totalEntries, action)
    }

    private fun runTrackedTask(message: String, totalBytes: Long, totalEntries: Long, action: (TaskProgress) -> String) {
        if (!workActive.compareAndSet(false, true)) {
            toast("Já existe uma operação em curso. Aguarda que termine.")
            return
        }
        ensureNotificationChannel()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notification_permission_requested", false)
        ) {
            prefs.edit().putBoolean("notification_permission_requested", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 742)
        }

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val percentView = label("0%", 14, 0xFFD8D3FF.toInt(), true).apply { gravity = Gravity.END }
        val detailView = label(message, 12, 0xFFB9B9C3.toInt(), false).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(10), 0, 0)
        }
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(12))
            minimumWidth = dp(280)
            addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)))
            addView(percentView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(detailView, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(message)
            .setView(dialogContent)
            .setCancelable(false)
            .setPositiveButton(tr("Fechar"), null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        var latestPercent = 0
        var lastPercent = -1
        var lastDetail = ""
        var lastNoticeAt = 0L
        val meter = TaskProgress(totalBytes, totalEntries) { percent, detail ->
            latestPercent = percent.coerceIn(0, 99)
            runOnUiThread {
                if (!isFinishing && dialog.isShowing) {
                    progressBar.progress = percent
                    percentView.text = "$percent%"
                    detailView.text = detail
                }
            }
            val now = android.os.SystemClock.elapsedRealtime()
            val notificationDue = synchronized(this) {
                val due = percent != lastPercent || (detail != lastDetail && now - lastNoticeAt >= 800L)
                if (due) {
                    lastPercent = percent
                    lastDetail = detail
                    lastNoticeAt = now
                }
                due
            }
            if (notificationDue) showOperationNotification(message, detail, percent)
        }
        showOperationNotification(message, currentDir.absolutePath, 0)
        worker.execute {
            var result: String
            try {
                result = action(meter)
            } catch (e: Exception) {
                result = "Erro: " + (e.message ?: e.javaClass.simpleName)
            }
            val finalResult = result
            val failed = finalResult.startsWith("Erro:")
            runOnUiThread {
                workActive.set(false)
                if (finalResult.startsWith("Ficheiro guardado:")) {
                    editorSavedText = editorInput?.text?.toString() ?: editorSavedText
                    editorDirty = false
                    if (closeEditorAfterSave) closeTextEditor()
                } else if (finalResult.startsWith("Erro:")) {
                    closeEditorAfterSave = false
                }
                if (!isFinishing) {
                    if (failed) {
                        progressBar.progress = latestPercent
                        percentView.text = "$latestPercent%"
                    } else {
                        progressBar.progress = 100
                        percentView.text = "100%"
                    }
                    detailView.text = finalResult
                    dialog.setTitle(if (failed) tr("Falha na operação") else tr("Operação concluída"))
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                        text = tr("Fechar")
                        isEnabled = true
                    }
                    Toast.makeText(this, tr(finalResult), Toast.LENGTH_LONG).show()
                    if (hasStorageAccess() && browserRendered && !settingsOpen && !editorOpen) refreshFiles()
                } else {
                    dialog.dismiss()
                }
                showOperationNotification(message, tr(finalResult), null, finished = true, failed = failed)
            }
        }
    }

    private fun toolbarIcon(resource: Int, description: String, click: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(resource)
            contentDescription = description
            imageTintList = android.content.res.ColorStateList.valueOf(primaryTextColor())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = GradientDrawable().apply {
                setColor(0x00292D40)
                cornerRadius = dp(24).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }

    private fun selectionAction(symbol: String, title: String, click: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(1), dp(2), dp(1), dp(2))
            addView(TextView(this@MainActivity).apply {
                text = symbol
                textSize = if (symbol == "ZIP") 13f else 22f
                gravity = Gravity.CENTER
                setTextColor(accentColor())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 1
            }, LinearLayout.LayoutParams(-1, dp(30)))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(primaryTextColor())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, dp(22)))
            setOnClickListener { click() }
        }

    private fun roundedBackground(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun label(text: String, size: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = tr(text)
        textSize = size.toFloat()
        setTextColor(resolveTextColor(color))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun iconButton(symbol: String, description: String, click: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            contentDescription = description
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(if (uiTheme == "Claro") primaryTextColor() else 0xFFFFFFFF.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }

    private fun actionButton(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = tr(text)
        textSize = 12f
        isAllCaps = false
        setOnClickListener { click() }
    }


    private fun tr(text: String): String {
        if (appLanguage != "en") return text
        return mapOf(
            "Explorador" to "Explorer", "Gestor de ficheiros para Android" to "Android file manager",
            "Conceder acesso aos ficheiros" to "Grant file access", "Opções" to "Options",
            "Selecionar tudo" to "Select all", "Limpar seleção" to "Clear selection",
            "Copiar seleção" to "Copy selection", "Recortar seleção" to "Cut selection",
            "Colar" to "Paste", "Criar novo…" to "Create new…", "Comprimir em ZIP" to "Compress to ZIP",
            "Comprimir em 7Z" to "Compress to 7Z", "Pesquisar em subpastas" to "Search subfolders",
            "Favoritos" to "Favorites", "Ordenar" to "Sort", "Definições do explorador" to "Explorer settings",
            "Atualizar" to "Refresh", "Pasta de início" to "Home folder", "Ordenar ficheiros" to "Sort files",
            "Nome" to "Name", "Tamanho" to "Size", "Data" to "Date", "Tipo" to "Type",
            "Ordem crescente" to "Ascending", "Ordem decrescente" to "Descending",
            "Detalhado" to "Detailed", "Compacto" to "Compact", "Grelha" to "Grid",
            "Idioma" to "Language", "Tema da interface" to "Interface theme", "Cor de destaque" to "Accent color",
            "Cores das pastas" to "Folder colors", "Escolher o idioma da interface" to "Choose interface language",
            "Escuro" to "Dark", "Preto OLED" to "OLED black", "Claro" to "Light",
            "Lavanda" to "Lavender", "Azul" to "Blue", "Verde" to "Green", "Laranja" to "Orange",
            "Rosa" to "Pink", "Vermelho" to "Red", "Amarelo" to "Yellow",
            "Miniaturas" to "Thumbnails", "Miniaturas do PDF" to "PDF thumbnails",
            "Miniaturas de vídeo" to "Video thumbnails", "Capa do álbum" to "Album covers",
            "Ícones do APK" to "APK icons", "Miniaturas quadradas" to "Square thumbnails",
            "Nível de compressão" to "Compression level", "Escolher a ordem dos ficheiros" to "Choose file sorting",
            "Inverte a ordem de ordenação dos arquivos" to "Reverse the sort order",
            "Usar item \"..\" " to "Show parent-directory entry",
            "Permite subir para o diretório anterior a partir da lista" to "Show parent directory in the list",
            "Exibir arquivos ocultos" to "Show hidden files",
            "Mostra ficheiros e diretórios cujo nome começa por ponto" to "Show files and folders beginning with a dot",
            "Modo de exibição" to "View mode", "Detalhado, compacto ou grelha" to "Detailed, compact or grid",
            "Exibir tamanho" to "Show size", "Mostra o tamanho dos ficheiros na lista" to "Show file sizes",
            "Exibir data" to "Show date", "Mostra a data da última modificação" to "Show last modified date",
            "Exibir hora" to "Show time", "Mostra a hora da última modificação" to "Show last modified time",
            "Permite a exibição de miniaturas" to "Enable file thumbnails",
            "Mostrar miniaturas de documentos PDF" to "Show PDF previews",
            "Mostrar miniaturas de vídeo" to "Show video previews",
            "Mostrar a capa do álbum nos ficheiros de música" to "Show music album art",
            "Exibir os ícones das aplicações APK" to "Show APK application icons",
            "Recorta as miniaturas para formato quadrado" to "Crop previews to a square",
            "Copiar" to "Copy", "Mover" to "Move", "Eliminar" to "Delete", "Renomear" to "Rename",
            "Informações" to "Information", "Abrir" to "Open", "Abrir pasta" to "Open folder",
            "Partilhar" to "Share", "Recortar" to "Cut", "Ver conteúdo" to "Browse archive",
            "Testar arquivo" to "Test archive", "Extrair aqui" to "Extract here",
            "Extrair para nova pasta" to "Extract to new folder", "Adicionar aos Favoritos" to "Add to Favorites",
            "Remover dos Favoritos" to "Remove from Favorites", "Cor da pasta" to "Folder color",
            "Predefinida" to "Default", "Pasta vazia" to "Empty folder",
            "Armazenamento interno" to "Internal storage", "Guardar" to "Save", "Cancelar" to "Cancel",
            "Continuar a editar" to "Continue editing", "Sair sem guardar" to "Discard changes",
            "Alterações por guardar" to "Unsaved changes",
            "Queres guardar as alterações antes de sair do editor?" to "Would you like to save changes before leaving the editor?",
            "Já existe uma operação em curso. Aguarda que termine." to "Another operation is running. Please wait.",
            "A operação está em curso. Acompanha o progresso na janela ou na notificação." to "An operation is running. Follow progress in the dialog or notification."
        )[text] ?: text
    }

    private fun accentColor(): Int = when (accentName) {
        "Azul" -> 0xFF64B5F6.toInt()
        "Verde" -> 0xFF81C784.toInt()
        "Laranja" -> 0xFFFFB74D.toInt()
        "Rosa" -> 0xFFF48FB1.toInt()
        "Vermelho" -> 0xFFE57373.toInt()
        "Amarelo" -> 0xFFFFD54F.toInt()
        else -> 0xFFB8B5FF.toInt()
    }

    private fun pageBackgroundColor(): Int = when (uiTheme) {
        "Claro" -> 0xFFF5F6FA.toInt()
        "Preto OLED" -> 0xFF000000.toInt()
        else -> 0xFF19191D.toInt()
    }

    private fun panelBackgroundColor(): Int = when (uiTheme) {
        "Claro" -> 0xFFE7E8EF.toInt()
        "Preto OLED" -> 0xFF08080A.toInt()
        else -> 0xFF202025.toInt()
    }

    private fun toolbarBackgroundColor(): Int = when (uiTheme) {
        "Claro" -> 0xFFE0E3F0.toInt()
        "Preto OLED" -> 0xFF050507.toInt()
        else -> 0xFF292D40.toInt()
    }

    private fun rowSurfaceColor(): Int = when (uiTheme) {
        "Claro" -> 0xFFECEEF4.toInt()
        "Preto OLED" -> 0xFF08080A.toInt()
        else -> 0xFF24242A.toInt()
    }

    private fun selectedRowColor(): Int = when (uiTheme) {
        "Claro" -> 0xFFD9DCF1.toInt()
        "Preto OLED" -> 0xFF242033.toInt()
        else -> 0xFF353348.toInt()
    }

    private fun primaryTextColor(): Int = if (uiTheme == "Claro") 0xFF22232A.toInt() else 0xFFF3F3F5.toInt()
    private fun mutedTextColor(): Int = if (uiTheme == "Claro") 0xFF676A75.toInt() else 0xFFAAAAB4.toInt()

    private fun resolveTextColor(requested: Int): Int = when (requested) {
        0xFFFFFFFF.toInt(), 0xFFF4F4F6.toInt(), 0xFFF3F3F5.toInt(), 0xFFF1F1F4.toInt(),
        0xFFF0F0F3.toInt(), 0xFFE3E3E8.toInt(), 0xFFE2E2E8.toInt() -> primaryTextColor()
        0xFFAAAAB4.toInt(), 0xFFACACB6.toInt(), 0xFF9999A4.toInt(), 0xFF9D9DA8.toInt(),
        0xFFB9B9C3.toInt(), 0xFFABABB5.toInt() -> mutedTextColor()
        0xFFB8B5FF.toInt(), 0xFFD8D3FF.toInt(), 0xFFD0CBFF.toInt() -> accentColor()
        else -> requested
    }

    private fun applyPaletteToWindow() {
        window.statusBarColor = toolbarBackgroundColor()
        window.navigationBarColor = if (uiTheme == "Preto OLED") 0xFF000000.toInt() else 0xFF101014.toInt()
        window.decorView.systemUiVisibility = if (uiTheme == "Claro") {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else 0
    }

    private fun showLanguageMenu() {
        val options = arrayOf("Português", "English")
        AlertDialog.Builder(this).setTitle(tr("Idioma"))
            .setSingleChoiceItems(options, if (appLanguage == "en") 1 else 0) { dialog, which ->
                appLanguage = if (which == 1) "en" else "pt"
                savePreferences()
                dialog.dismiss()
                if (settingsOpen) renderSettingsScreen() else renderBrowser()
            }.setNegativeButton(tr("Cancelar"), null).show()
    }

    private fun showThemeMenu() {
        val options = arrayOf("Escuro", "Preto OLED", "Claro")
        AlertDialog.Builder(this).setTitle(tr("Tema da interface"))
            .setSingleChoiceItems(options.map { tr(it) }.toTypedArray(), options.indexOf(uiTheme).coerceAtLeast(0)) { dialog, which ->
                uiTheme = options[which]
                savePreferences()
                applyPaletteToWindow()
                dialog.dismiss()
                if (settingsOpen) renderSettingsScreen() else renderBrowser()
            }.setNegativeButton(tr("Cancelar"), null).show()
    }

    private fun showAccentMenu() {
        val options = arrayOf("Lavanda", "Azul", "Verde", "Laranja", "Rosa", "Vermelho", "Amarelo")
        AlertDialog.Builder(this).setTitle(tr("Cor de destaque"))
            .setSingleChoiceItems(options.map { tr(it) }.toTypedArray(), options.indexOf(accentName).coerceAtLeast(0)) { dialog, which ->
                accentName = options[which]
                savePreferences()
                dialog.dismiss()
                if (settingsOpen) renderSettingsScreen() else renderBrowser()
            }.setNegativeButton(tr("Cancelar"), null).show()
    }

    private fun showFolderColorMenu(file: File) {
        val options = arrayOf("Predefinida", "Lavanda", "Azul", "Verde", "Laranja", "Rosa", "Vermelho", "Amarelo")
        AlertDialog.Builder(this).setTitle(tr("Cor da pasta"))
            .setItems(options.map { tr(it) }.toTypedArray()) { _, which ->
                val key = pathKey(file)
                if (which == 0) folderColors.remove(key) else folderColors[key] = options[which]
                savePreferences()
                refreshFiles()
            }.setNegativeButton(tr("Cancelar"), null).show()
    }

    private fun folderColorValue(file: File): Int? = folderColors[pathKey(file)]?.let { color ->
        when (color) {
            "Lavanda" -> 0xFFB8B5FF.toInt()
            "Azul" -> 0xFF64B5F6.toInt()
            "Verde" -> 0xFF81C784.toInt()
            "Laranja" -> 0xFFFFB74D.toInt()
            "Rosa" -> 0xFFF48FB1.toInt()
            "Vermelho" -> 0xFFE57373.toInt()
            "Amarelo" -> 0xFFFFD54F.toInt()
            else -> null
        }
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
        runOnUiThread { Toast.makeText(this, tr(message), Toast.LENGTH_LONG).show() }
    }

    override fun onBackPressed() {
        if (workActive.get()) {
            toast("A operação está em curso. Acompanha o progresso na janela ou na notificação.")
            return
        }
        if (editorOpen) {
            requestLeaveTextEditor()
            return
        }
        if (settingsOpen) {
            if (settingsCategory != null) {
                settingsCategory = null
                renderSettingsScreen()
            } else {
                settingsOpen = false
                renderBrowser()
            }
            return
        }

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
        thumbnailWorker.shutdown()
        super.onDestroy()
    }
}
