package cn.tracktimer.app

import java.io.Closeable
import java.io.File

/** Process-local ownership spans .pending -> .mp4, inspection and callback publication. */
object ActiveVideoFiles {
    private val lock=Any()
    private val active=mutableMapOf<String,Any>()
    private fun key(file:File)=File(requireNotNull(file.parentFile).canonicalFile,file.nameWithoutExtension).path
    fun claim(file:File):Closeable=synchronized(lock){
        val key=key(file);check(key !in active){"录像文件正在使用"};val token=Any();active[key]=token
        Closeable{synchronized(lock){if(active[key]===token)active.remove(key)}}
    }
    fun isActive(file:File):Boolean=synchronized(lock){key(file) in active}
    fun <T> whileInactive(file:File,action:()->T):T=synchronized(lock){
        check(key(file) !in active){"录像仍在保存，请稍后再清理"};action()
    }
}
