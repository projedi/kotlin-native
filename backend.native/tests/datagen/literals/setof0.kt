/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package datagen.literals.setof0

import kotlin.test.*

@Test fun runTest() {
    val set = foo()
    println(set === foo())
    println(set.toString())
}

fun foo(): Set<String> {
    return setOf("a", "b", "c")
}
