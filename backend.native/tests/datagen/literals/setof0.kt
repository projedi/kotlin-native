/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package datagen.literals.setof0

import kotlin.test.*

@Test fun runTest() {
    val x = foo1()
    println(x === foo1())
    println(x === foo2())
    println(x.toString())
    println(x.contains("a"))
    println(x.contains("b"))
    println(x.contains("c"))
    println(x.contains("d"))
}

fun foo1(): Set<String> {
    return setOf("a", "b", "c")
}

fun foo2(): Set<String> {
    return setOf("a", "b", "c")
}
