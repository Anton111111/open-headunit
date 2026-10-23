package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import org.junit.Assert.*
import org.junit.Test

class ZbtSocketCompatibilityTest {
    /**
     * java.net.Socket is Closeable only from API 19. A JVM test cannot load an API 16 Socket, so
     * check the compiled code instead: socket consumers may not cast to Closeable, which is what
     * handing a Socket to a Closeable parameter compiles to.
     */
    @Test fun `no ZBT socket path casts to Closeable`() {
        val classes = listOf(
            ZbtByteChannel::class.java, ZbtByteChannel.Companion::class.java,
            ZbtProbe::class.java,
        )
        val casts = mutableListOf<String>()
        for (type in classes) {
            val resource = type.name.replace('.', '/') + ".class"
            val bytes = type.classLoader.getResourceAsStream(resource)!!.use { it.readBytes() }
            net.bytebuddy.jar.asm.ClassReader(bytes).accept(object : net.bytebuddy.jar.asm.ClassVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?
                ) = object : net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9) {
                    override fun visitTypeInsn(opcode: Int, operand: String) {
                        if (opcode == net.bytebuddy.jar.asm.Opcodes.CHECKCAST && operand == "java/io/Closeable") {
                            casts.add("${type.simpleName}.$name")
                        }
                    }
                }
            }, 0)
        }
        assertEquals(emptyList<String>(), casts)
    }
}
