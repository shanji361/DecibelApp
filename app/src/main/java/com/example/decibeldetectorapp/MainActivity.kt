package com.example.decibeldetectorapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.decibeldetectorapp.ui.theme.DecibelDetectorAppTheme
import kotlinx.coroutines.*
import kotlin.math.log10
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private var permissionGranted = mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        permissionGranted.value = isGranted
        Log.d("Permission", "Microphone permission granted: $isGranted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check permission
        permissionGranted.value = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        Log.d("Permission", "Initial permission status: ${permissionGranted.value}")

        setContent {
            DecibelDetectorAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DecibelMeterScreen(
                        permissionGranted = permissionGranted.value,
                        onRequestPermission = {
                            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DecibelMeterScreen(
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit
) {
    var isRecording by remember { mutableStateOf(false) }
    var decibels by remember { mutableStateOf(0f) }

    var alertShown by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var recordingJob by remember { mutableStateOf<Job?>(null) }

    val THRESHOLD_DB = 75f
    val TAG = "DecibelMeter"

    // Audio recording parameters
    val sampleRate = 44100
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    fun calculateDecibels(buffer: ShortArray): Float {
        var sum = 0.0
        for (sample in buffer) {
            sum += (sample * sample).toDouble()
        }
        val rms = sqrt(sum / buffer.size)

        // Avoid log of zero
        if (rms < 1.0) return 0f

        // Convert to decibels with proper reference
        val db = 20 * log10(rms / 1.0) // Reference to 1.0 for RMS

        // Normalize to a reasonable 0-100 dB range for display
        val normalizedDb = (db + 10).toFloat().coerceIn(0f, 100f)

        Log.d(TAG, "RMS: $rms, dB: $db, Normalized: $normalizedDb")
        return normalizedDb
    }

    fun startRecording() {
        errorMessage = ""
        recordingJob?.cancel()

        recordingJob = scope.launch(Dispatchers.IO) {
            var audioRecord: AudioRecord? = null
            try {
                Log.d(TAG, "Initializing AudioRecord with buffer size: $bufferSize")

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "AudioRecord initialization failed"
                        isRecording = false
                    }
                    Log.e(TAG, "AudioRecord not initialized properly")
                    return@launch
                }

                audioRecord.startRecording()
                Log.d(TAG, "Recording started")

                val buffer = ShortArray(bufferSize / 2)
                var readCount = 0

                while (isActive && isRecording) {
                    val readResult = audioRecord.read(buffer, 0, buffer.size)

                    if (readResult > 0) {
                        readCount++
                        val db = calculateDecibels(buffer)

                        withContext(Dispatchers.Main) {
                            decibels = db

                            alertShown = db > THRESHOLD_DB
                        }

                        if (readCount % 10 == 0) {
                            Log.d(TAG, "Read $readResult samples, dB: $db")
                        }
                    } else {
                        Log.e(TAG, "Error reading audio: $readResult")
                    }

                    delay(50) // Update every 50ms
                }

            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception: ${e.message}")
                withContext(Dispatchers.Main) {
                    errorMessage = "Microphone permission denied"
                    isRecording = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in recording: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    errorMessage = "Error: ${e.message}"
                    isRecording = false
                }
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                    Log.d(TAG, "AudioRecord stopped and released")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
                }
            }
        }
    }

    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        Log.d(TAG, "Recording stopped by user")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!permissionGranted) {
            Text(
                text = "Microphone Permission Required",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "This app needs access to your microphone to measure sound levels.",
                fontSize = 14.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestPermission) {
                Text("Grant Permission")
            }
        } else {
            Text(
                text = "Decibel Level Detector",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Current decibel display
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (alertShown) Color(0xFFFFCDD2) else Color(0xFFFDFDFD)
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "${decibels.toInt()} dB",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (alertShown) Color.Red else Color(0xFF725BF8)
                    )
                    Text(
                        text = if (isRecording) "Current Level" else "Not Recording",
                        fontSize = 16.sp,
                        color = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // sound progress bar
            Column(modifier = Modifier.fillMaxWidth()) {

                Spacer(modifier = Modifier.height(8.dp))

                LinearProgressIndicator(
                progress = { (decibels / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                                        .fillMaxWidth()
                                        .height(24.dp),
                color = when {
                                        decibels < 30 -> Color.Green
                                        decibels < 60 -> Color.Yellow
                                        decibels < THRESHOLD_DB -> Color(0xFFFFA500)
                                        else -> Color.Red
                                    },
                trackColor = ProgressIndicatorDefaults.linearTrackColor,
                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0 dB", fontSize = 12.sp, color = Color.Gray)
                    Text("100 dB", fontSize = 12.sp, color = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))



            Spacer(modifier = Modifier.height(24.dp))



            // Alert message
            if (alertShown && isRecording) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFF5252)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "WARNING: Noise level exceeds ${THRESHOLD_DB.toInt()} dB!",
                        modifier = Modifier.padding(16.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Start/Stop button
            Button(
                onClick = {
                    if (isRecording) {
                        stopRecording()
                    } else {
                        isRecording = true

                        decibels = 0f
                        startRecording()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color.Red else Color(0xFF7155EC)
                )
            ) {
                Text(
                    text = if (isRecording) "Stop Recording" else "Start Recording",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }


}
