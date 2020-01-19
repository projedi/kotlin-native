/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package datagen.literals.mapof0

import kotlin.test.*

@Test fun runTest() {
    val x = foo1()
    println(x === foo1())
    println(x === foo2())
    println(x.toString())
    println(x["a"])
    println(x["b"])
    println(x["c"])
    println(x["d"])
}

fun foo1(): Map<String, String> {
    return mapOf("a" to "x", "b" to "y", "c" to "z")
}

fun foo2(): Map<String, String> {
    return mapOf("a" to "x", "b" to "y", "c" to "z")
}
