package cn.tracktimer.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class FieldReceiverTest {
    @Test fun finishesWithOneStartAfterLostAckAndKeepsPostCalibration() {
        val clock={System.nanoTime()};val c=RaceCoordinator("host",clock)
        SyncServer("123456","host",clock,c).use {server ->
            val port=server.start(0);val signalled=CountDownLatch(1);val done=CountDownLatch(1)
            var result:ReceiverFinal?=null;var start=0L;var lost=false;var measurements=0
            lateinit var receiver:FieldReceiver
            receiver=FieldReceiver("finish","race",clock,
                {measurements++;SyncClient.measure("127.0.0.1",port,"123456","finish",clock,8).calibration},
                {host,verb -> val r=RaceClient.request("127.0.0.1",port,"123456",host,"finish","race",verb)
                    if(verb=="ACK" && !lost){lost=true;throw IOException("lost response")};r },
                {receiver.recordingReady=true},{r,_,_->start=r.startNs;signalled.countDown()},{},
                {result=it;done.countDown()})
            val thread=Thread(receiver);thread.start()
            val deadline=System.nanoTime()+5_000_000_000L
            while(c.current()?.readyFresh!=true && System.nanoTime()<deadline)Thread.sleep(10)
            val fired=c.fire();assertTrue(signalled.await(5,TimeUnit.SECONDS));receiver.finish(false)
            val doneDeadline=System.nanoTime()+4_000_000_000L
            while(c.current()?.phase!=RacePhase.DONE && System.nanoTime()<doneDeadline)Thread.sleep(10)
            assertEquals(RacePhase.DONE,c.current()!!.phase)
            assertFalse(done.await(100,TimeUnit.MILLISECONDS));assertEquals(1,measurements)
            receiver.recordingStopped=true
            assertTrue(done.await(6,TimeUnit.SECONDS));thread.join(1000);assertEquals(2,measurements)
            assertEquals(fired.startNs,start);assertTrue(result!!.completed);assertNotNull(result!!.post)
            assertEquals(RacePhase.DONE,c.current()!!.phase)
        }
    }
    @Test fun cancellationBeforeCameraReadyNeverArmsStarter() {
        var commands=0;val done=CountDownLatch(1)
        val pre=Calibration("finish","host",10L,0,0,0,0,8,8,0)
        lateinit var r:FieldReceiver
        r=FieldReceiver("finish","race",{100L},{pre},{_,_->commands++;error("must not send")},
            {r.finish(true)},{_,_,_->fail()},{},{assertFalse(it.completed);done.countDown()})
        Thread(r).start();assertTrue(done.await(2,TimeUnit.SECONDS));assertEquals(0,commands)
    }
}
