package com.example.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.model.ActiveRoomRider
import com.example.data.model.BikeSetup
import com.example.data.model.Circuit
import com.example.data.model.RiderAccount
import com.example.data.model.computeRankTitle
import com.example.data.model.formatLapMs
import java.io.File
import java.io.FileOutputStream

object CardExporter {

    fun shareWhatsAppText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(fallback, "Share"))
        }
    }

    fun formatSetupShareText(riderName: String, bike: BikeSetup, isSpanish: Boolean): String {
        val ratio = bike.effectivePinionSpur()
        return if (isSpanish) {
            "🏁 SETUP LOSI PROMOTO [${bike.discipline.name}] — METROPOLITANO MX PRO\n" +
                "Piloto: $riderName (#${bike.dorsal}) • ${bike.modelName}\n" +
                "Terreno: ${bike.terrain}\n" +
                "• Suspensión: Horquilla ${bike.forkOil} (${bike.forkSpring}) | Amort. ${bike.shockOil} (${bike.shockSpring}) | SAG ${bike.rearSagMm}\n" +
                "• Geometría: Lean Bars ${bike.leanBarSetting} | Freno Del. ${bike.frontBrakePercent}% | Servo Saver ${bike.servoSaverSetting}\n" +
                "• Spektrum MS6X / ESC: Gyro ${bike.gyroPercent}% (${bike.gyroMode}) | Punch ${bike.escPunch}/9 | Drag ${bike.escDragBrake}% | Max Freno ${bike.escMaxBrake}%\n" +
                "• Desarrollo y Ruedas: $ratio | Del. ${bike.frontTire} / Tras. ${bike.rearTire}\n" +
                "• LiPo: ${bike.lipoSpecs} (${bike.lipoCycles} ciclos)"
        } else {
            "🏁 LOSI PROMOTO SETUP [${bike.discipline.name}] — METROPOLITANO MX PRO\n" +
                "Rider: $riderName (#${bike.dorsal}) • ${bike.modelName}\n" +
                "Terrain: ${bike.terrain}\n" +
                "• Suspension: Fork ${bike.forkOil} (${bike.forkSpring}) | Shock ${bike.shockOil} (${bike.shockSpring}) | SAG ${bike.rearSagMm}\n" +
                "• Geometry: Lean Bars ${bike.leanBarSetting} | Front Brake ${bike.frontBrakePercent}% | Servo Saver ${bike.servoSaverSetting}\n" +
                "• Spektrum MS6X / ESC: Gyro ${bike.gyroPercent}% (${bike.gyroMode}) | Punch ${bike.escPunch}/9 | Drag ${bike.escDragBrake}% | Max Brake ${bike.escMaxBrake}%\n" +
                "• Gearing & Tires: $ratio | F: ${bike.frontTire} / R: ${bike.rearTire}\n" +
                "• LiPo: ${bike.lipoSpecs} (${bike.lipoCycles} cycles)"
        }
    }

    fun generateAndShareSummaryPng(
        context: Context,
        rider: RiderAccount,
        circuit: Circuit,
        bike: BikeSetup?,
        dayLapsCount: Int,
        dayBestLapMs: Long,
        isSpanish: Boolean
    ): Uri? {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(Color.parseColor("#0A0C10"), Color.parseColor("#161D2B"), Color.parseColor("#0A0C10")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5B731")
            textSize = 54f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 32f
        }
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A2232")
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5B731")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        c.drawText("METROPOLITANO MX PRO", 72f, 110f, goldPaint)
        c.drawText(circuit.name, 72f, 168f, subPaint)

        c.drawRoundRect(RectF(64f, 220f, 1016f, 440f), 28f, 28f, boxPaint)
        c.drawRoundRect(RectF(64f, 220f, 1016f, 440f), 28f, 28f, strokePaint)

        val rank = computeRankTitle(rider.totalLaps, isSpanish)
        c.drawText("${rider.name}  #${bike?.dorsal ?: "01"}", 104f, 305f, whitePaint)
        c.drawText("${bike?.modelName ?: "Losi Promoto"} • $rank", 104f, 365f, goldPaint.apply { textSize = 34f })

        val metrics = listOf(
            (if (isSpanish) "MEJOR VUELTA HOY" else "BEST LAP TODAY") to formatLapMs(dayBestLapMs),
            (if (isSpanish) "MEJOR HISTÓRICA" else "ALL-TIME BEST") to formatLapMs(rider.bestLapMs),
            (if (isSpanish) "VUELTAS SESIÓN" else "SESSION LAPS") to "$dayLapsCount",
            (if (isSpanish) "REGULARIDAD" else "REGULARITY") to "${String.format("%.1f", rider.regularityIndex)}%",
            (if (isSpanish) "VELOCIDAD MEDIA" else "AVG SPEED") to "${String.format("%.1f", rider.avgSpeedKmh)} km/h"
        )
        var y = 510f
        metrics.forEach { (label, value) ->
            c.drawRoundRect(RectF(64f, y, 1016f, y + 120f), 22f, 22f, boxPaint)
            c.drawText(label, 104f, y + 72f, subPaint)
            val valPaint = Paint(whitePaint).apply {
                textAlign = Paint.Align.RIGHT
                color = Color.parseColor("#FFD86B")
            }
            c.drawText(value, 976f, y + 74f, valPaint)
            y += 146f
        }

        val uri = saveAndShareBitmap(context, bmp, "mx_pro_summary_${rider.id}.png", isSpanish)
        return uri
    }

    fun generateAndShareMxgpPodium(
        context: Context,
        circuit: Circuit,
        topRiders: List<ActiveRoomRider>,
        isSpanish: Boolean
    ): Uri? {
        val w = 1080
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#0B0E14"))

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5B731")
            textSize = 58f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        c.drawText(if (isSpanish) "PODIO OFICIAL MXGP" else "OFFICIAL MXGP PODIUM", w / 2f, 110f, headerPaint)
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 32f
            textAlign = Paint.Align.CENTER
        }
        c.drawText(circuit.name, w / 2f, 165f, subPaint)

        val slots = listOf(
            Triple(1, 540f, 360f), // 1st center
            Triple(2, 210f, 470f), // 2nd left
            Triple(3, 870f, 520f)  // 3rd right
        )
        slots.forEach { (pos, cx, topY) ->
            val rider = topRiders.getOrNull(pos - 1)
            val col = when (pos) {
                1 -> Color.parseColor("#F5B731")
                2 -> Color.parseColor("#CBD5E1")
                else -> Color.parseColor("#D97706")
            }
            val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#18202E") }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = col
                style = Paint.Style.STROKE
                strokeWidth = 6f
            }
            val rect = RectF(cx - 150f, topY, cx + 150f, 940f)
            c.drawRoundRect(rect, 24f, 24f, blockPaint)
            c.drawRoundRect(rect, 24f, 24f, borderPaint)

            val posPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = col
                textSize = 72f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            c.drawText("P$pos", cx, topY + 95f, posPaint)
            val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 34f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            c.drawText(rider?.riderName ?: "---", cx, topY + 170f, namePaint)
            c.drawText(
                if (rider != null) formatLapMs(rider.bestLapMs) else "--.--",
                cx, topY + 225f, subPaint
            )
        }

        return saveAndShareBitmap(context, bmp, "mxgp_podium.png", isSpanish)
    }

    private fun saveAndShareBitmap(
        context: Context,
        bmp: Bitmap,
        filename: String,
        isSpanish: Boolean
    ): Uri? {
        return try {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, filename)
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(
                    shareIntent,
                    if (isSpanish) "Compartir Tarjeta PNG" else "Share PNG Card"
                )
            )
            uri
        } catch (_: Exception) {
            null
        }
    }
}
