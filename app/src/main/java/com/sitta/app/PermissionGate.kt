package com.sitta.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val requiredPermissions = remember { buildRequiredPermissions() }
    var allGranted by remember { mutableStateOf(false) }
    var requestedOnce by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        allGranted = requiredPermissions.all { perm -> result[perm] == true }
    }

    fun refreshPermissionState() {
        allGranted = requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(Unit) {
        refreshPermissionState()
        if (!allGranted && !requestedOnce) {
            requestedOnce = true
            launcher.launch(requiredPermissions)
        }
    }

    if (allGranted) {
        content()
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101214)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Permissions Required",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Camera access is required for capture. Media access enables gallery selection.",
                    color = Color(0xFF9AA6B2),
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.size(16.dp))
                Button(
                    onClick = {
                        launcher.launch(requiredPermissions)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14B8A6)),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(text = "Grant Permissions", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

private fun buildRequiredPermissions(): Array<String> {
    val permissions = mutableListOf(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= 33) {
        permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return permissions.toTypedArray()
}
