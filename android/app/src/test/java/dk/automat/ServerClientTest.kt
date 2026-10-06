package dk.automat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ServerClientTest {
    private fun tmp() = File.createTempFile("outbox", ".jsonl").apply { delete() }

    @Test fun outboxKeepsEventsUntilDropped() {
        val f = tmp()
        val o = Outbox(f)
        o.add(JSONObject().put("type", "a"))
        o.add(JSONObject().put("type", "b"))
        val sent = Outbox(f).peek(100)
        assertEquals(listOf("a", "b"), sent.map { it.getString("type") })
        o.add(JSONObject().put("type", "c")) // kommer mens heartbeat er i gang
        o.drop(sent.size)
        assertEquals(listOf("c"), o.peek(100).map { it.getString("type") })
    }

    @Test fun outboxIsBounded() {
        val o = Outbox(tmp(), maxEvents = 3)
        repeat(5) { o.add(JSONObject().put("n", it)) }
        assertEquals(listOf(2, 3, 4), o.peek(100).map { it.getInt("n") })
    }

    @Test fun parsesCommands() {
        val r = JSONObject("""{"commands":[{"id":"1","cmd":"refill"},{"id":"2"},{"id":"3","cmd":"setStock","productId":"x","count":2}]}""")
        val cmds = parseCommands(r)
        assertEquals(listOf("refill", "setStock"), cmds.map { it.cmd })
        assertEquals(2, cmds[1].args.getInt("count"))
        assertEquals(emptyList<ServerCommand>(), parseCommands(JSONObject()))
        assertEquals(emptyList<ServerCommand>(), parseCommands(null))
    }
}
