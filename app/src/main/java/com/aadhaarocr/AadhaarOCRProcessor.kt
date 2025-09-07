package com.aadhaarocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

data class AadhaarData(
    val name: String = "",
    val gender: String = "",
    val dob: String = "",
    val uid: String = "",
    val address: String = "",
    val isValidAadhaar: Boolean = false,
    val confidenceScore: Float = 0.0f,
    val validationMessage: String = "",
    val rawOcrText: String = "",  // Add raw OCR text for debugging
    val languageTaggedText: String = ""
)

data class LanguageTaggedLine(
    val text: String,
    val language: Language,
    val confidence: Float
)

enum class Language {
    HINDI, ENGLISH, MIXED, UNKNOWN
}

class AadhaarOCRProcessor {
    
    private val latinTextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagariTextRecognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    
    companion object {
        private const val TAG = "AadhaarOCRProcessor"
        
        // Complete list of Indian states and union territories for address detection
        private val INDIAN_STATES = listOf(
            // States
            "andhra pradesh", "arunachal pradesh", "assam", "bihar", "chhattisgarh", 
            "goa", "gujarat", "haryana", "himachal pradesh", "jharkhand", "karnataka", 
            "kerala", "madhya pradesh", "maharashtra", "manipur", "meghalaya", 
            "mizoram", "nagaland", "odisha", "punjab", "rajasthan", "sikkim", 
            "tamil nadu", "telangana", "tripura", "uttar pradesh", "uttarakhand", "west bengal",
            
            // Union Territories
            "andaman and nicobar islands", "chandigarh", "dadra and nagar haveli and daman and diu",
            "delhi", "jammu and kashmir", "ladakh", "lakshadweep", "puducherry",
            
            // Common variations and abbreviations
            "up", "mp", "hp", "ap", "tn", "wb", "ncr"
        )
        
        // Header keywords to skip when finding names
        private val HEADER_KEYWORDS = listOf(
            "uidai", "government", "unique", "identification", "authority", "india", "भारत"
        )
        
        // Aadhaar card validation keywords
        private val AADHAAR_KEYWORDS = listOf(
            "uidai", "unique identification authority", "government of india",
            "govt of india", "aadhaar", "aadhar", "भारत सरकार", "यूआईडीएआई"
        )
        
        // Suspicious keywords that indicate non-Aadhaar documents
        private val NON_AADHAAR_KEYWORDS = listOf(
            "passport", "driving license", "pan card", "voter id", "voter card",
            "birth certificate", "marksheet", "diploma", "degree", "invoice",
            "receipt", "bill", "statement", "bank", "atm", "debit", "credit"
        )
    }
    
    suspend fun processAadhaarCard(bitmap: Bitmap): AadhaarData {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            
            // Primary processing with Latin recognizer only
            val latinResult = latinTextRecognizer.process(inputImage).await()
            val latinText = latinResult.text
            
            // Secondary processing with Devanagari recognizer (for display only)
            val devanagariResult = devanagariTextRecognizer.process(inputImage).await()
            val rawDevanagariText = devanagariResult.text
            val devanagariText = filterDevanagariText(rawDevanagariText)
            
            // Use Latin text directly - no complex filtering
            val primaryText = latinText
            
            // Combine for debugging and language analysis
            val combinedText = combineRecognitionResults(latinText, devanagariText)
            
            // Simple language tagging for debugging
            val languageTaggedText = detectLanguageAndTag(primaryText)
            Log.d(TAG, "Language Tagged Text: $languageTaggedText")
            
            // Use Latin text directly for all processing
            val processedText = primaryText
            
            Log.d(TAG, "Latin text (PRIMARY): $latinText")
            Log.d(TAG, "Raw Devanagari text: $rawDevanagariText")
            Log.d(TAG, "Filtered Devanagari text: $devanagariText")
            Log.d(TAG, "Combined text (DEBUG): $combinedText")
            Log.d(TAG, "Processed text for extraction: $processedText")
            
            // Validate using Latin text
            val validation = validateAadhaarCard(latinText)
            
            if (!validation.isValid) {
                return AadhaarData(
                    isValidAadhaar = false,
                    confidenceScore = validation.score,
                    validationMessage = validation.message,
                    rawOcrText = "LATIN:\n$latinText\n\nDEVANAGARI:\n$devanagariText\n\nCOMBINED:\n$combinedText"
                )
            }
            
            // If valid, proceed with data extraction using primary text
            val parsedData = parseAadhaarData(processedText)
            parsedData.copy(
                isValidAadhaar = true,
                confidenceScore = validation.score,
                validationMessage = "Valid Aadhaar card detected",
                rawOcrText = "LATIN (PRIMARY):\n$latinText\n\nRAW DEVANAGARI:\n$rawDevanagariText\n\nFILTERED DEVANAGARI (HINDI ONLY):\n$devanagariText\n\nCOMBINED (DEBUG):\n$combinedText\n\nPROCESSED FOR EXTRACTION:\n$processedText",
                languageTaggedText = languageTaggedText
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing Aadhaar card", e)
            AadhaarData(
                isValidAadhaar = false,
                confidenceScore = 0.0f,
                validationMessage = "Error processing image: ${e.message}",
                rawOcrText = ""
            )
        }
    }
    
