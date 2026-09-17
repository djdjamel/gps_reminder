package com.remindly.location

import org.junit.Assert.assertEquals
import org.junit.Test

class PassiveLocationReceiverTest {

    @Test
    fun testClassifyLocationSource_gps() {
        val (icon1, name1) = PassiveLocationReceiver.classifyLocationSource("gps", 5f)
        assertEquals("🛰️", icon1)
        assertEquals("GPS (Satellites)", name1)

        val (icon2, name2) = PassiveLocationReceiver.classifyLocationSource("fused", 10f)
        assertEquals("🛰️", icon2)
        assertEquals("GPS (Satellites)", name2)
    }

    @Test
    fun testClassifyLocationSource_wifi() {
        val (icon, name) = PassiveLocationReceiver.classifyLocationSource("network", 45f)
        assertEquals("📶", icon)
        assertEquals("Wi-Fi (Triangulation)", name)

        val (icon2, name2) = PassiveLocationReceiver.classifyLocationSource("fused", 120f)
        assertEquals("📶", icon2)
        assertEquals("Wi-Fi (Triangulation)", name2)
    }

    @Test
    fun testClassifyLocationSource_cell() {
        val (icon, name) = PassiveLocationReceiver.classifyLocationSource("network", 850f)
        assertEquals("🗼", icon)
        assertEquals("Cellulaire (Antennes-relais)", name)

        val (icon2, name2) = PassiveLocationReceiver.classifyLocationSource("passive", 2000f)
        assertEquals("🗼", icon2)
        assertEquals("Cellulaire (Antennes-relais)", name2)
    }

    @Test
    fun testFormatDeltaTime() {
        assertEquals("1er point", PassiveLocationReceiver.formatDeltaTime(0L, isFirstPoint = true))
        assertEquals("5s", PassiveLocationReceiver.formatDeltaTime(5_000L, isFirstPoint = false))
        assertEquals("45s", PassiveLocationReceiver.formatDeltaTime(45_000L, isFirstPoint = false))
        assertEquals("1m 15s", PassiveLocationReceiver.formatDeltaTime(75_000L, isFirstPoint = false))
        assertEquals("4m 30s", PassiveLocationReceiver.formatDeltaTime(270_000L, isFirstPoint = false))
    }
}
