package com.aadhaarocr

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.provider.MediaStore
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraIntentTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun setUp() {
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    @Test
    fun testCameraCaptureIntentDoesNotCrashAndFiresCorrectly() {
        // Mock the camera intent so it doesn't actually launch the real camera
        val resultData = Intent()
        val result = ActivityResult(Activity.RESULT_OK, resultData)
        intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE)).respondWith(result)

        // Click the capture button
        onView(withId(R.id.btnCapture)).perform(click())

        // Verify the intent was fired (if it crashed, the test would fail before this point)
        intended(hasAction(MediaStore.ACTION_IMAGE_CAPTURE))
    }
}
