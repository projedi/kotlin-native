/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package datagen.literals.mapof0

import kotlin.test.*

@Test fun runTest() {
    val map = foo()
    println(map === foo())
    println(map.toString())
}

fun foo(): Map<String, String> {
    return mapOf("a" to "x", "b" to "y", "c" to "z")
}
