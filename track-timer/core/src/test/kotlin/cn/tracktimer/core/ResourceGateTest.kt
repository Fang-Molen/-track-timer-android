package cn.tracktimer.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream
import java.io.IOException

class ResourceGateTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun resourceArrivingAfterCancellationIsClosedImmediately() {
        val gate = ResourceGate<FileOutputStream> { it.close() }
        gate.close()
        val stream = FileOutputStream(folder.newFile())
        assertFalse(gate.accept(stream))
        assertThrows(IOException::class.java) { stream.write(1) }
    }
    @Test fun cancellationClosesAnAlreadyOpenedResource() {
        val gate = ResourceGate<FileOutputStream> { it.close() }
        val stream = FileOutputStream(folder.newFile())
        assertTrue(gate.accept(stream))
        stream.write(1)
        gate.close(); gate.close()
        assertThrows(IOException::class.java) { stream.write(1) }
    }
}
