package cn.tracktimer.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.Random

/**
 * Runs server/relay.mjs on localhost and drives a start phone and a finish phone through it,
 * with simulated (asymmetric, jittery) network delays on the finish side.
 */
class RelayIntegrationTest {
    private var process:Process?=null
    private var url=""
    private val hostOffset=3_700_123_456L            // true offset: start clock - finish clock
    private val finishClock={System.nanoTime()}
    private val startClock={System.nanoTime()+hostOffset}

    @Before fun startRelay() {
        val script=listOf(File("../../server/relay.mjs"),File("../server/relay.mjs")).firstOrNull{it.isFile}
        val node=listOf("/usr/local/bin/node","/usr/bin/node").firstOrNull{File(it).canExecute()}
        assumeTrue("node or relay.mjs not available",script!=null && node!=null)
        val port=ServerSocket(0).use{it.localPort}
        process=ProcessBuilder(node,script!!.absolutePath).apply{environment()["PORT"]=port.toString();redirectErrorStream(true)}.start()
        val line=process!!.inputStream.bufferedReader().readLine()
        assertTrue(line,line.contains("listening"))
        url="ws://127.0.0.1:$port/tt-relay"
    }
    @After fun stop(){process?.destroy()}

    private class Delayed(private val inner:LineTransport,private val upMs:Int,private val downMs:Int,private val jitterMs:Int,seed:Long):LineTransport {
        private val rnd=Random(seed)
        private fun pause(base:Int){val d=base+if(jitterMs>0)rnd.nextInt(jitterMs+1) else 0;if(d>0)Thread.sleep(d.toLong())}
        override fun exchange(line:String,timeoutMs:Int):String{pause(upMs);val r=inner.exchange(line,timeoutMs);pause(downMs);return r}
    }

    data class Outcome(val name:String,val offsetErrorNs:Long,val uncertaintyNs:Long,val startErrorNs:Long)

    private fun race(name:String,up:Int,down:Int,jitter:Int):Outcome {
        val session="host-session-1"
        val coordinator=RaceCoordinator(session,startClock)
        var responder:SyncResponder?=null
        val host=RelayLink.host(url,startClock){line,received->responder!!.respond(line,received).text}
        responder=SyncResponder(host.code,session,startClock,coordinator)
        val raw=RelayLink.join(url,host.code,finishClock)
        try {
            val link=Delayed(raw,up,down,jitter,up*31L+down)
            val cal=SyncClient.measure(link,host.code,"finish-1",finishClock,count=32,spacingMs=0).calibration
            val raceId="race-"+name.replace("_","-")
            RaceClient.request(link,host.code,session,"finish-1",raceId,"READY")
            val fired=coordinator.fire()
            val polled=RaceClient.request(link,host.code,session,"finish-1",raceId,"POLL")
            assertEquals(fired.startNs,polled.startNs)
            val local=SignalTiming.startLocal(polled.startNs,cal,session,"finish-1",SyncLimits.RELAY_MAX_NS)
            val truth=fired.startNs-hostOffset
            val out=Outcome(name,cal.offsetNs-hostOffset,cal.uncertaintyNs,local-truth)
            println("RELAY_SIM ${out.name}: offsetError=${out.offsetErrorNs/1e6}ms bound=±${out.uncertaintyNs/1e6}ms startError=${out.startErrorNs/1e6}ms rtt=${cal.bestRoundTripNs/1e6}ms")
            return out
        } finally {raw.close();host.close()}
    }

    @Test fun offsetErrorStaysWithinMeasuredBoundUnderVariousNetworks() {
        val results=listOf(race("loopback",0,0,0),race("sym20",20,20,0),race("asym5_40",5,40,0),race("jitter10_30",10,10,30),race("asym40_5_jitter",40,5,15))
        for(r in results) {
            assertTrue("${r.name} error ${r.offsetErrorNs} exceeds bound ${r.uncertaintyNs}",kotlin.math.abs(r.offsetErrorNs)<=r.uncertaintyNs+200_000)
            assertEquals(-r.offsetErrorNs,r.startErrorNs)
        }
        assertTrue(kotlin.math.abs(results[0].offsetErrorNs)<2_000_000)
    }

    @Test fun codeIsSingleUseAndWrongCodeRejected() {
        val host=RelayLink.host(url,startClock){_,_->null}
        try {
            val wrong=if(host.code=="000000")"111111" else "000000"
            assertEquals("BAD_CODE",assertThrows(RelayException::class.java){RelayLink.join(url,wrong,finishClock)}.code)
            RelayLink.join(url,host.code,finishClock).close()
            assertEquals("BAD_CODE",assertThrows(RelayException::class.java){RelayLink.join(url,host.code,finishClock)}.code)
        } finally {host.close()}
    }

    @Test fun flatJsonRoundTrip() {
        val m=FlatJson.decode(FlatJson.encode("t" to "fwd","id" to 5,"d" to "A|\"q\"\\b"))
        assertEquals("fwd",m["t"]);assertEquals("5",m["id"]);assertEquals("A|\"q\"\\b",m["d"])
    }
}
