package com.example.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.example.data.model.ActiveRoomRider
import com.example.data.model.AiStewardVerdict
import com.example.data.model.RiderColorProfile
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "GeminiStewardEngine"

/**
 * Multimodal AI Race Steward powered by Firebase AI Logic (`gemini-3.8-flash`).
 * Used asynchronously after local 0ms finish-line trigger for:
 * 1. Photo-Finish verification & false-positive rejection (shadows/marshals/wind).
 * 2. Dorsal OCR & multi-bike disambiguation when two bikes share fairing colors.
 * 3. Warm-Up automated rider & bike fairing identification.
 */
class GeminiStewardEngine {

    private val stewardSchema = Schema.obj(
        properties = mapOf(
            "matchedRiderId" to Schema.string(description = "Exact riderId from roster, or empty string if unknown"),
            "detectedDorsal" to Schema.string(description = "Visible number plate / jersey dorsal digits if readable, else empty string"),
            "fairingSummary" to Schema.string(description = "Concise visual description of bike plastics, helmet, and key colors"),
            "confidencePercent" to Schema.integer(description = "Confidence 0..100 for rider identification"),
            "isRealPass" to Schema.boolean(description = "True if image shows a real RC/MX motorcycle passing finish line, false for shadow/person/wind"),
            "reasoningEs" to Schema.string(description = "Short 1-sentence steward verdict in Spanish"),
            "reasoningEn" to Schema.string(description = "Short 1-sentence steward verdict in English")
        ),
        optionalProperties = listOf("matchedRiderId", "detectedDorsal")
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = stewardSchema
            }
        )
    }

    suspend fun inspectPassPhoto(
        photoBase64: String,
        activeRiders: List<ActiveRoomRider>,
        colorProfiles: List<RiderColorProfile>,
        detectedColorsHint: List<String>,
        ocrDorsalHint: String,
        nowMs: Long
    ): Result<AiStewardVerdict> {
        if (photoBase64.isBlank()) {
            return Result.failure(IllegalArgumentException("Photo-Finish vacía / Empty image"))
        }
        val bitmap = decodeBitmap(photoBase64)
            ?: return Result.failure(IllegalStateException("No se pudo decodificar imagen Photo-Finish"))

        val rosterDescription = if (activeRiders.isEmpty()) {
            "Sin pilotos activos registrados en sala."
        } else {
            activeRiders.joinToString("\n") { r ->
                val colors = colorProfiles.firstOrNull { it.riderId == r.riderId }
                    ?.samples?.joinToString(", ") { "${it.colorName} (${it.hex})" } ?: "sin muestra CIE Lab"
                "- riderId=\"${r.riderId}\", nombre=\"${r.riderName}\", dorsal=\"${r.dorsal}\", moto=\"${r.bikeModel}\", disciplina=${r.discipline.name}, colores=[$colors]"
            }
        }

        val prompt = """
            Eres el Comisario Jefe IA de Metropolitano MX Pro (cronometraje de motos RC 1/4 Losi Promoto MX y Supermotard).
            Analiza esta captura del túnel de meta y devuelve JSON estricto:
            1. Determina si es el paso real de una moto (`isRealPass`: true/false). Descarta sombras, personas caminando o viento.
            2. Lee cualquier número de dorsal visible en carenado o piloto (`detectedDorsal`). Pista OCR local: "$ocrDorsalHint".
            3. Pista colores locales detectados: ${detectedColorsHint.joinToString(", ").ifBlank { "ninguna" }}.
            4. Empareja con el piloto más probable de la parrilla usando dorsal + carenado + casco/traje:
            $rosterDescription
            Si no coincide con claridad con ningún piloto, deja matchedRiderId vacío "".
        """.trimIndent()

        return try {
            val response = withTimeout(45_000L) {
                model.generateContent(
                    content {
                        image(bitmap)
                        text(prompt)
                    }
                )
            }
            val raw = response.text ?: throw IllegalStateException("Respuesta vacía del Comisario IA")
            val json = JSONObject(raw)
            val matchedId = json.optString("matchedRiderId", "").trim()
            val matchedName = activeRiders.firstOrNull { it.riderId == matchedId }?.riderName ?: ""
            Result.success(
                AiStewardVerdict(
                    id = "ai_$nowMs",
                    matchedRiderId = matchedId,
                    riderName = matchedName,
                    detectedDorsal = json.optString("detectedDorsal", "").trim(),
                    fairingSummary = json.optString("fairingSummary", "").trim(),
                    confidencePercent = json.optInt("confidencePercent", 0).coerceIn(0, 100),
                    isRealPass = json.optBoolean("isRealPass", true),
                    reasoningEs = json.optString("reasoningEs", "Revisión completada."),
                    reasoningEn = json.optString("reasoningEn", "Inspection completed."),
                    timestampMs = nowMs
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini AI steward timed out after 45s", e)
            Result.failure(IllegalStateException("Comisario IA agotó el tiempo de espera (45s)"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini AI steward failed", e)
            Result.failure(e)
        }
    }

    private fun decodeBitmap(base64: String): Bitmap? {
        return try {
            val clean = base64.substringAfter("base64,", base64)
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }
}
