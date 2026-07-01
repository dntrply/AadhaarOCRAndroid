import java.util.regex.Regex

fun extractUID(text: String): String {
    val dobMatch = Regex("\\d{2}[/-]\\d{2}[/-](\\d{4})").find(text)
    val birthYear = dobMatch?.groupValues?.get(1) ?: ""

    val flexiblePattern = Regex("(?<![A-Za-z0-9])([0-9OolISB]{4})\\s+([0-9OolISB]{4})\\s+([0-9OolISB]{4})(?![A-Za-z0-9])")
    val matches = flexiblePattern.findAll(text)
    
    val candidates = mutableListOf<String>()
    for (match in matches) {
        var digits = match.value.replace(Regex("\\s"), "")
        digits = digits.replace(Regex("[Oo]"), "0")
            .replace(Regex("[lIi]"), "1")
            .replace(Regex("[Ss]"), "5")
            .replace("B", "8")
            
        if (digits.length == 12 && digits.all { it.isDigit() }) {
            if (digits.startsWith("19") || digits.startsWith("20")) continue
            if (birthYear.isNotEmpty() && digits.contains(birthYear)) continue
            candidates.add(digits)
        }
    }

    if (candidates.isNotEmpty()) {
        val dobPos = text.indexOf("Birth", ignoreCase = true)
        if (dobPos != -1) {
            val max = candidates.maxByOrNull { Math.abs(text.indexOf(it) - dobPos) }!!
            println("candidates not empty, returning max: $max")
            return max
        }
        return candidates[0]
    }

    val exact12Pattern = Regex("(?<!\\d)(\\d[\\s-]*){12}(?!\\d)")
    val exactMatches = exact12Pattern.findAll(text)
    for (match in exactMatches) {
        val digits = match.value.replace(Regex("[^0-9]"), "")
        if (digits.length == 12) {
            if (digits.startsWith("19") || digits.startsWith("20")) continue
            if (birthYear.isNotEmpty() && digits.contains(birthYear)) continue
            println("exact12Pattern found: $digits")
            return digits
        }
    }

    return ""
}

val text = """31TTR
GOVERNMENT OF INDIAE
Name:RAJESH KUMAR
Gender: yoq/ MALE
Date of Birth: 15/08/1982
1234 5678 9012
AADHAAR
4T: HO[H T 45, 1clt HR 3, GIvyG R, M fc - 110024
ADDRESS: H.No 45, Street No 3, Lajpat Nagar, New Delhi - 110024"""

println("Result: " + extractUID(text))
