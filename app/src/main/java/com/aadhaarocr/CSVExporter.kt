package com.aadhaarocr

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Handles exporting patient registration data to a central CSV file.
 * Consolidates all registrations into one file for easy HMS import.
 */
class CSVExporter(private val context: Context) {
    
    companion object {
        private const val TAG = "CSVExporter"
        private const val CSV_FILENAME = "aadhaar_registration_data.csv"
        private val CSV_HEADERS = arrayOf(
            "Registration Number", "Registration Date", "First Name", "Middle Name", "Last Name",
            "Gender", "Birth Date", "Address.Village", "Address.Tehsil", "Address.District", "Address.State"
        )
    }
    
    /**
     * Main entry point for export. Handles OS-specific storage logic.
     */
    fun exportToCSV(aadhaarData: AadhaarData): Result<String> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportToCSVModern(aadhaarData)
            } else {
                exportToCSVLegacy(aadhaarData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            Result.failure(e)
        }
    }

    private fun prepareCSVRow(aadhaarData: AadhaarData): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        
        // Robust Name Splitting
        val names = aadhaarData.name.trim().split("\\s+".toRegex())
        val firstName = if (names.isNotEmpty()) names[0] else ""
        val lastName = if (names.size > 1) names.last() else ""
        val middleName = if (names.size > 2) names.subList(1, names.size - 1).joinToString(" ") else ""

        // Registration ID: GAN + Date + Random suffix
        val regNum = "GAN" + SimpleDateFormat("ddMMyy", Locale.getDefault()).format(Date()) + (100..999).random()

        // Indian Address Part Mapping
        val addrParts = aadhaarData.address.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        var state = ""; var district = ""; var tehsil = ""; var village = ""
        
        val size = addrParts.size
        if (size >= 1) state = addrParts.last().split("-")[0].trim()
        if (size >= 2) district = addrParts[size - 2]
        if (size >= 3) tehsil = addrParts[size - 3]
        if (size >= 4) village = addrParts.subList(0, size - 3).joinToString(", ")
        else if (size in 1..3) village = addrParts[0]

        return buildString {
            append(escapeCSVField(regNum)).append(",")
            append(escapeCSVField(timestamp)).append(",")
            append(escapeCSVField(firstName.uppercase())).append(",")
            append(escapeCSVField(middleName.uppercase())).append(",")
            append(escapeCSVField(lastName.uppercase())).append(",")
            append(escapeCSVField(aadhaarData.gender.take(1).uppercase())).append(",")
            append(escapeCSVField(aadhaarData.dob)).append(",")
            append(escapeCSVField(village)).append(",")
            append(escapeCSVField(tehsil)).append(",")
            append(escapeCSVField(district)).append(",")
            append(escapeCSVField(state)).append("\n")
        }
    }

    /**
     * Modern Android (11+) Scoped Storage export using MediaStore.
     */
    private fun exportToCSVModern(aadhaarData: AadhaarData): Result<String> {
        val resolver = context.contentResolver
        val relativePath = Environment.DIRECTORY_DOCUMENTS + "/AadhaarOCR"
        
        // Find existing file to append or create new
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(CSV_FILENAME, "$relativePath/")
        
        val existingUri = resolver.query(
            MediaStore.Files.getContentUri("external"), 
            projection, selection, selectionArgs, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), id.toString())
            } else null
        }

        val uri = existingUri ?: run {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, CSV_FILENAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
            resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
        } ?: return Result.failure(IOException("Failed to create file entry"))

        resolver.openOutputStream(uri, "wa")?.use { outputStream ->
            // Write Header ONLY if the file was just created
            if (existingUri == null) {
                outputStream.write((CSV_HEADERS.joinToString(",") + "\n").toByteArray())
            }
            outputStream.write(prepareCSVRow(aadhaarData).toByteArray())
        }

        return Result.success("Documents/AadhaarOCR/$CSV_FILENAME")
    }
    
    /**
     * Legacy Android (<10) export using direct File API.
     */
    private fun exportToCSVLegacy(aadhaarData: AadhaarData): Result<String> {
        val documentsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "AadhaarOCR")
        if (!documentsDir.exists()) documentsDir.mkdirs()
        
        val file = File(documentsDir, CSV_FILENAME)
        val isNewFile = !file.exists()
        
        FileWriter(file, true).use { writer ->
            if (isNewFile) {
                writer.append(CSV_HEADERS.joinToString(",")).append('\n')
            }
            writer.append(prepareCSVRow(aadhaarData))
            writer.flush()
        }
        return Result.success(file.absolutePath)
    }
    
    private fun escapeCSVField(field: String): String {
        val clean = field.trim()
        return if (clean.contains(',') || clean.contains('"') || clean.contains('\n')) {
            "\"${clean.replace("\"", "\"\"")}\""
        } else {
            clean
        }
    }
}