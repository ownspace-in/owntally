package owntally.ui.lock

import kotlin.test.Test
import kotlin.test.assertEquals
import owntally.data.store.InMemorySettingsStore

class LockSettingStoreTest {
    private val settings = InMemorySettingsStore()

    @Test fun offWhenNothingIsStored() {
        assertEquals(LockMode.OFF, LockSettingStore(settings).load())
    }

    @Test fun savedModeComesBackAfterARestart() {
        LockSettingStore(settings).save(LockMode.PHONE)
        assertEquals(LockMode.PHONE, LockSettingStore(settings).load())
        LockSettingStore(settings).save(LockMode.OFF)
        assertEquals(LockMode.OFF, LockSettingStore(settings).load())
    }

    @Test fun aDamagedValueReadsAsOff() {
        settings.put(LockSettingStore.KEY, "banana")
        assertEquals(LockMode.OFF, LockSettingStore(settings).load())
    }

    @Test fun theKeyIsStable() {
        assertEquals("app_lock", LockSettingStore.KEY)
    }
}
