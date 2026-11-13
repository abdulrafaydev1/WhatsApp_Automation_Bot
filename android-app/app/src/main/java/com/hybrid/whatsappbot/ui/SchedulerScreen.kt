package com.hybrid.whatsappbot.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.hybrid.whatsappbot.data.ScheduledTaskEntity
import com.hybrid.whatsappbot.data.TaskStatus
import com.hybrid.whatsappbot.util.SystemPermissionHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulerScreen(viewModel: SchedulerViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val tasks by viewModel.tasksState.collectAsState()
    val serverUiState by viewModel.serverUiState.collectAsState()

    // Live System Readiness states refreshed automatically on ON_RESUME
    var isAccessibilityEnabled by remember {
        mutableStateOf(SystemPermissionHelper.isAccessibilityServiceEnabled(context))
    }
    var canScheduleExactAlarms by remember {
        mutableStateOf(SystemPermissionHelper.canScheduleExactAlarms(context))
    }
    var isBatteryOptimizationIgnored by remember {
        mutableStateOf(SystemPermissionHelper.isIgnoringBatteryOptimizations(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = SystemPermissionHelper.isAccessibilityServiceEnabled(context)
                canScheduleExactAlarms = SystemPermissionHelper.canScheduleExactAlarms(context)
                isBatteryOptimizationIgnored = SystemPermissionHelper.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Scheduler Form State
    var selectedImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var captionText by rememberSaveable { mutableStateOf("") }
    var targetGroupName by rememberSaveable { mutableStateOf("") }
    var targetGroupJid by rememberSaveable { mutableStateOf<String?>(null) }
    var scheduledTimeMillis by rememberSaveable {
        mutableLongStateOf(System.currentTimeMillis() + 5 * 60 * 1000L)
    }

    var backendUrlInput by rememberSaveable(serverUiState.backendUrl) {
        mutableStateOf(serverUiState.backendUrl)
    }
    var showGroupPickerDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Hybrid WhatsApp Scheduler",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "Dual-Engine: UI Accessibility + Node.js Baileys Bot",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { viewModel.refreshServerStatus() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Server Status",
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. SYSTEM READINESS CARD
            item {
                SystemReadinessCard(
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    canScheduleExactAlarms = canScheduleExactAlarms,
                    isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                    serverUiState = serverUiState,
                    backendUrlInput = backendUrlInput,
                    onBackendUrlChange = { backendUrlInput = it },
                    onSaveBackendUrl = { viewModel.updateBackendUrl(backendUrlInput) },
                    onOpenAccessibility = { SystemPermissionHelper.openAccessibilitySettings(context) },
                    onOpenExactAlarm = { SystemPermissionHelper.openExactAlarmSettings(context) },
                    onRequestBatteryExemption = {
                        SystemPermissionHelper.requestIgnoreBatteryOptimizations(context)
                    },
                    onShowQrModal = { showQrDialog = true }
                )
            }

            // 2. SCHEDULER FORM CARD
            item {
                SchedulerFormCard(
                    selectedImageUri = selectedImageUri,
                    captionText = captionText,
                    onCaptionChange = { captionText = it },
                    targetGroupName = targetGroupName,
                    targetGroupJid = targetGroupJid,
                    onTargetGroupNameChange = {
                        targetGroupName = it
                        targetGroupJid = null
                    },
                    scheduledTimeMillis = scheduledTimeMillis,
                    onScheduledTimeChange = { scheduledTimeMillis = it },
                    onPickImage = {
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onSyncGroupsClick = {
                        viewModel.syncGroupsFromServer { groups ->
                            if (groups.isNotEmpty()) {
                                showGroupPickerDialog = true
                            } else {
                                Toast.makeText(
                                    context,
                                    "No groups returned. Ensure Node.js Bot is CONNECTED.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    onScheduleSubmit = {
                        val imageUri = selectedImageUri
                        if (imageUri == null) {
                            Toast.makeText(context, "Please select an image first.", Toast.LENGTH_SHORT).show()
                            return@SchedulerFormCard
                        }
                        if (targetGroupName.isBlank()) {
                            Toast.makeText(context, "Please enter or select a Target Group Name.", Toast.LENGTH_SHORT).show()
                            return@SchedulerFormCard
                        }
                        viewModel.scheduleTask(
                            sourceImageUri = imageUri,
                            caption = captionText,
                            targetGroupName = targetGroupName,
                            targetGroupJid = targetGroupJid,
                            scheduledTimeMillis = scheduledTimeMillis
                        ) { success, message ->
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            if (success) {
                                selectedImageUri = null
                                captionText = ""
                            }
                        }
                    }
                )
            }

            // 3. TASK QUEUE HEADER
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Scheduled & Executed Tasks (${tasks.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (tasks.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No scheduled tasks yet. Pick an image and schedule your first hybrid WhatsApp post above!",
                                color = Color.Gray
                            )
                        }
                    }
                }
            } else {
                items(items = tasks, key = { it.taskId }) { task ->
                    TaskQueueItemCard(
                        task = task,
                        onTestNow = {
                            Toast.makeText(
                                context,
                                "Triggering task now via Smart State Detector...",
                                Toast.LENGTH_SHORT
                            ).show()
                            viewModel.testTaskNow(task)
                        },
                        onDelete = { viewModel.deleteTask(task) }
                    )
                }
            }
        }
    }

    // Group Picker Modal Dialog
    if (showGroupPickerDialog) {
        AlertDialog(
            onDismissRequest = { showGroupPickerDialog = false },
            title = { Text("Select Participating WhatsApp Group") },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(serverUiState.availableGroups, key = { it.id }) { group ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    targetGroupName = group.subject
                                    targetGroupJid = group.id
                                    showGroupPickerDialog = false
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFF0F4F8)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = group.subject,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "${group.id} • ${group.participantsCount} members",
                                    fontSize = 12.sp,
                                    color = Color.DarkGray
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGroupPickerDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // QR Code Viewer Modal Dialog for Linking Node.js Background Bot
    val qrCodeBase64 = serverUiState.qrCodeBase64
    if (showQrDialog && !qrCodeBase64.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("Link Node.js Background Bot") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val bitmap = remember(qrCodeBase64) {
                        decodeDataUrlBase64ToBitmap(qrCodeBase64)
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "WhatsApp QR Code",
                            modifier = Modifier
                                .size(240.dp)
                                .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        )
                    } else {
                        Text("Unable to decode QR image.")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Open WhatsApp -> Linked Devices -> Link a Device to enable silent background delivery while your phone is locked.",
                        fontSize = 13.sp,
                        color = Color.DarkGray
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text("Done")
                }
            }
        )
    }
}

@Composable
private fun SystemReadinessCard(
    isAccessibilityEnabled: Boolean,
    canScheduleExactAlarms: Boolean,
    isBatteryOptimizationIgnored: Boolean,
    serverUiState: ServerUiState,
    backendUrlInput: String,
    onBackendUrlChange: (String) -> Unit,
    onSaveBackendUrl: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenExactAlarm: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onShowQrModal: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "System Readiness & Hybrid Engine Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            ReadinessRow(
                title = "1. UI Accessibility Service (Engine 1)",
                subtitle = if (isAccessibilityEnabled) "Active (20s Window Lock Ready)" else "Disabled in Android Settings",
                isReady = isAccessibilityEnabled,
                actionLabel = if (isAccessibilityEnabled) "Settings" else "Enable",
                onActionClick = onOpenAccessibility
            )

            ReadinessRow(
                title = "2. Exact Alarm Permission",
                subtitle = if (canScheduleExactAlarms) "Granted (setExactAndAllowWhileIdle)" else "Required for exact wakeup",
                isReady = canScheduleExactAlarms,
                actionLabel = if (canScheduleExactAlarms) "Verified" else "Grant",
                onActionClick = onOpenExactAlarm
            )

            ReadinessRow(
                title = "3. Battery Optimization Exemption",
                subtitle = if (isBatteryOptimizationIgnored) "Unrestricted background execution" else "Restricted by Doze mode",
                isReady = isBatteryOptimizationIgnored,
                actionLabel = if (isBatteryOptimizationIgnored) "Verified" else "Whitelist",
                onActionClick = onRequestBatteryExemption
            )

            val isServerConnected = serverUiState.connectionStatus == "CONNECTED"
            ReadinessRow(
                title = "4. Node.js Baileys Server (Engine 2)",
                subtitle = when (serverUiState.connectionStatus) {
                    "CONNECTED" -> "CONNECTED (${serverUiState.linkedUserName ?: "Linked"})"
                    "QR_READY" -> "QR_READY (Scan QR to link WhatsApp)"
                    else -> "Status: ${serverUiState.connectionStatus}"
                },
                isReady = isServerConnected,
                actionLabel = if (serverUiState.connectionStatus == "QR_READY") "Scan QR" else "Ping",
                onActionClick = {
                    if (serverUiState.connectionStatus == "QR_READY") {
                        onShowQrModal()
                    } else {
                        onSaveBackendUrl()
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = backendUrlInput,
                    onValueChange = onBackendUrlChange,
                    label = { Text("Node.js Server URL (LAN IP)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = onSaveBackendUrl) {
                    if (serverUiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Text("Save")
                    }
                }
            }

            val bannerMessage = serverUiState.statusBannerMessage
            if (!bannerMessage.isNullOrBlank()) {
                Text(
                    text = bannerMessage,
                    fontSize = 12.sp,
                    color = if (isServerConnected) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                )
            }
        }
    }
}

@Composable
private fun ReadinessRow(
    title: String,
    subtitle: String,
    isReady: Boolean,
    actionLabel: String,
    onActionClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (isReady) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(text = subtitle, fontSize = 12.sp, color = Color.DarkGray)
            }
        }
        OutlinedButton(
            onClick = onActionClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(text = actionLabel, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SchedulerFormCard(
    selectedImageUri: Uri?,
    captionText: String,
    onCaptionChange: (String) -> Unit,
    targetGroupName: String,
    targetGroupJid: String?,
    onTargetGroupNameChange: (String) -> Unit,
    scheduledTimeMillis: Long,
    onScheduledTimeChange: (Long) -> Unit,
    onPickImage: () -> Unit,
    onSyncGroupsClick: () -> Unit,
    onScheduleSubmit: () -> Unit
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("EEE, dd MMM yyyy • hh:mm a", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Schedule New WhatsApp Group Post",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Image Picker + Thumbnail Preview
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFECEFF1))
                        .border(1.dp, Color(0xFFCFD8DC), RoundedCornerShape(10.dp))
                        .clickable { onPickImage() },
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedImageUri != null) {
                        AsyncImage(
                            model = selectedImageUri,
                            contentDescription = "Selected Image",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = "Pick Image",
                            tint = Color.DarkGray,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Button(
                        onClick = onPickImage,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (selectedImageUri == null) "Select Image from Gallery" else "Change Image")
                    }
                    Text(
                        text = "Image is immediately copied to private internal storage so its URI never expires.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }

            // Target Group Name + Sync from Node.js Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = targetGroupName,
                    onValueChange = onTargetGroupNameChange,
                    label = { Text("Target WhatsApp Group Name") },
                    placeholder = { Text("e.g. Marketing Updates") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onSyncGroupsClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = "Sync Groups",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sync", fontSize = 12.sp)
                }
            }

            if (!targetGroupJid.isNullOrBlank()) {
                Text(
                    text = "Linked Group JID: $targetGroupJid",
                    fontSize = 11.sp,
                    color = Color(0xFF075E54),
                    fontWeight = FontWeight.Medium
                )
            }

            // Multi-line Caption Input
            OutlinedTextField(
                value = captionText,
                onValueChange = onCaptionChange,
                label = { Text("Message Caption") },
                placeholder = { Text("Write your caption here...") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )

            // Exact Date & Time Picker Button
            OutlinedButton(
                onClick = {
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = maxOf(scheduledTimeMillis, System.currentTimeMillis())
                    }
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            TimePickerDialog(
                                context,
                                { _, hourOfDay, minute ->
                                    val chosenCal = Calendar.getInstance().apply {
                                        set(Calendar.YEAR, year)
                                        set(Calendar.MONTH, month)
                                        set(Calendar.DAY_OF_MONTH, dayOfMonth)
                                        set(Calendar.HOUR_OF_DAY, hourOfDay)
                                        set(Calendar.MINUTE, minute)
                                        set(Calendar.SECOND, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }
                                    onScheduledTimeChange(chosenCal.timeInMillis)
                                },
                                cal.get(Calendar.HOUR_OF_DAY),
                                cal.get(Calendar.MINUTE),
                                false
                            ).show()
                        },
                        cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH),
                        cal.get(Calendar.DAY_OF_MONTH)
                    ).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Default.Schedule, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Trigger Time: ${dateFormat.format(Date(scheduledTimeMillis))}")
            }

            // Submit Schedule Task Button
            Button(
                onClick = onScheduleSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = "Schedule Hybrid WhatsApp Task",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}

@Composable
private fun TaskQueueItemCard(
    task: ScheduledTaskEntity,
    onTestNow: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()) }

    val (badgeText, badgeColor) = when (task.status) {
        TaskStatus.PENDING -> "PENDING" to Color(0xFFF57C00)
        TaskStatus.RUNNING_UI -> "RUNNING UI BOT..." to Color(0xFF1976D2)
        TaskStatus.RUNNING_BACKGROUND -> "RUNNING BG BOT..." to Color(0xFF0288D1)
        TaskStatus.COMPLETED_UI -> "SENT VIA UI BOT" to Color(0xFF2E7D32)
        TaskStatus.COMPLETED_BACKGROUND -> "SENT VIA BACKGROUND BOT" to Color(0xFF00796B)
        TaskStatus.FAILED -> "FAILED" to Color(0xFFC62828)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = task.targetGroupName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = badgeColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = File(task.imageLocalPath),
                    contentDescription = "Scheduled Thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.LightGray)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.caption.ifBlank { "(No text caption)" },
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Trigger: ${dateFormat.format(Date(task.scheduledTimeMillis))}",
                        fontSize = 11.sp,
                        color = Color.DarkGray
                    )
                    Text(
                        text = task.executionLog,
                        fontSize = 11.sp,
                        color = Color.Gray,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onTestNow,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Test Now", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Task",
                        tint = Color(0xFFD32F2F)
                    )
                }
            }
        }
    }
}

private fun decodeDataUrlBase64ToBitmap(dataUrl: String): android.graphics.Bitmap? {
    return try {
        val base64Part = dataUrl.substringAfter("base64,", dataUrl)
        val decodedBytes = Base64.decode(base64Part, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    } catch (_: Exception) {
        null
    }
}
