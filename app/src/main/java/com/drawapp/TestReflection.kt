package com.drawapp
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.EngineConfig

object TestReflection {
    @JvmStatic
    fun main(args: Array<String>) {
        println("--- Engine ---")
        val b1 = Engine::class.java
        b1.methods.forEach { println("METHOD: " + it.name) }
        
        println("\n--- Conversation ---")
        val b2 = Conversation::class.java
        b2.methods.forEach { println("METHOD: " + it.name) }

        println("\n--- EngineConfig ---")
        val b3 = EngineConfig::class.java
        b3.methods.forEach { println("METHOD: " + it.name) }
    }
}
