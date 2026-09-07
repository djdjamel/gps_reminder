package com.remindly.domain.model

enum class PlaceCategory(
    val id: String,
    val displayName: String,
    val iconName: String,
    val searchQuery: String
) {
    SUPERMARKET(
        id = "SUPERMARKET",
        displayName = "Supérette / Supermarché",
        iconName = "shopping_cart",
        searchQuery = "supermarché supérette épicerie"
    ),
    PHARMACY(
        id = "PHARMACY",
        displayName = "Pharmacie",
        iconName = "local_pharmacy",
        searchQuery = "pharmacie"
    ),
    BAKERY(
        id = "BAKERY",
        displayName = "Boulangerie",
        iconName = "bakery_dining",
        searchQuery = "boulangerie"
    ),
    GAS_STATION(
        id = "GAS_STATION",
        displayName = "Station-service",
        iconName = "local_gas_station",
        searchQuery = "station service"
    ),
    ATM(
        id = "ATM",
        displayName = "Distributeur / Banque",
        iconName = "atm",
        searchQuery = "distributeur banque"
    ),
    RESTAURANT(
        id = "RESTAURANT",
        displayName = "Café / Restaurant",
        iconName = "restaurant",
        searchQuery = "restaurant café"
    );

    companion object {
        fun fromId(id: String?): PlaceCategory? {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
        }
    }
}

enum class CategoryReferenceType(val id: String, val displayName: String) {
    CURRENT_LOCATION("CURRENT_LOCATION", "Autour de ma position"),
    COMMUTE_ROUTE("COMMUTE_ROUTE", "Sur mon trajet habituel");

    companion object {
        fun fromId(id: String?): CategoryReferenceType {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CURRENT_LOCATION
        }
    }
}
