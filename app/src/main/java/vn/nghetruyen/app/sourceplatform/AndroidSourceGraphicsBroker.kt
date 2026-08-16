package vn.nghetruyen.app.sourceplatform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceGraphicsBroker
import vn.nghetruyen.source.api.SourceGraphicsRequest
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import java.io.ByteArrayOutputStream


class AndroidSourceGraphicsBroker : SourceGraphicsBroker {
    override fun render(manifest: SourceManifest, request: SourceGraphicsRequest): SourcePlatformResult<String> = runCatching {
        require(request.sourceId == manifest.id) { "SOURCE_GRAPHICS_SOURCE_ID_MISMATCH" }
        require(manifest.runtime.mode in setOf(SourceRuntimeMode.VBOOK_JS_COMPAT, SourceRuntimeMode.NATIVE_LUA_COMPAT)) {
            "SOURCE_GRAPHICS_RUNTIME_DENIED"
        }
        require(request.width in 1..MAX_DIMENSION && request.height in 1..MAX_DIMENSION) { "SOURCE_GRAPHICS_DIMENSION_INVALID" }
        require(request.width.toLong() * request.height.toLong() <= MAX_PIXELS) { "SOURCE_GRAPHICS_PIXEL_LIMIT" }
        require(request.operations.size <= MAX_OPERATIONS) { "SOURCE_GRAPHICS_OPERATION_LIMIT" }
        require(request.maxOutputBytes in 1024..MAX_OUTPUT_BYTES) { "SOURCE_GRAPHICS_OUTPUT_LIMIT_INVALID" }

        val output = Bitmap.createBitmap(request.width, request.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        try {
            request.operations.forEach { operation ->
                val imageBytes = decodeBase64(operation.imageBase64)
                require(imageBytes.size <= MAX_IMAGE_BYTES) { "SOURCE_GRAPHICS_IMAGE_TOO_LARGE" }
                val image = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    ?: error("SOURCE_GRAPHICS_IMAGE_INVALID")
                try {
                    paint.alpha = (operation.alpha.coerceIn(0.0, 1.0) * 255.0).toInt()
                    draw(canvas, image, operation.args)
                } finally {
                    image.recycle()
                }
            }
            val format = when (request.format.uppercase()) {
                "JPG", "JPEG" -> Bitmap.CompressFormat.JPEG
                "WEBP" -> Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.PNG
            }
            val encoded = ByteArrayOutputStream().use { stream ->
                require(output.compress(format, request.quality.coerceIn(1, 100), stream)) { "SOURCE_GRAPHICS_ENCODE_FAILED" }
                stream.toByteArray()
            }
            require(encoded.size <= request.maxOutputBytes) { "SOURCE_GRAPHICS_OUTPUT_TOO_LARGE" }
            Base64.encodeToString(encoded, Base64.NO_WRAP)
        } finally {
            output.recycle()
        }
    }.fold(
        onSuccess = { SourcePlatformResult.Success(it) },
        onFailure = { error ->
            SourcePlatformResult.Failure(SourcePlatformFailure(
                SourceErrorCode.GRAPHICS_UNAVAILABLE,
                error.message ?: "SOURCE_GRAPHICS_FAILED",
                request.traceId,
                error,
            ))
        },
    )

    private fun draw(canvas: Canvas, image: Bitmap, args: List<Double>) {
        when {
            args.size >= 8 -> {
                val src = Rect(
                    args[0].toInt().coerceIn(0, image.width),
                    args[1].toInt().coerceIn(0, image.height),
                    (args[0] + args[2]).toInt().coerceIn(0, image.width),
                    (args[1] + args[3]).toInt().coerceIn(0, image.height),
                )
                val dst = RectF(
                    args[4].toFloat(), args[5].toFloat(),
                    (args[4] + args[6]).toFloat(), (args[5] + args[7]).toFloat(),
                )
                if (src.width() > 0 && src.height() > 0 && dst.width() != 0f && dst.height() != 0f) canvas.drawBitmap(image, src, dst, null)
            }
            args.size >= 4 -> {
                val dst = RectF(
                    args[0].toFloat(), args[1].toFloat(),
                    (args[0] + args[2]).toFloat(), (args[1] + args[3]).toFloat(),
                )
                if (dst.width() != 0f && dst.height() != 0f) canvas.drawBitmap(image, null, dst, null)
            }
            else -> canvas.drawBitmap(image, args.getOrNull(0)?.toFloat() ?: 0f, args.getOrNull(1)?.toFloat() ?: 0f, null)
        }
    }

    private fun decodeBase64(raw: String): ByteArray {
        val payload = raw.substringAfter("base64,", raw).replace(Regex("\\s+"), "")
        require(payload.length <= MAX_IMAGE_BYTES * 2) { "SOURCE_GRAPHICS_IMAGE_TOO_LARGE" }
        return Base64.decode(payload, Base64.DEFAULT)
    }

    companion object {
        private const val MAX_DIMENSION = 4096
        private const val MAX_PIXELS = 16_777_216L
        private const val MAX_OPERATIONS = 128
        private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
        private const val MAX_OUTPUT_BYTES = 16 * 1024 * 1024
    }
}
