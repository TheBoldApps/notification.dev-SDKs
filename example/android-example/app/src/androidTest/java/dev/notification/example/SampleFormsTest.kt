package dev.notification.example

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises local forms only; never submits valid changes to the configured backend. */
@RunWith(AndroidJUnit4::class)
class SampleFormsTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun emailStartsWithoutConsentAndInvalidInputKeepsDialogOpen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario, "Add email")
            val consent = device.wait(Until.findObject(By.text("Opt this email into campaigns")), 3000)
            assertNotNull(consent)
            assertFalse(consent.isChecked)
            device.findObject(By.desc("Email address")).text = "invalid"
            device.findObject(By.res("android", "button1")).click()
            assertNotNull(device.wait(Until.findObject(By.text("Enter a valid email address.")), 3000))
            device.findObject(By.res("android", "button2")).click()
            assertFalse(device.hasObject(By.desc("Email address")))
        }
    }

    @Test
    fun tagDraftSurvivesRecreationAndRejectsInvalidNumber() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario, "Add tag")
            device.findObject(By.desc("Key")).text = "draft_number"
            device.findObject(By.desc("Value type")).click()
            device.wait(Until.findObject(By.text("Number")), 3000).click()
            device.findObject(By.desc("Value")).text = "-"
            scenario.recreate()
            assertNotNull(device.wait(Until.findObject(By.desc("Key")), 3000))
            assertEquals("draft_number", device.findObject(By.desc("Key")).text)
            assertEquals("-", device.findObject(By.desc("Value")).text)
            assertNotNull(device.findObject(By.text("Number")))
            device.findObject(By.res("android", "button1")).click()
            assertNotNull(device.wait(Until.findObject(By.text("Enter a finite number for draft_number.")), 3000))
            device.findObject(By.res("android", "button2")).click()
        }
    }

    @Test
    fun eventPropertiesSurviveRecreationAndRejectDuplicateKeys() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario, "Track event")
            device.findObject(By.desc("Event name")).text = "draft_event"
            device.findObject(By.text("Add property")).click()
            device.findObject(By.desc("Key")).text = "duplicate"
            device.findObject(By.desc("Value")).text = "first"
            device.findObject(By.text("Add property")).click()
            val keys = device.findObjects(By.desc("Key"))
            keys.last().text = "duplicate"
            scenario.recreate()
            assertNotNull(device.wait(Until.findObject(By.desc("Event name")), 3000))
            assertEquals("draft_event", device.findObject(By.desc("Event name")).text)
            assertEquals(2, device.findObjects(By.desc("Key")).size)
            device.findObject(By.res("android", "button1")).click()
            assertNotNull(device.wait(Until.findObject(By.text("Duplicate property key: duplicate")), 3000))
            device.findObject(By.res("android", "button2")).click()
        }
    }

    private fun open(scenario: ActivityScenario<MainActivity>, label: String) {
        fun find(view: View): Button? {
            if (view is Button && view.text.toString() == label) {
                return view
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    find(view.getChildAt(index))?.let { return it }
                }
            }

            return null
        }
        scenario.onActivity { activity ->
            val button = find(activity.window.decorView)
            assertNotNull(button)
            button!!.performClick()
        }
        assertNotNull(device.wait(Until.findObject(By.res("android", "button1")), 3000))
        device.waitForIdle()
    }
}