    private fun filterEnglishText(text: String): String {
        return text.split('\n').mapNotNull { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) return@mapNotNull null
            
            // Filter out lines that are primarily Hindi/Devanagari
            val filteredLine = filterHindiFromLine(trimmedLine)
            if (filteredLine.isNotBlank()) filteredLine else null
        }.joinToString("\n")
    }
    
    private fun filterHindiFromLine(line: String): String {
        val trimmed = line.trim()
        
        // Skip lines that are likely OCR garbage from Hindi text
        if (isOcrGarbageLine(trimmed)) {
            return ""
        }
        
        // Clean up the line
        val cleaned = trimmed.replace(Regex("\\s+"), " ").trim()
        
        // Keep lines that have meaningful English content
        return if (isMeaningfulEnglishContent(cleaned)) {
            cleaned
        } else {
            ""
        }
    }
    
    private fun isOcrGarbageLine(line: String): Boolean {
        val lineLower = line.lowercase()
        
        // Don't filter gender markers or single important letters
        if (lineLower in listOf("m", "f", "male", "female")) {
            return false
        }
        
        // Patterns that indicate OCR garbage from Hindi text
        val garbagePatterns = listOf(
            // Repetitive characters (common OCR error) - but allow 2 repetitions
            Regex("(.)\\1{3,}"),
            // Lines with excessive special characters
            Regex(".*[^a-zA-Z0-9\\s]{4,}.*"),
            // Common OCR misreads of Hindi text - but be more conservative
            Regex(".*(fft|ffr|rff|tft|rtr|ttt|rrr|fff).*"),
            // Very fragmented words
            Regex(".*\\b[a-z]{1}\\s+[a-z]{1}\\s+[a-z]{1}.*"),
        )
        
        // Check against garbage patterns
        if (garbagePatterns.any { it.matches(lineLower) }) {
            return true
        }
        
        // Statistical analysis: if line has too many uncommon letter combinations
        val uncommonBigrams = listOf("qx", "qz", "xq", "xz", "zx", "zq", "jx", "jz", "wx", "wz")
        val bigramCount = uncommonBigrams.count { lineLower.contains(it) }
        if (bigramCount >= 2) {
            return true
        }
        
        return false
    }
    
    private fun isMeaningfulEnglishContent(line: String): Boolean {
        // Always keep single important letters like M/F for gender
        if (line.length == 1 && line.uppercase() in listOf("M", "F")) return true
        
        if (line.length < 2) return false
        
        // Always keep lines with numbers (UIDs, dates, addresses)
        if (line.contains(Regex("\\d"))) return true
        
        // Check for known English words/patterns
        val commonWords = listOf(
            "government", "india", "of", "authority", "unique", "identification",
            "male", "female", "gender", "date", "birth", "year", "address",
            "name", "aadhaar", "aadhar", "uid", "dob", "yob", "pin", "m", "f",
            "road", "street", "lane", "east", "west", "north", "south",
            "maharashtra", "delhi", "mumbai", "bangalore", "chennai", "kolkata",
            "gujarat", "rajasthan", "punjab", "haryana", "kerala", "karnataka"
        )
        
        val lineLower = line.lowercase()
        if (commonWords.any { lineLower.contains(it) }) {
            return true
        }
        
        // Keep lines that look like proper names (2-4 words, reasonable length)
        val words = line.split("\\s+").filter { it.length > 1 }
        if (words.size in 2..4 && words.all { it.length <= 15 && it.matches(Regex("[A-Za-z]+")) }) {
            return true
        }
        
        // Keep lines with reasonable vowel-consonant ratio (English-like)
        val vowels = line.count { it.lowercase() in "aeiou" }
        val consonants = line.count { it.isLetter() && it.lowercase() !in "aeiou" }
        val vowelRatio = if (vowels + consonants > 0) vowels.toFloat() / (vowels + consonants) else 0f
        
        // Be more lenient with vowel ratios and accept very short meaningful words
        return (vowelRatio in 0.15f..0.75f && line.length >= 2) || line.length >= 6
    }
    
    private fun parseAadhaarData(text: String): AadhaarData {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        
        return AadhaarData(
            name = extractName(lines),
            gender = extractGender(text),
            dob = extractDateOfBirth(text),
            uid = extractUID(text),
            address = extractAddress(text, lines)
        )
    }
    
    private fun extractName(lines: List<String>): String {
        // Strategy 1: Look for name after "Government of India" header
        val nameAfterGovHeader = findNameAfterGovernmentHeader(lines)
        if (nameAfterGovHeader.isNotEmpty()) {
            Log.d(TAG, "Found name after Government header: $nameAfterGovHeader")
            return nameAfterGovHeader
        }
        
        // Strategy 2: Look for explicit name labels
        val nameWithLabel = findNameWithLabel(lines)
        if (nameWithLabel.isNotEmpty()) {
            Log.d(TAG, "Found name with label: $nameWithLabel")
            return nameWithLabel
        }
        
        // Strategy 3: Pattern-based name detection
        val nameByPattern = findNameByPattern(lines)
        if (nameByPattern.isNotEmpty()) {
            Log.d(TAG, "Found name by pattern: $nameByPattern")
            return nameByPattern
        }
        
        Log.d(TAG, "No name found using any strategy")
        return ""
    }
    
    private fun findNameAfterGovernmentHeader(lines: List<String>): String {
        for (i in lines.indices) {
            val line = lines[i].lowercase()
            
            // Look for "Government of India" or related headers
            if (line.contains("government of india") || 
                line.contains("govt of india") ||
                line.contains("भारत सरकार") ||
                line.contains("uidai")) {
                
                // Collect all potential name candidates from next few lines
                val nameCandidates = mutableListOf<String>()
                
                // Look for name in next few lines (expanded range)
                for (j in (i + 1)..(i + 6).coerceAtMost(lines.size - 1)) {
                    val candidateLine = lines[j].trim()
                    
                    // Skip empty lines
                    if (candidateLine.isEmpty()) continue
                    
                    // Skip lines that are clearly not names
                    if (isLineNotName(candidateLine)) continue
                    
                    // Extract English text (Latin alphabet only)
                    val englishText = candidateLine.replace(Regex("[^A-Za-z\\s]"), " ")
                        .replace(Regex("\\s+"), " ").trim()
                    
                    if (englishText.isNotBlank() && englishText.length >= 4) {
                        nameCandidates.add(englishText)
                    }
                }
                
                // Score and select the best name candidate
                val bestName = selectBestNameCandidate(nameCandidates)
                if (bestName.isNotEmpty()) {
                    return bestName
                }
            }
        }
        return ""
    }
    
    private fun selectBestNameCandidate(candidates: List<String>): String {
        if (candidates.isEmpty()) return ""
        
        // Score each candidate
        val scoredCandidates = candidates.map { candidate ->
            val score = scoreNameCandidate(candidate)
            Pair(candidate, score)
        }.sortedByDescending { it.second }
        
        Log.d(TAG, "Name candidates with scores: ${scoredCandidates.map { "${it.first} (${it.second})" }}")
        
        // Return the highest scoring candidate if it meets minimum criteria
        val bestCandidate = scoredCandidates.firstOrNull()
        return if (bestCandidate != null && bestCandidate.second >= 50.0f) {
            bestCandidate.first
        } else {
            ""
        }
    }
    
    private fun scoreNameCandidate(candidate: String): Float {
        var score = 0.0f
        val words = candidate.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val candidateLower = candidate.lowercase()
        
        // Basic validation
        if (candidate.isBlank() || words.isEmpty()) return 0.0f
        
        // Word count scoring (prefer 2-4 words)
        score += when (words.size) {
            2 -> 40.0f  // First name + Last name
            3 -> 50.0f  // First + Middle + Last (ideal for Indian names)
            4 -> 45.0f  // Still good
            1 -> 10.0f  // Unlikely but possible
            else -> 20.0f  // Too many words, might be noisy
        }
        
        // Length scoring (prefer names of reasonable length)
        score += when (candidate.length) {
            in 8..30 -> 30.0f   // Good length for full names
            in 6..40 -> 20.0f   // Acceptable
            in 4..50 -> 10.0f   // Minimum acceptable
            else -> 0.0f        // Too short or too long
        }
        
        // Penalize suspicious patterns
        if (candidateLower.contains("authority") || 
            candidateLower.contains("government") ||
            candidateLower.contains("unique") ||
            candidateLower.contains("identification") ||
            candidateLower.contains("card") ||
            candidateLower.contains("umited") ||  // Common OCR error
            candidateLower.contains("limited")) {
            score -= 30.0f
        }
        
        // Bonus for typical Indian name patterns
        if (words.any { word -> 
            listOf("kumar", "singh", "sharma", "patel", "mehta", "gupta", "verma", "shah", "jain").any { 
                word.lowercase().contains(it) 
            }
        }) {
            score += 15.0f
        }
        
        // Each word should be reasonable length
        if (words.all { it.length >= 2 && it.length <= 15 }) {
            score += 10.0f
        } else {
            score -= 10.0f
        }
        
        // Penalize single character words (likely OCR errors)
        if (words.any { it.length == 1 }) {
            score -= 20.0f
        }
        
        return score.coerceAtLeast(0.0f)
    }
    
    private fun findNameWithLabel(lines: List<String>): String {
        for (line in lines) {
            val lineLower = line.lowercase()
            
            // Look for explicit name labels
            if (lineLower.contains("name:") || lineLower.contains("नाम:")) {
                val nameMatch = Regex("(?:name|नाम)\\s*:?\\s*([a-z\\s]+)", RegexOption.IGNORE_CASE)
                    .find(line)
                nameMatch?.let { match ->
                    val extractedName = match.groupValues[1].trim()
                    if (isValidName(extractedName)) {
                        return extractedName
                    }
                }
            }
        }
        return ""
    }
    
    private fun findNameByPattern(lines: List<String>): String {
        for (line in lines) {
            // Skip header/official text lines
            if (HEADER_KEYWORDS.any { keyword -> line.lowercase().contains(keyword) }) {
                continue
            }
            
            if (isLineNotName(line)) continue
            
            // Extract and clean potential name
            val cleanedName = line.replace(Regex("[^A-Za-z ]"), " ")
                .replace(Regex("\\s+"), " ").trim()
            
            if (isValidName(cleanedName)) {
                return cleanedName
            }
        }
        return ""
    }
    
    private fun isLineNotName(line: String): Boolean {
        val lineLower = line.lowercase()
        
        // Skip lines with numbers (IDs, dates, etc.)
        if (line.contains(Regex("\\d{4}\\s?\\d{4}\\s?\\d{4}")) || 
            line.contains(Regex("\\d{2}[/-]\\d{2}[/-]\\d{4}")) ||
            line.contains(Regex("\\b\\d{6}\\b"))) { // Pin codes
            return true
        }
        
        // Skip gender-only lines
        if (lineLower.matches(Regex("^(male|female|m|f|पुरुष|महिला)\\s*$"))) {
            return true
        }
        
        // Skip address indicators
        if (INDIAN_STATES.any { lineLower.contains(it) }) {
            return true
        }
        
        // Skip very short lines
        if (line.trim().length < 3) {
            return true
        }
        
        return false
    }
    
    private fun isValidName(name: String): Boolean {
        if (name.isBlank()) return false
        
        // Should have at least 2 words (first name + last name)
        val words = name.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.size < 2) return false
        
        // Should be reasonable length
        if (name.length < 4 || name.length > 50) return false
        
        // Should not contain suspicious keywords
        val nameLower = name.lowercase()
        if (listOf("authority", "government", "unique", "identification", "card").any { 
            nameLower.contains(it) 
        }) return false
        
        // Each word should be at least 2 characters
        if (words.any { it.length < 2 }) return false
        
        return true
    }
    
    private fun extractGender(text: String): String {
        val lines = text.split("\n").map { it.trim() }
        
        // Strategy 1: Look for explicit gender labels
        val genderPatterns = listOf(
            Regex("Gender[:\\s]*([MF])", RegexOption.IGNORE_CASE),
            Regex("Sex[:\\s]*([MF])", RegexOption.IGNORE_CASE),
            Regex("लिंग[:\\s]*([MF])", RegexOption.IGNORE_CASE), // Hindi for gender
            Regex("\\b(M)ale\\b", RegexOption.IGNORE_CASE),  // Match "Male" specifically
            Regex("\\b(F)emale\\b", RegexOption.IGNORE_CASE) // Match "Female" specifically
        )
        
        for (line in lines) {
            for (pattern in genderPatterns) {
                val match = pattern.find(line)
                if (match != null) {
                    val gender = match.groupValues[1].uppercase()
                    Log.d(TAG, "Found gender with pattern: '$gender' in line: '$line'")
                    return gender
                }
            }
        }
        
        // Strategy 2: Look for standalone M/F near other personal info
        for (i in lines.indices) {
            val line = lines[i].lowercase()
            if (line.matches(Regex("^[\\s]*[mf][\\s]*$"))) {
                // Check if this line is near date/name info (likely gender)
                val nearPersonalInfo = (i > 0 && lines[i-1].contains(Regex("\\d{2}[/-]\\d{2}[/-]\\d{4}"))) ||
                                     (i < lines.size - 1 && lines[i+1].contains(Regex("\\d{2}[/-]\\d{2}[/-]\\d{4}")))
                
                if (nearPersonalInfo) {
                    val gender = line.trim().uppercase()
                    Log.d(TAG, "Found standalone gender: '$gender' near personal info")
                    return gender
                }
            }
        }
        
        // Strategy 3: Look for full words (check "female" first since it contains "male")
        for (line in lines) {
            val lineLower = line.lowercase()
            when {
                lineLower.contains("female") -> {
                    Log.d(TAG, "Found 'female' in line: '$line'")
                    return "F"
                }
                lineLower.contains("male") -> {
                    Log.d(TAG, "Found 'male' in line: '$line'")
                    return "M"
                }
            }
        }
        
        Log.d(TAG, "No gender found in text")
        return ""
    }
    
    private fun extractDateOfBirth(text: String): String {
        // Strategy 1: Look for explicit DOB labels with dates
        val dobWithLabel = findDateWithLabel(text)
        if (dobWithLabel.isNotEmpty()) {
            Log.d(TAG, "Found DOB with label: $dobWithLabel")
            return dobWithLabel
        }
        
        // Strategy 2: Look for DD/MM/YYYY or DD-MM-YYYY patterns
        val dobMatch = Regex("(\\d{2}[/-]\\d{2}[/-]\\d{4})").find(text)
        if (dobMatch != null) {
            val dobStr = dobMatch.groupValues[1]
            Log.d(TAG, "Found DOB pattern: $dobStr")
            return try {
                val inputFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
                val outputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val date = inputFormat.parse(dobStr.replace("/", "-"))
                date?.let { outputFormat.format(it) } ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing date: $dobStr", e)
                ""
            }
        }
        
        // Strategy 3: Look for YOB (Year of Birth) in proper context
        val yob = findYearOfBirth(text)
        if (yob.isNotEmpty()) {
            Log.d(TAG, "Found YOB: $yob")
            return "$yob-01-01"
        }
        
        Log.d(TAG, "No date found")
        return ""
    }
    
    private fun findDateWithLabel(text: String): String {
        val lines = text.split("\n")
        val dateLabels = listOf("dob", "date of birth", "birth", "born")
        
        for (line in lines) {
            val lineLower = line.lowercase()
            for (label in dateLabels) {
                if (lineLower.contains(label)) {
                    // Look for date in this line
                    val dateMatch = Regex("(\\d{2}[/-]\\d{2}[/-]\\d{4})").find(line)
                    if (dateMatch != null) {
                        val dobStr = dateMatch.groupValues[1]
                        return try {
                            val inputFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
                            val outputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            val date = inputFormat.parse(dobStr.replace("/", "-"))
                            date?.let { outputFormat.format(it) } ?: ""
                        } catch (e: Exception) {
                            ""
                        }
                    }
                }
            }
        }
        return ""
    }
    
    private fun findYearOfBirth(text: String): String {
        val lines = text.split("\n")
        val yobLabels = listOf("yob", "year of birth", "birth year")
        
        // First look for explicit YOB labels
        for (line in lines) {
            val lineLower = line.lowercase()
            for (label in yobLabels) {
                if (lineLower.contains(label)) {
                    val yearMatch = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(line)
                    if (yearMatch != null) {
                        val year = yearMatch.groupValues[1].toInt()
                        // Validate reasonable birth year (1930-2010)
                        if (year in 1930..2010) {
                            return year.toString()
                        }
                    }
                }
            }
        }
        
        // If no labeled YOB, look for standalone 4-digit years in reasonable range
        // But be more conservative - avoid random years
        val yearMatches = Regex("\\b(19[5-9]\\d|200\\d|201[0-5])\\b").findAll(text).toList()
        
        // Only return if we find exactly one reasonable birth year
        if (yearMatches.size == 1) {
            val year = yearMatches[0].groupValues[1].toInt()
            if (year in 1950..2015) {
                return year.toString()
            }
        }
        
        return ""
    }
    
    private fun extractUID(text: String): String {
        // Strategy 1: Look for UID after explicit labels
        val labeledUID = extractLabeledUID(text)
        if (labeledUID.isNotEmpty()) {
            Log.d(TAG, "Found UID with label: $labeledUID")
            return labeledUID
        }
        
        // Strategy 2: Look for UID at the bottom of the card
        val bottomUID = extractBottomUID(text)
        if (bottomUID.isNotEmpty()) {
            Log.d(TAG, "Found UID at bottom: $bottomUID")
            return bottomUID
        }
        
        // Strategy 3: Fallback to first 12-digit pattern found
        val uidMatch = Regex("\\d{4}\\s?\\d{4}\\s?\\d{4}").find(text)
        val fallbackUID = uidMatch?.groupValues?.get(0)?.replace(" ", "") ?: ""
        Log.d(TAG, "Fallback UID: $fallbackUID")
        return fallbackUID
    }
    
    private fun extractLabeledUID(text: String): String {
        // Common Aadhaar number labels (English and Hindi variations)
        val uidLabels = listOf(
            "your aadhaar no",
            "aadhaar no",
            "aadhar no", 
            "aadhaar number",
            "aadhar number",
            "uid no",
            "unique id",
            "आधार संख्या",
            "आधार नंबर"
        )
        
        val lines = text.split("\n")
        
        for (line in lines) {
            val lineLower = line.lowercase()
            
            // Check if line contains any UID label
            for (label in uidLabels) {
                if (lineLower.contains(label)) {
                    // Look for 12-digit pattern in this line or next few lines
                    val uidInSameLine = Regex("\\d{4}\\s?\\d{4}\\s?\\d{4}").find(line)
                    if (uidInSameLine != null) {
                        return formatUID(uidInSameLine.groupValues[0])
                    }
                    
                    // Check next 2 lines if not found in same line
                    val lineIndex = lines.indexOf(line)
                    for (i in (lineIndex + 1)..(lineIndex + 2).coerceAtMost(lines.size - 1)) {
                        val uidInNextLine = Regex("\\d{4}\\s?\\d{4}\\s?\\d{4}").find(lines[i])
                        if (uidInNextLine != null) {
                            return formatUID(uidInNextLine.groupValues[0])
                        }
                    }
                }
            }
        }
        return ""
    }
    
    private fun extractBottomUID(text: String): String {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        
        // Look in last 3 lines for UID pattern
        val lastLines = lines.takeLast(3)
        
        for (line in lastLines.reversed()) {  // Check from bottom up
            val uidMatch = Regex("\\d{4}\\s?\\d{4}\\s?\\d{4}").find(line)
            if (uidMatch != null) {
                // Verify this line doesn't contain non-UID indicators
                val lineLower = line.lowercase()
                if (!lineLower.contains("date") && 
                    !lineLower.contains("gender") && 
                    !lineLower.contains("phone") && 
                    !lineLower.contains("mobile")) {
                    return formatUID(uidMatch.groupValues[0])
                }
            }
        }
        return ""
    }
    
    private fun formatUID(rawUID: String): String {
        // Ensure proper 4-4-4 format
        val digitsOnly = rawUID.replace(Regex("\\s"), "")
        return if (digitsOnly.length == 12) {
            "${digitsOnly.substring(0, 4)} ${digitsOnly.substring(4, 8)} ${digitsOnly.substring(8, 12)}"
        } else {
            rawUID
        }
    }
    
    private fun extractAddress(text: String, lines: List<String>): String {
        // Strategy 1: Find address using state + pin code pattern
        val stateBasedAddress = extractAddressByStateAndPin(lines)
        if (stateBasedAddress.isNotEmpty()) {
            Log.d(TAG, "Found address by state+pin: $stateBasedAddress")
            return stateBasedAddress
        }
        
        // Strategy 2: Extract address section between gender and UID
        val sectionBasedAddress = extractAddressBySection(text)
        if (sectionBasedAddress.isNotEmpty()) {
            Log.d(TAG, "Found address by section: $sectionBasedAddress")
            return sectionBasedAddress
        }
        
        Log.d(TAG, "No address found")
        return ""
    }
    
    private fun extractAddressByStateAndPin(lines: List<String>): String {
        // Find the line with state name and pin code
        var stateLineIndex = -1
        var matchedState = ""
        var pinCode = ""
        
        for (i in lines.indices) {
            val line = lines[i].lowercase()
            for (state in INDIAN_STATES) {
                if (line.contains(state)) {
                    val pinMatch = findPinCodeNearLine(lines, i)
                    if (pinMatch.isNotEmpty()) {
                        stateLineIndex = i
                        matchedState = state.replaceFirstChar { it.uppercase() }
                        pinCode = pinMatch
                        break
                    }
                }
            }
            if (stateLineIndex != -1) break
        }
        
        if (stateLineIndex == -1) return ""
        
        // Find the address block: street address to state+pin
        val addressBlock = extractAddressBlock(lines, stateLineIndex)
        
        // Format the address properly
        return formatCleanAddress(addressBlock, matchedState, pinCode)
    }
    
    private fun extractAddressBlock(lines: List<String>, stateLineIndex: Int): List<String> {
        val addressLines = mutableListOf<String>()
        var startIndex = -1
        
        // Find the street address (first line with number + road/street keywords)
        for (i in 0 until stateLineIndex) {
            val line = lines[i].trim()
            if (isStreetAddress(line)) {
                startIndex = i
                break
            }
        }
        
        if (startIndex == -1) {
            // If no street address found, be more flexible and look for any address-like content
            startIndex = findAddressStart(lines, stateLineIndex)
        }
        
        // Collect clean address lines from street to state
        for (i in startIndex until stateLineIndex) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            
            // Skip obvious non-address content
            if (isGarbageLine(line)) continue
            
            val cleanLine = cleanAddressLine(line)
            if (cleanLine.isNotEmpty() && cleanLine.length >= 3) {
                addressLines.add(cleanLine)
            }
        }
        
        return addressLines
    }
    
    private fun findAddressStart(lines: List<String>, stateLineIndex: Int): Int {
        // Look for first line that could be part of an address
        // (contains some alphabetic content and isn't obviously non-address)
        for (i in maxOf(0, stateLineIndex - 8) until stateLineIndex) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            
            // Skip obvious non-address content
            if (isGarbageLine(line)) continue
            
            // If it has some reasonable content, use it as start
            if (line.length >= 5 && line.contains(Regex("[a-zA-Z]"))) {
                Log.d(TAG, "Found address start at line $i: $line")
                return i
            }
        }
        
        // Fallback to reasonable position
        return maxOf(0, stateLineIndex - 6)
    }
    
    private fun isStreetAddress(line: String): Boolean {
        val lineLower = line.lowercase()
        return lineLower.contains(Regex("\\d+")) && 
               (lineLower.contains("road") || lineLower.contains("street") || 
                lineLower.contains("lane") || lineLower.contains("plot") || 
                lineLower.contains("house") || lineLower.contains("building") ||
                lineLower.contains("apartment") || lineLower.contains("flat"))
    }
    
    private fun isGarbageLine(line: String): Boolean {
        val lineLower = line.lowercase()
        
        // Skip government headers
        if (HEADER_KEYWORDS.any { lineLower.contains(it) }) return true
        
        // Skip UID patterns
        if (Regex("\\d{4}\\s?\\d{4}\\s?\\d{4}").containsMatchIn(line)) return true
        
        // Skip date patterns
        if (Regex("\\d{2}[/-]\\d{2}[/-]\\d{4}").containsMatchIn(line)) return true
        
        // Skip gender info
        if (lineLower.matches(Regex(".*(male|female|gender).*"))) return true
        
        // Skip name-like patterns that appear after street address (likely person's name)
        if (isPersonNamePattern(line)) return true
        
        // Skip very short lines
        if (line.trim().length < 3) return true
        
        // Skip lines with only symbols/punctuation
        if (Regex("^[^a-zA-Z0-9]*$").matches(line)) return true
        
        return false
    }
    
    private fun isPersonNamePattern(line: String): Boolean {
        val words = line.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        
        // If it's 2-3 words with no numbers and no address keywords, it might be a name
        if (words.size in 2..3 && 
            !line.contains(Regex("\\d")) &&
            !containsAddressKeywords(line)) {
            return true
        }
        
        return false
    }
    
    private fun containsAddressKeywords(line: String): Boolean {
        val lineLower = line.lowercase()
        val addressKeywords = listOf(
            "road", "street", "lane", "plot", "house", "building", "apartment", "flat",
            "east", "west", "north", "south", "nagar", "colony", "society", "area",
            "district", "pin", "pincode"
        )
        return addressKeywords.any { lineLower.contains(it) }
    }
    
    private fun formatCleanAddress(addressLines: List<String>, state: String, pinCode: String): String {
        if (addressLines.isEmpty()) return ""
        
        // Remove duplicates and consolidate city names
        val uniqueLines = mutableSetOf<String>()
        val cityNames = mutableSetOf<String>()
        
        for (line in addressLines) {
            val lineLower = line.lowercase()
            
            // Check if this is a known city that appears multiple times
            if (isKnownCity(line) || cityNames.any { lineLower.contains(it.lowercase()) }) {
                cityNames.add(line.toTitleCase())
            } else {
                uniqueLines.add(line)
            }
        }
        
        // Build final address
        val finalParts = mutableListOf<String>()
        finalParts.addAll(uniqueLines)
        
        // Add the best city name (longest one if multiple)
        val bestCity = cityNames.maxByOrNull { it.length }
        if (bestCity != null) {
            finalParts.add(bestCity)
        }
        
        // Add state and pin
        finalParts.add(state.toTitleCase())
        finalParts.add(pinCode)
        
        return finalParts.joinToString(", ")
    }
    
    private fun formatCompleteAddress(rawAddressLines: List<String>, state: String, pinCode: String): String {
        if (rawAddressLines.isEmpty()) return ""
        
        // Separate different types of address components
        val streetComponents = mutableListOf<String>()
        val areaComponents = mutableListOf<String>()
        val cityComponents = mutableSetOf<String>()  // Use Set to avoid duplicates
        
        for (line in rawAddressLines) {
            val lineLower = line.lowercase()
            
            // Skip the state+pin line itself
            if (lineLower.contains(state.lowercase()) && line.contains(pinCode)) continue
            
            // Identify component type based on content
            when {
                // Street address (contains numbers, "road", "street", etc.)
                containsStreetIndicators(lineLower) -> streetComponents.add(line)
                
                // Area/locality (contains "east", "west", area-like terms)
                containsAreaIndicators(lineLower) -> areaComponents.add(line)
                
                // City names (known cities or repeated words)
                containsCityIndicators(lineLower, rawAddressLines) -> cityComponents.add(line.toTitleCase())
                
                // Default to area if unsure
                else -> areaComponents.add(line)
            }
        }
        
        // Build final address in proper format
        val finalAddress = mutableListOf<String>()
        
        // Add street address first
        if (streetComponents.isNotEmpty()) {
            finalAddress.addAll(streetComponents)
        }
        
        // Add areas/localities
        if (areaComponents.isNotEmpty()) {
            finalAddress.addAll(areaComponents)
        }
        
        // Add unique city (take the most common/longest one)
        val bestCity = cityComponents.maxByOrNull { it.length } ?: ""
        if (bestCity.isNotEmpty()) {
            finalAddress.add(bestCity)
        }
        
        // Add state
        finalAddress.add(state.toTitleCase())
        
        // Add pin code
        finalAddress.add(pinCode)
        
        return finalAddress.joinToString(", ")
    }
    
    private fun containsStreetIndicators(line: String): Boolean {
        return line.contains(Regex("\\d+")) && // Contains numbers
               (line.contains("road") || line.contains("street") || line.contains("lane") || 
                line.contains("plot") || line.contains("house") || line.contains("building"))
    }
    
    private fun containsAreaIndicators(line: String): Boolean {
        return line.contains("east") || line.contains("west") || line.contains("north") || line.contains("south") ||
               line.contains("nagar") || line.contains("colony") || line.contains("society")
    }
    
    private fun containsCityIndicators(line: String, allLines: List<String>): Boolean {
        // Check if this word appears multiple times (likely a city name)
        val wordCount = allLines.count { it.lowercase().contains(line.lowercase()) }
        return wordCount > 1 || isKnownCity(line)
    }
    
    private fun isKnownCity(line: String): Boolean {
        val knownCities = listOf("mumbai", "delhi", "bangalore", "kolkata", "chennai", "hyderabad", "pune", "ahmedabad")
        return knownCities.any { line.lowercase().contains(it) }
    }
    
    private fun String.toTitleCase(): String {
        return this.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
    
    private fun findPinCodeNearLine(lines: List<String>, centerIndex: Int): String {
        val searchRange = maxOf(0, centerIndex - 2)..minOf(lines.size - 1, centerIndex + 2)
        
        for (i in searchRange) {
            val pinMatch = Regex("\\b(\\d{6})\\b").find(lines[i])
            if (pinMatch != null) {
                return pinMatch.groupValues[1]
            }
        }
        return ""
    }
    
    private fun isNonAddressLine(line: String): Boolean {
        val lineLower = line.lowercase()
        
        // Skip header lines
        if (HEADER_KEYWORDS.any { lineLower.contains(it) }) return true
        
        // Skip UID lines
        if (Regex("\\d{4}\\s?\\d{4}\\s?\\d{4}").containsMatchIn(line)) return true
        
        // Skip gender/DOB lines
        if (lineLower.matches(Regex(".*\\b(male|female|gender)\\b.*")) ||
            lineLower.matches(Regex(".*\\d{2}[/-]\\d{2}[/-]\\d{4}.*"))) return true
        
        // Skip very short lines (likely noise)
        if (line.trim().length < 3) return true
        
        return false
    }
    
    private fun cleanAddressLine(line: String): String {
        return line.replace(Regex("[^\\w\\s,.-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    
    private fun extractAddressBySection(text: String): String {
        // Fallback method: extract between gender and UID
        val genderPos = text.indexOf("Gender:", ignoreCase = true)
        val uidMatch = Regex("\\d{4}\\s?\\d{4}\\s?\\d{4}").find(text)
        
        if (genderPos == -1 || uidMatch == null) return ""
        
        val genderEnd = text.indexOf('\n', genderPos)
        val uidStart = uidMatch.range.first
        
        if (genderEnd == -1 || uidStart <= genderEnd) return ""
        
        val addressSection = text.substring(genderEnd, uidStart).trim()
        val addressLines = addressSection.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length > 3 }
            .filter { !isNonAddressLine(it) }
            .map { cleanAddressLine(it) }
            .filter { it.isNotEmpty() }
        
        return if (addressLines.isNotEmpty()) {
            addressLines.joinToString(", ")
        } else ""
    }
    
    private data class ValidationResult(
        val isValid: Boolean,
        val score: Float,
        val message: String
    )
    
    private fun validateAadhaarCard(text: String): ValidationResult {
        val textLower = text.lowercase()
        var score = 0.0f
        val issues = mutableListOf<String>()
        
        // Check for Aadhaar-specific keywords (positive indicators)
        val aadhaarKeywordCount = AADHAAR_KEYWORDS.count { keyword ->
            textLower.contains(keyword.lowercase())
        }
        score += aadhaarKeywordCount * 15.0f // Up to 60 points for keywords
        
        // Check for 12-digit UID pattern (strong positive indicator)
        val uidPattern = Regex("\\d{4}\\s?\\d{4}\\s?\\d{4}")
        val hasUID = uidPattern.containsMatchIn(text)
        if (hasUID) {
            score += 25.0f
        } else {
            issues.add("No 12-digit UID found")
        }
        
        // Check for typical Aadhaar structure patterns
        val hasGender = textLower.contains("male") || textLower.contains("female") || 
                       textLower.contains("gender") || Regex("gender:\\s*[mf]", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (hasGender) {
            score += 10.0f
        } else {
            issues.add("No gender information found")
        }
        
        // Check for date pattern (DOB or YOB)
        val hasDatePattern = Regex("\\d{2}[/-]\\d{2}[/-]\\d{4}").containsMatchIn(text) ||
                           Regex("\\b(19|20)\\d{2}\\b").containsMatchIn(text)
        if (hasDatePattern) {
            score += 10.0f
        } else {
            issues.add("No date/birth year pattern found")
        }
        
        // Check for Indian state or pincode (address indicators)
        val hasIndianLocation = INDIAN_STATES.any { state -> textLower.contains(state) } ||
                               Regex("\\b\\d{6}\\b").containsMatchIn(text)
        if (hasIndianLocation) {
            score += 10.0f
        }
        
        // Check for suspicious non-Aadhaar keywords (negative indicators)
        val suspiciousKeywords = NON_AADHAAR_KEYWORDS.filter { keyword ->
            textLower.contains(keyword.lowercase())
        }
        if (suspiciousKeywords.isNotEmpty()) {
            score -= suspiciousKeywords.size * 20.0f // Penalty for non-Aadhaar keywords
            issues.add("Contains non-Aadhaar keywords: ${suspiciousKeywords.joinToString(", ")}")
        }
        
        // Check text length (Aadhaar cards should have reasonable amount of text)
        if (text.length < 50) {
            score -= 15.0f
            issues.add("Insufficient text content")
        }
        
        // Check for minimum word count
        val wordCount = text.split("\\s+".toRegex()).size
        if (wordCount < 10) {
            score -= 10.0f
            issues.add("Too few words detected")
        }
        
        // Normalize score to 0-100 range
        score = score.coerceIn(0.0f, 100.0f)
        
        val threshold = 50.0f // Minimum score to consider it a valid Aadhaar
        val isValid = score >= threshold
        
        val message = if (isValid) {
            "Valid Aadhaar card detected (confidence: ${score.toInt()}%)"
        } else {
            val mainIssues = issues.take(2).joinToString("; ")
            "Not an Aadhaar card (confidence: ${score.toInt()}%). Issues: $mainIssues"
        }
        
        Log.d(TAG, "Aadhaar validation - Score: $score, Valid: $isValid, Issues: $issues")
        
        return ValidationResult(isValid, score, message)
    }
    
    fun cleanup() {
        latinTextRecognizer.close()
        devanagariTextRecognizer.close()
    }
    
    private fun combineRecognitionResults(latinText: String, devanagariText: String): String {
        val combinedLines = mutableListOf<String>()
        
        // Add Latin text (used for data extraction)
        if (latinText.isNotBlank()) {
            combinedLines.add("=== LATIN SCRIPT (PRIMARY FOR DATA EXTRACTION) ===")
            combinedLines.addAll(latinText.split("\n"))
            combinedLines.add("")
        }
        
        // Add Devanagari text (for Hindi identification only)
        if (devanagariText.isNotBlank()) {
            combinedLines.add("=== DEVANAGARI SCRIPT (HINDI TEXT ONLY) ===")
            combinedLines.addAll(devanagariText.split("\n"))
        }
        
        return combinedLines.joinToString("\n")
    }
    
    private fun detectLanguageAndTag(text: String): String {
        val lines = text.split("\n")
        val taggedLines = mutableListOf<String>()
        
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) {
                taggedLines.add(trimmedLine)
                continue
            }
            
            val languageInfo = detectLineLanguage(trimmedLine)
            val tag = when (languageInfo.language) {
                Language.HINDI -> "[HI]"
                Language.ENGLISH -> "[EN]"
                Language.MIXED -> "[MX]"
                Language.UNKNOWN -> "[??]"
            }
            
            taggedLines.add("$tag $trimmedLine")
        }
        
        return taggedLines.joinToString("\n")
    }
    
    private fun detectLineLanguage(line: String): LanguageTaggedLine {
        var hindiChars = 0
        var englishChars = 0
        var totalChars = 0
        
        // First check for actual Devanagari script
        for (char in line) {
            when {
                // Devanagari script range (Hindi)
                char in '\u0900'..'\u097F' -> {
                    hindiChars++
                    totalChars++
                }
                // Latin script range (English)
                char in 'A'..'Z' || char in 'a'..'z' -> {
                    englishChars++
                    totalChars++
                }
                // Numbers are neutral
                char.isDigit() -> totalChars++
            }
        }
        
        // If no Devanagari found, check for OCR-converted Hindi patterns
        if (hindiChars == 0) {
            val ocrHindiScore = detectOCRHindiPatterns(line)
            if (ocrHindiScore > 0.5) {
                return LanguageTaggedLine(line, Language.HINDI, ocrHindiScore)
            }
        }
        
        if (totalChars == 0) {
            return LanguageTaggedLine(line, Language.UNKNOWN, 0.0f)
        }
        
        val hindiRatio = hindiChars.toFloat() / totalChars
        val englishRatio = englishChars.toFloat() / totalChars
        
        return when {
            hindiRatio > 0.7 -> LanguageTaggedLine(line, Language.HINDI, hindiRatio)
            englishRatio > 0.7 -> LanguageTaggedLine(line, Language.ENGLISH, englishRatio)
            hindiRatio > 0.3 && englishRatio > 0.3 -> LanguageTaggedLine(line, Language.MIXED, maxOf(hindiRatio, englishRatio))
            hindiRatio > englishRatio -> LanguageTaggedLine(line, Language.HINDI, hindiRatio)
            englishRatio > hindiRatio -> LanguageTaggedLine(line, Language.ENGLISH, englishRatio)
            else -> LanguageTaggedLine(line, Language.UNKNOWN, 0.5f)
        }
    }
    
    private fun detectOCRHindiPatterns(line: String): Float {
        val lineLower = line.lowercase()
        var hindiScore = 0.0f
        
        // Common OCR misreads of Hindi text patterns
        val hindiOCRPatterns = listOf(
            // Consecutive consonants without vowels (very uncommon in English)
            Regex("([bcdfghjklmnpqrstvwxyz]){3,}"),
            // Unusual character combinations from Hindi OCR
            Regex("(sr|tr|kr|pr|br|dr|gr|hr|jr|mr|nr|wr|yr|th|dh|bh|kh|gh|ch|jh|ph|sh|rh)"),
            // Repetitive patterns common in OCR of Devanagari
            Regex("(.)\\1{2,}"),
            // Very short fragments that could be OCR artifacts
            Regex("^[a-z]{1,2}$"),
            // Lines with high consonant density
            Regex("[bcdfghjklmnpqrstvwxyz]{2,}")
        )
        
        // Common Hindi words that might appear correctly in OCR
        val hindiWords = listOf(
            "bharata", "sarkar", "sarkara", "bharat", "india", "hindi", 
            "naam", "janam", "pita", "mata", "ghar", "pata", "shahar"
        )
        
        // Check for known Hindi words
        for (word in hindiWords) {
            if (lineLower.contains(word)) {
                hindiScore += 0.3f
            }
        }
        
        // Check for OCR patterns
        for (pattern in hindiOCRPatterns) {
            if (pattern.containsMatchIn(lineLower)) {
                hindiScore += 0.2f
            }
        }
        
        // Check consonant density (Hindi OCR often produces high consonant ratios)
        val consonants = lineLower.count { it in "bcdfghjklmnpqrstvwxyz" }
        val vowels = lineLower.count { it in "aeiou" }
        val letters = consonants + vowels
        
        if (letters > 3) {
            val consonantRatio = consonants.toFloat() / letters
            if (consonantRatio > 0.7) { // Very high consonant ratio suggests Hindi OCR
                hindiScore += 0.4f
            }
        }
        
        // Check for fragmented, low-quality text (common in Hindi OCR)
        val words = lineLower.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val shortWords = words.count { it.length <= 2 }
        if (words.isNotEmpty() && shortWords.toFloat() / words.size > 0.6) {
            hindiScore += 0.3f
        }
        
        return minOf(hindiScore, 1.0f)
    }
    
    private fun extractEnglishOnlyText(text: String): String {
        val lines = text.split("\n")
        val englishLines = mutableListOf<String>()
        
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue
            
            val languageInfo = detectLineLanguage(trimmedLine)
            if (languageInfo.language == Language.ENGLISH || 
                (languageInfo.language == Language.MIXED && languageInfo.confidence > 0.4)) {
                englishLines.add(trimmedLine)
            }
        }
        
        return englishLines.joinToString("\n")
    }
    
    private fun extractHindiRegions(devanagariResult: Text): List<Rect> {
        val hindiRegions = mutableListOf<Rect>()
        
        for (block in devanagariResult.textBlocks) {
            for (line in block.lines) {
                // Process entire lines that contain Devanagari, not just individual elements
                val lineText = line.text
                val hasDevanagari = lineText.any { char ->
                    char in '\u0900'..'\u097F'
                }
                
                if (hasDevanagari && lineText.isNotBlank()) {
                    line.boundingBox?.let { boundingBox ->
                        // Moderate padding to catch nearby OCR garbage
                        val paddedBox = Rect(
                            boundingBox.left - 10,  // Moderate padding
                            boundingBox.top - 5,
                            boundingBox.right + 10, // Moderate padding
                            boundingBox.bottom + 5
                        )
                        hindiRegions.add(paddedBox)
                        Log.d(TAG, "Hindi line region detected: '$lineText' at $paddedBox")
                    }
                }
                
                // Also process individual elements for fine-grained detection
                for (element in line.elements) {
                    val text = element.text
                    val hasDevanagari = text.any { char ->
                        char in '\u0900'..'\u097F'
                    }
                    
                    if (hasDevanagari && text.isNotBlank()) {
                        element.boundingBox?.let { boundingBox ->
                            // Moderate padding around individual Hindi elements
                            val paddedBox = Rect(
                                boundingBox.left - 8,
                                boundingBox.top - 4,
                                boundingBox.right + 8,
                                boundingBox.bottom + 4
                            )
                            hindiRegions.add(paddedBox)
                            Log.d(TAG, "Hindi element region detected: '$text' at $paddedBox")
                        }
                    }
                }
            }
        }
        
        return hindiRegions
    }
    
    private fun filterOutHindiRegions(latinResult: Text, hindiRegions: List<Rect>): String {
        val filteredText = mutableListOf<String>()
        
        for (block in latinResult.textBlocks) {
            for (line in block.lines) {
                val lineElements = mutableListOf<String>()
                
                for (element in line.elements) {
                    val elementBox = element.boundingBox
                    val text = element.text
                    
                    if (elementBox != null) {
                        // Check if this element overlaps with any Hindi region
                        val overlapsWithHindi = hindiRegions.any { hindiRegion ->
                            Rect.intersects(elementBox, hindiRegion)
                        }
                        
                        // Also check for OCR garbage patterns even if no spatial overlap
                        val isOCRGarbage = isLikelyOCRGarbage(text)
                        
                        if (!overlapsWithHindi && !isOCRGarbage && text.isNotBlank()) {
                            lineElements.add(text)
                            Log.d(TAG, "Keeping English element: '$text'")
                        } else if (overlapsWithHindi) {
                            Log.d(TAG, "Filtering out spatially overlapping element: '$text'")
                        } else if (isOCRGarbage) {
                            Log.d(TAG, "Filtering out OCR garbage element: '$text'")
                        }
                    } else {
                        // If no bounding box, include the text (fallback)
                        if (text.isNotBlank()) {
                            lineElements.add(text)
                        }
                    }
                }
                
                if (lineElements.isNotEmpty()) {
                    filteredText.add(lineElements.joinToString(" "))
                }
            }
        }
        
        return filteredText.joinToString("\n")
    }
    
    private fun isLikelyOCRGarbage(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2) return false
        
        // Don't filter important words
        val importantWords = listOf("male", "female", "government", "india", "aadhaar", "name", "dob", "address")
        if (importantWords.any { trimmed.lowercase().contains(it) }) {
            return false
        }
        
        // Only filter very obvious garbage - be conservative
        val garbagePatterns = listOf(
            // Repetitive characters (3+ repetitions)
            Regex("(.)\\1{3,}"),
            // Very short nonsense (1-2 chars only)
            Regex("^[a-z]{1,2}$"),
            // Lines with excessive special characters
            Regex(".*[^a-zA-Z0-9\\s]{3,}.*")
        )
        
        // Check against patterns
        for (pattern in garbagePatterns) {
            if (pattern.matches(trimmed.lowercase())) {
                return true
            }
        }
        
        // Very conservative consonant ratio check (only extreme cases)
        val consonants = trimmed.count { it.lowercase() in "bcdfghjklmnpqrstvwxyz" }
        val vowels = trimmed.count { it.lowercase() in "aeiou" }
        val letters = consonants + vowels
        
        if (letters > 4) {
            val consonantRatio = consonants.toFloat() / letters
            // Only filter if extremely high consonant ratio AND short text
            if (consonantRatio > 0.9 && letters < 8) {
                return true
            }
        }
        
        return false
    }
    
    private fun filterDevanagariText(text: String): String {
        return text.split('\n').filter { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) return@filter false
            
            // Keep lines that have actual Devanagari characters
            val hasDevanagari = trimmedLine.any { char ->
                char in '\u0900'..'\u097F'
            }
            
            // Filter out lines that are mostly English
            val englishChars = trimmedLine.count { char ->
                char in 'A'..'Z' || char in 'a'..'z'
            }
            val devanagariChars = trimmedLine.count { char ->
                char in '\u0900'..'\u097F'
            }
            val totalLetters = englishChars + devanagariChars
            
            if (totalLetters > 0) {
                val devanagariRatio = devanagariChars.toFloat() / totalLetters
                // Keep lines that are at least 30% Devanagari
                return@filter hasDevanagari && devanagariRatio >= 0.3
            }
            
            return@filter hasDevanagari
        }.joinToString("\n")
    }
}