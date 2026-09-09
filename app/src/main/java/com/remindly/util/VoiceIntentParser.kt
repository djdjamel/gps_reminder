package com.remindly.util

import com.remindly.domain.model.PlaceCategory
import java.util.Locale

data class ParsedVoiceIntent(
    val rawText: String,
    val cleanedReminderText: String,
    val detectedCategory: PlaceCategory?,
    val matchedKeyword: String? = null
)

object VoiceIntentParser {

    // ─── Dictionnaire Sémantique par Catégorie ─────────────────────────────────
    // Support : Français, Arabe standard, Arabe dialectal / Derja (Algérie/Maghreb), Anglais
    private val categoryKeywordsMap: Map<PlaceCategory, List<String>> = mapOf(
        PlaceCategory.BAKERY to listOf(
            // Français
            "boulangerie", "boulanger", "patisserie", "pâtisserie", "baguette", "pain", "croissant", "viennoiserie",
            // Arabe standard & Derja
            "مخبزة", "مخبز", "كوشة", "الكوشة", "بولونجي", "بولونجغي", "خبز", "الخبز", "كرواسون", "حلويات", "باتيسري",
            // Anglais
            "bakery", "bakehouse", "pastry", "bread", "baguette", "baker"
        ),
        PlaceCategory.PHARMACY to listOf(
            // Français
            "pharmacie", "pharmacien", "médicament", "medicament", "médicaments", "medicaments", "ordonnance", "doliprane", "paracétamol", "sirop",
            // Arabe standard & Derja
            "صيدلية", "صيدلي", "فارماسي", "لافارماسي", "دواء", "الدواء", "دوا", "الادوية", "الأدوية", "سيرو", "بومادا",
            // Anglais
            "pharmacy", "drugstore", "chemist", "medicine", "medication", "prescription", "pills"
        ),
        PlaceCategory.SUPERMARKET to listOf(
            // Français
            "supermarché", "supermarche", "supérette", "superette", "épicerie", "epicerie", "magasin", "courses", "alimentation", "market", "hyper", "supérettes",
            // Arabe standard & Derja
            "سوبرماركت", "سوبر ماركت", "سوبيرات", "السوبيرات", "حانوت", "الحانوت", "حوانت", "بقال", "بقالة", "قضيان", "قضية", "مارشي", "المارشي",
            // Anglais
            "supermarket", "grocery", "groceries", "convenience store", "mart", "market", "food store"
        ),
        PlaceCategory.GAS_STATION to listOf(
            // Français
            "station-service", "station service", "station", "essence", "carburant", "gasoil", "gazole", "naftal", "total", "pompe",
            // Arabe standard & Derja
            "محطة وقود", "محطة بنزين", "بنزين", "وقود", "مازوت", "ليسانس", "نافتال", "نفطال", "بومبة", "البومبة", "لاسيستانسيون", "سيستانسيون",
            // Anglais
            "gas station", "petrol station", "gas", "fuel", "diesel", "petrol", "filling station"
        ),
        PlaceCategory.ATM to listOf(
            // Français
            "distributeur", "banque", "guichet", "dab", "gab", "retrait", "argent", "espece", "espèces", "billets",
            // Arabe standard & Derja
            "صراف آلي", "صراف", "بنك", "البنك", "بانكة", "البانكة", "داب", "الداب", "دراهم", "الدراهم", "سحب",
            // Anglais
            "atm", "cash machine", "bank", "cashpoint", "cash dispenser", "withdraw money", "cash"
        ),
        PlaceCategory.RESTAURANT to listOf(
            // Français
            "restaurant", "resto", "café", "cafe", "bistrot", "brasserie", "pizzeria", "fast food", "fastfood", "snack", "sandwich", "déjeuner", "dîner", "manger",
            // Arabe standard & Derja
            "مطعم", "ريسطو", "الريسطو", "قهوة", "القهوة", "مقهى", "بيتزاريا", "بيتزا", "ساندويتش", "فاست فود", "غداء", "عشاء", "ماكلة",
            // Anglais
            "restaurant", "cafe", "coffee shop", "diner", "pizzeria", "bistro", "fast food", "lunch", "dinner", "eat"
        )
    )

    // ─── Préfixes d'intention à retirer ─────────────────────────────────────────
    private val triggerPrefixPatterns = listOf(
        // Français (avec gestion des tirets rappelle-moi et des apostrophes d' / d’)
        Regex("""(?i)^(rappelle[-\s]*moi\s*(de\s+|d'|d’)?|rappelle\s*(de\s+|d'|d’)?|pense\s*à\s+|penser\s*à\s+|n'oublie[-\s]*pas\s*(de\s+|d'|d’)?|il\s*faut\s*(que\s+)?|je\s*dois\s+|faut\s+)\s*"""),
        // Arabe & Derja
        Regex("""(?i)^(فكرني\s*(باش\s+|ان\s+|ب)?|تفكر\s*(باش\s+)?|لازم\s*(عليا\s+)?|خاصني\s+|نحتاج\s+|ماتنساش\s*(باش\s+)?)\s*"""),
        // Anglais
        Regex("""(?i)^(remind\s*me\s*to\s+|remember\s*to\s+|don't\s*forget\s*to\s+|i\s*need\s*to\s+|i\s*must\s+|i\s*have\s*to\s+)\s*""")
    )

