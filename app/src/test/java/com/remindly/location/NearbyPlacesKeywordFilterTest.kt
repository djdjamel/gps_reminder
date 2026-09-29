package com.remindly.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPlacesKeywordFilterTest {

    @Test
    fun normalizeForSearch_removesAccentsAndDiacritics() {
        assertEquals("monoprix", NearbyPlacesService.normalizeForSearch("Monóprix"))
        assertEquals("cafe de la gare", NearbyPlacesService.normalizeForSearch("Café de la Gare"))
        assertEquals("lelephant", NearbyPlacesService.normalizeForSearch("L'éléphant").replace("'", ""))
        assertEquals("hopital saint-louis", NearbyPlacesService.normalizeForSearch("  Hôpital Saint-Louis  "))
    }

    @Test
    fun normalizeForSearch_collapsesWhitespaceAndLowercases() {
        assertEquals("monoprix republique", NearbyPlacesService.normalizeForSearch("  MONOPRIX   République  "))
        assertEquals("", NearbyPlacesService.normalizeForSearch("   "))
    }

    @Test
    fun keywordFilter_matchesSubstringsInName() {
        val normKeyword = NearbyPlacesService.normalizeForSearch("Monoprix")
        
        val place1 = NearbyPlace("id1", "Monoprix République", 48.86, 2.36)
        val place2 = NearbyPlace("id2", "MONOPRIX NATION", 48.85, 2.39)
        val place3 = NearbyPlace("id3", "Monoprix", 48.87, 2.33)
        val place4 = NearbyPlace("id4", "Carrefour City", 48.86, 2.35)
        val place5 = NearbyPlace("id5", "Franprix", 48.86, 2.35)

        val places = listOf(place1, place2, place3, place4, place5)

        val filtered = places.filter {
            NearbyPlacesService.normalizeForSearch(it.name).contains(normKeyword)
        }

        assertEquals(3, filtered.size)
        assertTrue(filtered.contains(place1))
        assertTrue(filtered.contains(place2))
        assertTrue(filtered.contains(place3))
        assertFalse(filtered.contains(place4))
        assertFalse(filtered.contains(place5))
    }

    @Test
    fun keywordFilter_rejectsWhenNotInTitle() {
        val normKeyword = NearbyPlacesService.normalizeForSearch("Monoprix")
        
        // Un commerce dont le nom ne contient pas "Monoprix", même s'il vend des produits Monoprix
        val otherSupermarket = NearbyPlace("id1", "Supermarché Central", 48.86, 2.36)
        assertFalse(NearbyPlacesService.normalizeForSearch(otherSupermarket.name).contains(normKeyword))
    }

    @Test
    fun keywordFilter_neutralWhenKeywordIsNull() {
        val places = listOf(
            NearbyPlace("id1", "Monoprix République", 48.86, 2.36),
            NearbyPlace("id2", "Carrefour City", 48.86, 2.35)
        )

        val keyword: String? = null
        val filtered = if (keyword.isNullOrBlank()) {
            places
        } else {
            val norm = NearbyPlacesService.normalizeForSearch(keyword)
            places.filter { NearbyPlacesService.normalizeForSearch(it.name).contains(norm) }
        }

        assertEquals(2, filtered.size)
    }

    @Test
    fun keywordFilter_neutralWhenKeywordIsBlank() {
        val places = listOf(
            NearbyPlace("id1", "Monoprix République", 48.86, 2.36),
            NearbyPlace("id2", "Carrefour City", 48.86, 2.35)
        )

        val keyword = "   "
        val filtered = if (keyword.isNullOrBlank()) {
            places
        } else {
            val norm = NearbyPlacesService.normalizeForSearch(keyword)
            places.filter { NearbyPlacesService.normalizeForSearch(it.name).contains(norm) }
        }

        assertEquals(2, filtered.size)
    }
}
