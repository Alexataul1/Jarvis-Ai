package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.service.ActionType
import com.example.service.DeviceAssistantManager
import com.example.service.OfflineAssistantEngine
import com.example.service.SpeechManager
import com.example.service.VoicePersona
import com.example.service.WhisperNativeBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `app name remains Jarvis`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Jarvis", context.getString(R.string.app_name))
    }

    @Test
    fun `offline engine only handles explicit device actions and returns null for chat`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deviceManager = DeviceAssistantManager(context)
        val offlineEngine = OfflineAssistantEngine(deviceManager)

        val mathResp = offlineEngine.processDeviceActionOrNull("25 + 17")
        assertNotNull(mathResp)
        assertEquals(ActionType.CALCULATOR, mathResp!!.actionType)
        assertTrue(mathResp.text.contains("42"))

        val timeResp = offlineEngine.processDeviceActionOrNull("what time is it")
        assertNotNull(timeResp)
        assertEquals(ActionType.DEVICE_INFO, timeResp!!.actionType)

        // Conversational chat MUST NOT return canned fake answers
        assertNull(offlineEngine.processDeviceActionOrNull("hi"))
        assertNull(offlineEngine.processDeviceActionOrNull("capital of india"))
        assertNull(offlineEngine.processDeviceActionOrNull("explain quantum computing in Hindi"))
    }

    @Test
    fun `room MIGRATION_1_2 preserves existing chat sessions and messages`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_jarvis_${System.nanoTime()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        // Create a v1 schema manually and insert a pre-existing chat row
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `chat_sessions` (`sessionId` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, PRIMARY KEY(`sessionId`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `chat_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT NOT NULL, `text` TEXT NOT NULL, `isUser` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `imageUri` TEXT, `isVoice` INTEGER NOT NULL, `isOfflineEngine` INTEGER NOT NULL)"
                        )
                        db.execSQL("INSERT INTO `chat_sessions` VALUES ('s1', 'Old Chat', 1000, 1000)")
                        db.execSQL("INSERT INTO `chat_messages` (`sessionId`, `text`, `isUser`, `timestamp`, `imageUri`, `isVoice`, `isOfflineEngine`) VALUES ('s1', 'Preserved message', 1, 1001, NULL, 0, 0)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        openHelper.writableDatabase.close()
        openHelper.close()

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        val restored = roomDb.chatDao().getMessagesForSession("s1").first()
        assertEquals(1, restored.size)
        assertEquals("Preserved message", restored[0].text)
        assertEquals("", restored[0].engineLabel)
        assertFalse(restored[0].isError)

        roomDb.close()
        dbFile.delete()
    }

    @Test
    fun `voice persona and speech manager report honest offline stt absence when whisper missing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(VoicePersona.DIYA_HINDI, VoicePersona.fromId("unknown_id"))
        assertEquals(VoicePersona.ARJUN_HINDI, VoicePersona.fromId("arjun_hindi"))

        val fakeWhisperUnavailable = object : WhisperNativeBackend {
            override fun isNativeAvailable(): Boolean = false
            override fun initModel(modelPath: String): Boolean = false
            override fun transcribeWav(wavPath: String, language: String?, initialPrompt: String?): String = ""
            override fun release() {}
        }

        val speechManager = SpeechManager(context, modelManager = null, whisperBackend = fakeWhisperUnavailable)
        val caps = speechManager.capabilities.value
        assertFalse(caps.hasVerifiedWhisperModel)
        assertTrue(caps.offlineSttStatus.contains("not installed", ignoreCase = true))
        speechManager.release()
    }
}
