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

    fun getLocalizedDisplayName(language: String?): String {
        return when (language?.lowercase()) {
            "ar" -> when (this) {
                SUPERMARKET -> "سوبيرات / بقالة"
                PHARMACY -> "صيدلية"
                BAKERY -> "مخبزة / كوشة"
                GAS_STATION -> "محطة وقود / بومبة"
                ATM -> "صراف آلي / بنك"
                RESTAURANT -> "مطعم / مقهى"
            }
            "en" -> when (this) {
                SUPERMARKET -> "Supermarket / Grocery"
                PHARMACY -> "Pharmacy"
                BAKERY -> "Bakery"
                GAS_STATION -> "Gas Station"
                ATM -> "ATM / Bank"
                RESTAURANT -> "Restaurant / Cafe"
            }
            else -> displayName
        }
    }

    companion object {
        fun fromId(id: String?): PlaceCategory? {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
        }
    }
}

enum class CategoryReferenceType(val id: String, val displayName: String) {
    CURRENT_LOCATION("CURRENT_LOCATION", "Autour de ma position"),
    COMMUTE_ROUTE("COMMUTE_ROUTE", "Sur mon trajet habituel");

    fun getLocalizedDisplayName(language: String?): String {
        return when (language?.lowercase()) {
            "ar" -> when (this) {
                CURRENT_LOCATION -> "حول موقعي الحالي"
                COMMUTE_ROUTE -> "على مسار طريقي اليومي"
            }
            "en" -> when (this) {
                CURRENT_LOCATION -> "Around my current location"
                COMMUTE_ROUTE -> "On my usual commute route"
            }
            else -> displayName
        }
    }

    companion object {
        fun fromId(id: String?): CategoryReferenceType {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CURRENT_LOCATION
        }
    }
}
