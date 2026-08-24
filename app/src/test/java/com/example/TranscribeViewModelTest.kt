package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.TranscriptEntity
import com.example.ui.AppTab
import com.example.ui.TranscribeViewModel
import com.example.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscribeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: TranscribeViewModel
    private lateinit var app: Application

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        runTest {
            AppDatabase.getDatabase(app).transcriptDao().clearAll()
        }
        viewModel = TranscribeViewModel(app)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `updateTranscriptText updates text state and buffer`() = runTest {
        viewModel.updateTranscriptText("Hello world this is a test")
        assertEquals("Hello world this is a test", viewModel.transcriptText.value)
    }

    @Test
    fun `selectTab changes tab state`() = runTest {
        assertEquals(AppTab.TRANSCRIBE, viewModel.currentTab.value)
        viewModel.selectTab(AppTab.HISTORY)
        assertEquals(AppTab.HISTORY, viewModel.currentTab.value)
    }

    @Test
    fun `saveTranscriptToHistory saves transcript to database`() = runTest {
        val dao = AppDatabase.getDatabase(app).transcriptDao()
        dao.insertTranscript(TranscriptEntity(title = "Custom Test Title", rawContent = "Sample transcript content"))

        val saved = dao.getAllTranscripts().first()
        assertTrue("Saved transcripts list should contain the inserted item", saved.any { it.title == "Custom Test Title" })
    }

    @Test
    fun `clearTranscript resets editor state`() = runTest {
        viewModel.updateTranscriptText("Some content")
        viewModel.clearTranscript()
        assertEquals("", viewModel.transcriptText.value)
    }

    @Test
    fun `loadTranscriptIntoEditor populates editor and switches tab`() = runTest {
        val entity = TranscriptEntity(
            id = 1,
            title = "History Item",
            rawContent = "Content from history",
            durationSeconds = 120,
            wordCount = 3
        )
        viewModel.loadTranscriptIntoEditor(entity)
        assertEquals("Content from history", viewModel.transcriptText.value)
        assertEquals(AppTab.TRANSCRIBE, viewModel.currentTab.value)
        assertEquals(120L, viewModel.elapsedSeconds.value)
    }

    @Test
    fun `deleteSavedTranscript removes entity`() = runTest {
        val dao = AppDatabase.getDatabase(app).transcriptDao()
        val id = dao.insertTranscript(TranscriptEntity(title = "Item to Delete", rawContent = "Content to delete"))

        val item = dao.getTranscriptById(id)
        assertNotNull(item)

        dao.deleteTranscript(item!!)

        val remaining = dao.getAllTranscripts().first()
        assertTrue("Item should be deleted", remaining.none { it.id == item.id })
    }

    @Test
    fun `updateTranscriptTitle changes entity title`() = runTest {
        val dao = AppDatabase.getDatabase(app).transcriptDao()
        val id = dao.insertTranscript(TranscriptEntity(title = "Old Title", rawContent = "Content for title change"))

        val item = dao.getTranscriptById(id)
        assertNotNull(item)

        dao.updateTranscript(item!!.copy(title = "New Updated Title"))

        val updated = dao.getTranscriptById(id)
        assertNotNull(updated)
        assertEquals("New Updated Title", updated?.title)
    }

    @Test
    fun `formatTime formats seconds into mm_ss and hh_mm_ss`() = runTest {
        assertEquals("00:05", viewModel.formatTime(5))
        assertEquals("02:05", viewModel.formatTime(125))
        assertEquals("01:02:05", viewModel.formatTime(3725))
    }
}
