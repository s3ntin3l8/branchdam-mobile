package com.branchdam.mobile.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.branchdam.mobile.OnboardingAccess
import com.branchdam.mobile.PairingResult
import com.branchdam.mobile.R
import kotlinx.coroutines.launch
import com.branchdam.mobile.ui.theme.BranchDamTheme

@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    onRequestPermissions: () -> Unit,
    access: OnboardingAccess = OnboardingAccess.PENDING,
    onSelectMorePhotos: () -> Unit = {},
    pairingResult: PairingResult = PairingResult.Idle,
    onScanQr: () -> Unit = {},
    onEnterManually: () -> Unit = {},
    onRetryPairing: () -> Unit = {},
) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()

    // Full access granted from the Access page: move on to the next page.
    LaunchedEffect(access) {
        if (access == OnboardingAccess.GRANTED && pagerState.currentPage == 2) {
            pagerState.animateScrollToPage(3)
        }
    }

    Scaffold(
        bottomBar = {
            OnboardingBottomBar(
                pagerState = pagerState,
                onNext = {
                    if (pagerState.currentPage < 3) {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    } else {
                        onOnboardingComplete()
                    }
                },
                onSkip = onOnboardingComplete
            )
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(padding).fillMaxSize()
        ) { page ->
            when (page) {
                0 -> WelcomePage()
                1 -> SecurityPage()
                2 -> PermissionsRationalePage(access, onRequestPermissions, onSelectMorePhotos)
                3 -> PairPage(pairingResult, onScanQr, onEnterManually, onRetryPairing, onOnboardingComplete)
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    OnboardingPageContent(
        title = stringResource(R.string.onboarding_welcome_title),
        description = stringResource(R.string.onboarding_welcome_body),
        icon = Icons.Default.CloudUpload
    )
}

@Composable
private fun SecurityPage() {
    OnboardingPageContent(
        title = stringResource(R.string.onboarding_security_title),
        description = stringResource(R.string.onboarding_security_body),
        icon = Icons.Default.Lock
    )
}

@Composable
private fun PermissionsRationalePage(
    access: OnboardingAccess,
    onRequestPermissions: () -> Unit,
    onSelectMorePhotos: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.onboarding_access_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_access_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        when (access) {
            OnboardingAccess.PENDING -> Button(
                onClick = onRequestPermissions,
                shape = MaterialTheme.shapes.large
            ) {
                Text(stringResource(R.string.onboarding_grant_access))
            }
            OnboardingAccess.GRANTED -> Text(
                stringResource(R.string.onboarding_access_granted),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            OnboardingAccess.LIMITED -> {
                Text(
                    stringResource(R.string.onboarding_access_limited),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onSelectMorePhotos, shape = MaterialTheme.shapes.large) {
                    Text(stringResource(R.string.onboarding_select_photos))
                }
            }
            OnboardingAccess.DENIED -> {
                Text(
                    stringResource(R.string.onboarding_access_denied),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(stringResource(R.string.onboarding_open_settings))
                }
            }
        }
    }
}

@Composable
private fun PairPage(
    result: PairingResult,
    onScanQr: () -> Unit,
    onEnterManually: () -> Unit,
    onRetry: () -> Unit,
    onComplete: () -> Unit,
) {
    OnboardingPageContent(
        title = stringResource(R.string.onboarding_pair_title),
        description = stringResource(R.string.onboarding_pair_body),
        icon = Icons.Filled.QrCodeScanner,
        cta = {
            AnimatedContent(
                targetState = result,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                contentKey = { it::class },
                label = "pairing-result",
            ) { state ->
                Column(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (state) {
                        is PairingResult.Idle -> PairActions(onScanQr, onEnterManually, onComplete)
                        is PairingResult.Connecting -> {
                            CircularProgressIndicator()
                            Text(
                                stringResource(R.string.connection_connecting),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        is PairingResult.Connected -> {
                            PairStatus(
                                icon = Icons.Filled.CheckCircle,
                                tint = MaterialTheme.colorScheme.primary,
                                text = stringResource(R.string.onboarding_pair_connected, state.serverUrl),
                                textColor = MaterialTheme.colorScheme.onSurface,
                            )
                            Button(
                                onClick = onComplete,
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                shape = MaterialTheme.shapes.large,
                            ) { Text(stringResource(R.string.onboarding_get_started)) }
                        }
                        is PairingResult.Failed -> {
                            PairStatus(
                                icon = Icons.Filled.ErrorOutline,
                                tint = MaterialTheme.colorScheme.error,
                                text = stringResource(R.string.onboarding_pair_failed, state.message),
                                textColor = MaterialTheme.colorScheme.error,
                            )
                            Button(
                                onClick = onRetry,
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                shape = MaterialTheme.shapes.large,
                            ) { Text(stringResource(R.string.onboarding_pair_retry)) }
                            PairActions(onScanQr, onEnterManually, onComplete, scanIsPrimary = false)
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun PairActions(
    onScanQr: () -> Unit,
    onEnterManually: () -> Unit,
    onLater: () -> Unit,
    scanIsPrimary: Boolean = true,
) {
    val scanContent: @Composable RowScope.() -> Unit = {
        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.onboarding_pair_scan))
    }
    val scanModifier = Modifier.fillMaxWidth().height(56.dp)
    if (scanIsPrimary) {
        Button(onClick = onScanQr, modifier = scanModifier, shape = MaterialTheme.shapes.large, content = scanContent)
    } else {
        OutlinedButton(onClick = onScanQr, modifier = scanModifier, shape = MaterialTheme.shapes.large, content = scanContent)
    }
    OutlinedButton(
        onClick = onEnterManually,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.onboarding_pair_manual))
    }
    TextButton(onClick = onLater) { Text(stringResource(R.string.onboarding_pair_later)) }
}

/** Icon plus text, so the outcome is not conveyed by colour alone; announced by TalkBack. */
@Composable
private fun PairStatus(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    text: String,
    textColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = textColor)
    }
}

@Composable
private fun OnboardingPageContent(
    title: String,
    description: String,
    icon: ImageVector,
    cta: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.size(140.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.height(64.dp))
        Text(
            title,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Text(
            description,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (cta != null) {
            Spacer(Modifier.height(64.dp))
            cta()
        }
    }
}

@Composable
private fun OnboardingBottomBar(
    pagerState: androidx.compose.foundation.pager.PagerState,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onSkip) {
            Text(stringResource(R.string.onboarding_skip))
        }

        val pagePosition = stringResource(R.string.onboarding_page_position, pagerState.currentPage + 1, 4)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.clearAndSetSemantics { contentDescription = pagePosition },
        ) {
            repeat(4) { index ->
                val color = if (pagerState.currentPage == index)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
        }

        IconButton(
            onClick = onNext,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.onboarding_next))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun OnboardingPreview() {
    BranchDamTheme {
        OnboardingScreen(onOnboardingComplete = {}, onRequestPermissions = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun PairPageIdlePreview() = PairPagePreview(PairingResult.Idle)

@Preview(showBackground = true)
@Composable
private fun PairPageConnectingPreview() = PairPagePreview(PairingResult.Connecting)

@Preview(showBackground = true)
@Composable
private fun PairPageConnectedPreview() = PairPagePreview(PairingResult.Connected("https://dam.example.com"))

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PairPageFailedDarkPreview() = PairPagePreview(PairingResult.Failed("HTTP 401"))

@Composable
private fun PairPagePreview(result: PairingResult) {
    BranchDamTheme { PairPage(result, {}, {}, {}, {}) }
}
