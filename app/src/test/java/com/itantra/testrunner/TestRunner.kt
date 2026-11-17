package com.itantra.testrunner

import org.junit.Before
import org.junit.After
import org.junit.Test

fun main(args: Array<String>) {
    val testClasses = listOf(
        com.itantra.protocol.PacketProtocolTest::class.java,
        com.itantra.crypto.AesGcmCryptoTest::class.java,
        com.itantra.ble.BleChunkerTest::class.java,
        com.itantra.protocol.PacketFragmentationTest::class.java,
        com.itantra.pairing.PairingManagerTest::class.java,
        com.itantra.transport.PriorityTransportQueueTest::class.java,
        com.itantra.transport.StopAndWaitArqTest::class.java,
        com.itantra.speech.EnergyAndSileroVadTest::class.java,
        com.itantra.speech.LanguageModelManagerTest::class.java,
        com.itantra.speech.AudioBufferSecurityTest::class.java,
        com.itantra.integration.FullLinkPipelineTest::class.java
    )

    var totalTests = 0
    var passedTests = 0
    var failedTests = 0
    val failures = mutableListOf<String>()

    println("==================================================")
    println("      iTantra Complete Test Suite (Milestones 1-7)")
    println("==================================================")

    for (clazz in testClasses) {
        println("\nRunning test class: ${clazz.simpleName}")
        val beforeMethods = clazz.declaredMethods.filter { it.isAnnotationPresent(Before::class.java) }
        val afterMethods = clazz.declaredMethods.filter { it.isAnnotationPresent(After::class.java) }
        val testMethods = clazz.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }

        for (method in testMethods) {
            val instance = clazz.getDeclaredConstructor().newInstance()
            totalTests++
            print("  - ${method.name} ... ")
            try {
                for (before in beforeMethods) {
                    before.invoke(instance)
                }
                method.invoke(instance)
                for (after in afterMethods) {
                    after.invoke(instance)
                }
                passedTests++
                println("PASSED")
            } catch (e: Exception) {
                failedTests++
                println("FAILED")
                val cause = e.cause ?: e
                failures.add("${clazz.simpleName}.${method.name}: ${cause.message}\n${cause.stackTraceToString()}")
            }
        }
    }

    println("\n==================================================")
    println("Test Summary: Total: $totalTests | Passed: $passedTests | Failed: $failedTests")
    println("==================================================")

    if (failedTests > 0) {
        println("\nFAILURES:")
        for (f in failures) {
            println(f)
        }
        System.exit(1)
    } else {
        println("\nALL UNIT TESTS PASSED SUCCESSFULLY!")
        System.exit(0)
    }
}
