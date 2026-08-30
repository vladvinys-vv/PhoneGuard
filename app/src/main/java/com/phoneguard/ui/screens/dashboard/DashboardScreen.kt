package com.phoneguard.ui.screens.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneguard.R
import com.phoneguard.ui.theme.*
import com.phoneguard.ui.screens.dashboard.DashboardViewModel.FullScanSummary
import kotlinx.coroutines.flow.first

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToAntiTheft: () -> Unit,
    onNavigateToCallBlocker: () -> Unit,
    onNavigateToPrivacyScanner: () -> Unit,
    onNavigateToSpywareCheck: () -> Unit,
    onNavigateToFullScan: () -> Unit,
    onNavigateToSimSwap: () -> Unit
) {
    val score by viewModel.securityScore.collectAsState()
    val fullScanSummary by viewModel.fullScanSummary.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.dashboard),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        SecurityScoreCard(score = score.total)

        FullScanCard(
            summary = fullScanSummary,
            onClick = onNavigateToFullScan
        )

        SecurityFeatureCards(
            score = score,
            onAntiTheftClick = onNavigateToAntiTheft,
            onCallBlockerClick = onNavigateToCallBlocker,
            onPrivacyClick = onNavigateToPrivacyScanner,
            onSpywareClick = onNavigateToSpywareCheck
        )

        SimSwapStatusCard(onClick = onNavigateToSimSwap)
    }
}

@Composable
fun SimSwapStatusCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sim_swap_status),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.sim_swap_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = stringResource(R.string.sim_swap),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun FullScanCard(summary: FullScanSummary, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.full_scan),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = summary.dateText ?: "Сканирование ещё не выполнялось",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (summary.riskScore != null) {
                Text(
                    text = "${summary.riskScore}/100",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        summary.riskScore >= 80 -> ScoreExcellent
                        summary.riskScore >= 50 -> ScoreGood
                        else -> ScoreCritical
                    }
                )
            }
        }
    }
}

@Composable
fun SecurityScoreCard(score: Int) {
    val (color, label) = when {
        score >= 80 -> ScoreExcellent to stringResource(R.string.excellent)
        score >= 60 -> ScoreGood to stringResource(R.string.good)
        score >= 40 -> ScoreFair to stringResource(R.string.fair)
        score >= 20 -> ScorePoor to stringResource(R.string.poor)
        else -> ScoreCritical to stringResource(R.string.critical)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.security_score),
                style = MaterialTheme.typography.titleMedium,
                color = color
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(150.dp)
            ) {
                CircularProgressIndicator(
                    progress = score / 100f,
                    modifier = Modifier.size(150.dp),
                    color = color,
                    strokeWidth = 12.dp,
                    trackColor = color.copy(alpha = 0.2f)
                )
                Text(
                    text = "$score",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = color,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun SecurityFeatureCards(
    score: SecurityScore,
    onAntiTheftClick: () -> Unit,
    onCallBlockerClick: () -> Unit,
    onPrivacyClick: () -> Unit,
    onSpywareClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FeatureCard(
            title = stringResource(R.string.anti_theft),
            score = score.antiTheft,
            icon = androidx.compose.material.icons.Icons.Default.Lock,
            onClick = onAntiTheftClick
        )
        
        FeatureCard(
            title = stringResource(R.string.call_sms_blocker),
            score = score.callBlocker,
            icon = androidx.compose.material.icons.Icons.Default.Block,
            onClick = onCallBlockerClick
        )
        
        FeatureCard(
            title = stringResource(R.string.privacy_scanner),
            score = score.privacy,
            icon = androidx.compose.material.icons.Icons.Default.VisibilityOff,
            onClick = onPrivacyClick
        )
        
        FeatureCard(
            title = stringResource(R.string.spyware_check),
            score = score.spyware,
            icon = androidx.compose.material.icons.Icons.Default.Search,
            onClick = onSpywareClick
        )
    }
}

@Composable
fun FeatureCard(
    title: String,
    score: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Box(
                modifier = Modifier.size(50.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = score / 100f,
                    modifier = Modifier.size(50.dp),
                    color = getScoreColor(score),
                    strokeWidth = 6.dp,
                    trackColor = getScoreColor(score).copy(alpha = 0.2f)
                )
                Text(
                    text = "$score",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = getScoreColor(score)
                )
            }
        }
    }
}

private fun getScoreColor(score: Int): Color {
    return when {
        score >= 80 -> ScoreExcellent
        score >= 60 -> ScoreGood
        score >= 40 -> ScoreFair
        score >= 20 -> ScorePoor
        else -> ScoreCritical
    }
}
