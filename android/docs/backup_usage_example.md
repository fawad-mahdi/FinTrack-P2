# Database Backup Usage Example

This document demonstrates how to use the database backup functionality implemented in Task 8.2.

## Overview

The `StorageManager.backupDatabase()` method provides a safe way to backup the SQLite database by:
1. Stopping the server to prevent database corruption
2. Copying the database file to a user-selected location
3. Restarting the server automatically

## Usage in Activity

Here's an example of how to integrate the backup functionality in an Activity (e.g., SettingsActivity):

```kotlin
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.fintrack.pk.server.ServerProcessManager
import com.fintrack.pk.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var storageManager: StorageManager
    private lateinit var serverProcessManager: ServerProcessManager
    
    // Register for Storage Access Framework result
    private val createBackupFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-sqlite3")
    ) { uri: Uri? ->
        uri?.let { performBackup(it) }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize managers
        storageManager = StorageManager(this)
        serverProcessManager = ServerProcessManager(this)
        
        // Setup backup button
        findViewById<Button>(R.id.backup_button).setOnClickListener {
            initiateBackup()
        }
    }
    
    /**
     * Initiate the backup process by opening Storage Access Framework
     */
    private fun initiateBackup() {
        // Generate a default filename with timestamp
        val timestamp = System.currentTimeMillis()
        val filename = "fintrack_backup_$timestamp.db"
        
        // Launch Storage Access Framework to let user choose destination
        createBackupFile.launch(filename)
    }
    
    /**
     * Perform the actual backup operation
     */
    private fun performBackup(destinationUri: Uri) {
        // Show progress indicator
        Toast.makeText(this, "Backing up database...", Toast.LENGTH_SHORT).show()
        
        // Perform backup in background thread
        CoroutineScope(Dispatchers.IO).launch {
            val success = storageManager.backupDatabase(destinationUri, serverProcessManager)
            
            // Show result on main thread
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Database backup completed successfully",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Database backup failed. Check logs for details.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
```

## Key Points

### 1. Storage Access Framework
The backup uses Android's Storage Access Framework (SAF) to let users choose where to save the backup file. This provides:
- User control over backup location
- Proper permissions handling
- Support for cloud storage providers

### 2. Server Coordination
The backup method automatically:
- Checks if the server is running
- Stops the server before backup
- Waits for server to fully stop (500ms delay)
- Performs the backup
- Restarts the server if it was running before

### 3. Error Handling
The method includes comprehensive error handling:
- Checks if database file exists
- Logs all operations for diagnostics
- Uses try-finally to ensure server restart
- Returns boolean success status

### 4. Thread Safety
The backup operation should be performed on a background thread to avoid blocking the UI:
- Use Kotlin Coroutines with `Dispatchers.IO`
- Or use AsyncTask (deprecated but still works)
- Or use ExecutorService

## Testing the Implementation

### Manual Test Steps

1. **Setup**: Ensure the app is running with a populated database
2. **Initiate Backup**: Tap the backup button in settings
3. **Select Location**: Choose a destination using the file picker
4. **Verify Success**: Check for success toast message
5. **Verify File**: Navigate to the backup location and verify the file exists
6. **Verify Server**: Ensure the app continues to work normally after backup

### Expected Behavior

- Server stops briefly during backup (user may notice a brief pause)
- Backup file is created at the selected location
- Server restarts automatically
- App continues to function normally
- All operations are logged for diagnostics

### Log Messages

The backup process generates the following log messages:

```
[INFO] StorageManager: Starting database backup to content://...
[INFO] StorageManager: Stopping server before backup
[INFO] ServerProcessManager: Stopping FastAPI server
[INFO] ServerProcessManager: Server stopped successfully
[INFO] StorageManager: Database backup completed successfully
[INFO] StorageManager: Restarting server after backup
[INFO] ServerProcessManager: Starting FastAPI server
```

## Error Scenarios

### Database File Not Found
If the database file doesn't exist:
- Method returns `false`
- Error logged: "Database file does not exist: /path/to/fintrack.db"
- User sees failure toast

### Server Restart Failure
If the server fails to restart after backup:
- Backup still succeeds (file is copied)
- Error logged: "Failed to restart server after backup"
- User should manually restart the app

### File Copy Failure
If the file copy operation fails:
- Method returns `false`
- Error logged with exception details
- Server is still restarted (if it was running)

## Integration Checklist

When integrating this functionality into an Activity:

- [ ] Add Storage Access Framework permission to AndroidManifest.xml
- [ ] Register ActivityResultLauncher for CreateDocument
- [ ] Initialize StorageManager and ServerProcessManager
- [ ] Call backup on background thread
- [ ] Show progress indicator during backup
- [ ] Display success/failure notification
- [ ] Handle edge cases (no storage permission, low storage space)
- [ ] Test on different Android versions (API 24+)

## Requirements Satisfied

This implementation satisfies:
- **Requirement 7.3**: "THE Android_App SHALL provide a backup function that exports the SQLite_Database to device storage"
- Server coordination prevents database corruption
- User-friendly Storage Access Framework integration
- Comprehensive error handling and logging
