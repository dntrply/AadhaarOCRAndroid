package com.aadhaarocr

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AadhaarOCRProcessorTest {

    @Test
    fun testAadhaarExtraction() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val inputStream = context.assets.open("fictional_aadhaar.jpg")
        val bitmap = BitmapFactory.decodeStream(inputStream)
        
        val processor = AadhaarOCRProcessor()
        val result = processor.processAadhaarCard(bitmap)
        
        println("================== OCR RAW TEXT ==================")
        println(result.rawOcrText)
        println("==================================================")
        
        println("Extracted Name: ${result.name}")
        println("Extracted DOB: ${result.dob}")
        println("Extracted UID: ${result.uid}")
        println("Extracted Address: ${result.address}")
        
        processor.cleanup()
    }
}
