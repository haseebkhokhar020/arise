package com.arise.assistant

import com.arise.assistant.intent.IntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentParserTest {
    private val p = IntentParser()

    @Test fun openYouTube() {
        val i = p.parse("open youtube")!!
        assertEquals("open_app", i.intent)
        assertEquals("YouTube", i.appHint)
        assertTrue(!i.sensitive)
    }

    @Test fun sendWhatsAppToAli() {
        val i = p.parse("send ali a whatsapp message saying i will be on my way")!!
        assertEquals("send_whatsapp_message", i.intent)
        assertEquals("ali", i.params["contact"]?.lowercase())
        assertTrue(i.params["message"]!!.contains("on my way"))
        assertTrue(!i.sensitive)
    }

    @Test fun naturalVariantsMapToSameIntent() {
        for (t in listOf(
            "message ali that i am coming",
            "tell ali i will be there soon",
            "send a whatsapp to ali saying i am on my way"
        )) {
            val i = p.parse(t)
            assertNotNull("no match for: $t", i)
            if (t.contains("whatsapp")) assertEquals("send_whatsapp_message", i!!.intent)
        }
    }

    @Test fun weatherFallsBackToCloud() {
        val i = p.parse("what is the weather in lahore")
        assertNotNull(i)
        assertTrue(i!!.needsModel || i.intent == "search_web" || i.intent == "unknown")
    }

    @Test fun stopAndCancel() {
        assertEquals("stop", p.parse("stop")!!.intent)
        assertEquals("stop", p.parse("cancel that")!!.intent)
    }

    @Test fun sensitiveDestructiveIntents() {
        assertTrue(p.parse("delete all my photos")!!.sensitive)
        assertTrue(p.parse("buy me the 99 dollar plan")!!.sensitive)
        assertTrue(p.parse("transfer one hundred dollars to ali")!!.sensitive)
    }

    @Test fun callAndSms() {
        assertEquals("call_contact", p.parse("call ali")!!.intent)
        assertEquals("send_sms", p.parse("send ali a text saying see you soon")!!.intent)
    }

    @Test fun mediaAndVolume() {
        assertEquals("control_media", p.parse("play music")!!.intent)
        assertEquals("control_media", p.parse("pause")!!.intent)
        assertEquals("change_volume", p.parse("turn the volume down")!!.intent)
        assertEquals("change_volume", p.parse("set volume to 70 percent")!!.intent)
    }

    @Test fun sendMessageToContactOnWhatsApp() {
        val i = p.parse("send hi message to ali on whatsapp")!!
        assertEquals("send_whatsapp_message", i.intent)
        assertEquals("ali", i.params["contact"])
        assertEquals("hi", i.params["message"])
    }

    @Test fun sendMessageToContactViaWhatsAppNoOn() {
        val i = p.parse("send see you soon to sarah via whatsapp")!!
        assertEquals("send_whatsapp_message", i.intent)
        assertEquals("sarah", i.params["contact"])
        assertEquals("see you soon", i.params["message"])
    }

    @Test fun sendWhatsappMessageToContactSaying() {
        val i = p.parse("send a whatsapp message to ali saying i am on my way")!!
        assertEquals("send_whatsapp_message", i.intent)
        assertEquals("ali", i.params["contact"])
        assertTrue(i.params["message"]!!.contains("on my way"))
    }

    @Test fun playSongOnYouTube() {
        val i = p.parse("play despacito on youtube")!!
        assertEquals("play_on_youtube", i.intent)
        assertEquals("despacito", i.params["song"])
    }

    @Test fun playSongTitleOnYouTube() {
        val i = p.parse("play shape of you song on youtube")!!
        assertEquals("play_on_youtube", i.intent)
        assertEquals("shape of you", i.params["song"])
    }

}
