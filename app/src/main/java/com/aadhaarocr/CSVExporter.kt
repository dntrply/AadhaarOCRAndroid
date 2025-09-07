package com.aadhaarocr

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class CSVExporter(private val context: Context) {
    
    companion object {
        private const val TAG = "CSVExporter"
        private const val CSV_FILENAME = "aadhaar_data.csv"
        private val CSV_HEADERS = arrayOf("Name", "Gender", "DOB", "UID", "Address", "Timestamp")
    }
    
    fun exportToCSV(aadhaarData: AadhaarData): Result<String> {
        return try {
            val file = getCSVFile()
            val fileExists = file.exists()
            
            FileWriter(file, true).use { writer ->
                // Write header if file is new
                if (!fileExists) {
                    writer.append(CSV_HEADERS.joinToString(","))
                    writer.append('\n')
                }
                
                // Write data
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date())
                
                writer.append(escapeCSVField(aadhaarData.name))
                writer.append(',')
                writer.append(escapeCSVField(aadhaarData.gender))
                writer.append(',')
                writer.append(escapeCSVField(aadhaarData.dob))
                writer.append(',')
                writer.append(escapeCSVField(aadhaarData.uid))
                writer.append(',')
                writer.append(escapeCSVField(aadhaarData.address))
                writer.append(',')
                writer.append(escapeCSVField(timestamp))
                writer.append('\n')
                
                writer.flush()
            }
            
            Log.d(TAG, "Data exported to: ${file.absolutePath}")
            Result.success(file.absolutePath)
        } catch (e: IOException) {
            Log.e(TAG, "Error exporting to CSV", e)
            Result.failure(e)
        }
    }
    
    private fun getCSVFile(): File {
        val documentsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "AadhaarOCR"
        )
        
        if (!documentsDir.exists()) {
            documentsDir.mkdirs()
        }
        
        return File(documentsDir, CSV_FILENAME)
    }
    
    private fun escapeCSVField(field: String): String {
        return if (field.contains(',') || field.contains('"') || field.contains('\n')) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }
    
    fun getCSVFilePath(): String? {
        val file = getCSVFile()
        return if (file.exists()) file.absolutePath else null
    }
    
    fun getAllRecords(): List<Map<String, String>> {
        val records = mutableListOf<Map<String, String>>()
        val file = getCSVFile()
        
        if (!file.exists()) return records
        
        try {
            file.bufferedReader().use { reader ->
                val lines = reader.readLines()
                if (lines.isEmpty()) return records
                
                val headers = lines[0].split(',').map { it.trim() }
                
                for (i in 1 until lines.size) {
                    val values = parseCSVLine(lines[i])
                    if (values.size == headers.size) {
                        val record = mutableMapOf<String, String>()
                        headers.forEachIndexed { index, header ->
                            record[header] = values[index]
                        }
                        records.add(record)
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading CSV file", e)
        }
        
        return records
    }
    
    private fun parseCSVLine(line: String): List<String> {
        val values = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var i = 0
        
        while (i < line.length) {
            val char = line[i]
            when {
                char == '"' && (i == 0 || line[i-1] == ',') -> inQuotes = true
                char == '"' && inQuotes && (i == line.length - 1 || line[i+1] == ',') -> {
                    inQuotes = false
                    values.add(current.toString())
                    current = StringBuilder()
                    i++ // Skip the comma
                }
                char == ',' && !inQuotes -> {
                    values.add(current.toString())
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
            i++
        }
        
        if (current.isNotEmpty()) {
            values.add(current.toString())
        }
        
        return values
    }
}