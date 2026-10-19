package com.nexauren.archiver

import android.os.Environment
import android.system.Os
import android.system.OsConstants
import com.github.junrar.Archive as JunrarArchive
import net.lingala.zip4j.ZipFile as Zip4jFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Optional Shizuku UserService used only when the user explicitly enables advanced
 * storage access. Its file API is intentionally limited to shared Android/data and
 * Android/obb trees; it never accesses /data/data or other apps' internal sandboxes.
 */
class RestrictedStorageUserService : IRestrictedStorageService.Stub() {
    private data class ListedEntry(
        val file: File,
        val symbolicLink: Boolean,
        val directory: Boolean,
        val sortName: String,
        val stat: android.system.StructStat?
    )


    override fun destroy() { System.exit(0) }

    override fun getRestrictedRootPaths(): Array<String> {
        val primary = Environment.getExternalStorageDirectory().canonicalFile
        val androidData = File(primary, "Android/data").canonicalFile
        val androidObb = File(primary, "Android/obb").canonicalFile
        if (Os.getuid() == 0) {
            return listOf(
                File("/").canonicalFile,
                File("/data").canonicalFile,
                primary,
                androidData,
                androidObb,
                File("/system").canonicalFile,
                File("/vendor").canonicalFile,
                File("/product").canonicalFile
            ).map { it.absolutePath }.distinct().toTypedArray()
        }
        return listOf(primary, androidData, androidObb).map { it.absolutePath }.distinct().toTypedArray()
    }

    override fun getAccessIdentity(): String = when (Os.getuid()) {
        0 -> "ROOT (UID 0)"
        2000 -> "ADB / Shell (UID 2000)"
        else -> "UID ${Os.getuid()}"
    }

    override fun listDirectory(path: String): String {
        return try {
            val directory = resolveAllowedPath(path)
            if (isSymbolicLink(File(path))) throw IOException("Os links simbólicos não são abertos por segurança.")
            if (!directory.isDirectory) throw IOException("O caminho não é uma pasta.")
            val files = directory.listFiles() ?: throw IOException(
                "O Android ou o fabricante bloqueou a leitura desta pasta. A permissão avançada não garante acesso em todos os dispositivos."
            )

            // Cache stat data once per entry so sorting and JSON serialization do not
            // repeatedly issue lstat() system calls for the same file.
            // For very large folders, retain only the first N entries using a bounded
            // heap instead of sorting every entry just to discard everything after N.
            fun listEntry(child: File): ListedEntry {
                val stat = try { Os.lstat(child.absolutePath) } catch (_: Exception) { null }
                val type = stat?.let { it.st_mode and OsConstants.S_IFMT }
                val link = if (type == null) isSymbolicLink(child) else type == OsConstants.S_IFLNK
                val directoryEntry = !link && (if (type == null) child.isDirectory else type == OsConstants.S_IFDIR)
                return ListedEntry(child, link, directoryEntry, child.name.lowercase(Locale.ROOT), stat)
            }

            val comparator = compareByDescending<ListedEntry> { it.directory }.thenBy { it.sortName }
            val sorted = if (files.size <= MAX_DIRECTORY_ENTRIES) {
                files.map(::listEntry).sortedWith(comparator)
            } else {
                // A reversed comparator places the worst retained candidate at the heap head.
                val best = java.util.PriorityQueue<ListedEntry>(MAX_DIRECTORY_ENTRIES, comparator.reversed())
                files.forEach { child ->
                    val entry = listEntry(child)
                    if (best.size < MAX_DIRECTORY_ENTRIES) {
                        best.add(entry)
                    } else if (comparator.compare(entry, best.peek()) < 0) {
                        best.poll()
                        best.add(entry)
                    }
                }
                best.toList().sortedWith(comparator)
            }

            val entries = JSONArray()
            sorted.forEach { item ->
                val child = item.file
                val link = item.symbolicLink
                val type = item.stat?.let { it.st_mode and OsConstants.S_IFMT }
                val regularFile = if (type == null) child.isFile else type == OsConstants.S_IFREG
                entries.put(JSONObject()
                    .put("name", child.name)
                    .put("path", child.absolutePath)
                    .put("directory", item.directory)
                    .put("symlink", link)
                    .put("size", if (!link && regularFile) (item.stat?.st_size ?: child.length()).coerceAtLeast(0L) else 0L)
                    .put("modified", child.lastModified())
                    .put("readable", !link && child.canRead())
                    .put("writable", !link && child.canWrite())
                    .put("mode", if (link) "" else (item.stat?.let { Integer.toOctalString(it.st_mode) } ?: ""))
                    .put("uid", if (link) -1 else (item.stat?.st_uid ?: -1))
                    .put("gid", if (link) -1 else (item.stat?.st_gid ?: -1)))
            }
            JSONObject()
                .put("path", directory.absolutePath)
                .put("entries", entries)
                .put("truncated", files.size > MAX_DIRECTORY_ENTRIES)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("error", safeMessage(e)).toString()
        }
    }

