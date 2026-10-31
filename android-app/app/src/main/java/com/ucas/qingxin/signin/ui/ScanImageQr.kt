package com.ucas.qingxin.signin.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 「从相册选图扫码」的**装载与解码**。
 *
 * 与相机取景（[FastScanAnalyzer]）共用同一套码制策略：**先只认二维码，
 * 认不出再放宽到全部码制** —— 相册里的截图 / 拍照同样可能是别的码制。
 * 在此之上多两个图片特有的维度：
 *
 * - **旋转重试**：照片的 EXIF 旋转已在装载时校正，但部分来源（原图转发、
 *   截图工具）会丢失 EXIF，码可能是横躺的 —— 90° 再试一遍；
 * - **降采样**：现代相机动辄 4000×3000，ZXing 在大图上既慢又容易失败
 *   （二值化的噪点被放大），装载时压到 [MAX_DIMEN] 内。
 *
 * 全部函数都可能做磁盘与像素级操作，**必须在 IO 线程调用**。
 */
object ScanImageQr {

    /** 装载后的最大边长：识别二维码绰绰有余，再大只是浪费时间。 */
    const val MAX_DIMEN = 1600

    /** 只认二维码的 hints，与 [FastScanAnalyzer] 的收窄配置逐字一致。 */
    private val QR_HINTS: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /**
     * 从内容 URI 装载一张降采样、已按 EXIF 校正方向的位图；失败返回 null。
     */
    fun load(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 标准 inSampleSize 折半：目标是把长边压到 [MAX_DIMEN] 量级。
        var sample = 1
        if (bounds.outHeight > MAX_DIMEN || bounds.outWidth > MAX_DIMEN) {
            val halfH = bounds.outHeight / 2
            val halfW = bounds.outWidth / 2
            while ((halfH / sample) >= MAX_DIMEN && (halfW / sample) >= MAX_DIMEN) sample *= 2
        }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: return null
        return applyExifRotation(resolver, uri, bitmap)
    }

    /**
     * 解出图片里的二维码文本；认不出返回 null。
     *
     * 尝试顺序：原图（QR → 全码制）→ 旋转 90°（QR → 全码制）。
     * 「QR 优先」的理由见 [FastScanAnalyzer] 的性能账；旋转兜底覆盖
     * 「EXIF 缺失的竖拍照片」。反色重试刻意不做：班牌与截图都是白底黑码，
     * 反色再解一遍只对极罕见的反色码有价值，却让每张图多一倍成本。
     */
    fun decode(bitmap: Bitmap): String? {
        val rotated = rotate(bitmap, 90f)
        for (image in listOf(bitmap, rotated)) {
            tryDecode(image, QR_HINTS)?.let { return it }
            tryDecode(image, emptyMap())?.let { return it }
        }
        return null
    }

    private fun tryDecode(bitmap: Bitmap, hints: Map<DecodeHintType, Any>): String? = try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val reader = MultiFormatReader()
        reader.setHints(hints)
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: Exception) {
        null
    }

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap =
        Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { postRotate(degrees) },
            true,
        )

    /** 按 EXIF 方向校正照片（截图通常无 EXIF，原样返回）。 */
    private fun applyExifRotation(
        resolver: android.content.ContentResolver,
        uri: Uri,
        bitmap: Bitmap,
    ): Bitmap {
        val degrees = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                when (
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (degrees == 0f) return bitmap
        return rotate(bitmap, degrees)
    }
}
