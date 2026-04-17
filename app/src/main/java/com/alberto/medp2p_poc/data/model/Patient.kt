package com.alberto.medp2p_poc.data.model

import java.util.UUID

/**
 * Modelo de datos para un Paciente/Contacto en la red P2P.
 * Se almacena en SQLite de forma local.
 */
data class Patient(
    val id: String = UUID.randomUUID().toString(),
    val alias: String,           // Nombre/Alias del dispositivo remoto (ej: "iPhone de Juan", "Tablet Hospital")
    val peerId: String,          // Identificador único P2P (libp2p)
    val multiaddr: String = "",  // Dirección completa multiaddr (ej: /ip4/.../p2p/...)
    val lastSeen: Long = System.currentTimeMillis(),  // Última conexión
    val isFavorite: Boolean = false  // Si está marcado como favorito
)
