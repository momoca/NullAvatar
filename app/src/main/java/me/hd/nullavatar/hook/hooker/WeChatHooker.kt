package me.hd.nullavatar.hook.hooker

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import me.hd.nullavatar.hook.base.BaseHook
import me.hd.nullavatar.hook.util.AvatarUtil
import me.hd.nullavatar.hook.util.toStream
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.io.FileNotFoundException

object WeChatHooker : BaseHook() {
    private const val TAG = "NullAvatar"
    private val WCF_AVATAR_PREFIXES = listOf("wcf:/avatar/", "wcf://avatar/")
    private lateinit var getClipBitmapMethod: DexMethod

    override fun onDexFind(dexkit: DexKitBridge) {
        getClipBitmapMethod = dexkit.findMethod {
            matcher {
                usingEqStrings(
                    "MediaTailor",
                    "Rect width or height contains zero. contentRect: ",
                )
            }
        }.single().toDexMethod()
    }

    private fun resolveAvatarFile(ctx: Context, path: String): File {
        require(path.isNotBlank()) { "Empty WeChat avatar path" }
        // WeChat versions use both single-slash and double-slash WCF paths.
        val prefix = WCF_AVATAR_PREFIXES.firstOrNull { path.startsWith(it) }
        if (prefix == null) {
            require(!path.startsWith("wcf:")) { "Unsupported WeChat VFS path: $path" }
            return File(path)
        }

        val relativePath = path.removePrefix(prefix)
        require(relativePath.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Invalid WCF avatar path: $path"
        }
        Log.i(TAG, "Resolve WCF avatar: $relativePath")

        // Use the host's data directory: /data/user/0/com.tencent.mm for the primary
        // Android user, while also supporting secondary users and app clones.
        val microMsgDir = File(ctx.dataDir, "MicroMsg")
        val accounts = microMsgDir.listFiles()
            ?: throw FileNotFoundException("Cannot list WeChat account directories: $microMsgDir")
        val matches = accounts.asSequence()
            .filter { it.isDirectory && it.name.matches(Regex("[0-9a-fA-F]{32}")) }
            .map { account ->
                val avatarDir = File(account, "avatar").canonicalFile
                val target = File(avatarDir, relativePath).canonicalFile
                require(target.path.startsWith(avatarDir.path + File.separator)) {
                    "Avatar path escapes its account directory: $path"
                }
                target
            }
            .filter { it.isFile }
            .distinctBy { it.path }
            .toList()

        if (matches.isEmpty()) {
            throw FileNotFoundException("No existing avatar under $microMsgDir for $path")
        }
        check(matches.size == 1) {
            "Ambiguous WCF avatar path: $path matches ${matches.size} accounts; skipping overwrite"
        }
        return matches.single()
    }


    private fun logPng(label: String, bytes: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000L) {
            "Invalid or oversized avatar: ${bounds.outWidth}x${bounds.outHeight}"
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "Cannot decode avatar"
        }
        try {
            var transparent = 0L
            var partial = 0L
            val row = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (pixel in row) {
                    val alpha = pixel ushr 24
                    if (alpha == 0) transparent++ else if (alpha < 255) partial++
                }
            }
            Log.i(TAG, "$label: ${bitmap.width}x${bitmap.height}, " +
                "hasAlpha=${bitmap.hasAlpha()}, transparent=$transparent, partial=$partial, bytes=${bytes.size}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun replacementPng(ctx: Context): ByteArray {
        val source = File(ctx.filesDir, "NullAvatar.png")
        val png = if (source.exists()) {
            require(source.isFile && source.length() in 1L..20_000_000L) {
                "Custom PNG is empty, oversized, or not a file: $source"
            }
            Log.i(TAG, "Replacement mode: original PNG bytes from $source")
            source.readBytes()
        } else {
            Log.i(TAG, "Replacement mode: default transparent 64x64")
            AvatarUtil.getBitmap(ctx).toStream().use { it.toByteArray() }
        }
        val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        require(png.size >= signature.size &&
            signature.indices.all { png[it] == signature[it] }) {
            "Replacement file is not a PNG; skipping overwrite"
        }
        // Decode only for diagnostics. The decoded bitmap is never used for writing.
        logPng("Source PNG", png)
        return png
    }

    override fun onBaseHook(ctx: Context, loader: ClassLoader) {
        getClipBitmapMethod.toAppMethod().hook {
            after {
                try {
                    val clipResult = result
                    if (clipResult == null) {
                        Log.w(TAG, "Skip avatar overwrite: crop result is null")
                    } else {
                        val path = clipResult.asResolver()
                            .firstField { type = String::class }.get<String>()
                        require(!path.isNullOrBlank()) { "Crop result contains no avatar path" }
                        Log.i(TAG, "Original avatar path: $path")
                        val target = resolveAvatarFile(ctx, path)
                        Log.i(TAG, "Resolved avatar: ${target.absolutePath}")

                        val png = replacementPng(ctx)
                        target.writeBytes(png)
                        val written = target.readBytes()
                        check(written.contentEquals(png)) { "Avatar read-back differs from source" }
                        logPng("Written avatar", written)
                        Log.i(TAG, "Overwrite avatar: ${target.absolutePath} (${png.size} bytes, byteExact=true)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WeChat avatar replacement failed", e)
                }
            }
        }
    }
}
