package com.remindly.domain.model

enum class CommuteDirection(val id: String, val displayName: String, val description: String) {
    RETURN("return", "Au retour", "Travail ➔ Maison (déclenche au retour)"),
    OUTWARD("outward", "À l'aller", "Maison ➔ Travail (déclenche à l'aller)"),
    BOTH("both", "Dans les deux sens", "Dès le prochain passage");

    companion object {
        fun fromId(id: String?): CommuteDirection {
            return entries.firstOrNull { it.id == id } ?: RETURN
        }
    }
}