    // ─── Suffixes de localisation / destination à nettoyer ─────────────────────
    private val locationSuffixPatterns = listOf(
        // Français (ex: "à la boulangerie", "au supermarché", "dans une pharmacie", "chez le boulanger")
        Regex("""(?i)\s+(à\s+la|au|aux|dans\s+(une|le|la|les)?|chez\s+(le|la|les)?|près\s+de\s+(la|le|l')?)\s+(boulangerie|boulanger|patisserie|pâtisserie|pharmacie|supermarché|supermarche|supérette|superette|épicerie|epicerie|magasin|station(\s*-\s*service)?|station|banque|distributeur|dab|restaurant|resto|café|pizzeria)(\s+du\s+coin)?$"""),
        // Arabe & Derja (ex: "من الكوشة", "مالسوبيرات", "في لافارماسي", "عند البولونجي", "من الحانوت", "مالداب")
        Regex("""(?i)\s*(من\s+|مال\s*|في\s+|ف\s*|عند\s+)?(الكوشة|كوشة|البولونجي|بولونجي|الصيدلية|صيدلية|لافارماسي|فارماسي|السوبيرات|سوبيرات|الحانوت|حانوت|المارشي|مارشي|البومبة|بومبة|نافتال|لاسيستانسيون|البانكة|بانكة|الداب|داب|الريسطو|ريسطو|المطعم|مطعم|القهوة|قهوة)$"""),
        // Anglais (ex: "at the bakery", "from the grocery store", "in the pharmacy")
        Regex("""(?i)\s+(at\s+(the|a)?|from\s+(the|a)?|in\s+(the|a)?)\s+(bakery|pharmacy|drugstore|supermarket|grocery(\s*store)?|store|gas\s*station|atm|bank|restaurant|cafe)$""")
    )

    /**
     * Analyse une chaîne brute dictée ou saisie, extrait la catégorie cible
     * et nettoie le texte du rappel pour ne garder que l'action pertinente.
     */
    fun parse(rawText: String): ParsedVoiceIntent {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) {
            return ParsedVoiceIntent(rawText, "", null)
        }

        val normalized = normalizeForSearch(trimmed)
        
        // 1. Détection de la catégorie
        var detectedCategory: PlaceCategory? = null
        var matchedKeyword: String? = null
        var bestKeywordLength = 0

        for ((category, keywords) in categoryKeywordsMap) {
            for (kw in keywords) {
                val normalizedKw = normalizeForSearch(kw)
                val isArabic = normalizedKw.any { it in '\u0600'..'\u06FF' }

                val isMatch = if (isArabic) {
                    // En arabe, les préfixes مال/بال/فال/ال s'attachent directement
                    normalized.contains(normalizedKw)
                } else {
                    val pattern = Regex("""(?i)(^|\s|[^\p{L}\p{N}])${Regex.escape(normalizedKw)}($|\s|[^\p{L}\p{N}])""")
                    pattern.containsMatchIn(normalized)
                }

                if (isMatch) {
                    // Préférer les mots-clés les plus précis/longs
                    if (kw.length > bestKeywordLength) {
                        detectedCategory = category
                        matchedKeyword = kw
                        bestKeywordLength = kw.length
                    }
                }
            }
        }

        // 2. Nettoyage du texte du rappel
        var cleaned = trimmed

        // Suppression des préfixes ("Rappelle-moi de...", "فكرني نشري...")
        for (pattern in triggerPrefixPatterns) {
            cleaned = pattern.replace(cleaned, "")
        }

        // Suppression des suffixes de localisation ("... à la boulangerie", "... مالسوبيرات")
        for (pattern in locationSuffixPatterns) {
            cleaned = pattern.replace(cleaned, "")
        }

        // Nettoyage final de ponctuation et espacement
        cleaned = cleaned.trim()
            .replace(Regex("""^[,\.;:!?-]+\s*"""), "")
            .replace(Regex("""\s*[,\.;:!?-]+$"""), "")
            .trim()

        // Si le nettoyage a tout vidé (ex: l'utilisateur a juste dit "Pharmacie"), restaurer le terme utile
        val finalReminderText = if (cleaned.isBlank()) {
            trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        } else {
            cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }

        return ParsedVoiceIntent(
            rawText = trimmed,
            cleanedReminderText = finalReminderText,
            detectedCategory = detectedCategory,
            matchedKeyword = matchedKeyword
        )
    }

    private fun normalizeForSearch(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace('é', 'e')
            .replace('è', 'e')
            .replace('ê', 'e')
            .replace('ë', 'e')
            .replace('à', 'a')
            .replace('â', 'a')
            .replace('ô', 'o')
            .replace('î', 'i')
            .replace('ï', 'i')
            .replace('û', 'u')
            .replace('ù', 'u')
            .replace('ç', 'c')
            // Normalisation arabe : Unifier les alifs et yaa/ta-marbouta
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ة', 'ه')
            .replace('ى', 'ي')
    }
}
