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
import android.os.Handler
import android.os.Looper
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
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
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
    private val searchWorker: ExecutorService = Executors.newSingleThreadExecutor()
    private val thumbnailWorker: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workActive = AtomicBoolean(false)
    private val searchActive = AtomicBoolean(false)
    private val fileRowViews = linkedMapOf<String, View>()
    private val fileNameViews = linkedMapOf<String, TextView>()
    private var visibleFileCount = 0
    private val notificationChannelId = "archiver_file_operations"
    private val operationNotificationId = 7410
    private data class FolderAnalysisResult(
        val root: File, val fileCount: Long, val folderCount: Long, val totalBytes: Long,
        val largest: List<Pair<File, Long>>, val inaccessibleFolders: Int, val reachedLimit: Boolean
    )
    private val thumbnailCache = object : LruCache<String, Bitmap>(2 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = maxOf(1, value.byteCount / 1024)
    }
    private val directoryCountCache = LruCache<String, Int>(512)

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
        applyLanguageDirection()
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
            layoutDirection = if (appLanguage == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
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
            contentDescription = tr("Criar ficheiro ou arquivo")
            background = roundedBackground(accentColor(), 28)
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
        fileRowViews.clear()
        fileNameViews.clear()
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
        visibleFileCount = files.size
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
            selected.isNotEmpty() -> selected.size.toString() + " " + tr("selecionado(s)") + " • " + files.size + " " + tr("itens")
            files.isEmpty() -> tr("Pasta vazia")
            else -> files.size.toString() + " " + tr(if (files.size == 1) "item" else "itens")
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
            if (file.isDirectory && !shouldLoadThumbnail(file)) {
                folderColorValue(file)?.let { setColorFilter(it, android.graphics.PorterDuff.Mode.SRC_IN) }
            }
        }
        val iconWidth = dp(if (inGrid) 72 else 48)
        val iconHeight = dp(if (inGrid) 66 else 48)
        if (inGrid) item.addView(icon, LinearLayout.LayoutParams(iconWidth, iconHeight).apply { gravity = Gravity.CENTER_HORIZONTAL })
        else item.addView(icon, LinearLayout.LayoutParams(iconWidth, iconHeight).apply { rightMargin = dp(8) })
        if (shouldLoadThumbnail(file)) loadThumbnailAsync(file, icon)

        val cachedCount = if (file.isDirectory) directoryCountCache.get(key) else null
        val details = if (file.isDirectory) {
            if (cachedCount == null) tr("Pasta")
            else cachedCount.toString() + " " + tr(if (cachedCount == 1) "item" else "itens")
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
        val metaView = if (metaText.isNotEmpty()) TextView(this).apply {
            text = metaText
            textSize = 12f
            setTextColor(mutedTextColor())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = if (inGrid) Gravity.CENTER else Gravity.START
            setPadding(0, dp(4), 0, 0)
        } else null
        metaView?.let { middle.addView(it, LinearLayout.LayoutParams(-1, -2)) }
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
        fileRowViews[key] = item
        fileNameViews[key] = name
        if (file.isDirectory && showSize && cachedCount == null && metaView != null) {
            thumbnailWorker.execute {
                val childCount = try { file.listFiles()?.size ?: -1 } catch (_: Exception) { -1 }
                if (childCount >= 0) {
                    directoryCountCache.put(key, childCount)
                    mainHandler.post {
                        if (fileRowViews[key] === item && metaView.isAttachedToWindow) {
                            val updatedDetails = childCount.toString() + " " + tr(if (childCount == 1) "item" else "itens")
                            metaView.text = buildString {
                                append(updatedDetails)
                                if (showTime && modified.isNotEmpty() && !inGrid) {
                                    append("   •   ")
                                    append(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(file.lastModified())))
                                }
                            }
                        }
                    }
                }
            }
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
            "zip" -> R.drawable.ic_zip_file
            "7z" -> R.drawable.ic_7z_file
            "rar" -> R.drawable.ic_rar_file
            "tar", "tgz", "tbz" -> R.drawable.ic_tar_file
            "gz", "bz2", "xz", "lz4", "zst", "iso", "cab", "arj" -> R.drawable.ic_archive
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif", "tif", "tiff", "svg" -> R.drawable.ic_image
            "mp3", "wav", "ogg", "m4a", "flac", "aac", "opus", "wma", "mid", "midi" -> R.drawable.ic_audio
            "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "mpeg", "mpg" -> R.drawable.ic_video
            "apk", "aab", "apks", "xapk" -> R.drawable.ic_android_file
            "pdf" -> R.drawable.ic_pdf
            "txt", "text", "md", "markdown", "log", "readme", "nfo" -> R.drawable.ic_text_file
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
        val isSelected = selected.contains(key)
        fileRowViews[key]?.apply {
            background = roundedBackground(
                if (isSelected) selectedRowColor() else if (gridRows != null) rowSurfaceColor() else pageBackgroundColor(),
                if (gridRows != null) 10 else 4
            )
        }
        fileNameViews[key]?.apply {
            setTextColor(if (isSelected) accentColor() else primaryTextColor())
            typeface = if (isSelected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        countView.text = if (selected.isNotEmpty()) {
            "${selected.size} ${tr("selecionado(s)")} • $visibleFileCount ${tr("itens")}"
        } else if (visibleFileCount == 0) {
            tr("Pasta vazia")
        } else {
            "$visibleFileCount ${tr(if (visibleFileCount == 1) "item" else "itens")}"
        }
        updateSelectionBar()
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
            "Ordenar", "Definições do explorador", "Atualizar", "Pasta de início", "Partilhar aplicação", "Analisar pasta atual"
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
                8 -> promptSearch(true)
                9 -> showFavorites()
                10 -> showSortMenu()
                11 -> showExplorerSettings()
                12 -> refreshFiles()
                13 -> { currentDir = rootDir; selected.clear(); refreshFiles() }
                14 -> shareApplication()
                15 -> showFolderAnalysis(currentDir)
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
            layoutDirection = if (appLanguage == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
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
                addSettingChoice(content, "Geral", "8 idiomas", "Idioma, notificações e informações") {
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
                addSettingChoice(content, "Idioma", languageDisplayName(),
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
        if (ext in setOf("glb", "gltf")) options += "Inspecionar modelo 3D"
        options += listOf("Comprimir para ZIP", "Comprimir para 7Z", "Comprimir…")
        if (file.isFile) options += "Calcular SHA-256"
        if (file.isDirectory) options += "Analisar pasta"
        options += listOf("Partilhar", "Copiar", "Recortar", "Renomear", "Eliminar", "Informações")
        options += if (favorites.contains(pathKey(file))) "Remover dos Favoritos" else "Adicionar aos Favoritos"
        if (file.isDirectory) options += "Cor da pasta"
        AlertDialog.Builder(this).setTitle(file.name).setItems(options.map { tr(it) }.toTypedArray()) { _, which ->
            when (options[which]) {
                "Abrir pasta" -> { currentDir = file; selected.clear(); refreshFiles() }
                "Cor da pasta" -> showFolderColorMenu(file)
                "Abrir" -> openFile(file)
                "Inspecionar modelo 3D" -> showModelInspector(file)
                "Comprimir para ZIP" -> promptArchive(chosenOrSingle(file), "zip")
                "Comprimir para 7Z" -> promptArchive(chosenOrSingle(file), "7z")
                "Comprimir…" -> promptArchive(chosenOrSingle(file), if (ext == "7z") "7z" else "zip")
                "Calcular SHA-256" -> calculateSha256(file)
                "Analisar pasta" -> showFolderAnalysis(file)
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

    private fun calculateSha256(file: File) {
        if (!file.isFile || !file.canRead()) {
            toast("Não é possível ler este ficheiro.")
            return
        }
        val bytes = file.length().coerceAtLeast(1L)
        runTask(tr("A calcular SHA-256…"), bytes, 1L) { meter ->
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        digest.update(buffer, 0, count)
                        meter.bytesRead(count.toLong(), file)
                    }
                }
            }
            meter.itemDone(file)
            val hash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
            tr("SHA-256 calculado") + "\n" + file.name + "\n" + hash
        }
    }

    private fun showFolderAnalysis(folder: File) {
        if (workActive.get()) {
            toast("Já existe uma operação em curso. Aguarda que termine.")
            return
        }
        if (!searchActive.compareAndSet(false, true)) {
            toast("Já existe uma pesquisa ou análise em curso.")
            return
        }
        val startDir = try { folder.canonicalFile } catch (_: Exception) { folder.absoluteFile }
        if (!startDir.isDirectory || !isWithinRoot(startDir)) {
            searchActive.set(false)
            toast("A pasta escolhida não está disponível.")
            return
        }
        val cancelled = AtomicBoolean(false)
        val status = label(tr("A analisar a pasta…"), 13, mutedTextColor(), false)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            addView(progress, LinearLayout.LayoutParams(-1, dp(5)))
        }
        val dialog = AlertDialog.Builder(this).setTitle(tr("Análise da pasta")).setView(content)
            .setNegativeButton(tr("Cancelar"), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                cancelled.set(true)
                status.text = tr("A parar a análise…")
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            }
        }
        dialog.setOnCancelListener { cancelled.set(true) }
        dialog.setOnDismissListener { cancelled.set(true) }
        dialog.show()
        searchWorker.execute {
            var fileCount = 0L
            var folderCount = 0L
            var totalBytes = 0L
            var scanned = 0L
            var inaccessible = 0
            var reachedLimit = false
            val seen = HashSet<String>()
            val largest = ArrayList<Pair<File, Long>>()
            fun visit(dir: File, depth: Int) {
                if (cancelled.get()) return
                if (depth > 64 || scanned >= 200000L) { reachedLimit = true; return }
                val canonical = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
                if (!seen.add(canonical)) return
                val children = try { dir.listFiles() } catch (_: SecurityException) { null }
                if (children == null) { inaccessible++; return }
                for (child in children) {
                    if (cancelled.get()) return
                    if (!showHidden && child.name.startsWith(".")) continue
                    scanned++
                    if (child.isDirectory) {
                        folderCount++
                        visit(child, depth + 1)
                    } else if (child.isFile) {
                        fileCount++
                        val length = try { child.length().coerceAtLeast(0L) } catch (_: Exception) { 0L }
                        totalBytes = if (Long.MAX_VALUE - totalBytes < length) Long.MAX_VALUE else totalBytes + length
                        largest.add(child to length)
                        if (largest.size > 40) {
                            largest.sortByDescending { it.second }
                            while (largest.size > 20) largest.removeAt(largest.lastIndex)
                        }
                    }
                    if (scanned % 150L == 0L) {
                        val line = tr("Itens analisados") + ": $scanned\n" + tr("Ficheiros") + ": $fileCount • " + tr("Pastas") + ": $folderCount"
                        mainHandler.post { if (dialog.isShowing && !isFinishing) status.text = line }
                    }
                    if (scanned >= 200000L) { reachedLimit = true; return }
                }
            }
            visit(startDir, 0)
            largest.sortByDescending { it.second }
            val result = FolderAnalysisResult(startDir, fileCount, folderCount, totalBytes, largest.take(12), inaccessible, reachedLimit)
            mainHandler.post {
                try {
                    if (!isFinishing && dialog.isShowing) dialog.dismiss()
                    if (!isFinishing) showFolderAnalysisResults(result, cancelled.get())
                } finally {
                    searchActive.set(false)
                }
            }
        }
    }

    private fun showFolderAnalysisResults(result: FolderAnalysisResult, cancelled: Boolean) {
        val summary = buildString {
            append(result.root.absolutePath)
            append("\n\n").append(tr("Tamanho total")).append(": ").append(formatSize(result.totalBytes))
            append("\n").append(tr("Ficheiros")).append(": ").append(result.fileCount)
            append("   •   ").append(tr("Pastas")).append(": ").append(result.folderCount)
            if (result.inaccessibleFolders > 0) append("\n").append(tr("Pastas sem acesso")).append(": ").append(result.inaccessibleFolders)
            if (result.reachedLimit) append("\n").append(tr("A análise foi limitada para proteger o desempenho."))
            if (cancelled) append("\n").append(tr("A análise foi interrompida. Os totais são parciais."))
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(8)) }
        content.addView(TextView(this).apply {
            text = summary; textSize = 13f; setTextColor(primaryTextColor())
            setPadding(dp(8), dp(8), dp(8), dp(14))
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(label(tr("Maiores ficheiros"), 15, primaryTextColor(), true).apply {
            setPadding(dp(8), dp(4), dp(8), dp(6))
        }, LinearLayout.LayoutParams(-1, -2))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        result.largest.forEach { (file, length) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(9), dp(10), dp(9))
                background = roundedBackground(rowSurfaceColor(), 8); isClickable = true; isFocusable = true
            }
            row.addView(label(file.name, 14, primaryTextColor(), true), LinearLayout.LayoutParams(-1, -2))
            row.addView(label(formatSize(length) + " • " + (file.parentFile?.absolutePath ?: ""), 11, mutedTextColor(), false).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0, dp(3), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            row.setOnClickListener {
                currentDir = file.parentFile ?: result.root
                selected.clear(); refreshFiles(); showFileActions(file)
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3); bottomMargin = dp(3) })
        }
        content.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, dp(300)))
        AlertDialog.Builder(this).setTitle(tr("Análise da pasta")).setView(content)
            .setPositiveButton(tr("Fechar"), null)
            .setNeutralButton(tr("Partilhar relatório")) { _, _ ->
                val report = summary + "\n\n" + tr("Maiores ficheiros") + "\n" +
                    result.largest.joinToString("\n") { "${it.first.name} — ${formatSize(it.second)}" }
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, tr("Relatório de armazenamento"))
                    .putExtra(Intent.EXTRA_TEXT, report)
                startActivity(Intent.createChooser(intent, tr("Partilhar relatório")))
            }.show()
    }

    private fun openFile(file: File) {
        if (isEditableTextFile(file) && file.length() <= 4L * 1024L * 1024L) {
            showTextEditor(file)
            return
        }
        if (extension(file.name) in setOf("glb", "gltf")) {
            showModelInspector(file)
            return
        }
        openFileExternally(file)
    }

    private fun openFileExternally(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
            val mime = when (extension(file.name)) {
                "glb" -> "model/gltf-binary"
                "gltf" -> "model/gltf+json"
                "obj" -> "text/plain"
                "stl" -> "model/stl"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(file.name)) ?: "*/*"
            }
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, tr("Abrir ficheiro")))
        } catch (_: ActivityNotFoundException) {
            toast("Não existe uma aplicação compatível para abrir este ficheiro.")
        } catch (e: Exception) {
            toast("Não foi possível abrir: " + (e.message ?: "erro desconhecido"))
        }
    }

    private fun showModelInspector(file: File) {
        try {
            val jsonText = if (extension(file.name) == "gltf") {
                if (file.length() > 32L * 1024L * 1024L) throw IOException("O manifesto GLTF excede o limite de leitura de 32 MB.")
                file.readText(Charsets.UTF_8)
            } else {
                readGlbJsonChunk(file)
            }
            val json = JSONObject(jsonText)
            val asset = json.optJSONObject("asset")
            fun count(key: String): Int = json.optJSONArray(key)?.length() ?: 0
            val formatLabel = if (extension(file.name) == "glb") "GLB (binário)" else "GLTF (JSON)"
            val details = listOf(
                "Formato: $formatLabel",
                "Versão GLTF: " + (asset?.optString("version", "desconhecida") ?: "desconhecida"),
                "Cenas: " + count("scenes"),
                "Objetos/nós: " + count("nodes"),
                "Meshes: " + count("meshes"),
                "Materiais: " + count("materials"),
                "Texturas: " + count("textures"),
                "Imagens: " + count("images"),
                "Animações: " + count("animations"),
                "Buffers: " + count("buffers"),
                "Tamanho do ficheiro: " + formatSize(file.length()),
                "",
                "Esta versão lê os metadados do modelo; a renderização 3D interativa ainda requer uma aplicação compatível."
            ).joinToString("\n")
            AlertDialog.Builder(this)
                .setTitle("Leitor de modelo 3D")
                .setMessage(details)
                .setPositiveButton("Abrir noutra aplicação") { _, _ -> openFileExternally(file) }
                .setNegativeButton(tr("Fechar"), null)
                .show()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Modelo 3D")
                .setMessage("Não foi possível ler os metadados do modelo.\n" + (e.message ?: "Formato inválido."))
                .setPositiveButton("Tentar aplicação externa") { _, _ -> openFileExternally(file) }
                .setNegativeButton(tr("Fechar"), null)
                .show()
        }
    }

    private fun readGlbJsonChunk(file: File): String {
        java.io.DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val header = ByteArray(12)
            input.readFully(header)
            val headerBuffer = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (headerBuffer.int != 0x46546C67) throw IOException("Assinatura GLB inválida.")
            val version = headerBuffer.int
            if (version != 2) throw IOException("Só é suportado GLB 2.0.")
            val declaredLength = headerBuffer.int.toLong() and 0xFFFFFFFFL
            if (declaredLength < 20L || declaredLength > file.length()) throw IOException("Comprimento GLB inválido.")
            var consumed = 12L
            while (consumed + 8L <= declaredLength) {
                val chunkHeader = ByteArray(8)
                input.readFully(chunkHeader)
                val chunkBuffer = java.nio.ByteBuffer.wrap(chunkHeader).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val chunkLength = chunkBuffer.int.toLong() and 0xFFFFFFFFL
                val chunkType = chunkBuffer.int
                consumed += 8L
                if (chunkLength > declaredLength - consumed || chunkLength > 32L * 1024L * 1024L) {
                    throw IOException("Bloco JSON GLB demasiado grande ou inválido.")
                }
                if (chunkType == 0x4E4F534A) {
                    val data = ByteArray(chunkLength.toInt())
                    input.readFully(data)
                    return String(data, Charsets.UTF_8).trimEnd('\u0000', ' ', '\r', '\n', '\t')
                }
                var remaining = chunkLength
                val buffer = ByteArray(8192)
                while (remaining > 0L) {
                    val amount = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (amount < 0) throw IOException("O ficheiro GLB terminou inesperadamente.")
                    remaining -= amount.toLong()
                }
                consumed += chunkLength
            }
        }
        throw IOException("O ficheiro GLB não contém um bloco JSON válido.")
    }

    private fun isEditableTextFile(file: File): Boolean = extension(file.name) in setOf(
        "txt", "text", "md", "markdown", "log", "nfo", "readme", "json", "xml", "yaml", "yml",
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
            layoutDirection = if (appLanguage == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
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
                        if (!deleteRecursively(
                                file,
                                onDeleting = { working -> meter.show(working) },
                                onDeleted = { deleted -> meter.itemDone(deleted) }
                            )) {
                            throw IOException("Não foi possível eliminar " + file.name)
                        }
                    }
                    "Eliminação concluída: " + totals.entries + " itens."
                }
            }.show()
    }

    private fun deleteRecursively(
        file: File,
        onDeleting: ((File) -> Unit)? = null,
        onDeleted: ((File) -> Unit)? = null
    ): Boolean {
        return try {
            onDeleting?.invoke(file)
            if (file.isDirectory) {
                val children = file.listFiles() ?: return false
                for (child in children) {
                    if (!deleteRecursively(child, onDeleting, onDeleted)) return false
                }
            }
            val deleted = file.delete()
            if (deleted) onDeleted?.invoke(file)
            deleted
        } catch (_: Exception) { false }
    }

    private fun showInfo(file: File) {
        val type = tr(if (file.isDirectory) "Pasta" else "Ficheiro")
        val message = tr("Nome") + ": " + file.name +
            "\n" + tr("Tipo") + ": " + type +
            "\n" + tr("Tamanho") + ": " + if (file.isDirectory) "—" else formatSize(file.length()) +
            "\n" + tr("Modificado") + ": " + DateFormat.getDateTimeInstance().format(Date(file.lastModified())) +
            "\n" + tr("Pode ler") + ": " + file.canRead() + "\n" + tr("Pode escrever") + ": " + file.canWrite()
        AlertDialog.Builder(this).setTitle(tr("Informações")).setMessage(message).setPositiveButton("OK", null).show()
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
                if (cut && !deleteRecursively(
                    source,
                    onDeleting = { working -> meter.show(working) },
                    onDeleted = { deleted -> meter.itemDone(deleted) }
                )) {
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

    private fun promptSearch(includeSubfoldersByDefault: Boolean = false) {
        if (workActive.get()) {
            toast("Aguarda que a operação atual termine antes de pesquisar.")
            return
        }
        val input = EditText(this).apply {
            hint = tr("Nome, parte do nome ou padrão (*.zip)")
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val typeNames = arrayOf(
            "Todos os tipos", "Pastas", "Imagens", "Vídeos", "Áudio",
            "Documentos", "Texto e código", "Arquivos comprimidos", "Aplicações APK", "Outros ficheiros"
        )
        val typePicker = makeSpinner(typeNames.map { tr(it) }.toTypedArray())
        val includeSubfolders = CheckBox(this).apply {
            text = tr("Incluir subpastas")
            isChecked = includeSubfoldersByDefault
        }
        val scope = TextView(this).apply {
            text = tr("Âmbito da pesquisa") + "\n" + currentDir.absolutePath
            textSize = 12f
            setTextColor(mutedTextColor())
            setPadding(0, dp(4), 0, dp(8))
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(label(tr("A pesquisa começa nesta pasta e nunca sai dela."), 13, mutedTextColor(), false))
            addView(scope, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(input, LinearLayout.LayoutParams(-1, dp(52)))
            addView(label(tr("Filtrar resultados"), 13, mutedTextColor(), false).apply {
                setPadding(0, dp(12), 0, dp(3))
            })
            addView(typePicker, LinearLayout.LayoutParams(-1, dp(48)))
            addView(includeSubfolders, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("Pesquisar nesta pasta"))
            .setView(form)
            .setNegativeButton(tr("Cancelar"), null)
            .setPositiveButton(tr("Pesquisar"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isBlank()) {
                    input.error = tr("Escreve o nome ou padrão que procuras.")
                    input.requestFocus()
                    return@setOnClickListener
                }
                val start = currentDir.canonicalFile
                val typeIndex = typePicker.selectedItemPosition
                val recursive = includeSubfolders.isChecked
                dialog.dismiss()
                runScopedSearch(start, query, typeIndex, recursive)
            }
        }
        dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    private fun matchesSearchType(file: File, typeIndex: Int): Boolean {
        if (typeIndex == 0) return true
        if (typeIndex == 1) return file.isDirectory
        if (file.isDirectory) return false
        val ext = extension(file.name)
        return when (typeIndex) {
            2 -> ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif", "tif", "tiff", "svg")
            3 -> ext in setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "mpeg", "mpg")
            4 -> ext in setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus", "wma", "mid", "midi")
            5 -> ext in setOf("pdf", "doc", "docx", "odt", "rtf", "pages", "epub", "xls", "xlsx", "ods", "csv", "tsv")
            6 -> isEditableTextFile(file)
            7 -> ext in setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "lz4", "zst", "tgz", "tbz", "iso", "cab", "arj")
            8 -> ext in setOf("apk", "aab", "apks", "xapk")
            else -> ext !in setOf(
                "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif", "tif", "tiff", "svg",
                "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "mpeg", "mpg",
                "mp3", "wav", "ogg", "m4a", "flac", "aac", "opus", "wma", "mid", "midi",
                "pdf", "doc", "docx", "odt", "rtf", "pages", "epub", "xls", "xlsx", "ods", "csv", "tsv",
                "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "lz4", "zst", "tgz", "tbz", "iso", "cab", "arj",
                "apk", "aab", "apks", "xapk"
            )
        }
    }

    private fun runScopedSearch(startDir: File, rawQuery: String, typeIndex: Int, recursive: Boolean) {
        if (!searchActive.compareAndSet(false, true)) {
            toast("Já existe uma pesquisa em curso.")
            return
        }
        val cancelled = AtomicBoolean(false)
        val query = rawQuery.lowercase(Locale.ROOT)
        val queryRegex = if (query.contains('*') || query.contains('?')) {
            Regex("^" + query.map { ch ->
                when (ch) {
                    '*' -> ".*"
                    '?' -> "."
                    else -> Regex.escape(ch.toString())
                }
            }.joinToString("") + "$")
        } else null

        val statusView = label(tr("A preparar pesquisa…"), 13, mutedTextColor(), false)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        val resultsColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(resultsColumn, ScrollView.LayoutParams(-1, -2))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
            addView(statusView, LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(8)
            })
            addView(progress, LinearLayout.LayoutParams(-1, dp(5)).apply {
                leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(8)
            })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("Resultados da pesquisa"))
            .setView(content)
            .setNegativeButton(tr("Parar"), null)
            .setPositiveButton(tr("Fechar"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                cancelled.set(true)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                statusView.text = tr("A parar a pesquisa…")
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                cancelled.set(true)
                dialog.dismiss()
            }
        }
        dialog.setOnCancelListener { cancelled.set(true) }
        dialog.setOnDismissListener { cancelled.set(true) }
        dialog.show()

        searchWorker.execute {
            var scanned = 0
            var directoriesSkipped = 0
            val hits = ArrayList<File>()
            fun visit(directory: File, depth: Int) {
                if (cancelled.get() || depth > 48 || hits.size >= 500) return
                val children = try {
                    directory.listFiles()
                } catch (_: SecurityException) {
                    null
                }
                if (children == null) {
                    directoriesSkipped++
                    return
                }
                children.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                for (child in children) {
                    if (cancelled.get() || hits.size >= 500) return
                    if (!showHidden && child.name.startsWith(".")) continue
                    scanned++
                    val name = child.name.lowercase(Locale.ROOT)
                    val matchesName = if (queryRegex != null) queryRegex.matches(name) else name.contains(query)
                    if (matchesName && matchesSearchType(child, typeIndex)) hits.add(child)
                    if (recursive && child.isDirectory) visit(child, depth + 1)
                    if (scanned % 80 == 0) {
                        val status = tr("Analisados") + ": $scanned  •  " + tr("resultados") + ": " + hits.size
                        mainHandler.post {
                            if (dialog.isShowing && !isFinishing) statusView.text = status
                        }
                    }
                }
            }
            visit(startDir, 0)
            hits.sortWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            val scannedFinal = scanned
            val skippedFinal = directoriesSkipped
            val limited = hits.size >= 500
            mainHandler.post {
                try {
                    if (!isFinishing && dialog.isShowing) {
                        progress.visibility = View.GONE
                        statusView.text = buildString {
                            append(tr(if (cancelled.get()) "Pesquisa interrompida" else "Pesquisa concluída"))
                            append(" • "); append(scannedFinal); append(" "); append(tr("itens analisados"))
                            append(" • "); append(hits.size); append(" "); append(tr("resultados"))
                            if (limited) append(" • ").append(tr("Limite de 500 resultados; afina a pesquisa."))
                            if (skippedFinal > 0) append(" • ").append(skippedFinal).append(" ").append(tr("pastas sem acesso"))
                        }
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).visibility = View.GONE
                        resultsColumn.removeAllViews()
                        if (hits.isEmpty()) {
                            resultsColumn.addView(label(tr("Nenhum resultado encontrado nesta pasta."), 15, mutedTextColor(), false).apply {
                                setPadding(dp(12), dp(18), dp(12), dp(18))
                            }, LinearLayout.LayoutParams(-1, -2))
                        } else {
                            hits.forEach { found ->
                                resultsColumn.addView(createSearchResultRow(found, dialog), LinearLayout.LayoutParams(-1, -2))
                                resultsColumn.addView(View(this).apply { setBackgroundColor(0x22333339) },
                                    LinearLayout.LayoutParams(-1, dp(1)))
                            }
                        }
                    }
                } finally {
                    searchActive.set(false)
                }
            }
        }
    }

    private fun createSearchResultRow(file: File, resultsDialog: AlertDialog): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(9), dp(4), dp(9))
            isClickable = true
            isFocusable = true
            background = roundedBackground(rowSurfaceColor(), 8)
        }
        val icon = ImageView(this).apply {
            setImageResource(iconForFile(file))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = tr(if (file.isDirectory) "Pasta" else "Ficheiro")
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(10) })
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        textColumn.addView(TextView(this).apply {
            text = file.name
            textSize = 15f
            setTextColor(primaryTextColor())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2))
        val parentPath = file.parentFile?.absolutePath ?: file.absolutePath
        textColumn.addView(TextView(this).apply {
            text = parentPath
            textSize = 11f
            setTextColor(mutedTextColor())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(2), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        val meta = if (file.isDirectory) {
            val count = directoryCountCache.get(pathKey(file))
            if (count == null) tr("Pasta") else count.toString() + " " + tr(if (count == 1) "item" else "itens")
        } else {
            formatSize(file.length()) + " • " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.lastModified()))
        }
        textColumn.addView(label(meta, 11, mutedTextColor(), false).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2))
        row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        val more = TextView(this).apply {
            text = "⋮"
            textSize = 24f
            gravity = Gravity.CENTER
            contentDescription = tr("Mais ações")
            setTextColor(primaryTextColor())
            isClickable = true
            setOnClickListener {
                resultsDialog.dismiss()
                showFileActions(file)
            }
        }
        row.addView(more, LinearLayout.LayoutParams(dp(40), dp(48)))
        row.setOnClickListener {
            resultsDialog.dismiss()
            if (file.isDirectory) {
                currentDir = file
                selected.clear()
                refreshFiles()
            } else {
                currentDir = file.parentFile ?: rootDir
                selected.clear()
                refreshFiles()
                showFileActions(file)
            }
        }
        row.setOnLongClickListener {
            resultsDialog.dismiss()
            currentDir = file.parentFile ?: rootDir
            selected.clear()
            selected.add(pathKey(file))
            refreshFiles()
            true
        }
        return row
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
                                if (!deleteRecursively(
                                    source,
                                    onDeleting = { working -> meter.show(working) },
                                    onDeleted = { deleted -> meter.itemDone(deleted) }
                                )) {
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
        val encrypted = try { Zip4jFile(archive).isEncrypted } catch (_: Exception) { false }
        if (encrypted) {
            val passwordInput = EditText(this).apply {
                hint = "Palavra-passe do ZIP"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
            }
            AlertDialog.Builder(this)
                .setTitle("ZIP protegido por palavra-passe")
                .setView(passwordInput)
                .setNegativeButton(tr("Cancelar"), null)
                .setPositiveButton("Extrair") { _, _ ->
                    val password = passwordInput.text.toString()
                    if (password.isEmpty()) {
                        toast("Escreve a palavra-passe do arquivo.")
                    } else {
                        extractZipContents(archive, destinationDir, makeSubfolder, password)
                    }
                }
                .show()
        } else {
            extractZipContents(archive, destinationDir, makeSubfolder, null)
        }
    }

    private fun extractZipContents(
        archive: File,
        destinationDir: File,
        makeSubfolder: Boolean,
        password: String?
    ) {
        runTask("A extrair ZIP…", 0L, 1L) { meter ->
            val zip = if (password.isNullOrEmpty()) Zip4jFile(archive) else Zip4jFile(archive, password.toCharArray())
            val headers = zip.fileHeaders ?: throw IOException("Não foi possível ler o índice do ZIP.")
            if (headers.isEmpty()) return@runTask "O arquivo ZIP está vazio."
            val totalBytes = headers.sumOf { if (it.isDirectory) 0L else it.uncompressedSize.coerceAtLeast(0L) }
            meter.setTotals(totalBytes, headers.size.toLong())
            val destination = if (makeSubfolder) {
                File(destinationDir, uniqueName(destinationDir, archive.nameWithoutExtension))
            } else destinationDir
            if (makeSubfolder && !destination.mkdirs()) throw IOException("Não foi possível criar a pasta de destino.")

            var count = 0
            headers.forEach { entry ->
                val output = safeArchiveTarget(destination, entry.fileName, entry.isDirectory)
                meter.show(output)
                if (entry.isDirectory) {
                    if (!output.exists() && !output.mkdirs()) throw IOException("Não foi possível criar " + output.name)
                } else {
                    output.parentFile?.let {
                        if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível criar uma pasta de destino.")
                    }
                    val target = if (output.exists()) File(output.parentFile, uniqueName(output.parentFile!!, output.name)) else output
                    zip.getInputStream(entry).use { input ->
                        BufferedOutputStream(FileOutputStream(target)).use { stream ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (n > 0) {
                                    stream.write(buffer, 0, n)
                                    meter.bytesRead(n.toLong(), target)
                                }
                            }
                        }
                    }
                }
                meter.itemDone(output)
                count++
            }
            "Extração ZIP concluída: $count entradas."
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
                !child.isDirectory && extension(child.name) in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif", "tif", "tiff")
            }
        }
        return when (extension(file.name)) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif", "tif", "tiff" -> true
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
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif", "tif", "tiff" -> {
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
            !child.isDirectory && extension(child.name) in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif", "tif", "tiff")
        } ?: return null
        val preview = loadThumbnail(sample) ?: return null
        val size = dp(64)
        val folderBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(folderBitmap)
        val folderDrawable: Drawable = getDrawable(R.drawable.ic_folder) ?: return null
        folderColorValue(directory)?.let { folderDrawable.setColorFilter(it, android.graphics.PorterDuff.Mode.SRC_IN) }
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
                val shouldAskToShare = !failed && recordSuccessfulOperation()
                mainHandler.postDelayed({
                    if (dialog.isShowing) dialog.dismiss()
                    try {
                        getSystemService(NotificationManager::class.java).cancel(operationNotificationId)
                    } catch (_: Exception) { }
                    if (shouldAskToShare && !isFinishing) showSharePrompt()
                }, if (failed) 2400L else 1300L)
            }
        }
    }

    private fun recordSuccessfulOperation(): Boolean {
        val completed = prefs.getInt("successful_operations", 0) + 1
        prefs.edit().putInt("successful_operations", completed).apply()
        if (completed >= 5 && !prefs.getBoolean("share_prompt_shown", false)) {
            prefs.edit().putBoolean("share_prompt_shown", true).apply()
            return true
        }
        return false
    }

    private fun showSharePrompt() {
        AlertDialog.Builder(this)
            .setTitle(tr("Gostas do Archiver?"))
            .setMessage(tr("Se a aplicação te está a ajudar, partilha-a com alguém. É opcional e não volta a aparecer."))
            .setPositiveButton(tr("Partilhar aplicação")) { _, _ -> shareApplication() }
            .setNegativeButton(tr("Agora não"), null)
            .show()
    }

    private fun shareApplication() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, tr("Archiver — gestor de ficheiros Android"))
            putExtra(
                Intent.EXTRA_TEXT,
                tr("Experimenta o Archiver, um gestor de ficheiros Android com compressão ZIP/7Z, pesquisa e editor de texto.") +
                    "\nhttps://github.com/nexauren1/Archiver-File-Manager/releases"
            )
        }
        try {
            startActivity(Intent.createChooser(shareIntent, tr("Partilhar Archiver")))
        } catch (_: ActivityNotFoundException) {
            toast("Não foi possível abrir o menu de partilha.")
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


    private val englishTranslations: Map<String, String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        mapOf(
            "Explorador" to "Explorer", "Gestor de ficheiros para Android" to "Android file manager",
            "Configurações" to "Settings", "Geral" to "General", "Interface" to "Interface",
            "Tema escuro equilibrado para uso diário." to "Balanced dark theme for everyday use.",
            "Fundo preto para ecrãs OLED, com menos brilho." to "Pure black for OLED displays with less glow.",
            "Fundo claro com texto escuro e contraste elevado." to "Light background with dark text and strong contrast.",
            "Destaque suave e confortável." to "A soft, comfortable accent.",
            "Destaque frio e nítido." to "A crisp, cool accent.",
            "Destaque verde natural." to "A natural green accent.",
            "Destaque quente e vivo." to "A warm, vivid accent.",
            "Destaque rosa suave." to "A soft pink accent.",
            "Destaque vermelho forte." to "A strong red accent.",
            "Destaque amarelo luminoso." to "A bright yellow accent.",
            "Os teus ficheiros" to "Your files",
            "selecionado(s)" to "selected", "item" to "item", "itens" to "items",
            "Nome, parte do nome ou padrão (*.zip)" to "Name, part of a name, or pattern (*.zip)",
            "Pastas" to "Folders", "Imagens" to "Images", "Vídeos" to "Videos", "Áudio" to "Audio",
            "Documentos" to "Documents", "Texto e código" to "Text and code", "Arquivos comprimidos" to "Archives",
            "Aplicações APK" to "APK applications", "Outros ficheiros" to "Other files",
            "Incluir subpastas" to "Include subfolders", "Âmbito da pesquisa" to "Search scope",
            "Pesquisar nesta pasta" to "Search this folder", "Resultados da pesquisa" to "Search results",
            "A pesquisa começa nesta pasta e nunca sai dela." to "Search starts in this folder and never leaves it.",
            "Filtrar resultados" to "Filter results", "Nenhum resultado encontrado nesta pasta." to "No results found in this folder.",
            "A preparar pesquisa…" to "Preparing search…", "A parar a pesquisa…" to "Stopping search…",
            "Pesquisa concluída" to "Search complete", "Pesquisa interrompida" to "Search stopped",
            "Analisados" to "Scanned", "resultados" to "results", "itens analisados" to "items scanned",
            "pastas sem acesso" to "folders without access", "Limite de 500 resultados; afina a pesquisa." to "500-result limit; refine your search.",
            "Mais ações" to "More actions", "Pasta" to "Folder", "Ficheiro" to "File",
            "Tema escuro equilibrado para uso diário." to "Balanced dark theme for everyday use.",
            "Fundo preto para ecrãs OLED, com menos brilho." to "Pure black for OLED displays with less glow.",
            "Fundo claro com texto escuro e contraste elevado." to "Light background with dark text and strong contrast.",
            "Destaque suave e confortável." to "A soft, comfortable accent.",
            "Destaque frio e nítido." to "A crisp, cool accent.",
            "Destaque verde natural." to "A natural green accent.",
            "Destaque quente e vivo." to "A warm, vivid accent.",
            "Destaque rosa suave." to "A soft pink accent.",
            "Destaque vermelho forte." to "A strong red accent.",
            "Destaque amarelo luminoso." to "A bright yellow accent.",
            "Compressão" to "Compression", "2 idiomas" to "2 languages",
            "Idioma, notificações e informações" to "Language, notifications and app information",
            "Tema, cor de destaque e aparência" to "Theme, accent color and appearance",
            "Ordenação, ficheiros ocultos e miniaturas" to "Sorting, hidden files and thumbnails",
            "Formato, velocidade e opções de arquivos" to "Formats, speed and archive options",
            "Não configurado" to "Not configured", "Nenhum" to "None",
            "O sistema de plugins ainda não está disponível" to "The plugin system is not available yet",
            "Notificações" to "Notifications", "Sobre o Archiver" to "About Archiver",
            "Progresso e conclusão das operações de ficheiros" to "File operation progress and completion",
            "Gestor de ficheiros Android nativo em Kotlin" to "Native Kotlin Android file manager",
            "Personaliza os controlos e realces" to "Customize controls and highlights",
            "Cores das pastas" to "Folder colors", "Por pasta" to "Per folder",
            "Escolhe uma cor no menu de cada pasta" to "Choose a color from each folder's menu",
            "Abre o menu de uma pasta e escolhe Cor da pasta." to "Open a folder menu and choose Folder color.",
            "O Archiver não implementa acesso ROOT nem Shizuku nesta versão. A permissão Android «Gerir todos os ficheiros» permite gerir o armazenamento partilhado, mas não desbloqueia as pastas privadas de outras aplicações nem concede root." to "Root or Shizuku access is not implemented in this version. Android's all-files permission manages shared storage, but it does not unlock other apps' private folders or grant root access.",
            "Ainda não existe um sistema de plugins nesta versão. Os formatos e leitores disponíveis são os integrados na aplicação ou nas aplicações instaladas no dispositivo." to "A plugin system is not available yet. Supported formats and readers are built into Archiver or provided by apps installed on the device.",
            "ZIP: suporta ZipCrypto, AES-256 e divisão em volumes. 7Z: criação e extração com níveis de compressão; palavra-passe e volumes não estão ligados nesta versão." to "ZIP supports ZipCrypto, AES-256 and split volumes. 7Z supports create/extract and compression levels; password protection and split volumes are not wired up for 7Z in this version.",
            "Leitor de modelo 3D" to "3D model inspector", "Abrir noutra aplicação" to "Open in another app",
            "Tentar aplicação externa" to "Try external app", "Modelo 3D" to "3D model",
            "Abrir ficheiro" to "Open file", "Formato" to "Format", "Versão GLTF" to "GLTF version",
            "Cenas" to "Scenes", "Objetos/nós" to "Objects/nodes", "Materiais" to "Materials",
            "Texturas" to "Textures", "Imagens" to "Images", "Animações" to "Animations",
            "Buffers" to "Buffers", "Tamanho do ficheiro" to "File size",
            "Esta versão lê os metadados do modelo; a renderização 3D interativa ainda requer uma aplicação compatível." to "This version reads model metadata; interactive 3D rendering still requires a compatible app.",
            "Não foi possível ler os metadados do modelo." to "Could not read model metadata.",
            "O ficheiro é demasiado grande para o editor integrado e não existe outra aplicação para o abrir." to "The file is too large for the built-in editor and no other app can open it.",
            "ZIP protegido por palavra-passe" to "Password-protected ZIP",
            "Palavra-passe do ZIP" to "ZIP password", "Escreve a palavra-passe do arquivo." to "Enter the archive password.",
            "Inspecionar modelo 3D" to "Inspect 3D model",
            "Comprimir para ZIP" to "Compress to ZIP", "Comprimir para 7Z" to "Compress to 7Z",
            "Comprimir…" to "Compress…", "text" to "text",
            "Conceder acesso aos ficheiros" to "Grant file access", "Opções" to "Options",
            "Selecionar tudo" to "Select all", "Limpar seleção" to "Clear selection",
            "Copiar seleção" to "Copy selection", "Recortar seleção" to "Cut selection",
            "Colar" to "Paste", "Criar novo…" to "Create new…", "Comprimir em ZIP" to "Compress to ZIP",
            "Comprimir em 7Z" to "Compress to 7Z", "Pesquisar em subpastas" to "Search subfolders",
            "Modificado" to "Modified", "Pode ler" to "Can read", "Pode escrever" to "Can write",
            "selecionado(s)" to "selected", "item" to "item", "itens" to "items",
            "Pasta" to "Folder", "Ficheiro" to "File", "Dados do ficheiro" to "File details",
            "Cenas" to "Scenes", "Objetos/nós" to "Objects/nodes", "Materiais" to "Materials",
            "Texturas" to "Textures", "Imagens" to "Images", "Animações" to "Animations",
            "Buffers" to "Buffers", "Versão GLTF" to "GLTF version", "Formato" to "Format",
            "Para explorar, copiar, mover, renomear, eliminar e comprimir ficheiros em diferentes pastas, o Archiver precisa de acesso aos ficheiros partilhados do dispositivo." to "To browse, copy, move, rename, delete and archive files across folders, Archiver needs access to shared files on this device.",
            "O Android abre uma página especial de definições para confirmar este acesso. O sistema continua a bloquear algumas pastas privadas das aplicações." to "Android opens a special settings page to grant this access. The system still restricts some apps' private folders.",
            "Não foi possível abrir as definições de acesso aos ficheiros." to "Could not open file access settings.",
            "Pasta anterior" to "Previous folder", "Pesquisar" to "Search", "Alterar modo de visualização" to "Change view mode",
            "Mais opções" to "More options", "Criar ficheiro ou arquivo" to "Create file or archive", "Comprimir" to "Compress",
            "Subir um diretório" to "Go up one directory", "Já estás na raiz do armazenamento partilhado." to "You are already at the shared storage root.",
            "Para renomear, seleciona apenas um item." to "Select only one item to rename it.",
            "Seleciona apenas um item para ver as informações." to "Select only one item to view its information.",
            "O acesso root não está ativo no Archiver" to "Root access is not enabled in Archiver.",
            "As notificações estão disponíveis." to "Notifications are available.",
            "Ativa as notificações nas definições do Android." to "Enable notifications in Android settings.",
            "Gestor de ficheiros Android independente, inspirado em exploradores clássicos. ZIP/7Z, editor de texto, miniaturas e operações com progresso." to "An independent Android file manager inspired by classic file explorers, with ZIP/7Z, a text editor, thumbnails and operation progress.",
            "Ordenar arquivos" to "Sort files", "Sem compressão, rápida, normal ou ultra" to "No compression, fast, normal or ultra",
            "Sem compressão (mais rápido)" to "No compression (fastest)", "Rápido" to "Fast", "Nível guardado." to "Setting saved.",
            "Nova pasta" to "New folder", "Novo ficheiro vazio" to "New empty file", "Criar arquivo ZIP" to "Create ZIP archive",
            "Criar arquivo 7Z" to "Create 7Z archive", "Criar" to "Create", "Nome da pasta" to "Folder name",
            "Nome do ficheiro" to "File name", "Novo ficheiro" to "New file", "Nome inválido." to "Invalid name.",
            "Já existe um ficheiro com esse nome." to "A file with that name already exists.",
            "Não foi possível criar. Verifica as permissões e o espaço livre." to "Could not create it. Check permissions and free space.",
            "Preparado para copiar." to "Ready to copy.", "Preparado para mover." to "Ready to move.",
            "Adicionado aos Favoritos." to "Added to Favorites.", "Removido dos Favoritos." to "Removed from Favorites.",
            "Não é possível ler este ficheiro." to "This file cannot be read.",
            "Já existe uma pesquisa ou análise em curso." to "A search or analysis is already running.",
            "A pasta escolhida não está disponível." to "The selected folder is unavailable.",
            "Não existe uma aplicação compatível para abrir este ficheiro." to "No compatible app is available to open this file.",
            "erro desconhecido" to "unknown error", "GLB (binário)" to "GLB (binary)", "Formato inválido." to "Invalid format.",
            "Assinatura GLB inválida." to "Invalid GLB signature.", "Só é suportado GLB 2.0." to "Only GLB 2.0 is supported.",
            "Comprimento GLB inválido." to "Invalid GLB length.", "Bloco JSON GLB demasiado grande ou inválido." to "GLB JSON chunk is too large or invalid.",
            "O ficheiro GLB terminou inesperadamente." to "The GLB file ended unexpectedly.", "O ficheiro GLB não contém um bloco JSON válido." to "The GLB file contains no valid JSON chunk.",
            "A guardar texto…" to "Saving text…", "A pasta do ficheiro não está disponível." to "The file's folder is unavailable.",
            "Não foi possível proteger a versão anterior do ficheiro." to "Could not preserve the previous version of the file.",
            "Não foi possível substituir o ficheiro original." to "Could not replace the original file.", "Ficheiro guardado: " to "File saved: ",
            "Partilhar ficheiro" to "Share file", "erro" to "error", "Já existe um item com esse nome." to "An item with that name already exists.",
            "Não foi possível renomear o item." to "Could not rename the item.", "Seleciona pelo menos um item." to "Select at least one item.",
            "A eliminar ficheiros…" to "Deleting files…", "Não foi possível eliminar " to "Could not delete ",
            "Eliminação concluída: " to "Deletion complete: ", " itens." to " items.",
            "Seleciona primeiro os ficheiros ou pastas." to "Select files or folders first.",
            "Preparado para mover. Abre o destino e toca em Colar." to "Ready to move. Open the destination and tap Paste.",
            "Preparado para copiar. Abre o destino e toca em Colar." to "Ready to copy. Open the destination and tap Paste.",
            "Não podes copiar ou mover uma pasta para dentro de si própria." to "You cannot copy or move a folder into itself.",
            "Os itens já estão nesta pasta." to "The items are already in this folder.", "A mover…" to "Moving…", "A copiar…" to "Copying…",
            "Copiado, mas não foi possível remover a origem: " to "Copied, but the source could not be removed: ",
            "Não foi possível gerar um nome único." to "Could not generate a unique name.",
            "Não foi possível criar " to "Could not create ", "Não foi possível ler a pasta " to "Could not read folder ",
            "O destino já existe: " to "The destination already exists: ", "Escreve o nome ou padrão que procuras." to "Enter the name or pattern you want to find.",
            "Ainda não existem Favoritos." to "There are no Favorites yet.", "Os Favoritos guardados já não existem." to "The saved Favorites no longer exist.",
            "Seleciona pelo menos um ficheiro ou pasta." to "Select at least one file or folder.", "Sem compressão" to "No compression",
            "Rápida" to "Fast", "Não" to "No", "Arquivo" to "Archive",
            "Eliminar originais depois de comprimir com sucesso" to "Delete originals after successful compression",
            "Criar um arquivo separado por item selecionado" to "Create a separate archive for each selected item",
            "Formato do arquivo" to "Archive format", "Criação de arquivo" to "Create archive",
            "Escolhe o formato, a compressão e, para ZIP, opções de segurança e volumes." to "Choose the format and compression, plus ZIP security and split-volume options.",
            "Senha e volumes divididos estão disponíveis apenas para ZIP nesta versão." to "Password protection and split volumes are available for ZIP only in this version.",
            "Nome de destino inválido." to "Invalid destination name.", "A criar arquivos separados…" to "Creating separate archives…",
            "sem compressão" to "no compression", "rápida" to "fast", "Para dividir o ZIP, seleciona apenas ficheiros ou uma única pasta por arquivo." to "To split a ZIP, select only files or one folder per archive.",
            "Falha ao criar ZIP dividido." to "Could not create split ZIP.", "Falha ao comprimir " to "Failed to compress ",
            "Não existem ficheiros para adicionar ao ZIP." to "There are no files to add to the ZIP.", "Não foi possível ler " to "Could not read ",
            "Extrair" to "Extract", "A extrair ZIP…" to "Extracting ZIP…", "Não foi possível ler o índice do ZIP." to "Could not read the ZIP index.",
            "O arquivo ZIP está vazio." to "The ZIP archive is empty.", "Não foi possível criar a pasta de destino." to "Could not create the destination folder.",
            "Não foi possível criar uma pasta de destino." to "Could not create a destination folder.", "A extrair 7Z…" to "Extracting 7Z…",
            "O arquivo contém um caminho inseguro." to "The archive contains an unsafe path.",
            "O arquivo tenta escrever fora da pasta de destino." to "The archive attempts to write outside the destination folder.",
            "A ler arquivo…" to "Reading archive…", "Formato não suportado." to "Unsupported format.",
            "Conteúdo lido: " to "Entries read: ", "O arquivo está vazio." to "The archive is empty.",
            "Pasta do arquivo" to "Archive folder", "Usa «Extrair aqui» para extrair esta pasta juntamente com o restante conteúdo." to "Use “Extract here” to extract this folder with the rest of the contents.",
            "Extrair item" to "Extract item", "A extrair item…" to "Extracting item…", "Não foi possível criar a pasta." to "Could not create the folder.",
            "A entrada já não existe." to "The entry no longer exists.", "A preparar operação…" to "Preparing operation…",
            "Operações de ficheiros" to "File operations", "Progresso e conclusão das operações do Archiver." to "Progress and completion of Archiver operations.",
            "Archiver — erro" to "Archiver — error", "Archiver — concluído" to "Archiver — completed",
            "Falha na operação" to "Operation failed", "Operação concluída" to "Operation complete",
            "Muda o fundo, os painéis, os botões e o contraste do texto." to "Changes the background, panels, buttons and text contrast.",
            "Dados do ficheiro" to "File details", "Pode ler:" to "Readable:", "Pode escrever:" to "Writable:",
            "Modificado:" to "Modified:", "Tipo:" to "Type:", "Tamanho:" to "Size:", "Nome:" to "Name:";

            "Analisar pasta atual" to "Analyze current folder", "Analisar pasta" to "Analyze folder",
            "Calcular SHA-256" to "Calculate SHA-256", "A calcular SHA-256…" to "Calculating SHA-256…",
            "SHA-256 calculado" to "SHA-256 calculated", "A analisar a pasta…" to "Analyzing folder…",
            "Análise da pasta" to "Folder analysis", "A parar a análise…" to "Stopping analysis…",
            "Itens analisados" to "Items scanned", "Ficheiros" to "Files", "Pastas" to "Folders",
            "Tamanho total" to "Total size", "Maiores ficheiros" to "Largest files",
            "A análise foi limitada para proteger o desempenho." to "Analysis was limited to protect performance.",
            "A análise foi interrompida. Os totais são parciais." to "Analysis was stopped. Totals are partial.",
            "Partilhar relatório" to "Share report", "Relatório de armazenamento" to "Storage report",
            "Partilhar aplicação" to "Share app", "Gostas do Archiver?" to "Enjoying Archiver?",
            "Se a aplicação te está a ajudar, partilha-a com alguém. É opcional e não volta a aparecer." to "If the app is useful to you, share it with someone. This is optional and will not appear again.",
            "Agora não" to "Not now", "Partilhar Archiver" to "Share Archiver",
            "Archiver — gestor de ficheiros Android" to "Archiver — Android file manager",
            "Experimenta o Archiver, um gestor de ficheiros Android com compressão ZIP/7Z, pesquisa e editor de texto." to "Try Archiver, an Android file manager with ZIP/7Z archives, search and a text editor.",
            "Não foi possível abrir o menu de partilha." to "Could not open the share sheet.",
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
        )
    }

    private val localizedTranslationCache = HashMap<String, Map<String, String>>()

    private fun localizedTranslations(language: String): Map<String, String> =
        synchronized(localizedTranslationCache) {
            localizedTranslationCache.getOrPut(language) { createLocalizedTranslations(language) }
        }

    private fun createLocalizedTranslations(language: String): Map<String, String> =
        when (language) {
            "es" -> mapOf(
                "Explorer" to "Explorador", "Settings" to "Ajustes", "General" to "General", "Interface" to "Interfaz",
                "Compression" to "Compresión", "Language" to "Idioma", "Notifications" to "Notificaciones",
                "Internal storage" to "Almacenamiento interno", "Options" to "Opciones", "Select all" to "Seleccionar todo",
                "Clear selection" to "Quitar selección", "Copy selection" to "Copiar selección", "Cut selection" to "Cortar selección",
                "Paste" to "Pegar", "Create new…" to "Crear nuevo…", "Compress to ZIP" to "Comprimir en ZIP",
                "Compress to 7Z" to "Comprimir en 7Z", "Search subfolders" to "Buscar en subcarpetas", "Favorites" to "Favoritos",
                "Sort" to "Ordenar", "Explorer settings" to "Ajustes del explorador", "Refresh" to "Actualizar",
                "Home folder" to "Carpeta de inicio", "Search" to "Buscar", "Search this folder" to "Buscar en esta carpeta",
                "Search results" to "Resultados de búsqueda", "Close" to "Cerrar", "Stop" to "Detener", "Cancel" to "Cancelar",
                "Name" to "Nombre", "Size" to "Tamaño", "Date" to "Fecha", "Type" to "Tipo", "Detailed" to "Detallado",
                "Compact" to "Compacto", "Grid" to "Cuadrícula", "Dark" to "Oscuro", "OLED black" to "Negro OLED",
                "Light" to "Claro", "Accent color" to "Color de acento", "Folder colors" to "Colores de carpeta",
                "Lavender" to "Lavanda", "Blue" to "Azul", "Green" to "Verde", "Orange" to "Naranja",
                "Pink" to "Rosa", "Red" to "Rojo", "Yellow" to "Amarillo", "Thumbnails" to "Miniaturas",
                "PDF thumbnails" to "Miniaturas PDF", "Video thumbnails" to "Miniaturas de vídeo", "Album covers" to "Carátulas",
                "APK icons" to "Iconos APK", "Copy" to "Copiar", "Move" to "Mover", "Delete" to "Eliminar",
                "Rename" to "Cambiar nombre", "Information" to "Información", "Open" to "Abrir", "Share" to "Compartir",
                "Test archive" to "Probar archivo", "Extract here" to "Extraer aquí", "Save" to "Guardar",
                "Compression level" to "Nivel de compresión", "Todos os tipos" to "Todos los tipos", "Pastas" to "Carpetas",
                "Imagens" to "Imágenes", "Vídeos" to "Vídeos", "Áudio" to "Audio", "Documentos" to "Documentos",
                "Texto e código" to "Texto y código", "Arquivos comprimidos" to "Archivos comprimidos", "Aplicações APK" to "Aplicaciones APK",
                "Outros ficheiros" to "Otros archivos", "Incluir subpastas" to "Incluir subcarpetas",
                "Âmbito da pesquisa" to "Ámbito de búsqueda", "Pesquisar nesta pasta" to "Buscar en esta carpeta",
                "Resultados da pesquisa" to "Resultados de búsqueda", "A pesquisa começa nesta pasta e nunca sai dela." to "La búsqueda se limita a esta carpeta.",
                "Nome, parte do nome ou padrão (*.zip)" to "Nombre, parte del nombre o patrón (*.zip)", "Filtrar resultados" to "Filtrar resultados",
                "Nenhum resultado encontrado nesta pasta." to "No se encontraron resultados en esta carpeta.",
                "Pesquisa concluída" to "Búsqueda completada", "Pesquisa interrompida" to "Búsqueda detenida",
                "Limite de 500 resultados; afina a pesquisa." to "Límite de 500 resultados; concreta la búsqueda.",
                "itens analisados" to "elementos analizados", "pastas sem acesso" to "carpetas sin acceso",
                "Mais ações" to "Más acciones", "Gostas do Archiver?" to "¿Te gusta Archiver?", "Agora não" to "Ahora no",
                "Partilhar aplicação" to "Compartir aplicación", "Partilhar Archiver" to "Compartir Archiver", "8 idiomas" to "8 idiomas",
                "Muda o fundo, os painéis, os botões e o contraste do texto." to "Cambia el fondo, los paneles, los botones y el contraste del texto."
            )
            "fr" -> mapOf(
                "Explorer" to "Explorateur", "Settings" to "Paramètres", "General" to "Général", "Interface" to "Interface",
                "Compression" to "Compression", "Language" to "Langue", "Notifications" to "Notifications",
                "Internal storage" to "Stockage interne", "Options" to "Options", "Select all" to "Tout sélectionner",
                "Clear selection" to "Effacer la sélection", "Copy selection" to "Copier la sélection", "Cut selection" to "Couper la sélection",
                "Paste" to "Coller", "Create new…" to "Créer…", "Compress to ZIP" to "Compresser en ZIP",
                "Compress to 7Z" to "Compresser en 7Z", "Search subfolders" to "Rechercher dans les sous-dossiers",
                "Favorites" to "Favoris", "Sort" to "Trier", "Explorer settings" to "Paramètres de l’explorateur",
                "Refresh" to "Actualiser", "Home folder" to "Dossier d’accueil", "Search" to "Rechercher",
                "Search this folder" to "Rechercher dans ce dossier", "Search results" to "Résultats de recherche",
                "Close" to "Fermer", "Stop" to "Arrêter", "Cancel" to "Annuler", "Name" to "Nom", "Size" to "Taille",
                "Date" to "Date", "Type" to "Type", "Detailed" to "Détaillé", "Compact" to "Compact", "Grid" to "Grille",
                "Dark" to "Sombre", "OLED black" to "Noir OLED", "Light" to "Clair", "Accent color" to "Couleur d’accent",
                "Folder colors" to "Couleurs des dossiers", "Lavender" to "Lavande", "Blue" to "Bleu", "Green" to "Vert",
                "Orange" to "Orange", "Pink" to "Rose", "Red" to "Rouge", "Yellow" to "Jaune", "Thumbnails" to "Miniatures",
                "PDF thumbnails" to "Miniatures PDF", "Video thumbnails" to "Miniatures vidéo", "Album covers" to "Pochettes d’album",
                "APK icons" to "Icônes APK", "Copy" to "Copier", "Move" to "Déplacer", "Delete" to "Supprimer",
                "Rename" to "Renommer", "Information" to "Informations", "Open" to "Ouvrir", "Share" to "Partager",
                "Test archive" to "Tester l’archive", "Extract here" to "Extraire ici", "Save" to "Enregistrer",
                "Compression level" to "Niveau de compression", "Todos os tipos" to "Tous les types", "Pastas" to "Dossiers",
                "Imagens" to "Images", "Vídeos" to "Vidéos", "Áudio" to "Audio", "Documentos" to "Documents",
                "Texto e código" to "Texte et code", "Arquivos comprimidos" to "Archives compressées", "Aplicações APK" to "Applications APK",
                "Outros ficheiros" to "Autres fichiers", "Incluir subpastas" to "Inclure les sous-dossiers",
                "Âmbito da pesquisa" to "Portée de la recherche", "Pesquisar nesta pasta" to "Rechercher dans ce dossier",
                "Resultados da pesquisa" to "Résultats de recherche", "A pesquisa começa nesta pasta e nunca sai dela." to "La recherche reste dans ce dossier.",
                "Nome, parte do nome ou padrão (*.zip)" to "Nom, partie du nom ou motif (*.zip)", "Filtrar resultados" to "Filtrer les résultats",
                "Nenhum resultado encontrado nesta pasta." to "Aucun résultat dans ce dossier.",
                "Pesquisa concluída" to "Recherche terminée", "Pesquisa interrompida" to "Recherche arrêtée",
                "Limite de 500 resultados; afina a pesquisa." to "Limite de 500 résultats ; affinez la recherche.",
                "itens analisados" to "éléments analysés", "pastas sem acesso" to "dossiers inaccessibles", "Mais ações" to "Plus d’actions",
                "Gostas do Archiver?" to "Archiver vous plaît ?", "Agora não" to "Pas maintenant",
                "Partilhar aplicação" to "Partager l’application", "Partilhar Archiver" to "Partager Archiver", "8 idiomas" to "8 langues"
            )
            "zh" -> mapOf(
                "Explorer" to "文件管理器", "Settings" to "设置", "General" to "通用", "Interface" to "界面",
                "Compression" to "压缩", "Language" to "语言", "Notifications" to "通知", "Internal storage" to "内部存储",
                "Options" to "选项", "Select all" to "全选", "Clear selection" to "清除选择", "Copy selection" to "复制所选项",
                "Cut selection" to "剪切所选项", "Paste" to "粘贴", "Create new…" to "新建…", "Compress to ZIP" to "压缩为 ZIP",
                "Compress to 7Z" to "压缩为 7Z", "Search subfolders" to "搜索子文件夹", "Favorites" to "收藏夹",
                "Sort" to "排序", "Explorer settings" to "文件管理器设置", "Refresh" to "刷新", "Home folder" to "主文件夹",
                "Search" to "搜索", "Search this folder" to "搜索此文件夹", "Search results" to "搜索结果", "Close" to "关闭",
                "Stop" to "停止", "Cancel" to "取消", "Name" to "名称", "Size" to "大小", "Date" to "日期", "Type" to "类型",
                "Detailed" to "详细", "Compact" to "紧凑", "Grid" to "网格", "Dark" to "深色", "OLED black" to "OLED 黑色",
                "Light" to "浅色", "Accent color" to "强调色", "Folder colors" to "文件夹颜色", "Lavender" to "薰衣草紫",
                "Blue" to "蓝色", "Green" to "绿色", "Orange" to "橙色", "Pink" to "粉色", "Red" to "红色", "Yellow" to "黄色",
                "Thumbnails" to "缩略图", "PDF thumbnails" to "PDF 缩略图", "Video thumbnails" to "视频缩略图",
                "Album covers" to "专辑封面", "APK icons" to "APK 图标", "Copy" to "复制", "Move" to "移动", "Delete" to "删除",
                "Rename" to "重命名", "Information" to "信息", "Open" to "打开", "Share" to "分享", "Test archive" to "测试压缩包",
                "Extract here" to "解压到此处", "Save" to "保存", "Compression level" to "压缩级别", "Todos os tipos" to "所有类型",
                "Pastas" to "文件夹", "Imagens" to "图片", "Vídeos" to "视频", "Áudio" to "音频", "Documentos" to "文档",
                "Texto e código" to "文本和代码", "Arquivos comprimidos" to "压缩文件", "Aplicações APK" to "APK 应用",
                "Outros ficheiros" to "其他文件", "Incluir subpastas" to "包含子文件夹", "Âmbito da pesquisa" to "搜索范围",
                "Pesquisar nesta pasta" to "搜索此文件夹", "Resultados da pesquisa" to "搜索结果",
                "A pesquisa começa nesta pasta e nunca sai dela." to "搜索仅在当前文件夹范围内进行。",
                "Nome, parte do nome ou padrão (*.zip)" to "输入名称、部分名称或模式（*.zip）", "Filtrar resultados" to "筛选结果",
                "Nenhum resultado encontrado nesta pasta." to "当前文件夹中没有找到结果。", "Pesquisa concluída" to "搜索完成",
                "Pesquisa interrompida" to "搜索已停止", "Limite de 500 resultados; afina a pesquisa." to "结果最多显示 500 项，请缩小搜索范围。",
                "itens analisados" to "个项目已扫描", "pastas sem acesso" to "个文件夹无法访问", "Mais ações" to "更多操作",
                "Gostas do Archiver?" to "喜欢 Archiver 吗？", "Agora não" to "暂不", "Partilhar aplicação" to "分享应用",
                "Partilhar Archiver" to "分享 Archiver", "8 idiomas" to "8 种语言"
            )
            "hi" -> mapOf(
                "Explorer" to "फ़ाइल प्रबंधक", "Settings" to "सेटिंग", "General" to "सामान्य", "Interface" to "इंटरफ़ेस",
                "Compression" to "कम्प्रेशन", "Language" to "भाषा", "Notifications" to "सूचनाएँ", "Internal storage" to "आंतरिक संग्रहण",
                "Options" to "विकल्प", "Select all" to "सभी चुनें", "Clear selection" to "चयन हटाएँ", "Copy selection" to "चयन कॉपी करें",
                "Cut selection" to "चयन काटें", "Paste" to "पेस्ट करें", "Create new…" to "नया बनाएँ…",
                "Compress to ZIP" to "ZIP में संपीड़ित करें", "Compress to 7Z" to "7Z में संपीड़ित करें",
                "Search subfolders" to "उपफ़ोल्डर खोजें", "Favorites" to "पसंदीदा", "Sort" to "क्रमबद्ध करें",
                "Explorer settings" to "फ़ाइल प्रबंधक सेटिंग", "Refresh" to "रीफ़्रेश", "Home folder" to "मुख्य फ़ोल्डर",
                "Search" to "खोजें", "Search this folder" to "इस फ़ोल्डर में खोजें", "Search results" to "खोज परिणाम",
                "Close" to "बंद करें", "Stop" to "रोकें", "Cancel" to "रद्द करें", "Name" to "नाम", "Size" to "आकार",
                "Date" to "तारीख", "Type" to "प्रकार", "Detailed" to "विस्तृत", "Compact" to "कॉम्पैक्ट", "Grid" to "ग्रिड",
                "Dark" to "डार्क", "OLED black" to "OLED काला", "Light" to "लाइट", "Accent color" to "एक्सेंट रंग",
                "Folder colors" to "फ़ोल्डर रंग", "Blue" to "नीला", "Green" to "हरा", "Orange" to "नारंगी", "Pink" to "गुलाबी",
                "Red" to "लाल", "Yellow" to "पीला", "Thumbnails" to "थंबनेल", "Copy" to "कॉपी", "Move" to "स्थानांतरित करें",
                "Delete" to "हटाएँ", "Rename" to "नाम बदलें", "Information" to "जानकारी", "Open" to "खोलें",
                "Share" to "साझा करें", "Test archive" to "आर्काइव जाँचें", "Extract here" to "यहाँ निकालें", "Save" to "सहेजें",
                "Compression level" to "कम्प्रेशन स्तर", "Todos os tipos" to "सभी प्रकार", "Pastas" to "फ़ोल्डर", "Imagens" to "चित्र",
                "Vídeos" to "वीडियो", "Áudio" to "ऑडियो", "Documentos" to "दस्तावेज़", "Texto e código" to "टेक्स्ट और कोड",
                "Arquivos comprimidos" to "संपीड़ित फ़ाइलें", "Aplicações APK" to "APK ऐप", "Outros ficheiros" to "अन्य फ़ाइलें",
                "Incluir subpastas" to "उपफ़ोल्डर शामिल करें", "Âmbito da pesquisa" to "खोज का दायरा",
                "Pesquisar nesta pasta" to "इस फ़ोल्डर में खोजें", "Resultados da pesquisa" to "खोज परिणाम",
                "A pesquisa começa nesta pasta e nunca sai dela." to "खोज इसी फ़ोल्डर में सीमित रहेगी।",
                "Nome, parte do nome ou padrão (*.zip)" to "नाम, नाम का हिस्सा या पैटर्न (*.zip) लिखें", "Filtrar resultados" to "खोज फ़िल्टर",
                "Nenhum resultado encontrado nesta pasta." to "इस फ़ोल्डर में कोई परिणाम नहीं मिला।", "Pesquisa concluída" to "खोज पूरी हुई",
                "Pesquisa interrompida" to "खोज रोक दी गई", "Limite de 500 resultados; afina a pesquisa." to "500 परिणामों की सीमा; खोज को संकीर्ण करें।",
                "itens analisados" to "आइटम जाँचे गए", "pastas sem acesso" to "अनुमति रहित फ़ोल्डर",
                "Mais ações" to "अधिक कार्रवाइयाँ", "Gostas do Archiver?" to "Archiver पसंद आ रहा है?", "Agora não" to "अभी नहीं",
                "Partilhar aplicação" to "ऐप साझा करें", "Partilhar Archiver" to "Archiver साझा करें", "8 idiomas" to "8 भाषाएँ"
            )
            "ar" -> mapOf(
                "Explorer" to "مدير الملفات", "Settings" to "الإعدادات", "General" to "عام", "Interface" to "الواجهة",
                "Compression" to "الضغط", "Language" to "اللغة", "Notifications" to "الإشعارات", "Internal storage" to "التخزين الداخلي",
                "Options" to "الخيارات", "Select all" to "تحديد الكل", "Clear selection" to "إلغاء التحديد", "Copy selection" to "نسخ المحدد",
                "Cut selection" to "قص المحدد", "Paste" to "لصق", "Create new…" to "إنشاء جديد…", "Compress to ZIP" to "ضغط إلى ZIP",
                "Compress to 7Z" to "ضغط إلى 7Z", "Search subfolders" to "البحث في المجلدات الفرعية", "Favorites" to "المفضلة",
                "Sort" to "ترتيب", "Explorer settings" to "إعدادات مدير الملفات", "Refresh" to "تحديث", "Home folder" to "المجلد الرئيسي",
                "Search" to "بحث", "Search this folder" to "البحث في هذا المجلد", "Search results" to "نتائج البحث", "Close" to "إغلاق",
                "Stop" to "إيقاف", "Cancel" to "إلغاء", "Name" to "الاسم", "Size" to "الحجم", "Date" to "التاريخ", "Type" to "النوع",
                "Detailed" to "تفصيلي", "Compact" to "مضغوط", "Grid" to "شبكة", "Dark" to "داكن", "OLED black" to "أسود OLED",
                "Light" to "فاتح", "Accent color" to "لون التمييز", "Folder colors" to "ألوان المجلدات", "Blue" to "أزرق",
                "Green" to "أخضر", "Orange" to "برتقالي", "Pink" to "وردي", "Red" to "أحمر", "Yellow" to "أصفر",
                "Thumbnails" to "صور مصغرة", "Copy" to "نسخ", "Move" to "نقل", "Delete" to "حذف", "Rename" to "إعادة تسمية",
                "Information" to "معلومات", "Open" to "فتح", "Share" to "مشاركة", "Test archive" to "اختبار الأرشيف",
                "Extract here" to "استخراج هنا", "Save" to "حفظ", "Compression level" to "مستوى الضغط",
                "Todos os tipos" to "كل الأنواع", "Pastas" to "مجلدات", "Imagens" to "صور", "Vídeos" to "فيديو",
                "Áudio" to "صوت", "Documentos" to "مستندات", "Texto e código" to "نص وبرمجة", "Arquivos comprimidos" to "ملفات مضغوطة",
                "Aplicações APK" to "تطبيقات APK", "Outros ficheiros" to "ملفات أخرى", "Incluir subpastas" to "تضمين المجلدات الفرعية",
                "Âmbito da pesquisa" to "نطاق البحث", "Pesquisar nesta pasta" to "البحث في هذا المجلد", "Resultados da pesquisa" to "نتائج البحث",
                "A pesquisa começa nesta pasta e nunca sai dela." to "يبدأ البحث في هذا المجلد ولا يتجاوزه.",
                "Nome, parte do nome ou padrão (*.zip)" to "أدخل الاسم أو جزءاً منه أو نمطاً مثل *.zip",
                "Filtrar resultados" to "تصفية النتائج", "Nenhum resultado encontrado nesta pasta." to "لم يتم العثور على نتائج في هذا المجلد.",
                "Pesquisa concluída" to "اكتمل البحث", "Pesquisa interrompida" to "تم إيقاف البحث",
                "Limite de 500 resultados; afina a pesquisa." to "الحد الأقصى 500 نتيجة؛ حدّد البحث أكثر.",
                "itens analisados" to "عناصر تم فحصها", "pastas sem acesso" to "مجلدات لا يمكن الوصول إليها",
                "Mais ações" to "إجراءات أخرى", "Gostas do Archiver?" to "هل يعجبك Archiver؟", "Agora não" to "ليس الآن",
                "Partilhar aplicação" to "مشاركة التطبيق", "Partilhar Archiver" to "مشاركة Archiver", "8 idiomas" to "8 لغات"
            )
            "ru" -> mapOf(
                "Explorer" to "Файловый менеджер", "Settings" to "Настройки", "General" to "Общие", "Interface" to "Интерфейс",
                "Compression" to "Сжатие", "Language" to "Язык", "Notifications" to "Уведомления", "Internal storage" to "Внутренняя память",
                "Options" to "Параметры", "Select all" to "Выбрать всё", "Clear selection" to "Снять выделение",
                "Copy selection" to "Копировать выбранное", "Cut selection" to "Вырезать выбранное", "Paste" to "Вставить",
                "Create new…" to "Создать…", "Compress to ZIP" to "Сжать в ZIP", "Compress to 7Z" to "Сжать в 7Z",
                "Search subfolders" to "Искать во вложенных папках", "Favorites" to "Избранное", "Sort" to "Сортировка",
                "Explorer settings" to "Настройки проводника", "Refresh" to "Обновить", "Home folder" to "Домашняя папка",
                "Search" to "Поиск", "Search this folder" to "Поиск в этой папке", "Search results" to "Результаты поиска",
                "Close" to "Закрыть", "Stop" to "Остановить", "Cancel" to "Отмена", "Name" to "Имя", "Size" to "Размер",
                "Date" to "Дата", "Type" to "Тип", "Detailed" to "Подробно", "Compact" to "Компактно", "Grid" to "Сетка",
                "Dark" to "Тёмная", "OLED black" to "Чёрная OLED", "Light" to "Светлая", "Accent color" to "Акцентный цвет",
                "Folder colors" to "Цвета папок", "Blue" to "Синий", "Green" to "Зелёный", "Orange" to "Оранжевый",
                "Pink" to "Розовый", "Red" to "Красный", "Yellow" to "Жёлтый", "Thumbnails" to "Миниатюры",
                "Copy" to "Копировать", "Move" to "Переместить", "Delete" to "Удалить", "Rename" to "Переименовать",
                "Information" to "Информация", "Open" to "Открыть", "Share" to "Поделиться", "Test archive" to "Проверить архив",
                "Extract here" to "Извлечь сюда", "Save" to "Сохранить", "Compression level" to "Уровень сжатия",
                "Todos os tipos" to "Все типы", "Pastas" to "Папки", "Imagens" to "Изображения", "Vídeos" to "Видео",
                "Áudio" to "Аудио", "Documentos" to "Документы", "Texto e código" to "Текст и код", "Arquivos comprimidos" to "Архивы",
                "Aplicações APK" to "Приложения APK", "Outros ficheiros" to "Другие файлы", "Incluir subpastas" to "Включать подпапки",
                "Âmbito da pesquisa" to "Область поиска", "Pesquisar nesta pasta" to "Поиск в этой папке", "Resultados da pesquisa" to "Результаты поиска",
                "A pesquisa começa nesta pasta e nunca sai dela." to "Поиск выполняется только в этой папке.",
                "Nome, parte do nome ou padrão (*.zip)" to "Введите имя, часть имени или шаблон (*.zip)",
                "Filtrar resultados" to "Фильтры поиска", "Nenhum resultado encontrado nesta pasta." to "В этой папке ничего не найдено.",
                "Pesquisa concluída" to "Поиск завершён", "Pesquisa interrompida" to "Поиск остановлен",
                "Limite de 500 resultados; afina a pesquisa." to "Предел 500 результатов; уточните поиск.",
                "itens analisados" to "элементов просмотрено", "pastas sem acesso" to "папок без доступа",
                "Mais ações" to "Другие действия", "Gostas do Archiver?" to "Нравится Archiver?", "Agora não" to "Не сейчас",
                "Partilhar aplicação" to "Поделиться приложением", "Partilhar Archiver" to "Поделиться Archiver", "8 idiomas" to "8 языков"
            )
            else -> emptyMap()
        }

    private fun tr(text: String): String {
        if (appLanguage == "pt") return text
        val english = englishTranslations[text] ?: text
        if (appLanguage == "en") return english
        val localized = localizedTranslations(appLanguage)
        return localized[text] ?: localized[english] ?: english
    }

    private fun accentColor(): Int = colorForAccent(accentName)

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

    private fun languageDisplayName(): String = when (appLanguage) {
        "en" -> "English"
        "es" -> "Español"
        "fr" -> "Français"
        "zh" -> "中文 (简体)"
        "hi" -> "हिन्दी"
        "ar" -> "العربية"
        "ru" -> "Русский"
        else -> "Português"
    }

    private fun applyLanguageDirection() {
        val direction = if (appLanguage == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        window.decorView.layoutDirection = direction
        if (::page.isInitialized) page.layoutDirection = direction
    }

    private fun showLanguageMenu() {
        val tags = arrayOf("pt", "en", "es", "fr", "zh", "hi", "ar", "ru")
        val names = arrayOf("Português", "English", "Español", "Français", "中文 (简体)", "हिन्दी", "العربية", "Русский")
        AlertDialog.Builder(this).setTitle(tr("Idioma"))
            .setSingleChoiceItems(names, tags.indexOf(appLanguage).coerceAtLeast(0)) { dialog, which ->
                appLanguage = tags[which]
                savePreferences()
                applyLanguageDirection()
                dialog.dismiss()
                if (settingsOpen) renderSettingsScreen() else renderBrowser()
            }.setNegativeButton(tr("Cancelar"), null).show()
    }

    private fun themeSurface(theme: String): Int = when (theme) {
        "Claro" -> 0xFFF2F3F8.toInt()
        "Preto OLED" -> 0xFF050507.toInt()
        else -> 0xFF19191D.toInt()
    }

    private fun themeToolbar(theme: String): Int = when (theme) {
        "Claro" -> 0xFFDDE2F1.toInt()
        "Preto OLED" -> 0xFF111116.toInt()
        else -> 0xFF292D40.toInt()
    }

    private fun themeText(theme: String): Int =
        if (theme == "Claro") 0xFF24252D.toInt() else 0xFFF3F3F5.toInt()

    private fun showThemeMenu() {
        val options = arrayOf("Escuro", "Preto OLED", "Claro")
        val descriptions = arrayOf(
            "Tema escuro equilibrado para uso diário.",
            "Fundo preto para ecrãs OLED, com menos brilho.",
            "Fundo claro com texto escuro e contraste elevado."
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("Tema da interface"))
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(tr("Cancelar"), null)
            .create()
        options.forEachIndexed { index, theme ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundedBackground(themeSurface(theme), 14)
                isClickable = true
                isFocusable = true
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(this).apply {
                text = tr(theme)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(themeText(theme))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(TextView(this).apply {
                text = if (theme == uiTheme) "✓" else ""
                textSize = 20f
                setTextColor(accentColor())
            }, LinearLayout.LayoutParams(dp(28), -2))
            card.addView(header, LinearLayout.LayoutParams(-1, -2))
            card.addView(TextView(this).apply {
                text = tr(descriptions[index])
                textSize = 12f
                setTextColor(if (theme == "Claro") 0xFF60636F.toInt() else 0xFFB9B9C3.toInt())
                setPadding(0, dp(4), 0, dp(10))
            }, LinearLayout.LayoutParams(-1, -2))
            val sample = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBackground(themeToolbar(theme), 9)
                setPadding(dp(10), dp(7), dp(10), dp(7))
            }
            sample.addView(TextView(this).apply {
                text = tr("Armazenamento interno")
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(themeText(theme))
            }, LinearLayout.LayoutParams(-1, -2))
            val sampleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            sampleRow.addView(View(this).apply {
                background = roundedBackground(accentColor(), 4)
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(8) })
            sampleRow.addView(TextView(this).apply {
                text = tr("Os teus ficheiros")
                textSize = 12f
                setTextColor(themeText(theme))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            sampleRow.addView(TextView(this).apply {
                text = "100%"
                textSize = 11f
                setTextColor(accentColor())
            }, LinearLayout.LayoutParams(-2, -2))
            sample.addView(sampleRow)
            card.addView(sample)
            card.setOnClickListener {
                uiTheme = theme
                savePreferences()
                applyPaletteToWindow()
                dialog.dismiss()
                if (settingsOpen) renderSettingsScreen() else renderBrowser()
            }
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(5); bottomMargin = dp(5)
            })
        }
        dialog.show()
    }

    private fun showAccentMenu() {
        val options = arrayOf("Lavanda", "Azul", "Verde", "Laranja", "Rosa", "Vermelho", "Amarelo")
        val descriptions = arrayOf(
            "Destaque suave e confortável.", "Destaque frio e nítido.", "Destaque verde natural.",
            "Destaque quente e vivo.", "Destaque rosa suave.", "Destaque vermelho forte.", "Destaque amarelo luminoso."
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("Cor de destaque"))
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(tr("Cancelar"), null)
            .create()
        options.forEachIndexed { index, option ->
            val color = colorForAccent(option)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(8), dp(10))
                isClickable = true
                isFocusable = true
                background = roundedBackground(if (option == accentName) rowSurfaceColor() else pageBackgroundColor(), 10)
            }
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(2), if (option == accentName) primaryTextColor() else color)
                }
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(12) })
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(label(option, 15, primaryTextColor(), option == accentName))
            labels.addView(label(descriptions[index], 12, mutedTextColor(), false).apply {
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = if (option == accentName) "✓" else ""
                textSize = 18f
                setTextColor(color)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(26), dp(36)))
            row.setOnClickListener {
                accentName = option
                savePreferences()
                applyPaletteToWindow()
                dialog.dismiss()
                if (settingsOpen) renderSettingsScreen() else renderBrowser()
            }
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(3); bottomMargin = dp(3)
            })
        }
        dialog.show()
    }

    private fun colorForAccent(name: String): Int = when (name) {
        "Azul" -> 0xFF64B5F6.toInt()
        "Verde" -> 0xFF81C784.toInt()
        "Laranja" -> 0xFFFFB74D.toInt()
        "Rosa" -> 0xFFF48FB1.toInt()
        "Vermelho" -> 0xFFE57373.toInt()
        "Amarelo" -> 0xFFFFD54F.toInt()
        else -> 0xFFB8B5FF.toInt()
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
        searchWorker.shutdown()
        thumbnailWorker.shutdown()
        super.onDestroy()
    }
}
