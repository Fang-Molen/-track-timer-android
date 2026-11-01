package cn.tracktimer.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReportStoreTest {
    @Test fun smallExportIncludesJsonButExcludesMediaAndPartialFiles() {
        val store = fixture()
        val output = ByteArrayOutputStream()
        store.export(output, logsOnly = true)
        val files = unzip(output.toByteArray())
        assertEquals(setOf("说明.txt", "camera-test.json"), files.keys)
        assertTrue(files.getValue("说明.txt").decodeToString().contains("不含音视频"))
        assertEquals("camera_probe", JSONObject(files.getValue("camera-test.json").decodeToString()).getString("kind"))
        assertTrue(store.directory.resolve("test.mp4").exists())
        assertTrue(store.directory.resolve("test.wav").exists())
    }

    @Test fun fullExportStillIncludesCompletedMediaWithoutPendingFiles() {
        val store = fixture()
        val output = ByteArrayOutputStream()
        store.export(output)
        assertEquals(setOf("说明.txt", "camera-test.json", "test.mp4", "test.wav", "test.csv"), unzip(output.toByteArray()).keys)
    }

    private fun fixture(): ReportStore {
        val store = ReportStore(RuntimeEnvironment.getApplication())
        store.directory.listFiles().orEmpty().forEach { it.delete() }
        store.directory.resolve("camera-test.json").writeText("{\"kind\":\"camera_probe\"}")
        for (name in listOf("test.mp4", "test.wav", "test.csv", "test.pending", "test.json.bak"))
            store.directory.resolve(name).writeText("fixture-$name")
        return store
    }
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) { val next = zip.nextEntry ?: break; entries[next.name] = zip.readBytes(); zip.closeEntry() }
        }
        return entries
    }
}
