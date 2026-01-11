package com.sitta.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AuthManager {
    data class Tenant(val id: String, val displayName: String)

    private val tenants = listOf(
        Tenant(id = "local", displayName = "Local"),
        Tenant(id = "demo_corp", displayName = "Demo Corp"),
    )

    private val activeTenantState = MutableStateFlow(tenants.first())
    private val authenticatedState = MutableStateFlow(true)

    val activeTenant: StateFlow<Tenant> = activeTenantState
    val isAuthenticated: StateFlow<Boolean> = authenticatedState

    fun allTenants(): List<Tenant> = tenants

    fun switchTenant(id: String) {
        tenants.firstOrNull { it.id == id }?.let { tenant ->
            activeTenantState.value = tenant
        }
    }

    fun fakeLogin() {
        authenticatedState.value = true
    }

    fun fakeLogout() {
        authenticatedState.value = false
    }
}
