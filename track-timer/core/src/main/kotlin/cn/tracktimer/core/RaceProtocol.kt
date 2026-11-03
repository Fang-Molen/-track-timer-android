package cn.tracktimer.core

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

enum class RacePhase { READY, FIRED, DONE, ABORTED }
data class RaceSnapshot(val hostSession:String, val clientSession:String, val raceId:String,
    val phase:RacePhase, val startNs:Long, val acknowledged:Boolean, val readyFresh:Boolean)

/** One immutable electronic start per race; READY expires if the receiver stops polling. */
class RaceCoordinator(private val hostSession:String, private val clock:()->Long,private val requireCue:Boolean=false) {
    private data class Entry(var snapshot:RaceSnapshot, var readyAt:Long?,var cueComplete:Boolean)
    private val races=linkedMapOf<String,Entry>()
    private var active:String?=null
    private fun view(e:Entry):RaceSnapshot = e.snapshot.copy(readyFresh=e.snapshot.phase==RacePhase.READY &&
        e.readyAt?.let { clock()-it in 0..2_500_000_000L }==true)
    @Synchronized fun current():RaceSnapshot?=active?.let { races[it]?.let(::view) }
    @Synchronized fun fire():RaceSnapshot {
        val e=active?.let(races::get);check(e!=null && view(e).readyFresh){"NOT_READY"}
        e.snapshot=e.snapshot.copy(phase=RacePhase.FIRED,startNs=clock(),readyFresh=false)
        return view(e)
    }
    @Synchronized fun cancelActive() { active?.let(races::get)?.let { if(it.snapshot.phase!=RacePhase.DONE)
        it.snapshot=it.snapshot.copy(phase=RacePhase.ABORTED) } }
    /** A delayed cue callback is scoped to its original race, never to a newly active race. */
    @Synchronized fun confirmCue(race:String,complete:Boolean) {
        val e=races[race] ?: return
        if(complete && e.snapshot.phase==RacePhase.FIRED)e.cueComplete=true
        if(!complete && e.snapshot.phase in listOf(RacePhase.FIRED,RacePhase.DONE))
            e.snapshot=e.snapshot.copy(phase=RacePhase.ABORTED)
    }
    @Synchronized fun handle(expectedHost:String, client:String, race:String, verb:String):RaceSnapshot {
        require(expectedHost==hostSession){"SESSION_CHANGED"}
        require(listOf(client,race).all { it.matches(Regex("[A-Za-z0-9-]{1,64}")) }){"INVALID_ID"}
        var e=races[race]
        if(e==null) {
            require(verb=="READY"){"UNKNOWN_RACE"}
            require(current()?.phase !in listOf(RacePhase.READY,RacePhase.FIRED)){"BUSY"}
            e=Entry(RaceSnapshot(hostSession,client,race,RacePhase.READY,0,false,false),clock(),!requireCue)
            races[race]=e;active=race
            while(races.size>32) races.remove(races.keys.first())
        }
        require(e.snapshot.clientSession==client){"OWNER_CHANGED"}
        when(verb) {
            "READY" -> if(e.snapshot.phase==RacePhase.READY)e.readyAt=clock()
            "POLL" -> if(e.snapshot.phase==RacePhase.READY && view(e).readyFresh)e.readyAt=clock()
            "HOLD" -> if(e.snapshot.phase==RacePhase.READY)e.readyAt=null
            "ACK" -> {require(e.snapshot.phase in listOf(RacePhase.FIRED,RacePhase.DONE)){"NOT_FIRED"};e.snapshot=e.snapshot.copy(acknowledged=true)}
            "DONE" -> {require(e.snapshot.acknowledged && e.snapshot.phase in listOf(RacePhase.FIRED,RacePhase.DONE)){"NOT_ACKNOWLEDGED"};require(e.cueComplete){"CUE_PENDING"};e.snapshot=e.snapshot.copy(phase=RacePhase.DONE)}
            "CANCEL" -> if(e.snapshot.phase!=RacePhase.DONE)e.snapshot=e.snapshot.copy(phase=RacePhase.ABORTED)
            else -> throw IllegalArgumentException("UNKNOWN_COMMAND")
        }
        return view(e)
    }
}
class RaceCommandException(val code:String):IOException(code)
object RaceClient {
    fun request(host:String,port:Int,token:String,expectedHost:String,client:String,race:String,verb:String):RaceSnapshot {
        require(token.matches(Regex("[0-9]{6}")))
        require(listOf(expectedHost,client,race,verb).all{it.matches(Regex("[A-Za-z0-9-]{1,64}"))})
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host,port),1500);socket.tcpNoDelay=true
            socket.getOutputStream().apply { write("RACE1|$token|$expectedHost|$client|$race|$verb\n".toByteArray(Charsets.US_ASCII));flush() }
            val p=readBoundedLine(socket,1500).split('|')
            if(p.firstOrNull()=="ERROR")throw RaceCommandException(p.getOrElse(1){"PROTOCOL"})
            if(p.size!=8 || p[0]!="RACE1" || p[1]!=expectedHost || p[2]!=client || p[3]!=race)throw RaceCommandException("REPLY_SESSION")
            val phase=runCatching{RacePhase.valueOf(p[4])}.getOrNull()?:throw RaceCommandException("REPLY_PHASE")
            val start=p[5].toLongOrNull()?:throw RaceCommandException("REPLY_TIME")
            if(start<0 || (phase in listOf(RacePhase.FIRED,RacePhase.DONE) && start==0L) || p[6] !in listOf("0","1") || p[7] !in listOf("0","1"))throw RaceCommandException("REPLY_VALUES")
            return RaceSnapshot(p[1],p[2],p[3],phase,start,p[6]=="1",p[7]=="1")
        }
    }
}
