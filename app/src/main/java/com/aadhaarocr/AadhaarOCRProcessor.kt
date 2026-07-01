package com.aadhaarocr

import androidx.annotation.VisibleForTesting
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

/**
 * Robust OCR Data Structure for Aadhaar cards.
 */
data class AadhaarData(
    val name: String = "",
    val gender: String = "",
    val dob: String = "",
    val uid: String = "",
    val address: String = "",
    val isValidAadhaar: Boolean = false,
    val confidenceScore: Float = 0.0f,
    val validationMessage: String = "",
    val rawOcrText: String = ""
)

/**
 * Consolidated Aadhaar OCR Processor.
 * Stripped of redundant legacy strategies to improve speed and reliability.
 */
class AadhaarOCRProcessor {
    
    // Upgraded to Devanagari Recognizer to support both English & Hindi perfectly!
    private val textRecognizer by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }
    
    companion object {
        private const val TAG = "AadhaarOCRProcessor"
        
        private val INDIAN_STATES = listOf(
            "andhra pradesh", "arunachal pradesh", "assam", "bihar", "chhattisgarh", 
            "goa", "gujarat", "haryana", "himachal pradesh", "jharkhand", "karnataka", 
            "kerala", "madhya pradesh", "maharashtra", "manipur", "meghalaya", 
            "mizoram", "nagaland", "odisha", "punjab", "rajasthan", "sikkim", 
            "tamil nadu", "telangana", "tripura", "uttar pradesh", "uttarakhand", "west bengal",
            "delhi", "chandigarh", "jammu", "kashmir", "ladakh", "puducherry"
        )
    }
    
    suspend fun processAadhaarCard(bitmap: Bitmap, isBackOfCard: Boolean = false): AadhaarData {
        Log.d(TAG, "Starting OCR...")
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = textRecognizer.process(inputImage).await()
            if (result.text.isBlank()) {
                return AadhaarData(validationMessage = "No text detected in image.")
            }

            val filteredLinesList = mutableListOf<String>()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val points = line.cornerPoints
                    if (points != null && points.size >= 2) {
                        val dx = points[1].x - points[0].x
                        val dy = points[1].y - points[0].y
                        val angle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble()))
                        // Vertical text is around 90 or -90 (270) degrees. Filter out if between 45-135 or 225-315 (-135 to -45)
                        val absAngle = Math.abs(angle)
                        if (absAngle in 45.0..135.0) {
                            Log.d(TAG, "Filtered out vertical text: '${line.text}' (Angle: $angle)")
                            continue
                        }
                    }
                    val t = line.text.trim()
                    if (t.isNotEmpty()) {
                        filteredLinesList.add(t)
                    }
                }
            }
            
            if (filteredLinesList.isEmpty()) {
                return AadhaarData(validationMessage = "No readable horizontal text detected.")
            }

            val lines = filteredLinesList
            val fullText = lines.joinToString("\n")
            
            // Core Extraction Pipeline
            var name = ""
            var dob = ""
            var gender = ""
            var uid = ""
            
            if (!isBackOfCard) {
                name = extractName(lines)
                gender = extractGender(fullText)
                dob = extractDOB(fullText)
                uid = extractUID(fullText)
            }
            
            val address = extractAddress(lines, name)

            val isValid = if (isBackOfCard) address.isNotEmpty() else (uid.isNotEmpty() && name.isNotEmpty())
            val score = calculateConfidence(name, dob, uid)

            val debugText = buildString {
                append("--- RESULT.TEXT ---\n")
                append(fullText)
                append("\n--- BLOCKS ---\n")
                for (block in result.textBlocks) {
                    append("BLOCK [${block.boundingBox}]: ${block.text.replace("\n", " | ")}\n")
                    for (line in block.lines) {
                        append("  LINE [${line.boundingBox}]: ${line.text}\n")
                    }
                }
            }

            AadhaarData(
                name = name,
                gender = gender,
                dob = dob,
                uid = uid,
                address = address,
                isValidAadhaar = isValid,
                confidenceScore = score,
                validationMessage = if (isValid) "Valid Aadhaar Card" else "Incomplete data detected",
                rawOcrText = debugText
            )
        } catch (e: Exception) {
            Log.e(TAG, "OCR Error", e)
            AadhaarData(validationMessage = "Error: ${e.message}")
        }
    }

    private fun isIrrelevantArtifact(line: String): Boolean {
        val lower = line.lowercase()
        val headerKeywords = listOf(
            "government", "india", "authority", "unique", "identification",
            "भारत", "सरकार", "आधार", "मेरा", "पहचान", "name", "gender", "dob", "enrollment", "enrolment"
        )
        // 1. Fast path exact match
        if (headerKeywords.any { lower.contains(it) }) return true

        // 2. Fuzzy match to catch OCR bleeding like 'un1qu3' or 'ldentification'
        val words = lower.split("\\s+".toRegex())
        for (word in words) {
            if (word.length < 5) continue // Only fuzzy match longer words to avoid false positives
            for (keyword in headerKeywords) {
                if (keyword.length < 5) continue
                // If words are similar in length, check levenshtein distance
                if (Math.abs(word.length - keyword.length) <= 2) {
                    val dist = levenshtein(word, keyword)
                    // Allow 1 mistake for 5-6 letter words, 2 for longer words
                    val maxAllowed = if (keyword.length <= 6) 1 else 2
                    if (dist <= maxAllowed) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLength = lhs.length
        val rhsLength = rhs.length

        var cost = IntArray(lhsLength + 1) { it }
        var newCost = IntArray(lhsLength + 1)

        for (i in 1..rhsLength) {
            newCost[0] = i
            for (j in 1..lhsLength) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = minOf(costInsert, costDelete, costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLength]
    }

    private fun extractName(lines: List<String>): String {
        // Strategy 1: Find "Name:" or "नाम:" label
        val nameLabelPattern = Regex("(?i)(?:name|नाम)[:\\s]+(.*)$")
        for (line in lines) {
            val match = nameLabelPattern.find(line)
            if (match != null) {
                val extracted = match.groupValues[1].trim()
                    .replace(Regex("[^A-Za-z\\s]"), "").trim()
                if (extracted.split(" ").size >= 2) return extracted.uppercase()
            }
        }

        // Strategy 2: Heuristic - Find first line with 2+ words that isn't a header
        for (line in lines) {
            if (isIrrelevantArtifact(line)) continue
            if (line.contains(Regex("\\d"))) continue // Names don't have numbers
            
            val words = line.trim().split("\\s+".toRegex())
            if (words.size in 2..4 && line.length in 8..40) {
                // Avoid picking up Hindi text as the English Name if possible
                if (line.any { it in '\u0900'..'\u097F' }) continue 
                return line.uppercase()
            }
        }
        return ""
    }

    private fun extractGender(text: String): String {
        return when {
            text.contains("Female", ignoreCase = true) || text.contains("महिला", ignoreCase = true) -> "FEMALE"
            text.contains("Male", ignoreCase = true) || text.contains("पुरुष", ignoreCase = true) -> "MALE"
            else -> ""
        }
    }

    private fun extractDOB(text: String): String {
        val cleanText = text.replace("\n", " ")
        
        // 1. Look for explicit DOB label nearby
        val dobPattern = Regex("(?i)(?:dob|date\\s+of\\s+birth|जन्म|तिथि|तारीख|तारीख़)[^\\d]*(\\d{2}[/-]\\d{2}[/-]\\d{4})")
        val match = dobPattern.find(cleanText)
        if (match != null) {
            return match.groupValues[1].replace("/", "-")
        }

        // 2. Fallback to any DD/MM/YYYY date
        val datePattern = Regex("(\\d{2}[/-]\\d{2}[/-]\\d{4})")
        val allDates = datePattern.findAll(cleanText).map { it.groupValues[1].replace("/", "-") }.toList()
        
        if (allDates.isNotEmpty()) {
            // The vertically printed generation date is always >= DOB.
            // If multiple dates exist, the earliest one is the DOB.
            if (allDates.size > 1) {
                return allDates.minByOrNull { dateStr ->
                    try {
                        SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).parse(dateStr)?.time ?: Long.MAX_VALUE
                    } catch (e: Exception) { Long.MAX_VALUE }
                } ?: allDates[0]
            }
            return allDates[0]
        }
        
        // 3. Fallback: Look for Year of Birth (YOB)
        val yearMatch = Regex("(?i)(?:year of birth|yob|जन्म वर्ष)[:\\s]*(\\d{4})").find(cleanText)
        if (yearMatch != null) return "01-01-${yearMatch.groupValues[1]}"
        
        return ""
    }

    @VisibleForTesting
    internal fun extractUID(text: String): String {
        // Clean birth years first to avoid confusion
        val dobMatch = Regex("\\d{2}[/-]\\d{2}[/-](\\d{4})").find(text)
        val birthYear = dobMatch?.groupValues?.get(1) ?: ""

        // Find 12-character patterns allowing common OCR substitutions (O->0, l->1, S->5, etc.)
        val flexiblePattern = Regex("(?<![A-Za-z0-9])([0-9OolISB]{4})[ \\t]+([0-9OolISB]{4})[ \\t]+([0-9OolISB]{4})(?![A-Za-z0-9])")
        val matches = flexiblePattern.findAll(text)
        
        val candidates = mutableListOf<String>()
        for (match in matches) {
            var digits = match.value.replace(Regex("\\s"), "")
            digits = digits.replace(Regex("[Oo]"), "0")
                .replace(Regex("[lIi]"), "1")
                .replace(Regex("[Ss]"), "5")
                .replace("B", "8")
                
            if (digits.length == 12 && digits.all { it.isDigit() }) {
                // Heuristic: UIDs don't start with common birth years (19xx, 20xx)
                if (digits.startsWith("19") || digits.startsWith("20")) continue
                // Also ignore if it IS the birth year (unlikely for 12 digits, but safety first)
                if (birthYear.isNotEmpty() && digits.contains(birthYear)) continue
                candidates.add(digits)
            }
        }

        // If multiple candidates, pick the one furthest from "DOB" or "Date of Birth"
        if (candidates.isNotEmpty()) {
            val dobPos = text.indexOf("Birth", ignoreCase = true)
            if (dobPos != -1) {
                return formatUID(candidates.maxByOrNull { Math.abs(text.indexOf(it) - dobPos) }!!)
            }
            return formatUID(candidates[0])
        }

        // Safe fallback: Find any consecutive string of exactly 12 digits (allowing only spaces/dashes between them)
        val exact12Pattern = Regex("(?<!\\d)(\\d[ \\t-]*){12}(?!\\d)")
        val exactMatches = exact12Pattern.findAll(text)
        for (match in exactMatches) {
            val digits = match.value.replace(Regex("[^0-9]"), "")
            if (digits.length == 12) {
                if (digits.startsWith("19") || digits.startsWith("20")) continue
                if (birthYear.isNotEmpty() && digits.contains(birthYear)) continue
                return formatUID(digits)
            }
        }

        return ""
    }

    @VisibleForTesting
    internal fun extractAddress(lines: List<String>, name: String): String {
        val addressStartPattern = Regex("(?i)^(?:address|पता)[:\\s]+(.*)$")
        val englishAddressStartPattern = Regex("(?i)^address[:\\s]+(.*)$")
        
        var addressResult = ""
        var foundLabel = false
        
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!foundLabel) {
                val match = addressStartPattern.find(line)
                if (match != null) {
                    foundLabel = true
                    addressResult = match.groupValues[1].trim()
                }
            } else {
                // Check if this line is the English Address label overriding a previously found Hindi one
                val engMatch = englishAddressStartPattern.find(line)
                if (engMatch != null) {
                    addressResult = engMatch.groupValues[1].trim()
                    continue
                }
                
                // Stop at Name, Gender, or UID patterns
                if (line.contains(Regex("(?i)(?:name|gender|dob|uid|आधार|नाम|लिंग)")) || 
                    line.contains(Regex("\\d{4}[ \\t]+\\d{4}[ \\t]+\\d{4}"))) break
                
                if (line.isNotEmpty()) {
                    if (addressResult.isNotEmpty()) addressResult += ", "
                    addressResult += line
                }
                if (addressResult.split(",").size > 6) break
            }
        }
        
        if (addressResult.isNotEmpty()) return addressResult
        
        // Strategy 2: Fallback to finding State + PIN and walking backwards
        for (i in lines.indices) {
            val lower = lines[i].lowercase()
                if (INDIAN_STATES.any { lower.contains(it) }) {
                val pinMatch = Regex("\\b\\d{6}\\b").find(lines[i])
                if (pinMatch != null) {
                    val addressLines = mutableListOf<String>()
                    // Walk backwards from the State/PIN line
                    for (j in i downTo (i - 15).coerceAtLeast(0)) {
                        val currentLine = lines[j].trim()
                        // Stop if we hit the person's name, or a routing label like "To"
                        if (name.isNotEmpty() && currentLine.equals(name, ignoreCase = true)) break
                        if (currentLine.equals("To", ignoreCase = true)) break
                        // Stop if we hit a UID
                        if (currentLine.contains(Regex("\\d{4}[ \\t]+\\d{4}[ \\t]+\\d{4}"))) break
                        // Stop if we hit an address label
                        if (currentLine.contains(Regex("(?i)^(?:address|पता)"))) break
                        
                        // Filter out lines containing Devanagari since we are anchoring on an English state name
                        if (currentLine.any { it in '\u0900'..'\u097F' }) continue
                        // Filter out noisy 1-2 character artifacts
                        if (currentLine.length <= 2) continue
                        
                        // Avoid adding the exact same line twice (OCR overlap)
                        if (addressLines.isEmpty() || !addressLines.first().equals(currentLine, ignoreCase = true)) {
                            addressLines.add(0, currentLine)
                        }
                    }
                    
                    // Cleanup: Remove adjacent duplicate comma-separated components only
                    val rawParts = addressLines.joinToString(", ").split(Regex(",\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
                    val deduplicatedParts = mutableListOf<String>()
                    for (part in rawParts) {
                        if (deduplicatedParts.isEmpty() || !deduplicatedParts.last().equals(part, ignoreCase = true)) {
                            deduplicatedParts.add(part)
                        }
                    }
                    
                    // Further cleanup: remove duplicate adjacent words like "Mumbai Mumbai"
                    val finalAddress = deduplicatedParts.joinToString(", ").replace(Regex("\\b(\\w+)\\s+\\1\\b", RegexOption.IGNORE_CASE), "$1")
                    
                    return finalAddress
                }
            }
        }
        
        return ""
    }

    private fun calculateConfidence(name: String, dob: String, uid: String): Float {
        var score = 0f
        if (name.isNotEmpty()) score += 30f
        if (dob.isNotEmpty()) score += 20f
        if (uid.isNotEmpty()) score += 50f
        return score
    }

    private fun formatUID(digits: String): String {
        return if (digits.length == 12) {
            "${digits.substring(0, 4)} ${digits.substring(4, 8)} ${digits.substring(8, 12)}"
        } else digits
    }

    fun cleanup() {
        textRecognizer.close()
    }
}