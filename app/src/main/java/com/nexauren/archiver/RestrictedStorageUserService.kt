package com.nexauren.archiver

import android.os.Environment
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Optional Shizuku UserService used only when the user explicitly enables advanced
 * storage access. Its file API is intentionally limited to shared Android/data and
 * Android/obb trees; it never accesses /data/data or other apps' internal sandboxes.
 */
class RestrictedStorageUserService : IRestrictedStorageService.Stub() {

    override fun getRestrictedRootPaths(): Array<String> =
        restrictedRoots().map { it.absolutePath }.toTypedArray()

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
            val entries = JSONArray()
            val sorted = files.sortedWith(compareByDescending<File> { it.isDirectory && !isSymbolicLink(it) }
                .thenBy { it.name.lowercase() })
            sorted.take(MAX_DIRECTORY_ENTRIES).forEach { child ->
                val link = isSymbolicLink(child)
                entries.put(JSONObject()
                    .put("name", child.name)
                    .put("path", child.absolutePath)
                    .put("directory", !link && child.isDirectory)
                    .put("symlink", link)
                    .put("size", if (!link && child.isFile) child.length().coerceAtLeast(0L) else 0L)
                    .put("modified", child.lastModified())
                    .put("readable", !link && child.canRead())
                    .put("writable", !link && child.canWrite()))
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
        if (file.length() > MAX_TEXT_BYTES) throw IOException("O ficheiro excede 1 MiB; não será aberto no editor de texto.")
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
                if (total > MAX_TEXT_BYTES) throw IOException("O ficheiro excede 1 MiB.")
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
        if (bytes.size > MAX_TEXT_BYTES) throw IOException("O texto excede o limite de 1 MiB.")
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
        if (file.isDirectory && file.listFiles()?.isNotEmpty() != false) {
            throw IOException("Para evitar perda acidental de dados, esta ferramenta só elimina pastas vazias.")
        }
        if (!file.delete()) throw IOException("O Android ou o fabricante bloqueou a eliminação deste item.")
        return true
    }

    override fun renamePath(path: String, newName: String): Boolean {
        val source = resolveAllowedPath(path, allowRoot = false)
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

    private fun restrictedRoots(): List<File> {
        val primary = Environment.getExternalStorageDirectory().canonicalFile
        return listOf(File(primary, "Android/data").canonicalFile, File(primary, "Android/obb").canonicalFile)
    }

    private fun resolveAllowedPath(path: String, allowRoot: Boolean = true): File {
        if (path.isBlank() || path.contains('\u0000')) throw IOException("Caminho inválido.")
        val candidate = File(path).canonicalFile
        val roots = restrictedRoots()
        val root = roots.firstOrNull {
            candidate.path == it.path || candidate.path.startsWith(it.path + File.separator)
        } ?: throw SecurityException("O acesso avançado do Archiver está limitado a Android/data e Android/obb.")
        if (!allowRoot && candidate.path == root.path) throw IOException("A raiz protegida não pode ser renomeada nem eliminada.")
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
        private const val MAX_TEXT_BYTES = 1024 * 1024
        private val TEXT_EXTENSIONS = setOf(
            "txt", "xml", "json", "ini", "cfg", "conf", "properties", "log", "yml", "yaml",
            "html", "htm", "css", "js", "md", "csv", "toml", "sh", "kt", "java", "gradle",
            "gradle.kts", "sql", "py", "env"
        )
    }
}
