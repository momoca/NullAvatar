package me.hd.nullavatar.hook.hooker

import android.content.Context
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

                        // Prepare the PNG before opening (and truncating) the target.
                        // AvatarUtil loads files/NullAvatar.png, or the default transparent image.
                        AvatarUtil.getBitmap(ctx).toStream().use { png ->
                            check(png.size() > 0) { "Replacement PNG is empty" }
                            target.outputStream().use { png.writeTo(it) }
                            Log.i(TAG, "Overwrite avatar: ${target.absolutePath} (${png.size()} bytes)")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WeChat avatar replacement failed", e)
                }
            }
        }
    }
}
