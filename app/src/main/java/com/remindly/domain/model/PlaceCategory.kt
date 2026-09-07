package com.remindly.domain.model

enum class PlaceCategory(
    val id: String,
    val displayName: String,
    val iconName: String,
    val primaryType: String,
    val secondaryTypes: List<String> = emptyList(),
    val keywords: List<String> = emptyList()
) {
    SUPERMARKET(
        id = "SUPERMARKET",
        displayName = "Supérette / Supermarché",
        iconName = "shopping_cart",
        primaryType = "supermarket",
        secondaryTypes = listOf("convenience_store", "grocery_or_supermarket"),
        keywords = listOf("supermarché", "supérette", "épicerie", "alimentation")
    ),
    PHARMACY(
        id = "PHARMACY",
        displayName = "Pharmacie",
        iconName = "local_pharmacy",
        primaryType = "pharmacy",
        secondaryTypes = listOf("drugstore"),
        keywords = listOf("pharmacie")
    ),
    BAKERY(
        id = "BAKERY",
        displayName = "Boulangerie",
        iconName = "bakery_dining",
        primaryType = "bakery",
        secondaryTypes = emptyList(),
        keywords = listOf("boulangerie", "pâtisserie")
    ),
    GAS_STATION(
        id = "GAS_STATION",
        displayName = "Station-service",
        iconName = "local_gas_station",
        primaryType = "gas_station",
        secondaryTypes = emptyList(),
        keywords = listOf("station-service", "essence", "naftal", "total")
    ),
    ATM(
        id = "ATM",
        displayName = "Distributeur / Banque",
        iconName = "atm",
        primaryType = "atm",
        secondaryTypes = listOf("bank"),
        keywords = listOf("distributeur", "banque", "dab")
    ),
    RESTAURANT(
        id = "RESTAURANT",
        displayName = "Café / Restaurant",
        iconName = "restaurant",
        primaryType = "restaurant",
        secondaryTypes = listOf("cafe", "meal_takeaway", "fast_food_restaurant"),
        keywords = listOf("restaurant", "café", "pizzeria", "fast food")
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
