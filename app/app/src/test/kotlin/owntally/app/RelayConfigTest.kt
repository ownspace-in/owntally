package owntally.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import owntally.model.MAX_MEMBERS

/**
 * M9, T9.5 (decision R3): the relay's service file in the repo carries the limits the owner chose after the measurements of M7 (T7.9), they leave
 * room for the largest group, and the runbooks and the README say the same numbers, so what is written down is what runs.
 */
class RelayConfigTest {
    private val relay = File("../../relay")
    private val unit = File(relay, "ws-relay/deploy/ws-relay.service").readText()
    private fun flag(name: String) = Regex("""-$name\s+(\d+)""").find(unit)?.groupValues?.get(1)?.toInt()

    @Test fun theServiceFileHasTheChosenLimits() {
        assertEquals(300, flag("connects-per-min"), "new connections a minute from one address (R3)")
        assertEquals(40, flag("max-per-room"), "clients in one room (R3)")
        assertTrue("-trust-proxy" in unit, "the rate limits use the client address nginx passes on")
        assertTrue("-addr 127.0.0.1:8080" in unit, "the relay is reachable only through nginx")
    }

    @Test fun aRoomHoldsTheLargestGroupAndRoomForThoseWhoAreNotMembersAnyMore() {
        assertTrue(flag("max-per-room")!! >= 2 * MAX_MEMBERS, "a removed member who still syncs, or a person waiting to be admitted, is one more than the group's members")
    }

    @Test fun theRunbookAndTheReadmeSayTheSameNumbers() {
        for (f in listOf("ws-relay/RUNBOOK.md", "ws-relay/README.md")) {
            val text = File(relay, f).readText()
            assertTrue("300" in text && "40" in text && "connects-per-min" in text && "max-per-room" in text, "$f gives the production limits")
        }
        assertFalse("raise `-max-per-room` only if the owner agrees" in File(relay, "ws-relay/RUNBOOK.md").readText(), "the runbook no longer calls the room limit a matter of 20")
    }

    @Test fun theRunbooksDoNotContradictThemselvesAboutWhatWasRun() {
        val ws = File(relay, "ws-relay/RUNBOOK.md").readText()
        assertFalse("have not been run yet" in ws, "the steps on the real VM were run (20 Oct 2026)")
        val iroh = File(relay, "iroh-relay/RUNBOOK.md").readText()
        assertFalse("not yet run on a real VM, and the relay program has not been run by us at all" in iroh, "it was run on 29 Oct 2026")
    }

    @Test fun aFreshVmGetsEverythingTheRunbookNeeds() {
        val ws = File(relay, "ws-relay/RUNBOOK.md").readText()
        assertTrue("apt install nginx certbot" in ws, "nginx is installed by the runbook, not assumed")
        assertFalse("The VM already runs nginx." in ws)
        for (file in listOf("deploy/ws-relay.service", "deploy/nginx/relay.ownspace.in.conf", "deploy/nginx/bootstrap-http.conf")) assertTrue(File(relay, "ws-relay/$file").exists(), "the file the runbook copies: $file")
        for (file in listOf("config.toml", "iroh-relay.service", "renew-hook.sh", "nginx-locations.conf", "nginx-limits.conf")) assertTrue(File(relay, "iroh-relay/$file").exists(), "the file the Iroh runbook copies: $file")
    }

    @Test fun theMonitorSectionsNameTheAddressesToWatch() {
        val ws = File(relay, "ws-relay/RUNBOOK.md").readText()
        assertTrue("https://relay.ownspace.in/healthz" in ws && "https://joinowntally.ownspace.in/j" in ws, "the WebSocket relay and the invite page")
        val iroh = File(relay, "iroh-relay/RUNBOOK.md").readText()
        assertTrue("https://relay.ownspace.in/ping" in iroh && "https://relay.ownspace.in/generate_204" in iroh, "the Iroh relay")
    }
}
