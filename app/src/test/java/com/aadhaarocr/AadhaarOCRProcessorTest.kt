package com.aadhaarocr

import org.junit.Assert.assertEquals
import org.junit.Test

class AadhaarOCRProcessorTest {

    private val processor = AadhaarOCRProcessor()

    @Test
    fun testExtractUID() {
        val text = """31TTR
GOVERNMENT OF INDIAE
Name:RAJESH KUMAR
Gender: yoq/ MALE
Date of Birth: 15/08/1982
1234 5678 9012
AADHAAR
4T: HO[H T 45, 1clt HR 3, GIvyG R, M fc - 110024
ADDRESS: H.No 45, Street No 3, Lajpat Nagar, New Delhi - 110024"""

        val extractedUid = processor.extractUID(text)
        assertEquals("1234 5678 9012", extractedUid)
    }

    @Test
    fun testExtractAddress() {
        val text = """Name:RAJESH KUMAR
Gender: MALE
Date of Birth: 15/08/1982
1234 5678 9012
पता: मकान नंबर 45, गली नंबर 3, लाजपत नगर, नई दिल्ली - 110024
ADDRESS: H.No 45, Street No 3, Lajpat Nagar, New Delhi - 110024"""

        // The exact lines array format the parser uses internally
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        
        val extractedAddress = processor.extractAddress(lines, "RAJESH KUMAR")
        assertEquals("H.No 45, Street No 3, Lajpat Nagar, New Delhi - 110024", extractedAddress)
    }
}
