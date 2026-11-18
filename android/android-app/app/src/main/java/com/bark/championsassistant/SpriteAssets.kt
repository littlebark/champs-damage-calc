package com.bark.championsassistant

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 图鉴头像：直接读打包进 assets 的 Champions 菜单图标（每个形态普通/异色各一张）。
 * 这张图就是游戏里名牌用的同一套图标，形态识别也用它。
 */
internal data class SpriteAsset(val file: String, val dex: Int?)

internal object SpriteAssetCatalog {

    private val indexCache = ConcurrentHashMap<String, Map<String, SpriteAsset>>()
    private val bitmapCache = LruCache<String, ImageBitmap>(200)

    /**
     * 素材里没有单独出图的形态，借用同族基础图：图鉴里它们本来就只跟基础形态占一行，
     * 借用后编号和头像都能正常显示。
     */
    private val ALIASES = mapOf(
        "aegislashshield" to "aegislash",
        "aegislashboth" to "aegislash",
        "mimikyubusted" to "mimikyu",
        "mausholdfour" to "maushold",
        "polteageistantique" to "polteageist",
        "sinistchamasterpiece" to "sinistcha",
        "meowsticfmega" to "meowsticf",
    )

    private fun normalized(id: String): String {
        val key = id.lowercase().replace(Regex("[^a-z0-9]+"), "")
        return ALIASES[key] ?: key
    }

    fun indexOf(context: Context): Map<String, SpriteAsset> {
        indexCache["index"]?.let { return it }
        val map = runCatching {
            val text = context.assets.open("sprites/sprite-index.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val result = HashMap<String, SpriteAsset>(root.length() + 8)
            root.keys().forEach { id ->
                val entry = root.optJSONObject(id) ?: return@forEach
                val file = entry.optString("normal").takeIf { it.isNotBlank() && it != "null" }
                    ?: entry.optString("shiny").takeIf { it.isNotBlank() && it != "null" }
                if (file == null) return@forEach
                val dex = entry.optInt("dex", -1).takeIf { it > 0 }
                result[normalized(id)] = SpriteAsset(file, dex)
            }
            result
        }.getOrDefault(emptyMap())
        indexCache["index"] = map
        return map
    }

    fun assetFor(context: Context, showdownId: String): SpriteAsset? =
        indexOf(context)[normalized(showdownId)]

    fun image(context: Context, showdownId: String): ImageBitmap? {
        bitmapCache.get(showdownId)?.let { return it }
        val file = assetFor(context, showdownId)?.file ?: return null
        val bitmap = runCatching {
            context.assets.open("sprites/$file").use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null
        val image = bitmap.asImageBitmap()
        bitmapCache.put(showdownId, image)
        return image
    }

    /** 图鉴编号（001 这种格式），素材里没有这个形态时为 null。 */
    fun dexLabel(context: Context, showdownId: String): String? =
        assetFor(context, showdownId)?.dex?.let { "#" + it.toString().padStart(4, '0') }

    /** 纯编号，用于图鉴列表按编号去重。 */
    fun dex(context: Context, showdownId: String): Int? = assetFor(context, showdownId)?.dex
}

/** 一个小头像；素材里没有这个形态（换装皮卡丘之类）时退回色块 + 名字首字。 */
@Composable
internal fun PokemonAvatar(
    showdownId: String,
    displayName: String,
    size: Dp = 40.dp,
    fallbackColor: Color = Color(0xFFE3E5EA),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val image = remember(showdownId) { SpriteAssetCatalog.image(context, showdownId) }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = displayName,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
    } else {
        Box(
            modifier
                .size(size)
                .background(fallbackColor, RoundedCornerShape(percent = 24)),
            contentAlignment = Alignment.Center,
        ) {
            Text(displayName.take(1), fontSize = (size.value / 2.4f).sp, color = Color.White)
        }
    }
}

/** 持有物图标：读 assets/codex/item-icons/<icon>，没有图时显示灰色问号块。 */
@Composable
internal fun ItemAvatar(
    icon: String,
    displayName: String,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val image = remember(icon) { ItemIconCatalog.load(context, icon) }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = displayName,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
    } else {
        Box(
            modifier
                .size(size)
                .background(Color(0xFFEDEFF3), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("?", color = Color(0xFF9AA0A6))
        }
    }
}

internal object ItemIconCatalog {
    private val cache = LruCache<String, ImageBitmap>(64)

    fun load(context: Context, icon: String): ImageBitmap? {
        if (icon.isBlank()) return null
        cache.get(icon)?.let { return it }
        val image = runCatching {
            context.assets.open("codex/item-icons/$icon").use { BitmapFactory.decodeStream(it).asImageBitmap() }
        }.getOrNull() ?: return null
        cache.put(icon, image)
        return image
    }
}
