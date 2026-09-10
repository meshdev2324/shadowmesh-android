package com.shadowmesh.app.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.shadowmesh.app.R
import com.shadowmesh.app.VPNManagerViewModel
import com.shadowmesh.core_vpn.CoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private const val TAG = "QRScannerScreen"

/** What the scanned payload means and how it is normalized.
 *
 * [Activation] — a 25-char Sovereignty Token; dashes/spacing are stripped
 * (the login input's format). [Pairing] — a desktop pairing session token
 * (dashed UUID), optionally wrapped in the `shadowmesh://pair/` URI the
 * desktop renders into its QR; the dashed form is preserved verbatim
 * because the server matches session tokens exactly.
 */
enum class QrScanMode { Activation, Pairing }

/** Extracts the pairing token from a scanned payload (v2 pairing UX). */
fun normalizePairingToken(payload: String): String? {
    val trimmed = payload.trim()
    val token = if (trimmed.startsWith(PAIRING_URI_PREFIX, ignoreCase = true)) {
        trimmed.substring(PAIRING_URI_PREFIX.length)
    } else {
        trimmed
    }
    val cleaned = token.trim()
    return if (cleaned.length >= 8 && cleaned.matches(Regex("[0-9a-fA-F-]+"))) cleaned else null
}

private const val PAIRING_URI_PREFIX = "shadowmesh://pair/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRScannerScreen(
    viewModel: VPNManagerViewModel,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit = {},
    scanMode: QrScanMode = QrScanMode.Activation,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val themeColor = Color(uiState.themeColor)

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    var scannedCode by rememberSaveable { mutableStateOf<String?>(null) }
    var detectedBarcodes by remember { mutableStateOf<List<Barcode>>(emptyList()) }
    var analyzerSize by remember { mutableStateOf<Size?>(null) }
    var showPinPrompt by rememberSaveable { mutableStateOf(value = false) }
    var pairingPin by rememberSaveable { mutableStateOf(value = "") }
    var isFlashEnabled by rememberSaveable { mutableStateOf(value = false) }

    val galleryLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri ->
            uri?.let {
                scope.launch {
                    try {
                        val bitmap =
                            if (android.os.Build.VERSION.SDK_INT < 28) {
                                @Suppress("DEPRECATION")
                                android.provider.MediaStore.Images.Media
                                    .getBitmap(context.contentResolver, it)
                            } else {
                                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, it)
                                android.graphics.ImageDecoder.decodeBitmap(source)
                            }
                        val image = InputImage.fromBitmap(bitmap, 0)
                        val scanner =
                            BarcodeScanning.getClient(
                                BarcodeScannerOptions
                                    .Builder()
                                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                    .build(),
                            )
                        scanner
                            .process(image)
                            .addOnSuccessListener { barcodes ->
                                if (barcodes.isNotEmpty()) {
                                    scannedCode = barcodes[0].rawValue
                                }
                            }
                    } catch (e: Exception) {
                        Log.e(TAG, "Gallery process failed", e)
                    }
                }
            }
        }

    LaunchedEffect(scannedCode) {
        scannedCode?.let { code ->
            when (scanMode) {
                QrScanMode.Activation -> {
                    val normalized = CoreUtils.normalizeActivationCode(code)
                    if (normalized != null) {
                        onResult(normalized)
                        onDismiss()
                    } else {
                        showPinPrompt = true
                    }
                }
                QrScanMode.Pairing -> {
                    val token = normalizePairingToken(code)
                    if (token != null) {
                        onResult(token)
                        onDismiss()
                    } else {
                        showPinPrompt = true
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            QRCameraPreview(
                isFlashEnabled = isFlashEnabled,
                onBarcodesDetected = { barcodes, size ->
                    detectedBarcodes = barcodes
                    analyzerSize = size
                    if (scannedCode == null && (barcodes.isNotEmpty())) {
                        scannedCode = barcodes[0].rawValue
                    }
                },
            )

            SmartScannerOverlay(
                barcodes = detectedBarcodes,
                analyzerSize = analyzerSize,
                themeColor = themeColor,
                modifier = Modifier.fillMaxSize(),
                onSelectCode = { code ->
                    scannedCode = code
                },
            )

            QRScannerOverlay(themeColor = themeColor, modifier = Modifier.fillMaxSize())

            // UI Controls
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 56.dp, start = 24.dp, end = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.pairing_title),
                        color = Color.White,
                        style =
                            MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp,
                            ),
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton(
                            onClick = { isFlashEnabled = !isFlashEnabled },
                            modifier =
                                Modifier
                                    .size(44.dp)
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                        ) {
                            Icon(
                                if (isFlashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = "Flash",
                                tint = if (isFlashEnabled) Color(0xFFF59E0B) else Color.White,
                            )
                        }

                        IconButton(
                            onClick = { galleryLauncher.launch("image/*") },
                            modifier =
                                Modifier
                                    .size(44.dp)
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = "Upload", tint = Color.White)
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier =
                                Modifier
                                    .size(44.dp)
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                    ) {
                        Text(
                            stringResource(R.string.pairing_scan_hint),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.pairing_camera_required), color = Color.White, fontWeight = FontWeight.Bold)
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.pairing_grant_permission))
                    }
                }
            }
        }

        if (showPinPrompt) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(32.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF13131D)),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(64.dp).background(themeColor.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = themeColor, modifier = Modifier.size(32.dp))
                        }

                        Text(
                            stringResource(R.string.pairing_secure_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                        )
                        Text(
                            stringResource(R.string.pairing_secure_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp,
                        )

                        OutlinedTextField(
                            value = pairingPin,
                            onValueChange = { input ->
                                val clean = input.filter { it.isDigit() }
                                if (clean.length <= 6) pairingPin = clean
                            },
                            placeholder = { Text(stringResource(R.string.pairing_pin_placeholder), color = Color.White.copy(alpha = 0.2f)) },
                            singleLine = true,
                            textStyle =
                                androidx.compose.ui.text.TextStyle(
                                    color = Color.White,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black,
                                    textAlign = TextAlign.Center,
                                    letterSpacing = 8.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                ),
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = themeColor,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedContainerColor = Color.White.copy(alpha = 0.03f),
                                    unfocusedContainerColor = Color.White.copy(alpha = 0.03f),
                                ),
                            modifier = Modifier.fillMaxWidth().height(80.dp),
                            shape = RoundedCornerShape(16.dp),
                        )

                        uiState.errorMessage?.let {
                            Text(it, color = Color(0xFFEF4444), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TextButton(
                                onClick = {
                                    showPinPrompt = false
                                    scannedCode = null
                                    pairingPin = ""
                                },
                                modifier = Modifier.weight(1f).height(56.dp),
                            ) {
                                Text(stringResource(R.string.cancel), color = Color.Gray, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = {
                                    scannedCode?.let { viewModel.pairWithDesktop(it, pairingPin) }
                                },
                                modifier = Modifier.weight(1f).height(56.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = themeColor),
                                shape = RoundedCornerShape(16.dp),
                                enabled = pairingPin.length == 6 && !uiState.isActivating,
                            ) {
                                if (uiState.isActivating) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 3.dp)
                                } else {
                                    Text(stringResource(R.string.pairing_button_pair), fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                }
            }
        }

        LaunchedEffect(uiState.isActivating) {
            if (!uiState.isActivating && uiState.errorMessage == null && showPinPrompt && pairingPin.length == 6) {
                onDismiss()
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun QRCameraPreview(
    isFlashEnabled: Boolean,
    onBarcodesDetected: (List<Barcode>, Size) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val currentFlashEnabled by rememberUpdatedState(isFlashEnabled)

    val previewView =
        remember {
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            }
        }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    LaunchedEffect(Unit) {
        val cameraProvider =
            withContext(Dispatchers.IO) {
                try {
                    cameraProviderFuture.get()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get camera provider", e)
                    null
                }
            } ?: return@LaunchedEffect

        val resolutionSelector =
            ResolutionSelector
                .Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()

        val preview =
            Preview
                .Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

        val imageAnalysis =
            ImageAnalysis
                .Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

        val scanner =
            BarcodeScanning.getClient(
                BarcodeScannerOptions
                    .Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build(),
            )

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            val rotation = imageProxy.imageInfo.rotationDegrees
            val size =
                if (rotation == 90 || rotation == 270) {
                    Size(imageProxy.height, imageProxy.width)
                } else {
                    Size(imageProxy.width, imageProxy.height)
                }

            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, rotation)
                scanner
                    .process(image)
                    .addOnSuccessListener { barcodes ->
                        onBarcodesDetected(barcodes, size)
                    }.addOnCompleteListener { imageProxy.close() }
            } else {
                imageProxy.close()
            }
        }

        try {
            cameraProvider.unbindAll()
            val camera =
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )

            launch {
                snapshotFlow { currentFlashEnabled }.collect { enabled ->
                    if (camera.cameraInfo.hasFlashUnit()) {
                        try {
                            camera.cameraControl.enableTorch(enabled)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to toggle torch", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Binding failed", e)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
fun SmartScannerOverlay(
    barcodes: List<Barcode>,
    analyzerSize: Size?,
    themeColor: Color,
    modifier: Modifier,
    onSelectCode: (String) -> Unit = {},
) {
    if (analyzerSize == null) return

    val infiniteTransition = rememberInfiniteTransition(label = "targeting")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1000, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "alpha",
    )

    Canvas(
        modifier =
            modifier.pointerInput(barcodes, analyzerSize) {
                detectTapGestures { offset ->
                    val canvasWidth = size.width.toFloat()
                    val canvasHeight = size.height.toFloat()
                    val analyzerWidth = analyzerSize.width.toFloat()
                    val analyzerHeight = analyzerSize.height.toFloat()

                    val scale = kotlin.math.max(canvasWidth / analyzerWidth, canvasHeight / analyzerHeight)
                    val offsetX = (canvasWidth - analyzerWidth * scale) / 2
                    val offsetY = (canvasHeight - analyzerHeight * scale) / 2

                    for (barcode in barcodes) {
                        barcode.boundingBox?.let { box ->
                            val left = box.left * scale + offsetX
                            val top = box.top * scale + offsetY
                            val right = box.right * scale + offsetX
                            val bottom = box.bottom * scale + offsetY

                            if (offset.x in left..right && offset.y in top..bottom) {
                                barcode.rawValue?.let { onSelectCode(it) }
                            }
                        }
                    }
                }
            },
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val analyzerWidth = analyzerSize.width.toFloat()
        val analyzerHeight = analyzerSize.height.toFloat()

        val scale = kotlin.math.max(canvasWidth / analyzerWidth, canvasHeight / analyzerHeight)
        val offsetX = (canvasWidth.toFloat() - analyzerWidth * scale) / 2
        val offsetY = (canvasHeight.toFloat() - analyzerHeight * scale) / 2

        for (barcode in barcodes) {
            barcode.boundingBox?.let { box ->
                val left = box.left * scale + offsetX
                val top = box.top * scale + offsetY
                val right = box.right * scale + offsetX
                val bottom = box.bottom * scale + offsetY

                val cornerLen = 20.dp.toPx()
                val radius = 12.dp.toPx()
                val color = themeColor

                val path =
                    Path().apply {
                        moveTo(left, top + cornerLen)
                        lineTo(left, top + radius)
                        arcTo(
                            androidx.compose.ui.geometry
                                .Rect(left, top, left + 2 * radius, top + 2 * radius),
                            180f,
                            90f,
                            false,
                        )
                        lineTo(left + cornerLen, top)

                        moveTo(right - cornerLen, top)
                        lineTo(right - radius, top)
                        arcTo(
                            androidx.compose.ui.geometry
                                .Rect(right - 2 * radius, top, right, top + 2 * radius),
                            270f,
                            90f,
                            false,
                        )
                        lineTo(right, top + cornerLen)

                        moveTo(right, bottom - cornerLen)
                        lineTo(right, bottom - radius)
                        arcTo(
                            androidx.compose.ui.geometry
                                .Rect(right - 2 * radius, bottom - 2 * radius, right, bottom),
                            0f,
                            90f,
                            false,
                        )
                        lineTo(right - cornerLen, bottom)

                        moveTo(left + cornerLen, bottom)
                        lineTo(left + radius, bottom)
                        arcTo(
                            androidx.compose.ui.geometry
                                .Rect(left, bottom - 2 * radius, left + 2 * radius, bottom),
                            90f,
                            90f,
                            false,
                        )
                        lineTo(left, bottom - cornerLen)
                    }

                drawPath(
                    path = path,
                    color = color.copy(alpha = alpha),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )

                drawRoundRect(
                    color = color.copy(alpha = 0.1f),
                    topLeft = Offset(left, top),
                    size =
                        androidx.compose.ui.geometry
                            .Size(right - left, bottom - top),
                    cornerRadius = CornerRadius(radius),
                )
            }
        }
    }
}

@Composable
fun QRScannerOverlay(
    themeColor: Color,
    modifier: Modifier,
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val scanSize = width * 0.7f
        val left = (width - scanSize) / 2
        val top = (height - scanSize) / 2

        val radius = 40.dp.toPx()
        val rect =
            androidx.compose.ui.geometry
                .Rect(left, top, left + scanSize, top + scanSize)

        val combinedPath =
            Path().apply {
                addRect(
                    androidx.compose.ui.geometry
                        .Rect(0f, 0f, width, height),
                )
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        rect = rect,
                        cornerRadius = CornerRadius(radius),
                    ),
                )
                fillType = PathFillType.EvenOdd
            }

        drawPath(
            path = combinedPath,
            color = Color.Black.copy(alpha = 0.75f),
        )

        drawRoundRect(
            color = themeColor.copy(alpha = 0.5f),
            topLeft = Offset(left, top),
            size =
                androidx.compose.ui.geometry
                    .Size(scanSize, scanSize),
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = 2.dp.toPx()),
        )

        val accentLen = 48.dp.toPx()
        val accentWidth = 4.dp.toPx()
        val accentColor = Color.White

        val cornerPath =
            Path().apply {
                moveTo(left, top + accentLen)
                lineTo(left, top + radius)
                arcTo(
                    androidx.compose.ui.geometry
                        .Rect(left, top, left + 2 * radius, top + 2 * radius),
                    180f,
                    90f,
                    false,
                )
                lineTo(left + accentLen, top)

                moveTo(left + scanSize - accentLen, top)
                lineTo(left + scanSize - radius, top)
                arcTo(
                    androidx.compose.ui.geometry
                        .Rect(left + scanSize - 2 * radius, top, left + scanSize, top + 2 * radius),
                    270f,
                    90f,
                    false,
                )
                lineTo(left + scanSize, top + accentLen)

                moveTo(left + scanSize, top + scanSize - accentLen)
                lineTo(left + scanSize, top + scanSize - radius)
                arcTo(
                    androidx.compose.ui.geometry.Rect(
                        left + scanSize - 2 * radius,
                        top + scanSize - 2 * radius,
                        left + scanSize,
                        top + scanSize,
                    ),
                    0f,
                    90f,
                    false,
                )
                lineTo(left + scanSize - accentLen, top + scanSize)

                moveTo(left + accentLen, top + scanSize)
                lineTo(left + radius, top + scanSize)
                arcTo(
                    androidx.compose.ui.geometry
                        .Rect(left, top + scanSize - 2 * radius, left + 2 * radius, top + scanSize),
                    90f,
                    90f,
                    false,
                )
                lineTo(left, top + scanSize - accentLen)
            }

        drawPath(
            path = cornerPath,
            color = accentColor,
            style = Stroke(width = accentWidth, cap = StrokeCap.Round),
        )
    }
}
