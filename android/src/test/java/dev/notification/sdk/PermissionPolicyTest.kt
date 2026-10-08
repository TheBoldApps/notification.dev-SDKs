package dev.notification.sdk

import org.junit.Assert.*
import org.junit.Test

class PermissionPolicyTest {
    @Test fun permissionPolicyUsesFallbackOnlyWhenPromptUnavailable() {
        assertEquals(PermissionAction.GRANTED, permissionAction(true, true, true, false, true))
        assertEquals(PermissionAction.PROMPT, permissionAction(false, true, false, false, true))
        assertEquals(PermissionAction.PROMPT, permissionAction(false, true, true, true, true))
        assertEquals(PermissionAction.SETTINGS, permissionAction(false, true, true, false, true))
        assertEquals(PermissionAction.SETTINGS_REQUIRED, permissionAction(false, true, true, false, false))
        assertEquals(PermissionAction.SETTINGS, permissionAction(false, false, false, false, true))
    }
}
