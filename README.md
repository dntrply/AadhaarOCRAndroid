 i is it possible to 1. 1.is it# Aadhaar OCR Android

An Android application that extracts patient information from Aadhaar card images using Optical Character Recognition (OCR) and exports the data to CSV format.

## Features

- **Camera Integration**: Capture Aadhaar card images directly using the device camera
- **ML Kit OCR**: Uses Google ML Kit for accurate text recognition
- **Smart Data Parsing**: Intelligently extracts key information:
  - Name
  - Gender (M/F)  
  - Date of Birth
  - Aadhaar Number (UID)
  - Complete Address
- **CSV Export**: Saves extracted data to CSV files in Documents/AadhaarOCR/
- **Modern Android UI**: Material Design 3 components with responsive layout

## Technical Stack

- **Language**: Kotlin
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **OCR Engine**: Google ML Kit Text Recognition
- **Camera**: CameraX
- **UI**: Material Design 3, View Binding
- **Architecture**: MVVM with coroutines

## Prerequisites

- Android Studio Arctic Fox or later
- Android device with camera (API level 24+)
- Camera and storage permissions

## Installation

1. Clone the repository:
```bash
git clone <repository-url>
cd AadhaarOCRAndroid
```

2. Open the project in Android Studio

3. Build and run the project on your Android device

## Usage

1. **Launch the app** and grant camera and storage permissions
2. **Tap "Capture Aadhaar Card"** to open the camera
3. **Position the Aadhaar card** in the camera frame and tap capture
4. **Tap "Process Image"** to extract the data using OCR
5. **Review the extracted information** displayed on screen
6. **Tap "Export to CSV"** to save the data

## Project Structure

```
app/src/main/java/com/aadhaarocr/
├── MainActivity.kt              # Main activity with camera and UI logic
├── AadhaarOCRProcessor.kt       # Core OCR processing and data extraction
├── CSVExporter.kt              # CSV file creation and management
└── AadhaarData.kt              # Data class (included in AadhaarOCRProcessor.kt)

app/src/main/res/
├── layout/
│   └── activity_main.xml       # Main UI layout
├── values/
│   ├── strings.xml            # String resources
│   ├── colors.xml             # Color definitions
│   └── themes.xml             # Material themes
└── xml/
    ├── backup_rules.xml       # Backup configuration
    └── data_extraction_rules.xml # Data extraction rules
```

## Key Components

### AadhaarOCRProcessor
- Converts captured images to text using ML Kit
- Implements the same parsing logic as the original Python version
- Handles name extraction, gender detection, date parsing, and address extraction
- Uses regex patterns optimized for Aadhaar card format
- **Back of Card Processing**: The back of the card typically interleaves English and Hindi text in unpredictable OCR blocks and lacks demographic fields. The processor accepts an `isBackOfCard` flag. When true, it disables heuristic Name extraction to prevent false-positives (which otherwise cause premature termination of the Address scan). It also employs an extended 15-line backward scan, explicitly filters out Devanagari characters, and deduplicates adjacent repeating words to reconstruct a clean English address.

### CSVExporter  
- Creates CSV files in the Documents/AadhaarOCR directory
- Handles proper CSV escaping and formatting
- Appends new records to existing files
- Includes timestamp for each record

### MainActivity
- Manages camera permissions and CameraX integration
- Handles image capture and preview
- Coordinates between OCR processing and UI updates
- Manages the complete user workflow

## Build & Release Configuration

The project is configured to use a unified cryptographic keystore to ensure that APK signatures remain consistent across all developers and machines, avoiding "App Not Installed" signature mismatch errors during updates.

**Important Security Note**: The release keystore (`aadhaarocr.jks`) is purposely **not** committed to this repository.

### Automated Versioning
The app's `versionCode` and `versionName` are dynamically generated during the Gradle build based on the repository's Git history:
- **`versionCode`**: Calculated automatically using the total number of Git commits (`git rev-list --count HEAD`). This ensures the `versionCode` always safely increments, preventing "App not installed" downgrade errors when sharing APKs.
- **`versionName`**: Derived from the latest Git tag (e.g. `v1.2.0`). If no tags exist, it falls back to the short commit hash or a default value.
- **Requirements**: You must have Git installed on your machine and the project must be tracked as a Git repository for the versioning automation to work. If Git is unavailable, it safely defaults to version `1.0.0-dev` with a version code of `1`.
- **Manual Overrides**: If Git is unattached (e.g., when downloading the source as a zip), developers can manually override these values during the build process using Gradle project properties. For example:
  `./gradlew assembleRelease -PcustomVersionCode=15 -PcustomVersionName="1.5.0"`

