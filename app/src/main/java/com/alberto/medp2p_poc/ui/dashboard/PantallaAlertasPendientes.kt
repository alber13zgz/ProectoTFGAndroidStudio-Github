package com.alberto.medp2p_poc.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaAlertasPendientes(
    viewModel: DashboardViewModel,
    onBack: () -> Unit
) {
    val alertas by viewModel.alertasPendientes.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alertas de hoy", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF8FAFE))
            )
        },
        containerColor = Color(0xFFF8FAFE)
    ) { padding ->
        if (alertas.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.CheckCircle, null,
                        Modifier.size(56.dp), tint = Color(0xFF00C9A7))
                    Spacer(Modifier.height(16.dp))
                    Text("Todo al dia", fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, color = Color(0xFF1A1C2B))
                    Text(
                        "No hay medicacion pendiente para hoy",
                        fontSize = 14.sp, color = Color(0xFF6B7280),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 32.dp)
                            .padding(top = 8.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier            = Modifier.fillMaxSize().padding(padding),
                contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        "${alertas.size} medicacion${if (alertas.size != 1) "es" else ""} pendiente${if (alertas.size != 1) "s" else ""}",
                        fontSize = 13.sp,
                        color    = Color(0xFF6B7280),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(alertas, key = { it.id }) { pauta ->
                    AlertaMedicacionCard(
                        pauta          = pauta,
                        onSuministrada = {
                            viewModel.registrarSuministro(pauta.id, pauta.patientPeerId)
                        }
                    )
                }
            }
        }
    }
}