    override fun readTextFile(path: String): String {
        val file = resolveAllowedPath(path, allowRoot = false)
        if (isSymbolicLink(File(path))) throw IOException("Os links simbólicos não são abertos por segurança.")
        if (!file.isFile) throw IOException("Só é possível abrir ficheiros.")
        if (file.length() > MAX_TEXT_BYTES) throw IOException("O ficheiro excede 4 MiB; não será aberto no editor de texto.")
        if (file.extension.lowercase() !in TEXT_EXTENSIONS) {
            throw IOException("Este tipo de ficheiro não é reconhecido como texto editável.")
        }
        val bytes = FileInputStream(file).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_TEXT_BYTES) throw IOException("O ficheiro excede 4 MiB.")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        if (bytes.any { it == 0.toByte() }) throw IOException("O ficheiro parece ser binário e não será aberto como texto.")
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw IOException("O ficheiro não contém texto UTF-8 válido; não será editado para evitar corrupção.")
        }
    }

    override fun writeTextFile(path: String, content: String): Boolean {
        val target = resolveAllowedPath(path, allowRoot = false)
        if (isSymbolicLink(File(path))) throw IOException("Os links simbólicos não são modificados por segurança.")
        if (!target.isFile) throw IOException("Só é possível guardar texto em ficheiros existentes.")
        if (target.extension.lowercase() !in TEXT_EXTENSIONS) throw IOException("Formato de texto não permitido.")
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_TEXT_BYTES) throw IOException("O texto excede o limite de 4 MiB.")
        val parent = target.parentFile ?: throw IOException("Pasta de destino indisponível.")
        val temp = File.createTempFile(".archiver-advanced-", ".tmp", parent)
        val backup = File.createTempFile(".archiver-advanced-", ".bak", parent)
        backup.delete()
        try {
            FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
            if (!target.renameTo(backup)) throw IOException(
                "O Android não permite substituir este ficheiro. Fecha a aplicação proprietária ou verifica as permissões."
            )
            if (!temp.renameTo(target)) {
                backup.renameTo(target)
                throw IOException("Não foi possível instalar o ficheiro atualizado; a versão anterior foi restaurada quando possível.")
            }
            backup.delete()
            return true
        } finally {
            temp.delete()
            if (backup.exists() && !target.exists()) backup.renameTo(target)
            if (backup.exists() && target.exists()) backup.delete()
        }
    }

    override fun createDirectory(path: String): Boolean {
        val directory = resolveAllowedPath(path, allowRoot = false)
        if (directory.exists()) throw IOException("Já existe um ficheiro ou pasta com esse nome.")
        val parent = directory.parentFile ?: throw IOException("Pasta de destino indisponível.")
        resolveAllowedPath(parent.absolutePath)
        if (!parent.isDirectory) throw IOException("A pasta de destino não existe.")
        if (!directory.mkdir()) throw IOException("O Android ou o fabricante bloqueou a criação nesta pasta.")
        return true
    }

    override fun deletePath(path: String): Boolean {
        val file = resolveAllowedPath(path, allowRoot = false)
        if (isSymbolicLink(File(path))) throw IOException("Os links simbólicos não são eliminados por segurança.")
        ensureNotCriticalSystemRoot(file, "eliminar")
        if (file.isDirectory && Os.getuid() != 0 && file.listFiles()?.isNotEmpty() != false) {
            throw IOException("A conta ADB/Shell só elimina pastas vazias. Para apagar pastas com conteúdo, inicia Shizuku/Sui com root.")
        }
        ensureNoSymlinkTree(file)
        deleteTree(file)
        return true
    }

    override fun copyPath(sourcePath: String, destinationPath: String): Boolean {
        val source = resolveAllowedPath(sourcePath, allowRoot = false)
        val destination = resolveAllowedPath(destinationPath, allowRoot = false)
        if (isSymbolicLink(File(sourcePath)) || isSymbolicLink(File(destinationPath))) throw IOException("Links simbólicos não são copiados.")
        ensureNotCriticalSystemRoot(source, "copiar")
        if (!source.exists()) throw IOException("O item de origem não existe ou não está acessível.")
        if (destination.exists()) throw IOException("Já existe um item com esse nome no destino.")
        if (source.absolutePath == destination.absolutePath ||
            destination.absolutePath.startsWith(source.absolutePath.trimEnd(File.separatorChar) + File.separator)) {
            throw IOException("Não podes copiar uma pasta para dentro dela própria.")
        }
        val parent = destination.parentFile ?: throw IOException("Pasta de destino indisponível.")
        if (!parent.isDirectory) throw IOException("A pasta de destino não existe.")
        ensureNoSymlinkTree(source)
        try { copyTree(source, destination) }
        catch (e: Exception) {
            try { if (destination.exists()) deleteTree(destination) } catch (_: Exception) {}
            throw e
        }
        return true
    }

    override fun movePath(sourcePath: String, destinationPath: String): Boolean {
        val source = resolveAllowedPath(sourcePath, allowRoot = false)
        val destination = resolveAllowedPath(destinationPath, allowRoot = false)
        if (isSymbolicLink(File(sourcePath)) || isSymbolicLink(File(destinationPath))) throw IOException("Links simbólicos não são movidos.")
        ensureNotCriticalSystemRoot(source, "mover")
        if (!source.exists()) throw IOException("O item de origem não existe ou não está acessível.")
        if (destination.exists()) throw IOException("Já existe um item com esse nome no destino.")
        if (source.absolutePath == destination.absolutePath ||
            destination.absolutePath.startsWith(source.absolutePath.trimEnd(File.separatorChar) + File.separator)) {
            throw IOException("Não podes mover uma pasta para dentro dela própria.")
        }
        val parent = destination.parentFile ?: throw IOException("Pasta de destino indisponível.")
        if (!parent.isDirectory) throw IOException("A pasta de destino não existe.")
        ensureNoSymlinkTree(source)
        if (source.renameTo(destination)) return true
        try {
            copyTree(source, destination)
            deleteTree(source)
        } catch (e: Exception) {
            throw IOException("A transferência não terminou por completo: " + (e.message ?: "erro de armazenamento") +
                ". Verifica origem e destino antes de repetir.")
        }
        return true
    }

    override fun createEmptyFile(path: String): Boolean {
        val file = resolveAllowedPath(path, allowRoot = false)
        if (file.exists()) throw IOException("Já existe um ficheiro ou pasta com esse nome.")
        val parent = file.parentFile ?: throw IOException("Pasta de destino indisponível.")
        if (!parent.isDirectory) throw IOException("A pasta de destino não existe.")
        if (!file.createNewFile()) throw IOException("Não foi possível criar o ficheiro.")
        return true
    }

    override fun extractArchive(
        archivePath: String,
        destinationDirectory: String,
        format: String,
        password: String
    ): String {
        var count = 0
        var skippedLinks = 0
        val totalBytes = longArrayOf(0L)
        return try {
            val archive = resolveAllowedPath(archivePath, allowRoot = false)
            val destination = resolveAllowedPath(destinationDirectory, allowRoot = true)
            if (isSymbolicLink(File(archivePath)) || isSymbolicLink(File(destinationDirectory))) {
                throw IOException("Não é permitido usar um link simbólico como arquivo ou destino.")
            }
            if (!archive.isFile || !archive.canRead()) throw IOException("O arquivo não existe ou não pode ser lido.")
            if (!destination.isDirectory || !destination.canWrite()) throw IOException("A pasta de destino não é gravável.")
            ensureNotCriticalSystemRoot(destination, "extrair arquivo em")
            val kind = format.lowercase(Locale.ROOT)
            if (kind !in setOf("zip", "7z", "rar", "tar", "tgz", "tbz2", "txz")) {
                throw IOException("Formato de arquivo não suportado nesta pasta avançada.")
            }

            fun processDirectory(name: String) {
                if (count + skippedLinks >= MAX_ARCHIVE_ENTRIES) throw IOException("O arquivo excede o limite de entradas.")
                val target = prepareArchiveTarget(destination, name, true) ?: return
                if (!target.exists() && !target.mkdirs()) throw IOException("Não foi possível criar a pasta: " + target.absolutePath)
                count++
            }

            fun processFile(name: String, input: InputStream) {
                if (count + skippedLinks >= MAX_ARCHIVE_ENTRIES) throw IOException("O arquivo excede o limite de entradas.")
                val target = prepareArchiveTarget(destination, name, false)
                if (target == null) {
                    val buffer = ByteArray(8192)
                    while (input.read(buffer) >= 0) { }
                    return
                }
                try {
                    writeArchiveStream(input, target, totalBytes)
                } finally {
                    // Caller owns the stream and closes it when needed.
                }
                count++
            }

            when (kind) {
                "zip" -> {
                    val zip = if (password.isNotEmpty()) Zip4jFile(archive, password.toCharArray()) else Zip4jFile(archive)
                    try {
                        val headers = zip.fileHeaders
                        if (headers.size > MAX_ARCHIVE_ENTRIES) throw IOException("O arquivo excede o limite de entradas.")
                        headers.forEach { header ->
                            if (header.isDirectory) processDirectory(header.fileName)
                            else zip.getInputStream(header).use { input -> processFile(header.fileName, input) }
                        }
                    } finally {
                        try { zip.close() } catch (_: Exception) {}
                    }
                }
                "7z" -> {
                    val seven = if (password.isNotEmpty()) SevenZFile(archive, password.toCharArray()) else SevenZFile(archive)
                    seven.use { reader ->
                        while (true) {
                            val entry = reader.nextEntry ?: break
                            if (entry.isDirectory) {
                                processDirectory(entry.name)
                            } else {
                                if (count + skippedLinks >= MAX_ARCHIVE_ENTRIES) throw IOException("O arquivo excede o limite de entradas.")
                                val target = prepareArchiveTarget(destination, entry.name, false)
                                if (target == null) {
                                    val skip = ByteArray(8192)
                                    while (reader.read(skip, 0, skip.size) >= 0) { }
                                } else {
                                    try {
                                        FileOutputStream(target).use { output ->
                                            val buffer = ByteArray(128 * 1024)
                                            while (true) {
                                                val n = reader.read(buffer, 0, buffer.size)
                                                if (n < 0) break
                                                if (n == 0) continue
                                                if (n.toLong() > MAX_ARCHIVE_OUTPUT_BYTES - totalBytes[0]) {
                                                    throw IOException("O arquivo excede o limite de segurança de extração.")
                                                }
                                                output.write(buffer, 0, n)
                                                totalBytes[0] += n
                                            }
                                            output.fd.sync()
                                        }
                                    } catch (e: Exception) {
                                        try { target.delete() } catch (_: Exception) {}
                                        throw e
                                    }
                                    count++
                                }
                            }
                        }
                    }
                }
                "rar" -> {
                    val rar = if (password.isNotEmpty()) JunrarArchive(archive, password) else JunrarArchive(archive)
                    rar.use { reader ->
                        val headers = reader.fileHeaders
                        if (headers.size > MAX_ARCHIVE_ENTRIES) throw IOException("O arquivo excede o limite de entradas.")
                        headers.forEach { header ->
                            if (header.redirection != null) {
                                skippedLinks++
                            } else if (header.isDirectory) {
                                processDirectory(header.fileName)
                            } else {
                                reader.getInputStream(header).use { input -> processFile(header.fileName, input) }
                            }
                        }
                    }
                }
                "tar", "tgz", "tbz2", "txz" -> {
                    val raw = BufferedInputStream(FileInputStream(archive))
                    val decompressed: InputStream = when (kind) {
                        "tgz" -> GzipCompressorInputStream(raw)
                        "tbz2" -> BZip2CompressorInputStream(raw)
                        "txz" -> XZCompressorInputStream(raw)
                        else -> raw
                    }
                    TarArchiveInputStream(decompressed).use { tar ->
                        while (true) {
                            val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                            if (entry.isSymbolicLink || entry.isLink || (!entry.isDirectory && !entry.isFile)) {
                                skippedLinks++
                                continue
                            }
                            if (entry.isDirectory) processDirectory(entry.name)
                            else processFile(entry.name, tar)
                        }
                    }
                }
            }
            JSONObject()
                .put("success", true)
                .put("entries", count)
                .put("skippedLinks", skippedLinks)
                .put("bytes", totalBytes[0])
                .toString()
        } catch (e: Exception) {
            JSONObject()
                .put("success", false)
                .put("error", e.message ?: "A extração foi bloqueada.")
                .put("partialEntries", count)
                .put("partialBytes", totalBytes[0])
                .toString()
        }
    }

    private fun prepareArchiveTarget(destination: File, rawName: String, isDirectory: Boolean): File? {
        var normalized = rawName.replace('\\', '/').trim()
        while (normalized.startsWith("./")) normalized = normalized.substring(2)
        if (normalized.isBlank() || normalized == ".") return null
        if (normalized.startsWith("/") || normalized.startsWith("//") ||
            normalized.matches(Regex("^[A-Za-z]:.*")) || normalized.contains(0.toChar())) {
            throw IOException("O arquivo contém um caminho absoluto inválido: " + rawName)
        }
        val parts = normalized.split('/')
        if (parts.any { it == ".." }) throw IOException("O arquivo contém '..' no caminho: " + rawName)
        val safeParts = parts.filter { it.isNotEmpty() && it != "." }
        if (safeParts.isEmpty()) return null

        var lexical = destination.canonicalFile
        safeParts.forEachIndexed { index, component ->
            lexical = File(lexical, component)
            if (index < safeParts.lastIndex && lexical.exists() && isSymbolicLink(lexical)) {
                throw IOException("O destino contém um link simbólico; extração bloqueada.")
            }
        }
        val rootPath = destination.canonicalPath
        val target = lexical.canonicalFile
        val prefix = if (rootPath == File.separator) File.separator else rootPath.trimEnd(File.separatorChar) + File.separator
        if (target.path != rootPath && !target.path.startsWith(prefix)) {
            throw IOException("O caminho extraído sairia da pasta de destino.")
        }
        if (isSymbolicLink(target)) throw IOException("O destino já é um link simbólico.")
        if (target.path == rootPath) return if (isDirectory) destination else null

        if (isDirectory) {
            if (target.exists() && target.isDirectory) return target
            val actual = if (target.exists()) uniqueArchiveTarget(target) else target
            actual.parentFile?.let { ensureArchiveDirectory(it, destination) }
            if (!actual.exists() && !actual.mkdirs()) throw IOException("Não foi possível criar a pasta: " + actual.absolutePath)
            if (isSymbolicLink(actual)) throw IOException("A pasta criada é um link simbólico.")
            return actual
        }

        val parent = target.parentFile ?: throw IOException("Pasta de destino indisponível.")
        ensureArchiveDirectory(parent, destination)
        val actual = if (target.exists()) uniqueArchiveTarget(target) else target
        if (isSymbolicLink(actual)) throw IOException("O ficheiro de destino é um link simbólico.")
        return actual
    }

    private fun ensureArchiveDirectory(directory: File, destinationRoot: File) {
        val rootPath = destinationRoot.canonicalPath
        val prefix = if (rootPath == File.separator) File.separator else rootPath.trimEnd(File.separatorChar) + File.separator
        val canonical = directory.canonicalFile
        if (canonical.path != rootPath && !canonical.path.startsWith(prefix)) {
            throw IOException("A pasta necessária sairia do destino permitido.")
        }
        if (isSymbolicLink(directory)) throw IOException("Não é permitido criar ficheiros através de links simbólicos.")
        if (directory.exists()) {
            if (!directory.isDirectory) throw IOException("O caminho já existe e não é uma pasta: " + directory.absolutePath)
            return
        }
        directory.parentFile?.let { ensureArchiveDirectory(it, destinationRoot) }
        if (!directory.mkdir()) throw IOException("Não foi possível criar a pasta: " + directory.absolutePath)
        if (isSymbolicLink(directory)) throw IOException("A pasta criada é um link simbólico.")
    }

    private fun uniqueArchiveTarget(file: File): File {
        val parent = file.parentFile ?: throw IOException("Pasta de destino indisponível.")
        val name = file.name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        var counter = 1
        var candidate: File
        do {
            candidate = File(parent, stem + " (" + counter + ")" + extension)
            counter++
        } while (candidate.exists() && counter < 100000)
        if (candidate.exists()) throw IOException("Não foi possível criar um nome de destino único.")
        return candidate
    }

    private fun writeArchiveStream(input: InputStream, target: File, totalBytes: LongArray) {
        try {
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    if (n.toLong() > MAX_ARCHIVE_OUTPUT_BYTES - totalBytes[0]) {
                        throw IOException("O arquivo excede o limite de segurança de extração.")
                    }
                    output.write(buffer, 0, n)
                    totalBytes[0] += n
                }
                // Close normally and let the OS batch writes; avoid forcing a disk sync for
                // every archive member, which makes extraction of many small files very slow.
            }
        } catch (e: Exception) {
            try { target.delete() } catch (_: Exception) {}
            throw e
        }
    }

    private fun copyTree(source: File, destination: File) {
        if (isSymbolicLink(source)) throw IOException("A origem contém um link simbólico: " + source.name)
        if (source.isDirectory) {
            if (!destination.mkdir()) throw IOException("Não foi possível criar a pasta de destino: " + destination.name)
            val children = source.listFiles() ?: throw IOException("Não foi possível ler a pasta: " + source.absolutePath)
            children.forEach { copyTree(it, File(destination, it.name)) }
        } else if (source.isFile) {
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    // Larger blocks reduce system-call overhead when transferring large files.
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) output.write(buffer, 0, count)
                    }
                }
            }
        } else throw IOException("Tipo de ficheiro especial não suportado: " + source.absolutePath)
    }

    private fun ensureNoSymlinkTree(file: File) {
        if (isSymbolicLink(file)) throw IOException("A operação foi bloqueada porque o item contém links simbólicos.")
        if (file.isDirectory) {
            val children = file.listFiles() ?: throw IOException("Não foi possível verificar a pasta " + file.absolutePath)
            children.forEach { ensureNoSymlinkTree(it) }
        }
    }

    private fun deleteTree(file: File) {
        if (isSymbolicLink(file)) throw IOException("Não é permitido seguir nem eliminar links simbólicos.")
        if (file.isDirectory) {
            val children = file.listFiles() ?: throw IOException("Não foi possível ler o conteúdo de " + file.absolutePath)
            children.forEach { deleteTree(it) }
        }
        if (!file.delete()) throw IOException("Não foi possível eliminar " + file.absolutePath + "; a operação pode estar bloqueada.")
    }

    override fun renamePath(path: String, newName: String): Boolean {
        val source = resolveAllowedPath(path, allowRoot = false)
        ensureNotCriticalSystemRoot(source, "renomear")
        if (isSymbolicLink(File(path))) throw IOException("Os links simbólicos não são renomeados por segurança.")
        val name = newName.trim()
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/') ||
            name.contains('\\') || name.contains('\u0000')) {
            throw IOException("Nome inválido.")
        }
        if (name != newName) throw IOException("Remove os espaços no início/fim do nome.")
        val parent = source.parentFile ?: throw IOException("Pasta de origem indisponível.")
        val destination = resolveAllowedPath(File(parent, name).absolutePath, allowRoot = false)
        if (destination.exists()) throw IOException("Já existe um item com esse nome.")
        if (!source.renameTo(destination)) throw IOException("O Android ou o fabricante bloqueou a renomeação.")
        return true
    }

    private fun ensureNotCriticalSystemRoot(file: File, action: String) {
        val critical = setOf(
            "/", "/data", "/system", "/system/vendor", "/system/product",
            "/vendor", "/product", "/apex", "/proc", "/sys", "/dev", "/system_ext", "/odm", "/metadata", "/mnt", "/storage", "/config", "/linkerconfig"
        )
        if (file.canonicalPath in critical) {
            throw IOException("A ação '" + action + "' está bloqueada para uma raiz crítica do Android: " + file.absolutePath)
        }
    }

    private fun restrictedRoots(): List<File> {
        val primary = Environment.getExternalStorageDirectory().canonicalFile
        return listOf(File(primary, "Android/data").canonicalFile, File(primary, "Android/obb").canonicalFile)
    }

    private fun resolveAllowedPath(path: String, allowRoot: Boolean = true): File {
        if (path.isBlank() || path.contains('\u0000')) throw IOException("Caminho inválido.")
        val candidate = File(path).canonicalFile
        val primary = Environment.getExternalStorageDirectory().canonicalFile
        val protectedRoots = restrictedRoots()
        if (Os.getuid() != 0) {
            (protectedRoots + primary).firstOrNull {
                candidate.path == it.path || candidate.path.startsWith(it.path.trimEnd(File.separatorChar) + File.separator)
            } ?: throw SecurityException("O acesso avançado sem root está limitado ao armazenamento partilhado e a Android/data/Android/obb.")
            if (!allowRoot && (protectedRoots + primary).any { candidate.path == it.path }) {
                throw IOException("A raiz de uma área protegida não pode ser renomeada nem eliminada.")
            }
        }
        if (!allowRoot && candidate.path == File("/").canonicalPath) throw IOException("A raiz do sistema não pode ser modificada como item.")
        return candidate
    }

    private fun isSymbolicLink(file: File): Boolean = try {
        (Os.lstat(file.absolutePath).st_mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK
    } catch (_: Exception) {
        false
    }

    private fun safeMessage(error: Exception): String {
        val message = error.message?.trim().orEmpty()
        return if (message.isBlank()) "Acesso negado ou erro de armazenamento." else message.take(300)
    }

    companion object {
        private const val MAX_DIRECTORY_ENTRIES = 2000
        private const val MAX_TEXT_BYTES = 4 * 1024 * 1024
        private const val MAX_ARCHIVE_ENTRIES = 100000
        private const val MAX_ARCHIVE_OUTPUT_BYTES = 64L * 1024L * 1024L * 1024L
        private val TEXT_EXTENSIONS = setOf(
            "txt", "xml", "json", "ini", "cfg", "conf", "properties", "log", "yml", "yaml",
            "html", "htm", "css", "js", "md", "csv", "toml", "sh", "kt", "kts", "java", "gradle",
            "sql", "py", "env"
        )
    }
}