### Best Practices: `versionCode` vs `versionName`
When managing Android releases, it is important to understand the distinction between these two values:
- **`versionCode` (Internal Integer)**: This is the only value Android actually cares about when installing an update. It **must strictly increment** with every single release build you share (even for tiny bug fixes). If you generate an APK with a `versionCode` equal to or lower than the one installed on the device, Android will reject it with an "App not installed" error.
- **`versionName` (User-Facing String)**: This is simply the human-readable version string shown to users in the app settings (e.g., `1.0.0`, `v1.2-beta`). It does **not** strictly need to change on every internal build, but best practice dictates following Semantic Versioning (Major.Minor.Patch) for official public releases.

> By relying on the automated Git automation above, your `versionCode` safely increments on every commit automatically, ensuring you never run into Android update errors.

### Managing `versionName` with Git Tags (Best Practice)
To automatically set the user-facing `versionName`, this project uses `git describe`. Instead of typing version numbers into code, you manage them entirely through Git Tags:

1. **Tagging a Release**: When you are ready to publish a new version (e.g., 1.0.0), create a Git tag:
   ```bash
   git tag v1.0.0
   ```
   *The script will automatically detect this tag and set the `versionName` to `v1.0.0`.*

2. **Automatic Dev Tracking**: If you make 3 commits *after* tagging `v1.0.0`, the script dynamically generates a name like `v1.0.0-3-ga1b2c3` (showing it is 3 commits ahead of the tag).
3. **Dirty State Detection**: If you build the app with uncommitted changes in your editor, the script automatically appends `-dirty` (e.g., `v1.0.0-3-ga1b2c3-dirty`) to warn you that the build contains unrecorded changes.

### How to build an Official Release APK:
If you need to generate an official release build (or if you are a new developer setting up the project):
1. Obtain the `aadhaarocr.jks` file and the `keystore.properties` file from your team lead or secure password vault.
2. Place both files in the root folder of the project (`AadhaarOCRAndroid/`).
3. Run the release build: `./gradlew assembleRelease`
   
> The `build.gradle` file will automatically detect `keystore.properties` (which is safely ignored by `.gitignore`) and use it to securely sign your release APK. If these files are not present, Android Studio will safely fall back to using your computer's local, auto-generated debug key for testing.

## Firebase & Crashlytics Integration

This project is configured with Firebase Crashlytics to monitor field crashes (e.g. `OutOfMemoryError` on high-resolution cameras) directly in the Firebase console.

### Configuration
1. Ensure the `app/google-services.json` file (downloaded from your Firebase Console) is present in the `app/` directory.
2. The Firebase BOM, Analytics, and Crashlytics plugins are already configured in the build scripts.

### Crashlytics Testing (Staging Build Type)
To test that Crashlytics mapping uploads and exception reporting work properly, a dedicated `staging` build type is configured. 

The `staging` build type:
- Inherits exactly from the `release` configuration (fully obfuscated and minified via ProGuard/R8).
- Exposes a custom `BuildConfig.ENABLE_CRASH_BUTTON = true` flag.
- Enables a visible **"💥 SIMULATE CRASH"** button on the main screen that throws a `RuntimeException`.

To generate a build for testing Crashlytics:
```bash
./gradlew assembleStaging
```
This guarantees your testing accurately simulates a production release environment without accidentally exposing test crash buttons to real end users in the standard `release` build.

## Permissions

The app requires the following permissions:
- `CAMERA` - To capture Aadhaar card images
- `READ_EXTERNAL_STORAGE` - To read existing CSV files
- `WRITE_EXTERNAL_STORAGE` - To save CSV export files
- `READ_MEDIA_IMAGES` - For Android 13+ media access

## Output Format

CSV files are saved to: `Documents/AadhaarOCR/aadhaar_data.csv`

Columns:
- Name
- Gender
- DOB (YYYY-MM-DD format)
- UID (12-digit Aadhaar number)
- Address
- Timestamp

## Limitations

- Requires clear, well-lit images of Aadhaar cards
- Works best with standard Aadhaar card layouts
- OCR accuracy depends on image quality and lighting
- Currently optimized for English text extraction

## Security & Privacy

This application processes sensitive personal information. Ensure compliance with:
- Data protection regulations
- Privacy laws in your jurisdiction  
- Secure handling of personal identification documents
- Proper data storage and disposal practices

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes and test thoroughly
4. Create a Pull Request

## License

This project is intended for educational and legitimate administrative purposes only. Users must comply with applicable privacy laws and regulations when processing personal identification documents.

---

**Note**: This application is designed for legitimate document processing purposes. Users are responsible for ensuring compliance with all applicable laws and regulations regarding the processing of personal identification documents.